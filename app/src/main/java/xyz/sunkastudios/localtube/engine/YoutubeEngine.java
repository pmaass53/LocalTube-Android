package xyz.sunkastudios.localtube.engine;

import android.content.Context;
import android.util.Log;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.DownloadProgressCallback;

import com.google.gson.Gson;

import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.system.Os;
import android.system.ErrnoException;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import xyz.sunkastudios.localtube.FormatItem;
import xyz.sunkastudios.localtube.VideoFeed;
import xyz.sunkastudios.localtube.VideoItem;
import xyz.sunkastudios.localtube.VideoMetaData;
import xyz.sunkastudios.localtube.nodejs.NodeBridge;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.FileLoader;

public class YoutubeEngine {
    private static final Gson gson = new Gson();
    private static boolean initialized = false;
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();

    public interface VideoStreamCallback {
        void onItemLoaded(VideoItem item);
        void onFinished(VideoFeed fullFeed);
        void onError(Exception e);
    }

    public static synchronized int init(Context context) {
        if (initialized) return 1;

        // EAGER INIT: Start Node.js engine immediately in parallel
        try {
            NodeBridge.startNode(context.getApplicationContext());
        } catch (Exception e) {
            Log.e("YoutubeEngine", "Failed to start Node eagerly: " + e.getMessage());
        }

        try {
            YoutubeDL.getInstance().init(context);
            FFmpeg.getInstance().init(context);
            Log.d("YoutubeDL", "Libraries initialized successfully.");
            initialized = true;
        } catch (Exception e) {
            Log.e("YoutubeDL", "Failed to initialize library", e);
            return 0;
        }
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._NIGHTLY);
            Log.d("YoutubeDL", "yt-dlp updated successfully.");
        } catch (Exception e) {
            Log.e("YoutubeDL", "Failed to update yt-dlp", e);
            return 2;
        }
        return 1;
    }

    public static void getHomepageRecommendationsStreamed(Context context, int limit, VideoStreamCallback callback) {
        if (!initialized) init(context);
        
        executorService.execute(() -> {
            try {
                File cookieFile = new File(context.getFilesDir(), "cookies.txt");
                YoutubeDLRequest request = new YoutubeDLRequest("https://youtube.com");
                request.addOption("--cookies", cookieFile.getAbsolutePath());
                request.addOption("--flat-playlist");
                request.addOption("--dump-json");
                request.addOption("--no-playlist");
                request.addOption("--playlist-end", limit);

                final List<VideoItem> items = new ArrayList<>();
                
                executeSafe(request, null, (progress, eta, line) -> {
                    if (line != null && line.trim().startsWith("{")) {
                        try {
                            VideoItem item = gson.fromJson(line, VideoItem.class);
                            if (item != null && item.getId() != null) {
                                items.add(item);
                                if (callback != null) callback.onItemLoaded(item);
                            }
                        } catch (Exception ignored) {}
                    }
                    return Unit.INSTANCE;
                });

                if (callback != null) {
                    VideoFeed feed = new VideoFeed();
                    feed.setEntries(items);
                    callback.onFinished(feed);
                }
            } catch (Exception e) {
                if (callback != null) callback.onError(e);
            }
        });
    }

    public static VideoFeed getHomepageRecommendations(Context context) {
        return getHomepageRecommendations(context, 40);
    }

    public static VideoFeed getHomepageRecommendations(Context context, int limit) {
        if (!initialized) init(context);
        
        File cacheFile = new File(context.getFilesDir(), "homepage.txt");
        if (!cacheFile.exists()) return null;

        try {
            byte[] bytes = FileLoader.readFile(context.getApplicationContext(), "homepage.txt");
            if (bytes == null || bytes.length == 0) return null;
            return gson.fromJson(new String(bytes, StandardCharsets.UTF_8), VideoFeed.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static void saveHomepageCache(Context context, VideoFeed feed) {
        try {
            String json = gson.toJson(feed);
            FileLoader.writeFile(context.getApplicationContext(), "homepage.txt", json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e("YoutubeDL", "Failed to save homepage cache", e);
        }
    }

    public static List<VideoMetaData> getVideoFormats(Context context, String videoUrl) {
        if (!initialized) init(context);
        YoutubeDLRequest request = new YoutubeDLRequest(cleanYoutubeUrl(videoUrl));
        request.addOption("-4");
        int preferredHeight = ConfigManager.getInt("preferred_quality");
        String defaultLang = ConfigManager.getString("default_language");
        if (defaultLang == null || defaultLang.isEmpty()) defaultLang = "en";

        if (videoUrl.contains("/shorts/")) {
            request.addOption("-f", "bestvideo[height<=" + preferredHeight + "]+bestaudio[language*=" + defaultLang + "]/bestaudio/best");
        } else {
            request.addOption("--extractor-args", "youtube:include_dash_manifest");
        }
        request.addOption("-j");
        request.addOption("--no-playlist");
        request.addOption("--no-check-certificates");
        try {
            long startTime = System.currentTimeMillis();
            YoutubeDLResponse response = executeSafe(request);
            Log.d("LocalTube-Engine", "yt-dlp format extraction took: " + (System.currentTimeMillis() - startTime) + "ms");
            
            String json = response.getOut();
            if (json == null || json.trim().isEmpty()) return null;
            List<VideoMetaData> entries = new ArrayList<>();
            for (String line : json.split("\n")) {
                if (!line.trim().isEmpty()) {
                    try {
                        entries.add(gson.fromJson(line, VideoMetaData.class));
                    } catch (Exception ignored) {}
                }
            }
            return entries;
        } catch (Exception e) {
            return null;
        }
    }

    public static VideoFeed getSearchResults(Context context, String query) {
        if (!initialized) init(context);
        int max = ConfigManager.getInt("max_playlist");
        if (max <= 0) max = 20;
        YoutubeDLRequest request = new YoutubeDLRequest("ytsearch" + max + ":" + query);
        request.addOption("--dump-json");
        request.addOption("--flat-playlist");
        try {
            YoutubeDLResponse response = executeSafe(request);
            String out = response.getOut();
            if (out == null || out.trim().isEmpty()) return null;
            VideoFeed feed = new VideoFeed();
            List<VideoItem> entries = new ArrayList<>();
            for (String line : out.split("\n")) {
                if (!line.trim().isEmpty()) {
                    try {
                        entries.add(gson.fromJson(line, VideoItem.class));
                    } catch (Exception ignored) {}
                }
            }
            feed.setEntries(entries);
            return feed;
        } catch (Exception e) {
            return null;
        }
    }

    public static VideoFeed getPlaylistEntries(Context context, String url) {
        if (!initialized) init(context);
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--flat-playlist");
        int max = ConfigManager.getInt("max_playlist");
        if (max <= 0) max = 10;
        request.addOption("--playlist-end", max);
        request.addOption("-j");
        try {
            YoutubeDLResponse response = executeSafe(request);
            String out = response.getOut();
            if (out == null || out.trim().isEmpty()) return null;
            VideoFeed feed = new VideoFeed();
            List<VideoItem> entries = new ArrayList<>();
            for (String line : out.split("\n")) {
                if (!line.trim().isEmpty()) {
                    try {
                        entries.add(gson.fromJson(line, VideoItem.class));
                    } catch (Exception ignored) {}
                }
            }
            feed.setEntries(entries);
            return feed;
        } catch (Exception e) {
            return null;
        }
    }

    public static String[] getShortsUrls(Context context, String videoUrl) {
        if (!initialized) init(context);
        YoutubeDLRequest request = new YoutubeDLRequest(cleanYoutubeUrl(videoUrl));
        request.addOption("--get-url");
        request.addOption("--no-playlist");
        
        int preferredHeight = ConfigManager.getInt("preferred_quality");
        String defaultLang = ConfigManager.getString("default_language");
        if (defaultLang == null || defaultLang.isEmpty()) defaultLang = "en";

        request.addOption("-f", "bestvideo[height<=" + preferredHeight + "]+bestaudio[language*=" + defaultLang + "]/bestaudio/best");
        
        try {
            YoutubeDLResponse response = executeSafe(request);
            String out = response.getOut();
            if (out != null && !out.trim().isEmpty()) return out.split("\n");
        } catch (Exception ignored) {}
        return null;
    }

    public static void markWatched(Context context, String videoUrl) {
        historyExecutor.execute(() -> {
            if (!initialized) init(context);
            File cookieFile = new File(context.getFilesDir(), "cookies.txt");
            YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);
            request.addOption("--cookies", cookieFile.getAbsolutePath());
            request.addOption("--mark-watched");
            request.addOption("--skip-download");
            request.addOption("--no-check-certificates");
            try {
                long start = System.currentTimeMillis();
                executeSafe(request);
                Log.d("LocalTube-Engine", "markWatched (async) took: " + (System.currentTimeMillis() - start) + "ms for " + videoUrl);
            } catch (Exception e) {
                Log.e("YoutubeDL", "markWatched() error", e);
            }
        });
    }

    public static YoutubeDLResponse executeSafe(YoutubeDLRequest request) throws Exception {
        return executeSafe(request, null, null);
    }

    public static YoutubeDLResponse executeSafe(YoutubeDLRequest request, String videoId) throws Exception {
        return executeSafe(request, videoId, null);
    }

    public static YoutubeDLResponse executeSafe(YoutubeDLRequest request, String videoId, Function3<? super Float, ? super Long, ? super String, Unit> callback) throws Exception {
        String oldPythonPath = System.getenv("PYTHONPATH");
        String oldPythonHome = System.getenv("PYTHONHOME");
        try {
            Os.unsetenv("PYTHONPATH");
            Os.unsetenv("PYTHONHOME");
        } catch (ErrnoException ignored) {}

        try {
            long start = System.currentTimeMillis();
            YoutubeDLResponse resp = YoutubeDL.getInstance().execute(request, videoId, callback);
            Log.v("LocalTube-Engine", "Native yt-dlp execution took: " + (System.currentTimeMillis() - start) + "ms");
            return resp;
        } finally {
            try {
                if (oldPythonPath != null) Os.setenv("PYTHONPATH", oldPythonPath, true);
                if (oldPythonHome != null) Os.setenv("PYTHONHOME", oldPythonHome, true);
            } catch (ErrnoException ignored) {}
        }
    }

    public interface ShortsCallback {
        void onResult(List<xyz.sunkastudios.localtube.ShortItem> shorts);
        void onError(String error);
    }

    public static void getShortsBatch(Context context, ShortsCallback callback) {
        if (!initialized) init(context);
        final String reqId = String.valueOf(System.currentTimeMillis());
        NodeBridge.NodeMessageListener listener = new NodeBridge.NodeMessageListener() {
            @Override
            public void onMessage(String channel, String message) {
                if ("shorts_batch".equals(channel)) {
                    try {
                        JSONObject responseObj = new JSONObject(message);
                        if (responseObj.has("reqId") && !reqId.equals(responseObj.optString("reqId"))) return;
                        List<xyz.sunkastudios.localtube.ShortItem> shorts;
                        if (responseObj.has("shorts")) {
                            shorts = gson.fromJson(responseObj.getJSONArray("shorts").toString(), new TypeToken<List<xyz.sunkastudios.localtube.ShortItem>>(){}.getType());
                        } else {
                            shorts = gson.fromJson(message, new TypeToken<List<xyz.sunkastudios.localtube.ShortItem>>(){}.getType());
                        }
                        NodeBridge.removeListener(this);
                        if (callback != null) callback.onResult(shorts);
                    } catch (Exception e) {
                        try {
                            List<xyz.sunkastudios.localtube.ShortItem> shorts = gson.fromJson(message, new TypeToken<List<xyz.sunkastudios.localtube.ShortItem>>(){}.getType());
                            NodeBridge.removeListener(this);
                            if (callback != null) callback.onResult(shorts);
                        } catch (Exception ignored) {}
                    }
                } else if ("shorts_error".equals(channel) || "error".equals(channel)) {
                    try {
                        JSONObject errObj = new JSONObject(message);
                        if (errObj.has("reqId") && !reqId.equals(errObj.optString("reqId"))) return;
                        NodeBridge.removeListener(this);
                        if (callback != null) callback.onError(errObj.optString("error", "Unknown error"));
                    } catch (Exception e) {
                        NodeBridge.removeListener(this);
                        if (callback != null) callback.onError(message);
                    }
                }
            }
        };
        NodeBridge.addListener(listener);
        NodeBridge.sendMessage("getShorts", reqId, null);
    }
    public static FormatItem findBestAudio(List<FormatItem> formats) {
        if (formats == null || formats.isEmpty()) return null;

        String preferredLang = ConfigManager.getString("default_language");
        if (preferredLang == null || preferredLang.isEmpty()) preferredLang = "en";
        final String finalLang = preferredLang.toLowerCase();

        // 1. Try to find audio matching preferred language with highest bitrate
        FormatItem bestMatch = formats.stream()
                .filter(f -> f.isAudioOnly() && f.isDirectStream())
                .filter(f -> f.getLanguage() != null && f.getLanguage().toLowerCase().startsWith(finalLang))
                .max(Comparator.comparingDouble(FormatItem::getTbr))
                .orElse(null);

        // 2. If preferred language not found, try 'en' as fallback
        if (bestMatch == null && !finalLang.equals("en")) {
            bestMatch = formats.stream()
                    .filter(f -> f.isAudioOnly() && f.isDirectStream())
                    .filter(f -> f.getLanguage() != null && f.getLanguage().toLowerCase().startsWith("en"))
                    .max(Comparator.comparingDouble(FormatItem::getTbr))
                    .orElse(null);
        }

        // 3. Try to find any track that contains "original" in its note (common for multi-language videos)
        if (bestMatch == null) {
            bestMatch = formats.stream()
                    .filter(f -> f.isAudioOnly() && f.isDirectStream())
                    .filter(f -> f.getFormatNote() != null && f.getFormatNote().toLowerCase().contains("original"))
                    .max(Comparator.comparingDouble(FormatItem::getTbr))
                    .orElse(null);
        }

        // 4. Last resort: pick the highest bitrate regardless of language
        if (bestMatch == null) {
            bestMatch = formats.stream()
                    .filter(f -> f.isAudioOnly() && f.isDirectStream())
                    .max(Comparator.comparingDouble(FormatItem::getTbr))
                    .orElse(null);
        }

        return bestMatch;
    }

    private static String cleanYoutubeUrl(String url) {
        try {
            if (!url.contains("list=RDGME")) return url;
            android.net.Uri uri = android.net.Uri.parse(url);
            android.net.Uri.Builder builder = uri.buildUpon();
            builder.clearQuery();
            for (String key : uri.getQueryParameterNames()) {
                if (!"list".equalsIgnoreCase(key) && !"index".equalsIgnoreCase(key) && !"start_radio".equalsIgnoreCase(key)) {
                    builder.appendQueryParameter(key, uri.getQueryParameter(key));
                }
            }
            return builder.build().toString();
        } catch (Exception e) {
            return url;
        }
    }
}
