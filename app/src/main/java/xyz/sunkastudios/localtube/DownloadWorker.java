package xyz.sunkastudios.localtube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.DownloadStore;
import xyz.sunkastudios.localtube.util.NetworkManager;

public class DownloadWorker extends Worker {

    private static final String TAG = "DownloadWorker";
    private static final String CHANNEL_ID = "download_channel";
    private static final int MAX_RETRIES = 5;
    private static final long CHUNK_SIZE = 10 * 1024 * 1024;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private long totalDownloadSize = 0;
    private final AtomicLong totalBytesDownloaded = new AtomicLong(0);
    private long lastUIUpdateTime = 0;

    public DownloadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        long startTime = System.currentTimeMillis();
        String id = getInputData().getString("video_id");
        Log.i("LocalTube-Download", "[" + id + "] >>> Download Task Started");

        if (!NetworkManager.isOnline(getApplicationContext())) {
            Log.w("LocalTube-Download", "[" + id + "] Device offline, retrying later");
            return Result.retry();
        }
        String url = getInputData().getString("video_url");
        String title = getInputData().getString("video_title");
        String selectedVideoFormat = getInputData().getString("selected_video_format");
        String selectedAudioFormat = getInputData().getString("selected_audio_format");
        boolean isShort = getInputData().getBoolean("is_short", false);
        boolean isAnime = getInputData().getBoolean("is_anime", false);
        String headersJson = getInputData().getString("headers_json");
        String passedThumbnailUrl = getInputData().getString("thumbnail_url");

        if (url == null || id == null || title == null) {
            Log.e("LocalTube-Download", "[" + id + "] Missing critical input data");
            return Result.failure(new Data.Builder().putString("error", "Missing input data").build());
        }

        int notificationId = Math.abs(id.hashCode());
        setForegroundAsync(createForegroundInfo(title, Integer.toString(notificationId)));

