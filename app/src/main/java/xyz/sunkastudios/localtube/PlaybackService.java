package xyz.sunkastudios.localtube;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.FormatItem;
import xyz.sunkastudios.localtube.VideoMetaData;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.DownloadUtil;
import xyz.sunkastudios.localtube.util.NetworkManager;

public class PlaybackService extends MediaSessionService {

    private ExoPlayer player;
    private MediaSession mediaSession;
    private ExecutorService extractionExecutor;
    private DataSource.Factory dataSourceFactory;
    private final Set<String> watchedIds = new HashSet<>();

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();

        extractionExecutor = Executors.newFixedThreadPool(4);
        
        DataSource.Factory httpDataSourceFactory = createDataSourceFactory();
        dataSourceFactory = DownloadUtil.getCacheDataSourceFactory(this, httpDataSourceFactory);

        MediaSource.Factory mediaSourceFactory = new MergingMediaSourceFactory(this, dataSourceFactory, extractionExecutor);

        String policy = ConfigManager.getString("buffer_policy");
        int minBuffer = 15_000;
        int maxBuffer = 60_000;
        int playbackBuffer = 2_500;
        int afterRebuffer = 5_000;

        if ("Fast Start".equals(policy)) {
            minBuffer = 5_000;
            maxBuffer = 15_000;
            playbackBuffer = 500;
            afterRebuffer = 1_000;
        } else if ("High Stability".equals(policy)) {
            minBuffer = 60_000;
            maxBuffer = 120_000;
            playbackBuffer = 10_000;
            afterRebuffer = 15_000;
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        minBuffer,
                        maxBuffer,
                        playbackBuffer,
                        afterRebuffer
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(loadControl)
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();

        // Optional: Set a session activity to open the player when clicking the notification
        Intent intent = new Intent(this, xyz.sunkastudios.localtube.activity.PlayerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(pendingIntent)
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null && mediaItem.mediaId != null) {
                    if (watchedIds.contains(mediaItem.mediaId)) return;
                    
                    String videoUrl = null;
                    if (mediaItem.localConfiguration != null && mediaItem.localConfiguration.uri != null) {
                        String uri = mediaItem.localConfiguration.uri.toString();
                        
                        // FIX: Skip marking watched for local files and anime discovery URLs
                        String filesDir = getFilesDir().getAbsolutePath();
                        if (uri.startsWith("file://") || uri.contains(filesDir) || uri.contains("anime://")) {
                            Log.d("PlaybackService", "Skipping history for local/anime content: " + mediaItem.mediaId);
                            return;
                        }
                        
                        if (uri.contains("youtube.com") || uri.contains("youtu.be")) {
                            videoUrl = uri;
                        }
                    }
                    
                    // If it's a direct stream URL (googlevideo.com) or a local file, 
                    // reconstruct the YouTube URL from the mediaId
                    if (videoUrl == null && mediaItem.mediaId != null && mediaItem.mediaId.length() == 11) {
                        // Double check URI again for reconstruction case
                        String uri = mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri.toString() : "";
                        String filesDir = getFilesDir().getAbsolutePath();
                        if (uri.startsWith("file://") || uri.contains(filesDir)) {
                            return;
                        }
                        videoUrl = "https://www.youtube.com/watch?v=" + mediaItem.mediaId;
                    }

                    if (videoUrl != null && (videoUrl.contains("youtube.com") || videoUrl.contains("youtu.be"))) {
                        if (!ConfigManager.getBoolean("auto_add_history")) {
                            Log.d("PlaybackService", "Auto-history is disabled, skipping markWatched");
                            return;
                        }
                        final String finalUrl = videoUrl;
                        final String finalId = mediaItem.mediaId;
                        extractionExecutor.execute(() -> {
                            Log.d("PlaybackService", "Marking watched: " + finalId);
                            YoutubeEngine.markWatched(getApplicationContext(), finalUrl);
                            watchedIds.add(finalId);
                        });
                    }
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_PREPARE_PLAYLIST".equals(intent.getAction())) {
            Log.i("PlaybackService", "Guaranteed playlist preparation starting...");
            preparePlaylistFromIntent(intent);
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void preparePlaylistFromIntent(Intent intent) {
        String videoUri = intent.getStringExtra("video_uri");
        List<String> localVideoUris = intent.getStringArrayListExtra("playlist_video_uris");
        List<String> audioUris = intent.getStringArrayListExtra("playlist_audio_uris");
        List<String> videoIds = intent.getStringArrayListExtra("playlist_video_ids");
        int startIndex = intent.getIntExtra("playlist_start_index", 0);
        String targetVideoId = intent.getStringExtra("video_id");

        // Case 1: Online Playlist URL
        if (videoUri != null && videoUri.contains("list=")) {
            Log.i("PlaybackService", "Resolving online playlist: " + videoUri);
            extractionExecutor.execute(() -> {
                VideoFeed feed = YoutubeEngine.getPlaylistEntries(getApplicationContext(), videoUri);
                if (feed != null && feed.getEntries() != null) {
                    List<MediaItem> mediaItems = new ArrayList<>();
                    int sIndex = 0;
                    List<VideoItem> entries = feed.getEntries();
                    for (int i = 0; i < entries.size(); i++) {
                        VideoItem item = entries.get(i);
                        mediaItems.add(new MediaItem.Builder()
                                .setUri(item.getUrl())
                                .setMediaId(item.getId())
                                .setMediaMetadata(new MediaMetadata.Builder()
                                        .setTitle(item.getTitle())
                                        .build())
                                .build());
                        if (targetVideoId != null && targetVideoId.equals(item.getId())) sIndex = i;
                    }
                    final int finalSIndex = sIndex;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        player.setRepeatMode(Player.REPEAT_MODE_OFF);
                        player.setShuffleModeEnabled(false);
                        player.setMediaItems(mediaItems, finalSIndex, 0);
                        player.prepare();
                        player.setPlayWhenReady(true);
                        Log.i("PlaybackService", "Online playlist ready. Count: " + mediaItems.size());
                    });
                }
            });
            return;
        }

        // Case 2: Local Playlist (Downloads)
        if (localVideoUris == null || localVideoUris.isEmpty()) return;

        extractionExecutor.execute(() -> {
            List<MediaItem> mediaItems = new ArrayList<>();
            for (int i = 0; i < localVideoUris.size(); i++) {
                String vUri = localVideoUris.get(i);
                String aUri = (audioUris != null && i < audioUris.size()) ? audioUris.get(i) : null;
                String vId = (videoIds != null && i < videoIds.size()) ? videoIds.get(i) : vUri;

                MediaItem videoItem = new MediaItem.Builder()
                        .setUri(vUri.startsWith("/") ? Uri.fromFile(new File(vUri)) : Uri.parse(vUri))
                        .setMediaId(vId)
                        .build();

                MediaItem audioItem = (aUri != null && !aUri.isEmpty()) ? new MediaItem.Builder()
                        .setUri(aUri.startsWith("/") ? Uri.fromFile(new File(aUri)) : Uri.parse(aUri))
                        .setMediaId(vId + "_audio")
                        .build() : null;

                mediaItems.add(mergeItemsForService(videoItem, audioItem));
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
                player.setShuffleModeEnabled(false);
                player.setMediaItems(mediaItems, startIndex, 0);
                player.prepare();
                player.setPlayWhenReady(true);
                Log.i("PlaybackService", "Local playlist ready. Count: " + mediaItems.size());
            });
        });
    }

    private MediaItem mergeItemsForService(MediaItem videoItem, MediaItem audioItem) {
        if (videoItem == null) return null;
        
        long audio_delay = ConfigManager.getInt("audio_delay");
        long videoOffset = audio_delay > 0 ? audio_delay : 0;
        long audioOffset = audio_delay < 0 ? -audio_delay : 0;

        MediaItem.Builder videoBuilder = videoItem.buildUpon();
        if (videoOffset > 0) {
            videoBuilder.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(videoOffset)
                    .build());
        }

        if (audioItem == null) return videoBuilder.build();

        Bundle extras = new Bundle();
        if (audioItem.localConfiguration != null) {
            extras.putString("audio_uri", audioItem.localConfiguration.uri.toString());
        }
        if (audioOffset > 0) {
            extras.putLong("audio_start_ms", audioOffset);
        }

        return videoBuilder
                .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
                .build();
    }

    @OptIn(markerClass = UnstableApi.class)
    private DataSource.Factory createDataSourceFactory() {
        Map<String, String> defaultRequestHeaders = new HashMap<>();
        defaultRequestHeaders.put("Referer", "https://www.youtube.com/tv");
        defaultRequestHeaders.put("Accept", "*/*");
        defaultRequestHeaders.put("Accept-Language", "en-US,en;q=0.9");

        String userAgent = "Mozilla/5.0 (ChromiumStylePlatform; OpenTvVideoClient/) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36";

        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(defaultRequestHeaders);

        return new DefaultDataSource.Factory(this, httpDataSourceFactory);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }
    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
            player = null;
        }
        if (extractionExecutor != null) {
            extractionExecutor.shutdown();
        }
        super.onDestroy();
    }
    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        stopSelf();
    }

    /**
     * Custom MediaSource.Factory that intercepts MediaItems to build a MergingMediaSource
     * or resolve online URLs on-the-fly.
     */
    @UnstableApi
    private static class MergingMediaSourceFactory implements MediaSource.Factory {
        private final Context context;
        private final MediaSource.Factory baseFactory;
        private final ExecutorService executor;

        public MergingMediaSourceFactory(Context context, DataSource.Factory dataSourceFactory, ExecutorService executor) {
            this.context = context.getApplicationContext();
            
            // Customize extractors to be more lenient with YouTube streams
            ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory()
                    .setFragmentedMp4ExtractorFlags(FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK);
            
            this.baseFactory = new DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory);
            this.executor = executor;
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
            Bundle extras = mediaItem.mediaMetadata.extras;

            // Case 1: Pre-resolved (Local or single online video already processed)
            if (extras != null && extras.containsKey("audio_uri")) {
                return buildMergedSource(mediaItem, extras);
            }

            // Case 2: Custom headers (usually for Anime streams)
            if (extras != null && (extras.containsKey("Referer") || extras.containsKey("User-Agent") || extras.containsKey("Cookie"))) {
                return buildSourceWithHeaders(mediaItem, extras);
            }

            // Case 3: Online URL that needs resolution (e.g. from a playlist or shorts)
            String uriString = mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri.toString() : "";
            if (uriString.contains("youtube.com/watch") || uriString.contains("youtu.be/") || uriString.contains("youtube.com/shorts/")) {
                return new ResolvingMediaSource(mediaItem, baseFactory, executor, context);
            }

            return baseFactory.createMediaSource(mediaItem);
        }

        private MediaSource buildSourceWithHeaders(MediaItem mediaItem, Bundle headers) {
            Map<String, String> headerMap = new HashMap<>();
            for (String key : headers.keySet()) {
                Object val = headers.get(key);
                if (val instanceof String) {
                    headerMap.put(key, (String) val);
                }
            }

            Log.d("PlaybackService", "Building source with custom headers for: " + mediaItem.mediaId);
            
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(headerMap);
            
            if (headerMap.containsKey("User-Agent")) {
                httpFactory.setUserAgent(headerMap.get("User-Agent"));
            }

            DataSource.Factory dsFactory = new DefaultDataSource.Factory(context, httpFactory);
            
            // Explicitly handle HLS MIME type if URL looks like HLS
            MediaItem.Builder builder = mediaItem.buildUpon();
            String uri = mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri.toString() : "";
            if (uri.contains(".m3u8") || uri.contains("master.m3u8") || uri.contains("index.m3u8")) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8);
            }

            return new DefaultMediaSourceFactory(dsFactory).createMediaSource(builder.build());
        }

        private MediaSource buildMergedSource(MediaItem mediaItem, Bundle extras) {
            String audioUri = extras.getString("audio_uri");
            String audioMimeType = extras.getString("audio_mime_type");
            long audioStartMs = extras.getLong("audio_start_ms", 0);

            if (audioMimeType == null && audioUri != null) {
                audioMimeType = inferMimeTypeFromUri(audioUri);
            }

            Log.d("PlaybackService", "Building merged source for: " + mediaItem.mediaId + " with audio: " + audioUri + " (" + audioMimeType + ")");

            MediaSource videoSource = baseFactory.createMediaSource(mediaItem);

            MediaItem.Builder audioItemBuilder = new MediaItem.Builder().setUri(audioUri);
            if (audioMimeType != null) {
                audioItemBuilder.setMimeType(audioMimeType);
            }
            if (audioStartMs > 0) {
                audioItemBuilder.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(audioStartMs)
                        .build());
            }
            MediaSource audioSource = baseFactory.createMediaSource(audioItemBuilder.build());

            return new MergingMediaSource(
                    /* adjustPeriodTimeOffsets= */ true,
                    /* clipEditStarts= */ true,
                    videoSource,
                    audioSource
            );
        }

        private String inferMimeTypeFromUri(String uri) {
            if (uri == null) return null;
            String lower = uri.toLowerCase();
            if (lower.endsWith(".webm")) return MimeTypes.AUDIO_WEBM;
            if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return MimeTypes.AUDIO_MP4;
            if (lower.endsWith(".mp3")) return MimeTypes.AUDIO_MPEG;
            return null;
        }

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(@Nullable androidx.media3.exoplayer.drm.DrmSessionManagerProvider drmSessionManagerProvider) {
            assert drmSessionManagerProvider != null;
            baseFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
            return this;
        }
        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(@Nullable androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            assert loadErrorHandlingPolicy != null;
            baseFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
            return this;
        }
        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return baseFactory.getSupportedTypes();
        }
    }

    /**
     * A MediaSource that resolves a YouTube URL to direct stream URLs on-demand.
     */
    @UnstableApi
    private static class ResolvingMediaSource extends CompositeMediaSource<Void> {
        private final MediaItem mediaItem;
        private final MediaSource.Factory baseFactory;
        private final ExecutorService executor;
        private final Context context;
        private MediaSource resolvedSource;
        private boolean isReleased;

        public ResolvingMediaSource(MediaItem mediaItem, MediaSource.Factory baseFactory, ExecutorService executor, Context context) {
            this.mediaItem = mediaItem;
            this.baseFactory = baseFactory;
            this.executor = executor;
            this.context = context.getApplicationContext();
        }

        @NonNull
        @Override
        public MediaItem getMediaItem() {
            return mediaItem;
        }

        @Override
        protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
            isReleased = false;
            super.prepareSourceInternal(mediaTransferListener);
            if (!NetworkManager.isOnline(context)) {
                Log.w("PlaybackService", "No internet for resolving media source");
                return;
            }
            executor.execute(() -> {
                try {
                    String url = mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri.toString() : "";
                    
                    if (url.contains("youtube.com/shorts/")) {
                        String[] urls = YoutubeEngine.getShortsUrls(context, url);
                        if (urls != null && urls.length > 0) {
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (isReleased) return;
                                resolvedSource = buildSourceFromUrls(urls);
                                prepareChildSource(null, resolvedSource);
                            });
                            return;
                        }
                    }
                    
                    List<VideoMetaData> metaDataList = YoutubeEngine.getVideoFormats(context, url);
                    if (metaDataList != null && !metaDataList.isEmpty()) {
                        VideoMetaData metaData = metaDataList.get(0);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            if (isReleased) return;
                            resolvedSource = createSourceFromMetaData(metaData);
                            prepareChildSource(null, resolvedSource);
                        });
                    }
                } catch (Exception e) {
                    Log.e("PlaybackService", "Resolution failed for: " + mediaItem.mediaId, e);
                }
            });
        }

        private MediaSource buildSourceFromUrls(String[] urls) {
            String videoUrl = urls[0];
            String audioUrl = urls.length > 1 ? urls[1] : null;

            MediaItem vItem = new MediaItem.Builder().setUri(videoUrl).build();
            MediaSource vSource = baseFactory.createMediaSource(vItem);

            if (audioUrl != null) {
                MediaItem aItem = new MediaItem.Builder().setUri(audioUrl).build();
                MediaSource aSource = baseFactory.createMediaSource(aItem);
                return new MergingMediaSource(/* adjustPeriodTimeOffsets= */ true, /* clipEditStarts= */ true, vSource, aSource);
            }

            return vSource;
        }

        private MediaSource createSourceFromMetaData(VideoMetaData metaData) {
            // Prefer DASH manifest if available
            if (metaData.getDashUrl() != null) {
                Log.d("PlaybackService", "Using DASH manifest for: " + metaData.getId());
                MediaItem dashItem = buildMediaItem(metaData.getDashUrl(), MimeTypes.APPLICATION_MPD, 0);
                return baseFactory.createMediaSource(dashItem);
            }

            // Prefer HLS manifest if available
            if (metaData.getHlsUrl() != null) {
                Log.d("PlaybackService", "Using HLS manifest for: " + metaData.getId());
                MediaItem hlsItem = buildMediaItem(metaData.getHlsUrl(), MimeTypes.APPLICATION_M3U8, 0);
                return baseFactory.createMediaSource(hlsItem);
            }

            List<FormatItem> formats = metaData.getFormats();
            int preferredHeight = ConfigManager.getInt("preferred_quality");

            FormatItem bestVideo = formats.stream()
                    .filter(f -> f.isVideoOnly() && f.isDirectStream())
                    .filter(f -> f.getHeight() <= preferredHeight)
                    .max(Comparator.comparingInt(FormatItem::getHeight))
                    .orElse(null);
            
            if (bestVideo == null) {
                bestVideo = formats.stream()
                        .filter(f -> f.isVideoOnly() && f.isDirectStream())
                        .max(Comparator.comparingInt(FormatItem::getHeight))
                        .orElse(null);
            }

            FormatItem bestAudio = YoutubeEngine.findBestAudio(formats);

            long audio_delay = ConfigManager.getInt("audio_delay");
            long videoOffset = audio_delay > 0 ? audio_delay : 0;
            long audioOffset = audio_delay < 0 ? -audio_delay : 0;

            if (bestVideo != null && bestAudio != null) {
                Log.d("PlaybackService", "Merging separate audio/video streams for: " + metaData.getId() + " with delay: " + audio_delay + " (Target: " + preferredHeight + "p)");
                MediaItem vItem = buildMediaItem(bestVideo.getUrl(), inferMimeType(bestVideo, true), videoOffset);
                MediaItem aItem = buildMediaItem(bestAudio.getUrl(), inferMimeType(bestAudio, false), audioOffset);

                // Using the provided factory for better compatibility
                MediaSource vSource = baseFactory.createMediaSource(vItem);
                MediaSource aSource = baseFactory.createMediaSource(aItem);

                return new MergingMediaSource(/* adjustPeriodTimeOffsets= */ true, /* clipEditStarts= */ true, vSource, aSource);
            }

            FormatItem bestCombined = formats.stream()
                    .filter(f -> f.isCombined() && f.isDirectStream())
                    .filter(f -> f.getHeight() <= preferredHeight)
                    .max(Comparator.comparingInt(FormatItem::getHeight))
                    .orElse(null);
            
            if (bestCombined == null) {
                bestCombined = formats.stream()
                        .filter(f -> f.isCombined() && f.isDirectStream())
                        .max(Comparator.comparingInt(FormatItem::getHeight))
                        .orElse(null);
            }

            if (bestCombined != null) {
                Log.d("PlaybackService", "Using combined stream for: " + metaData.getId() + " with delay: " + audio_delay);
                MediaItem vItem = buildMediaItem(bestCombined.getUrl(), inferMimeType(bestCombined, true), videoOffset);
                
                if (audio_delay != 0) {
                    // Force sync even on combined stream by merging it with itself using offsets
                    MediaItem aItem = buildMediaItem(bestCombined.getUrl(), inferMimeType(bestCombined, false), audioOffset);
                    return new MergingMediaSource(true, true, baseFactory.createMediaSource(vItem), baseFactory.createMediaSource(aItem));
                }
                
                return baseFactory.createMediaSource(vItem);
            }

            Log.w("PlaybackService", "No suitable formats found, falling back to original item");
            return baseFactory.createMediaSource(mediaItem);
        }

        private MediaItem buildMediaItem(String url, String mimeType, long offsetMs) {
            MediaItem.Builder builder = new MediaItem.Builder().setUri(url).setMimeType(mimeType);
            if (offsetMs > 0) {
                builder.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(offsetMs)
                        .build());
            }
            return builder.build();
        }

        private String inferMimeType(FormatItem item, boolean isVideo) {
            if (item.getExt() != null) {
                String ext = item.getExt().toLowerCase();
                if (ext.equals("webm")) return isVideo ? MimeTypes.VIDEO_WEBM : MimeTypes.AUDIO_WEBM;
                if (ext.equals("mp4") || ext.equals("m4a")) return isVideo ? MimeTypes.VIDEO_MP4 : MimeTypes.AUDIO_MP4;
            }
            return isVideo ? MimeTypes.VIDEO_MP4 : MimeTypes.AUDIO_MP4;
        }

        @Override
        protected void onChildSourceInfoRefreshed(Void childSourceId, MediaSource mediaSource, Timeline timeline) {
            refreshSourceInfo(timeline);
        }

        @NonNull
        @Override
        public MediaPeriod createPeriod(@NonNull MediaPeriodId id, @NonNull Allocator allocator, long startPositionUs) {
            if (resolvedSource == null) {
                // This shouldn't happen as prepareChildSource is called before refreshSourceInfo
                throw new IllegalStateException("Source not resolved yet");
            }
            return resolvedSource.createPeriod(id, allocator, startPositionUs);
        }

        @Override
        public void releasePeriod(@NonNull MediaPeriod mediaPeriod) {
            if (resolvedSource != null) {
                resolvedSource.releasePeriod(mediaPeriod);
            }
        }

        @Override
        protected void releaseSourceInternal() {
            isReleased = true;
            super.releaseSourceInternal();
            resolvedSource = null;
        }
    }
}
