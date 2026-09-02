package xyz.sunkastudios.localtube.activity;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import xyz.sunkastudios.localtube.FormatItem;
import xyz.sunkastudios.localtube.PlaybackService;
import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.VideoFeed;
import xyz.sunkastudios.localtube.VideoItem;
import xyz.sunkastudios.localtube.VideoMetaData;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.UIUtil;

public class PlayerActivity extends AppCompatActivity {
    private static final String TAG = "PlayerActivity";

    private PlayerView playerView;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController player;
    private ProgressBar progressBar;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Future<?> extractionTask;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        UIUtil.applyInsets(this);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.navigationBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        playerView = findViewById(R.id.playerView);
        progressBar = findViewById(R.id.playerLoadingSpinner);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showTrackSelectionDialog() {
        if (player == null) return;

        new TrackSelectionDialogBuilder(this, "Select Audio Track", player, C.TRACK_TYPE_AUDIO)
                .build()
                .show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();

        controllerFuture.addListener(new Runnable() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public void run() {
                try {
                    player = controllerFuture.get();
                    playerView.setPlayer(player);
                    
                    // Fullscreen Toggle (Rotation)
                    playerView.setFullscreenButtonClickListener(isFullScreen -> {
                        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                        } else {
                            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                        }
                    });

                    View settingsBtn = playerView.findViewById(androidx.media3.ui.R.id.exo_settings);
                    if (settingsBtn != null) {
                        settingsBtn.setOnClickListener(v -> showTrackSelectionDialog());
                    }

                    player.addListener(new Player.Listener() {
                        @Override
                        public void onPlaybackStateChanged(int playbackState) {
                            if (progressBar != null) {
                                progressBar.setVisibility(playbackState == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                            }
                        }
                    });

                    playerView.showController();
                    handleIntent(getIntent());

                } catch (Exception e) {
                    Toast.makeText(PlayerActivity.this, "Failed to connect to playback service", Toast.LENGTH_SHORT).show();
                }
            }
        }, MoreExecutors.directExecutor());
    }

    @OptIn(markerClass = UnstableApi.class)
    private void handleIntent(Intent intent) {
        boolean isOnline = intent.getBooleanExtra("online", true);
        boolean isDirect = intent.getBooleanExtra("is_direct", false);
        
        if (isOnline && !NetworkManager.isOnline(this)) {
            finish();
            return;
        }
        
        String videoUri = intent.getStringExtra("video_uri");
        String videoId = intent.getStringExtra("video_id");
        Bundle headers = intent.getBundleExtra("headers");

        if (videoUri == null || videoUri.isEmpty()) {
            Toast.makeText(this, "Invalid video URI", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // RESET: Ensure normal playback defaults (fixes leak from Shorts)
        if (player != null) {
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
        }

        // IMPROVED: Check if already playing this content to prevent restart on screen off/on
        if (player.getMediaItemCount() > 0) {
            MediaItem currentItem = player.getCurrentMediaItem();
            if (currentItem != null) {
                // If single video, check mediaId
                if (!videoUri.contains("list=") && videoId != null && videoId.equals(currentItem.mediaId)) {
                    Log.d(TAG, "Already playing this video, skipping loadIntent");
                    return;
                }
                
                // If playlist, check if current video is part of the playlist being requested
                if (videoUri.contains("list=")) {
                    String reqListId = getPlaylistId(videoUri);
                    if (reqListId != null && player.getMediaItemCount() > 1) {
                        Log.d(TAG, "Already playing a playlist, skipping loadIntent to prevent restart.");
                        return;
                    }
                }

                List<String> playlistVideoIds = intent.getStringArrayListExtra("playlist_video_ids");
                if (playlistVideoIds != null && !playlistVideoIds.isEmpty()) {
                    if (playlistVideoIds.contains(currentItem.mediaId)) {
                        Log.d(TAG, "Already playing a video from this local playlist, skipping loadIntent");
                        return;
                    }
                }
                
                // Also check URI as fallback
                if (videoUri.equals(currentItem.localConfiguration != null ? currentItem.localConfiguration.uri.toString() : null)) {
                    Log.d(TAG, "Already playing this URI, skipping loadIntent");
                    return;
                }
            }
        }

        if (isDirect) {
            playDirectUri(videoUri, videoId, headers);
            return;
        }

        // IMPROVED: If it's a local playlist, skip loading the single video 
        // to let PlaybackService handle the ACTION_PREPARE_PLAYLIST task.
        if (!isOnline && intent.hasExtra("playlist_video_uris")) {
            Log.d(TAG, "Local playlist detected, skipping single-video load to let Service handle it.");
            return;
        }

        if (isOnline) {
            if (videoUri.contains("list=")) {
                loadOnlinePlaylist(videoUri, videoId);
            } else {
                loadOnlineVideo(videoUri, videoId);
            }
        } else {
            loadLocalVideo(videoUri, intent.getStringExtra("audio_uri"), videoId);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void playDirectUri(String uri, String mediaId, Bundle headers) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(uri)
                .setMediaId(mediaId);

        if (headers != null) {
            builder.setMediaMetadata(new MediaMetadata.Builder().setExtras(headers).build());
        }

        play(mergeItems(builder.build(), null));
    }

    @OptIn(markerClass = UnstableApi.class)
    private void loadLocalVideo(String videoPath, String audioPath, String videoId) {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        MediaItem videoItem = createBaseLocalItem(videoPath, videoId);
        MediaItem audioItem = (audioPath != null && !audioPath.isEmpty()) ? createBaseLocalItem(audioPath, videoId + "_audio") : null;
        play(mergeItems(videoItem, audioItem));
    }

    @OptIn(markerClass = UnstableApi.class)
    private MediaItem createBaseLocalItem(String path, String mediaId) {
        return new MediaItem.Builder()
                .setUri(Uri.fromFile(new File(path)))
                .setMediaId(mediaId)
                .build();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void loadOnlinePlaylist(String url, String targetVideoId) {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        extractionTask = executorService.submit(() -> {
            VideoFeed feed = YoutubeEngine.getPlaylistEntries(getApplicationContext(), url);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);

                if (feed == null || feed.getEntries() == null || feed.getEntries().isEmpty()) {
                    Toast.makeText(this, "Failed to load playlist.", Toast.LENGTH_SHORT).show();
                    return;
                }

                List<MediaItem> mediaItems = new ArrayList<>();
                int startIndex = 0;
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

                    if (targetVideoId != null && targetVideoId.equals(item.getId())) {
                        startIndex = i;
                    }
                }

                Log.d(TAG, "Loading online playlist. Count: " + mediaItems.size());
                player.setRepeatMode(Player.REPEAT_MODE_OFF);
                player.setMediaItems(mediaItems, startIndex, 0);
                player.prepare();
                player.setPlayWhenReady(true);
            });
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void loadOnlineVideo(String videoUrl, String videoId) {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        extractionTask = executorService.submit(() -> {
            List<VideoMetaData> metaDataList = YoutubeEngine.getVideoFormats(getApplicationContext(), videoUrl);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (metaDataList == null || metaDataList.isEmpty()) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    Toast.makeText(PlayerActivity.this, "Failed to extract video details.", Toast.LENGTH_SHORT).show();
                    return;
                }
                VideoMetaData primaryItem = metaDataList.get(0);
                if (primaryItem.getFormats() != null && !primaryItem.getFormats().isEmpty()) {
                    if (progressBar != null) progressBar.setVisibility(View.GONE);
                    play(createMediaItemFromFormats(primaryItem.getFormats(), videoId));
                } else {
                    Toast.makeText(PlayerActivity.this, "Unable to resolve stream formats.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void play(MediaItem mediaItem) {
        if (mediaItem != null && player != null) {
            Log.d(TAG, "Starting playback. Resetting repeat/shuffle modes.");
            player.setRepeatMode(Player.REPEAT_MODE_OFF);
            // We might want to keep shuffle mode if the user explicitly enabled it, 
            // but for a single video it doesn't matter much.
            // For now, let's keep it consistent with playlists.
            
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);
        } else {
            Toast.makeText(PlayerActivity.this, "No playable stream formats found.", Toast.LENGTH_SHORT).show();
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private MediaItem mergeItems(MediaItem videoItem, MediaItem audioItem) {
        if (videoItem == null) return null;
        long audio_delay = ConfigManager.getInt("audio_delay");
        
        // If no delay is needed and we only have one stream, return it as is
        if (audio_delay == 0 && audioItem == null) return videoItem;

        long videoOffset = audio_delay > 0 ? audio_delay : 0;
        long audioOffset = audio_delay < 0 ? -audio_delay : 0;

        MediaItem clippedVideo = applyClipping(videoItem, videoOffset);
        
        // If we only have one file (like a direct anime link or local single-stream mp4),
        // we can still sync by merging the same file as both video and audio with different offsets.
        MediaItem targetAudio = (audioItem != null) ? audioItem : videoItem;
        MediaItem clippedAudio = applyClipping(targetAudio, audioOffset);

        Bundle extras = new Bundle();
        if (clippedAudio.localConfiguration != null) {
            extras.putString("audio_uri", clippedAudio.localConfiguration.uri.toString());
            if (clippedAudio.localConfiguration.mimeType != null) extras.putString("audio_mime_type", clippedAudio.localConfiguration.mimeType);
        }
        if (clippedAudio.clippingConfiguration != null) {
            extras.putLong("audio_start_ms", clippedAudio.clippingConfiguration.startPositionMs);
        }

        return clippedVideo.buildUpon()
                .setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build())
                .build();
    }

    @OptIn(markerClass = UnstableApi.class)
    private MediaItem applyClipping(MediaItem mediaItem, long offsetMs) {
        if (offsetMs > 0) {
            return mediaItem.buildUpon()
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(offsetMs)
                            .build())
                    .build();
        }
        return mediaItem;
    }

    @OptIn(markerClass = UnstableApi.class)
    private MediaItem createMediaItemFromFormats(List<xyz.sunkastudios.localtube.FormatItem> formats, String videoId) {
        int preferredHeight = ConfigManager.getInt("preferred_quality");
        
        xyz.sunkastudios.localtube.FormatItem bestVideo = formats.stream()
                .filter(f -> f.isVideoOnly() && f.isDirectStream())
                .filter(f -> f.getHeight() <= preferredHeight) // Respect preference
                .max(Comparator.comparingInt(xyz.sunkastudios.localtube.FormatItem::getHeight))
                .orElse(null);
                
        if (bestVideo == null) {
            // Fallback to highest available if preference not met
            bestVideo = formats.stream()
                    .filter(f -> f.isVideoOnly() && f.isDirectStream())
                    .max(Comparator.comparingInt(xyz.sunkastudios.localtube.FormatItem::getHeight))
                    .orElse(null);
        }

        xyz.sunkastudios.localtube.FormatItem bestAudio = YoutubeEngine.findBestAudio(formats);

        if (bestVideo != null && bestAudio != null) {
            MediaItem videoItem = new MediaItem.Builder().setUri(bestVideo.getUrl()).setMimeType(inferMimeType(bestVideo, true)).setMediaId(videoId != null ? videoId : bestVideo.getUrl()).build();
            MediaItem audioItem = new MediaItem.Builder().setUri(bestAudio.getUrl()).setMimeType(inferMimeType(bestAudio, false)).setMediaId(videoId != null ? videoId + "_audio" : bestAudio.getUrl()).build();
            return mergeItems(videoItem, audioItem);
        }

        xyz.sunkastudios.localtube.FormatItem bestCombined = formats.stream()
                .filter(f -> f.isCombined() && f.isDirectStream())
                .filter(f -> f.getHeight() <= preferredHeight)
                .max(Comparator.comparingInt(xyz.sunkastudios.localtube.FormatItem::getHeight))
                .orElse(null);
        
        if (bestCombined == null) {
            bestCombined = formats.stream()
                .filter(f -> f.isCombined() && f.isDirectStream())
                .max(Comparator.comparingInt(xyz.sunkastudios.localtube.FormatItem::getHeight))
                .orElse(null);
        }

        if (bestCombined != null) {
            return new MediaItem.Builder().setUri(bestCombined.getUrl()).setMimeType(inferMimeType(bestCombined, true)).setMediaId(videoId != null ? videoId : bestCombined.getUrl()).build();
        }
        return null;
    }

    private String inferMimeType(xyz.sunkastudios.localtube.FormatItem item, boolean isVideo) {
        if (item.getExt() != null) {
            String ext = item.getExt().toLowerCase();
            if (ext.equals("webm")) return isVideo ? MimeTypes.VIDEO_WEBM : MimeTypes.AUDIO_WEBM;
            if (ext.equals("mp4") || ext.equals("m4a")) return isVideo ? MimeTypes.VIDEO_MP4 : MimeTypes.AUDIO_MP4;
        }
        String url = item.getUrl().toLowerCase();
        if (url.contains("mime=video%2fwebm") || url.contains("mime=video/webm") || url.contains("ext=webm")) return MimeTypes.VIDEO_WEBM;
        if (url.contains("mime=audio%2fwebm") || url.contains("mime=audio/webm")) return MimeTypes.AUDIO_WEBM;
        if (url.contains("mime=audio%2fmp4") || url.contains("mime=audio/mp4")) return MimeTypes.AUDIO_MP4;
        return isVideo ? MimeTypes.VIDEO_MP4 : MimeTypes.AUDIO_MP4;
    }

    private String getPlaylistId(String url) {
        if (url == null) return null;
        try {
            Uri uri = Uri.parse(url);
            return uri.getQueryParameter("list");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onNewIntent(@androidx.annotation.NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (player != null) {
            handleIntent(intent);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isFinishing() && player != null) {
            player.stop();
            player.clearMediaItems();
        }
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (extractionTask != null) extractionTask.cancel(true);
        executorService.shutdown();
    }
}