        try {
            if (isAnime) {
                return downloadAnimeWithYtDlp(url, id, title, headersJson, passedThumbnailUrl);
            }

            ExecutorService parallelExecutor = Executors.newFixedThreadPool(2);
            try {
                long extractionStart = System.currentTimeMillis();
                List<VideoMetaData> metaData = YoutubeEngine.getVideoFormats(getApplicationContext(), url);
                Log.i("LocalTube-Download", "[" + id + "] Metadata extraction took: " + (System.currentTimeMillis() - extractionStart) + "ms");
                
                if (metaData == null || metaData.isEmpty()) {
                    Log.e("LocalTube-Download", "[" + id + "] Extraction failed (null metadata)");
                    return Result.failure();
                }

                List<FormatItem> formats = metaData.get(0).getFormats();
                if (formats == null) {
                    Log.e("LocalTube-Download", "[" + id + "] Extraction failed (no formats)");
                    return Result.failure();
                }

                long selectionStart = System.currentTimeMillis();
                FormatItem bestVideo = null;
                if (selectedVideoFormat != null) {
                    bestVideo = formats.stream().filter(f -> f.getFormatId().equals(selectedVideoFormat)).findFirst().orElse(null);
                }
                if (bestVideo == null) {
                    bestVideo = formats.stream()
                            .filter(f -> f.isVideoOnly() && f.isDirectStream())
                            .max(Comparator.comparingInt(FormatItem::getHeight))
                            .orElse(null);
                }

                FormatItem bestAudio = null;
                if (selectedAudioFormat != null) {
                    bestAudio = formats.stream().filter(f -> f.getFormatId().equals(selectedAudioFormat)).findFirst().orElse(null);
                }
                if (bestAudio == null) {
                    bestAudio = YoutubeEngine.findBestAudio(formats);
                }

                if (bestVideo == null) {
                    bestVideo = formats.stream()
                            .filter(f -> f.isCombined() && f.isDirectStream())
                            .max(Comparator.comparingInt(FormatItem::getHeight))
                            .orElse(null);
                    if (bestVideo == null) {
                        Log.e("LocalTube-Download", "[" + id + "] No suitable video/combined formats found");
                        return Result.failure();
                    }
                }
                Log.i("LocalTube-Download", "[" + id + "] Format selection took: " + (System.currentTimeMillis() - selectionStart) + "ms");

                String videoUrl = bestVideo.getUrl();
                String audioUrl = bestAudio != null ? bestAudio.getUrl() : null;
                String extension = bestVideo.getExt();

                String thumbnailUrl = (passedThumbnailUrl != null && !passedThumbnailUrl.isEmpty()) 
                        ? passedThumbnailUrl 
                        : "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";

                DownloadedVideo data = new DownloadedVideo();
                data.videoId = id;
                data.title = title;
                File videoFile = new File(getApplicationContext().getFilesDir(), id + "_video." + extension);
                data.videoFilePath = videoFile.getAbsolutePath();
                File audioFile = null;
                if (audioUrl != null) {
                    audioFile = new File(getApplicationContext().getFilesDir(), id + "_audio.m4a");
                    data.audioFilePath = audioFile.getAbsolutePath();
                }
                File thumbFile = new File(getApplicationContext().getFilesDir(), id + "_thumb.jpg");
                data.thumbnailFilePath = thumbFile.getAbsolutePath();
                data.isWatched = false;

                final Map<String, String> headersMap = new java.util.HashMap<>();
                final String fVideoUrl = videoUrl;
                final String fAudioUrl = audioUrl;

                long sizeCheckStart = System.currentTimeMillis();
                long videoSizeMeta = bestVideo.getFilesize();
                long audioSizeMeta = (bestAudio != null) ? bestAudio.getFilesize() : 0;

                CompletableFuture<Long> videoSizeFuture = videoSizeMeta == 0 ? CompletableFuture.supplyAsync(() -> fetchFileSize(fVideoUrl, headersMap)) : CompletableFuture.completedFuture(videoSizeMeta);
                CompletableFuture<Long> audioSizeFuture = (audioSizeMeta == 0 && fAudioUrl != null) ? CompletableFuture.supplyAsync(() -> fetchFileSize(fAudioUrl, headersMap)) : CompletableFuture.completedFuture(audioSizeMeta);
                CompletableFuture<Long> thumbSizeFuture = (!thumbnailUrl.isEmpty()) ? CompletableFuture.supplyAsync(() -> fetchFileSize(thumbnailUrl, null)) : CompletableFuture.completedFuture(0L);

                long videoSize = videoSizeFuture.join();
                long audioSize = audioSizeFuture.join();
                long thumbSize = thumbSizeFuture.join();
                Log.i("LocalTube-Download", "[" + id + "] File size verification took: " + (System.currentTimeMillis() - sizeCheckStart) + "ms. Total: " + (videoSize + audioSize) + " bytes");

                totalDownloadSize = videoSize + audioSize + thumbSize;
                totalBytesDownloaded.set(0);

                long allocStart = System.currentTimeMillis();
                if (videoSize > 0) preallocateFile(videoFile, videoSize);
                if (audioFile != null && audioSize > 0) preallocateFile(audioFile, audioSize);
                Log.i("LocalTube-Download", "[" + id + "] Pre-allocation took: " + (System.currentTimeMillis() - allocStart) + "ms");

                List<CompletableFuture<Void>> tasks = new ArrayList<>();
                final long fVideoSize = videoSize;

                long downloadStart = System.currentTimeMillis();
                tasks.add(CompletableFuture.runAsync(() -> {
                    try {
                        long taskStart = System.currentTimeMillis();
                        downloadFileInChunks(fVideoUrl, videoFile, fVideoSize, headersMap);
                        Log.i("LocalTube-Download", "[" + id + "] Video stream download took: " + (System.currentTimeMillis() - taskStart) + "ms");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, parallelExecutor));

                if (fAudioUrl != null && audioFile != null && audioSize > 0) {
                    final long fAudioSize = audioSize;
                    final File fAudioFile = audioFile;
                    tasks.add(CompletableFuture.runAsync(() -> {
                        try {
                            long taskStart = System.currentTimeMillis();
                            downloadFileInChunks(fAudioUrl, fAudioFile, fAudioSize, headersMap);
                            Log.i("LocalTube-Download", "[" + id + "] Audio stream download took: " + (System.currentTimeMillis() - taskStart) + "ms");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, parallelExecutor));
                }

                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
                Log.i("LocalTube-Download", "[" + id + "] Full parallel download took: " + (System.currentTimeMillis() - downloadStart) + "ms");

                long thumbStart = System.currentTimeMillis();
                if (!thumbnailUrl.isEmpty()) downloadFileOkHttp(thumbnailUrl, thumbFile, null);
                Log.i("LocalTube-Download", "[" + id + "] Thumbnail download took: " + (System.currentTimeMillis() - thumbStart) + "ms");

                long dbStart = System.currentTimeMillis();
                if (isShort) {
                    DownloadStore.addShort(getApplicationContext(), data);
                } else {
                    DownloadStore.addDownload(getApplicationContext(), data);
                }
                Log.i("LocalTube-Download", "[" + id + "] DB storage took: " + (System.currentTimeMillis() - dbStart) + "ms");

                if (getInputData().getBoolean("add_to_history", false)) {
                    YoutubeEngine.markWatched(getApplicationContext(), url);
                }

                Log.i("LocalTube-Download", "[" + id + "] <<< Download SUCCESS. Total worker time: " + (System.currentTimeMillis() - startTime) + "ms");
                return Result.success();
            } finally {
                parallelExecutor.shutdownNow();
            }
        } catch (Exception e) {
            Log.e("LocalTube-Download", "[" + id + "] Download worker FAILED after " + (System.currentTimeMillis() - startTime) + "ms: " + e.getMessage(), e);
            return Result.failure(new Data.Builder().putString("error", e.getMessage()).build());
        }
    }

    private Result downloadAnimeWithYtDlp(String url, String id, String title, String headersJson, String thumbnailUrl) throws Exception {
        File videoFile = new File(getApplicationContext().getFilesDir(), id + "_anime.mp4");
        File thumbFile = new File(getApplicationContext().getFilesDir(), id + "_thumb.jpg");
        
        Log.d(TAG, "Starting Anime download with yt-dlp: " + url);
        Log.d(TAG, "Target file: " + videoFile.getAbsolutePath());

        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("-o", videoFile.getAbsolutePath());
        request.addOption("-f", "bestvideo+bestaudio/best");
        request.addOption("--merge-output-format", "mp4");
        request.addOption("--no-check-certificates");

        if (headersJson != null && !headersJson.isEmpty()) {
            org.json.JSONObject obj = new org.json.JSONObject(headersJson);
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                request.addOption("--add-header", key + ":" + obj.getString(key));
            }
        }

        ExecutorService progressExecutor = Executors.newSingleThreadExecutor();
        progressExecutor.execute(() -> {
            try {
                while (!isStopped()) {
                    if (videoFile.exists()) {
                        long size = videoFile.length();
                        setProgressAsync(new Data.Builder()
                                .putInt("progress", -1)
                                .putLong("totalDownloaded", size)
                                .build());
                    }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ignored) {}
        });

        try {
            YoutubeDLResponse resp = YoutubeEngine.executeSafe(request, id);
            Log.d(TAG, "yt-dlp finished. Output: " + resp.getOut());
            
            if (videoFile.exists() && videoFile.length() > 0) {
                if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
                    downloadFileOkHttp(thumbnailUrl, thumbFile, null);
                }

                DownloadedVideo data = new DownloadedVideo();
                data.videoId = id;
                data.title = title;
                data.videoFilePath = videoFile.getAbsolutePath();
                data.thumbnailFilePath = thumbFile.getAbsolutePath();
                data.isWatched = false;
                
                DownloadStore.addDownload(getApplicationContext(), data);
                return Result.success();
            } else {
                if (videoFile.exists()) videoFile.delete();
                return Result.failure(new Data.Builder().putString("error", "Output file is empty or missing. Check logcat for yt-dlp errors.").build());
            }
        } catch (Exception e) {
            if (videoFile.exists()) videoFile.delete();
            throw e;
        } finally {
            progressExecutor.shutdownNow();
        }
    }

    private void preallocateFile(File file, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(size);
        }
    }

    private void downloadFileInChunks(String url, File targetFile, long totalFileSize, Map<String, String> headers) throws IOException {
        if (totalFileSize <= 0) {
            downloadFileOkHttp(url, targetFile, headers);
            return;
        }

        long currentOffset = 0;
        while (currentOffset < totalFileSize) {
            if (isStopped()) throw new IOException("Download stopped");
            long end = Math.min(currentOffset + CHUNK_SIZE - 1, totalFileSize - 1);
            downloadChunk(url, targetFile, currentOffset, end, headers);
            currentOffset = end + 1;
        }
    }

    private void downloadChunk(String url, File targetFile, long start, long end, Map<String, String> customHeaders) throws IOException {
        int retryCount = 0;
        while (true) {
            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .addHeader("Range", "bytes=" + start + "-" + end);
            
            if (customHeaders != null) {
                for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                    reqBuilder.header(e.getKey(), e.getValue());
                }
            }
            
            try (Response response = HTTP_CLIENT.newCall(reqBuilder.build()).execute()) {
                if (response.code() != 206 && response.code() != 200) {
                    throw new IOException("Server error code: " + response.code());
                }

                try (InputStream is = response.body().byteStream();
                     RandomAccessFile raf = new RandomAccessFile(targetFile, "rw")) {
                    raf.seek(start);
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        if (isStopped()) throw new IOException("Download stopped");
                        raf.write(buffer, 0, read);
                        totalBytesDownloaded.addAndGet(read);
                        updateProgress();
                    }
                    return;
                }
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= MAX_RETRIES) throw new IOException(e);
                try { Thread.sleep(1000 * (long)retryCount); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void downloadFileOkHttp(String url, File targetFile, Map<String, String> customHeaders) throws IOException {
        Request.Builder reqBuilder = new Request.Builder().url(url);
        if (customHeaders != null) {
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                reqBuilder.header(e.getKey(), e.getValue());
            }
        }

        try (Response response = HTTP_CLIENT.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("Server error: " + response.code());
            try (InputStream is = new BufferedInputStream(response.body().byteStream());
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    if (isStopped()) throw new IOException("Download stopped");
                    fos.write(buffer, 0, read);
                    totalBytesDownloaded.addAndGet(read);
                    updateProgress();
                }
                fos.flush();
            }
        }
    }

