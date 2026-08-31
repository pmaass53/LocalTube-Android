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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.system.Os;
import android.system.ErrnoException;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;
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

    public interface VideoStreamCallback {
        void onItemLoaded(VideoItem item);
        void onFinished(VideoFeed fullFeed);
        void onError(Exception e);
    }

    public static synchronized int init(Context context) {
        if (initialized) return 1;
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
        try {
            NodeBridge.startNode(context.getApplicationContext());
        } catch (Exception e) {
            Log.d("YoutubeEngine", "startNode() threw error: " + e.getMessage());
            return 0;
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
        if (videoUrl.contains("/shorts/")) {
            request.addOption("-f", "bestvideo[height<=1080]+bestaudio[language*=en]/bestaudio/best");
        } else {
            request.addOption("--extractor-args", "youtube:include_dash_manifest");
        }
        request.addOption("-j");
        request.addOption("--no-playlist");
        request.addOption("--no-check-certificates");
        try {
            YoutubeDLResponse response = executeSafe(request);
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
        request.addOption("-f", "bestvideo[height<=1080]+bestaudio/best");
        try {
            YoutubeDLResponse response = executeSafe(request);
            String out = response.getOut();
            if (out != null && !out.trim().isEmpty()) return out.split("\n");
        } catch (Exception ignored) {}
        return null;
    }

    public static void markWatched(Context context, String videoUrl) {
        if (!initialized) init(context);
        File cookieFile = new File(context.getFilesDir(), "cookies.txt");
        YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);
        request.addOption("--cookies", cookieFile.getAbsolutePath());
        request.addOption("--mark-watched");
        request.addOption("--skip-download");
        try {
            executeSafe(request);
        } catch (Exception e) {
            Log.e("YoutubeDL", "markMatched() error", e);
        }
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
            return YoutubeDL.getInstance().execute(request, videoId, callback);
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