    private void updateProgress() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUIUpdateTime > 500 && totalDownloadSize > 0) {
            lastUIUpdateTime = currentTime;
            long downloaded = totalBytesDownloaded.get();
            int progress = (int) ((downloaded * 100) / totalDownloadSize);
            if (progress > 99) progress = 99;
            setProgressAsync(new Data.Builder()
                    .putInt("progress", progress)
                    .putLong("totalDownloaded", downloaded)
                    .putLong("totalSize", totalDownloadSize)
                    .build());
        }
    }

    @NonNull
    private ForegroundInfo createForegroundInfo(String title, String videoId) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Downloading Video")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(Math.abs(videoId.hashCode()), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            return new ForegroundInfo(Math.abs(videoId.hashCode()), notification);
        }
    }

    private long fetchFileSize(String url, Map<String, String> customHeaders) {
        Request.Builder headBuilder = new Request.Builder().url(url).head();
        if (customHeaders != null) {
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                headBuilder.header(e.getKey(), e.getValue());
            }
        }

        try (Response response = HTTP_CLIENT.newCall(headBuilder.build()).execute()) {
            if (response.isSuccessful()) {
                String contentLength = response.header("Content-Length");
                if (contentLength != null) return Long.parseLong(contentLength);
            }
        } catch (Exception ignored) {}

        // Fallback: Use GET with range 0-0 if HEAD fails
        Request.Builder getBuilder = new Request.Builder().url(url)
                .header("Range", "bytes=0-0");
        if (customHeaders != null) {
            for (Map.Entry<String, String> e : customHeaders.entrySet()) {
                getBuilder.header(e.getKey(), e.getValue());
            }
        }
        try (Response response = HTTP_CLIENT.newCall(getBuilder.build()).execute()) {
            if (response.isSuccessful() || response.code() == 206) {
                String range = response.header("Content-Range");
                if (range != null && range.contains("/")) {
                    return Long.parseLong(range.substring(range.lastIndexOf("/") + 1));
                }
                String cl = response.header("Content-Length");
                if (cl != null && !cl.equals("1")) return Long.parseLong(cl);
            }
        } catch (Exception ignored) {}

        return 0;
    }
}
