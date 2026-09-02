package xyz.sunkastudios.localtube.activity;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.DownloadedVideo;
import xyz.sunkastudios.localtube.PlaybackService;
import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.ShortItem;
import xyz.sunkastudios.localtube.ShortsAdapter;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.DownloadProgressManager;
import xyz.sunkastudios.localtube.util.DownloadStore;
import xyz.sunkastudios.localtube.util.DownloadUtil;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.PrefetchManager;
import xyz.sunkastudios.localtube.util.UIUtil;

@UnstableApi
public class ShortsActivity extends AppCompatActivity {
    private static final String TAG = "ShortsActivity";

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_ONLINE = "online";
    public static final String MODE_LOCAL = "local";
    public static final String EXTRA_FOLDER_TYPE = "folder_type"; // "UNWATCHED", "WATCHED", or "ALL"

    private String currentMode = MODE_ONLINE;
    private String folderType = "ALL";

    private final List<ShortItem> videoList = new ArrayList<>();
    private RecyclerView recyclerView;
    private ShortsAdapter adapter;
    private LinearLayoutManager layoutManager;
    
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController player;
    
    private boolean isLoading = false;
    private static final int PRELOAD_THRESHOLD = 3;
    private static final int PREFETCH_COUNT = 5;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService urlFetcherExecutor = Executors.newFixedThreadPool(3);
    private final Set<String> fetchingUrls = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable bufferingRunnable;
    private Runnable updateProgressRunnable;
    private int currentPlayingPosition = -1;
    private boolean isUserSeeking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (getIntent() != null) {
            currentMode = getIntent().getStringExtra(EXTRA_MODE);
            if (currentMode == null) currentMode = MODE_ONLINE;

            folderType = getIntent().getStringExtra(EXTRA_FOLDER_TYPE);
            if (folderType == null) folderType = "ALL";
        }

        setContentView(R.layout.activity_shorts);
        UIUtil.applyInsets(this);

        recyclerView = findViewById(R.id.recyclerView);
        layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);

        adapter = new ShortsAdapter(videoList);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.bottomMenuBar).setBackground(new ColorDrawable(ConfigManager.getColor("navbar_background_color", "#777777")));
        ((android.widget.ImageButton)findViewById(R.id.btnDownloadedShorts)).setColorFilter(UIUtil.getAccentColor());

        DownloadProgressManager.attachProgressView(this, this, findViewById(R.id.layout_download_progress));

        // Add snapping behavior (one item at a time)
        PagerSnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(recyclerView);

        // Playback/Pagination listener
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    playCurrentVisibleItem();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                
                int totalItemCount = layoutManager.getItemCount();
                int lastVisibleItem = layoutManager.findLastVisibleItemPosition();
                int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();

                if (MODE_ONLINE.equals(currentMode)) {
                    if (firstVisibleItem != -1) {
                        prefetchUrls(firstVisibleItem);
                    }

                    if (!isLoading && totalItemCount <= (lastVisibleItem + PRELOAD_THRESHOLD)) {
                        loadVideos(false);
                    }
                }
            }
        });

        setupNavigation();

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        if (MODE_LOCAL.equals(currentMode)) {
            swipeRefreshLayout.setEnabled(false);
        } else {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                if (NetworkManager.checkInternet(this)) {
                    loadVideos(true);
                } else {
                    swipeRefreshLayout.setRefreshing(false);
                }
            });
        }
        
        loadVideos(true);
    }

    private void setupNavigation() {
        findViewById(R.id.btnYoutubeHome).setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        });
        findViewById(R.id.btnYoutubeShorts).setOnClickListener(v -> {
            if (MODE_ONLINE.equals(currentMode)) {
                loadVideos(true);
            } else {
                Intent intent = new Intent(this, ShortsActivity.class);
                intent.putExtra(EXTRA_MODE, MODE_ONLINE);
                startActivity(intent);
                finish();
            }
        });
        findViewById(R.id.btnAniHome).setOnClickListener(v -> {
            startActivity(new Intent(this, AniHomeActivity.class));
        });
        findViewById(R.id.btnDownloads).setOnClickListener(v -> {
            startActivity(new Intent(this, DownloadsActivity.class));
            finish();
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        });

        View btnDownloadedShorts = findViewById(R.id.btnDownloadedShorts);
        if (btnDownloadedShorts != null) {
            btnDownloadedShorts.setOnClickListener(v -> startActivity(new Intent(this, DownloadedShortsActivity.class)));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        initializePlayer();
    }

    private void initializePlayer() {
        SessionToken sessionToken = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                player = controllerFuture.get();
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (playbackState == Player.STATE_READY) {
                            hideThumbnailForCurrentItem();
                            updateBufferingSpinner(false);
                            startProgressUpdates();
                        } else if (playbackState == Player.STATE_BUFFERING) {
                            updateBufferingSpinner(true);
                        } else if (playbackState == Player.STATE_ENDED) {
                            updateBufferingSpinner(false);
                            markCurrentAsWatched();
                        } else {
                            updateBufferingSpinner(false);
                        }
                    }

                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        updatePlayPauseIcon(isPlaying);
                        if (isPlaying) {
                            startProgressUpdates();
                        } else {
                            stopProgressUpdates();
                        }
                    }

                    @Override
                    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                        // When transitioning between items, we can mark the previous one as watched
                        // if we want more aggressive "watched" tracking.
                        // For now, STATE_ENDED handles the full watch, 
                        // but if we want it to move folders even if skipped near the end:
                        markCurrentAsWatched();
                    }
                });
                // Once controller is ready, try playing the first item if list is already loaded
                if (!videoList.isEmpty()) {
                    updatePlayerPlaylist(videoList);
                    playCurrentVisibleItem();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to playback service", e);
            }
        }, MoreExecutors.directExecutor());
    }

    private void playCurrentVisibleItem() {
        if (player == null) return;

        int position = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (position == -1) position = layoutManager.findFirstVisibleItemPosition();
        
        if (position >= 0 && position < videoList.size()) {
            // Clean up previous holder if it's different to avoid surface conflicts
            if (currentPlayingPosition != -1 && currentPlayingPosition != position) {
                ShortsAdapter.ShortViewHolder prevHolder = (ShortsAdapter.ShortViewHolder) 
                        recyclerView.findViewHolderForAdapterPosition(currentPlayingPosition);
                if (prevHolder != null) {
                    prevHolder.playerView.setPlayer(null);
                }
            }
            
            currentPlayingPosition = position;
            ShortItem item = videoList.get(position);
            Log.i(TAG, "Playing Short at position " + position + ": " + item.getVideoId());

            // Get the view holder to attach the player
            ShortsAdapter.ShortViewHolder holder = (ShortsAdapter.ShortViewHolder) 
                    recyclerView.findViewHolderForAdapterPosition(position);
            
            if (holder != null) {
                // re-bind player to ensure surface is active
                holder.playerView.setPlayer(null);
                holder.playerView.setPlayer(player);
                
                holder.clickOverlay.setOnClickListener(v -> {
                    // Toggle Playback
                    if (player.isPlaying()) {
                        player.pause();
                        flashPlayPauseIcon(false);
                    } else {
                        player.play();
                        flashPlayPauseIcon(true);
                    }
                });

                holder.seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && player != null) {
                            long duration = player.getDuration();
                            if (duration > 0) {
                                long newPos = (duration * progress) / 1000;
                                player.seekTo(newPos);
                            }
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                        isUserSeeking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                        isUserSeeking = false;
                    }
                });
                
                if (player.getMediaItemCount() > position) {
                    if (player.getCurrentMediaItemIndex() != position) {
                        player.seekTo(position, 0);
                    }
                    player.prepare();
                    player.setPlayWhenReady(true);
                    
                    // Delay to ensure surface is ready and properly attached
                    final int targetPos = position;
                    holder.playerView.postDelayed(() -> {
                         if (layoutManager.findFirstVisibleItemPosition() == targetPos) {
                             player.play();
                         }
                    }, 100);
                } else {
                    // Fallback if playlist is not in sync
                    MediaItem mediaItem = createMediaItem(item);
                    player.setMediaItem(mediaItem);
                    player.prepare();
                    player.setPlayWhenReady(true);
                    player.play();
                }
            }
        }
    }

    private void updatePlayerPlaylist(List<ShortItem> newShorts) {
        if (player == null) return;
        List<MediaItem> mediaItems = new ArrayList<>();
        for (ShortItem item : newShorts) {
            mediaItems.add(createMediaItem(item));
        }
        player.setMediaItems(mediaItems);
    }

    private MediaItem createMediaItem(ShortItem item) {
        long audio_delay = ConfigManager.getInt("audio_delay");
        long videoOffset = audio_delay > 0 ? audio_delay : 0;
        long audioOffset = audio_delay < 0 ? -audio_delay : 0;

        MediaItem.Builder builder = new MediaItem.Builder()
                .setMediaId(item.getVideoId());
        
        if (videoOffset > 0) {
            builder.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(videoOffset)
                    .build());
        }

        if (item.getResolvedVideoUrl() != null) {
            builder.setUri(item.getResolvedVideoUrl());
            if (item.getVideoMimeType() != null) {
                builder.setMimeType(item.getVideoMimeType());
            }
            
            String audioUri = item.getResolvedAudioUrl();
            if (audioUri == null && audio_delay != 0) {
                // Force sync for single stream short by using same URL as audio
                audioUri = item.getResolvedVideoUrl();
            }

            if (audioUri != null) {
                Bundle extras = new Bundle();
                extras.putString("audio_uri", audioUri);
                extras.putLong("audio_start_ms", audioOffset);
                if (item.getAudioMimeType() != null) {
                    extras.putString("audio_mime_type", item.getAudioMimeType());
                }
                builder.setMediaMetadata(new MediaMetadata.Builder().setExtras(extras).build());
            }
        } else {
            String shortUrl = item.getUrl();
            if (shortUrl != null && !shortUrl.startsWith("http")) {
                shortUrl = "https://www.youtube.com/shorts/" + item.getVideoId();
            }
            builder.setUri(shortUrl);
            
            // For un-resolved URLs that will be resolved by the Service,
            // the Service handles the delay based on metadata or global config.
        }
        return builder.build();
    }

    private void prefetchUrls(int currentPosition) {
        if (currentPosition == -1 || MODE_LOCAL.equals(currentMode)) return;
        for (int i = 1; i <= PREFETCH_COUNT; i++) {
            int targetPos = currentPosition + i;
            if (targetPos < videoList.size()) {
                ShortItem item = videoList.get(targetPos);
                if (item.getResolvedVideoUrl() == null) {
                    fetchUrlsForItem(item);
                }
            }
        }
    }

    private void fetchUrlsForItem(ShortItem item) {
        String videoId = item.getVideoId();
        synchronized (fetchingUrls) {
            if (fetchingUrls.contains(videoId)) return;
            fetchingUrls.add(videoId);
        }

        String shortUrl = item.getUrl();
        if (shortUrl != null && !shortUrl.startsWith("http")) {
            shortUrl = "https://www.youtube.com/shorts/" + videoId;
        }
        final String finalUrl = shortUrl;

        urlFetcherExecutor.execute(() -> {
            Log.d(TAG, "Fetching URLs for Short: " + videoId);
            String[] urls = YoutubeEngine.getShortsUrls(getApplicationContext(), finalUrl);
            if (urls != null && urls.length > 0) {
                String videoUrl = urls[0];
                String audioUrl = urls.length > 1 ? urls[1] : null;
                item.setResolvedUrls(videoUrl, audioUrl);
                Log.d(TAG, "Resolved URLs for: " + videoId);

                // Pre-cache the first 1MB of video and 256KB of audio
                DownloadUtil.preCache(getApplicationContext(), videoUrl, 1024 * 1024);
                if (audioUrl != null) {
                    DownloadUtil.preCache(getApplicationContext(), audioUrl, 256 * 1024);
                }
                
                runOnUiThread(() -> {
                    if (player != null) {
                        int index = videoList.indexOf(item);
                        if (index != -1 && index < player.getMediaItemCount()) {
                            // Don't replace if it's the currently playing item to avoid interruption
                            if (index != player.getCurrentMediaItemIndex()) {
                                player.replaceMediaItem(index, createMediaItem(item));
                            }
                        }
                    }
                });
            }
            synchronized (fetchingUrls) {
                fetchingUrls.remove(videoId);
            }
        });
    }

    private void hideThumbnailForCurrentItem() {
        int position = layoutManager.findFirstVisibleItemPosition();
        if (position != -1) {
            ShortsAdapter.ShortViewHolder holder = (ShortsAdapter.ShortViewHolder) 
                    recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                holder.thumbnailView.setVisibility(View.GONE);
            }
        }
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        updateProgressRunnable = new Runnable() {
            @Override
            public void run() {
                updateSeekBar();
                mainHandler.postDelayed(this, 200);
            }
        };
        mainHandler.post(updateProgressRunnable);
    }

    private void stopProgressUpdates() {
        if (updateProgressRunnable != null) {
            mainHandler.removeCallbacks(updateProgressRunnable);
            updateProgressRunnable = null;
        }
    }

    private void updateSeekBar() {
        if (player == null || isUserSeeking) return;

        int position = layoutManager.findFirstVisibleItemPosition();
        if (position != currentPlayingPosition) return;

        ShortsAdapter.ShortViewHolder holder = (ShortsAdapter.ShortViewHolder) 
                recyclerView.findViewHolderForAdapterPosition(position);
        
        if (holder != null && player.getDuration() > 0) {
            long currentPos = player.getCurrentPosition();
            long duration = player.getDuration();
            int progress = (int) (currentPos * 1000 / duration);
            holder.seekBar.setProgress(progress);
        }
    }

    private void updateBufferingSpinner(boolean isBuffering) {
        if (bufferingRunnable != null) {
            mainHandler.removeCallbacks(bufferingRunnable);
            bufferingRunnable = null;
        }

        int position = layoutManager.findFirstVisibleItemPosition();
        if (position != -1) {
            ShortsAdapter.ShortViewHolder holder = (ShortsAdapter.ShortViewHolder) 
                    recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                if (isBuffering) {
                    bufferingRunnable = () -> {
                        holder.bufferingSpinner.setVisibility(View.VISIBLE);
                        // Hide play icon if it's visible during buffering
                        holder.playPauseIcon.setVisibility(View.GONE);
                    };
                    mainHandler.postDelayed(bufferingRunnable, 1000);
                } else {
                    holder.bufferingSpinner.setVisibility(View.GONE);
                }
            }
        }
    }

    private void flashPlayPauseIcon(boolean isPlay) {
        int position = layoutManager.findFirstVisibleItemPosition();
        if (position == -1) return;

        ShortsAdapter.ShortViewHolder holder = (ShortsAdapter.ShortViewHolder) 
                recyclerView.findViewHolderForAdapterPosition(position);
        if (holder == null) return;

        holder.playPauseIcon.setImageResource(isPlay ? R.drawable.icon_play : R.drawable.icon_pause);
        holder.playPauseIcon.setAlpha(1.0f);
        holder.playPauseIcon.setVisibility(View.VISIBLE);
        holder.playPauseIcon.animate().cancel(); // Cancel any existing animation
        
        holder.playPauseIcon.animate()
                .alpha(0.0f)
                .setDuration(400)
                .setStartDelay(300)
                .withEndAction(() -> {
                    holder.playPauseIcon.setVisibility(View.GONE);
                    holder.playPauseIcon.setAlpha(1.0f); // Reset for next time
                })
                .start();
    }

    private void updatePlayPauseIcon(boolean isPlaying) {
        int position = layoutManager.findFirstVisibleItemPosition();
        if (position != -1) {
            ShortsAdapter.ShortViewHolder holder = (ShortsAdapter.ShortViewHolder) 
                    recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                // Toggle progress bar visibility based on playback state
                if (!isPlaying && player != null && !player.getPlayWhenReady()) {
                    holder.seekBar.setVisibility(View.VISIBLE);
                } else {
                    holder.seekBar.setVisibility(View.GONE);
                }
                
                // Ensure centered icon is hidden unless it's currently flashing
                // (Alpha check prevents hiding during a flash animation)
                if (holder.playPauseIcon.getAlpha() == 1.0f && 
                    (isPlaying || (player != null && player.getPlayWhenReady()))) {
                    holder.playPauseIcon.setVisibility(View.GONE);
                }
            }
        }
    }

    private void markCurrentAsWatched() {
        if (currentPlayingPosition >= 0 && currentPlayingPosition < videoList.size()) {
            ShortItem item = videoList.get(currentPlayingPosition);
            if (MODE_LOCAL.equals(currentMode)) {
                executorService.execute(() -> {
                    DownloadStore.markAsWatched(getApplicationContext(), item.getVideoId());
                });
            }
        }
    }

    private void loadVideos(boolean reset) {
        if (isLoading) return;

        if (MODE_LOCAL.equals(currentMode)) {
            loadLocalVideos();
        } else {
            loadOnlineVideos(reset);
        }
    }

    private void loadLocalVideos() {
        isLoading = true;
        ProgressBar loadingSpinner = findViewById(R.id.loadingSpinner);
        if (loadingSpinner != null) loadingSpinner.setVisibility(View.VISIBLE);

        executorService.execute(() -> {
            List<DownloadedVideo> allDownloaded = DownloadStore.getShorts(getApplicationContext());
            List<ShortItem> localShorts = new ArrayList<>();

            if (allDownloaded != null) {
                for (DownloadedVideo video : allDownloaded) {
                    boolean matchesFilter = false;

                    if ("UNWATCHED".equals(folderType) && !video.isWatched()) {
                        matchesFilter = true;
                    } else if ("WATCHED".equals(folderType) && video.isWatched()) {
                        matchesFilter = true;
                    } else if ("ALL".equals(folderType)) {
                        matchesFilter = true;
                    }

                    if (matchesFilter && video.getVideoFilePath() != null) {
                        File file = new File(video.getVideoFilePath());
                        if (file.exists()) {
                            ShortItem item = new ShortItem();
                            item.setVideoId(video.getVideoId());
                            item.setTitle(video.getTitle());
                            item.setResolvedUrls("file://" + file.getAbsolutePath(), 
                                video.getAudioFilePath() != null ? "file://" + video.getAudioFilePath() : null);
                            item.setVideoMimeType(video.getVideoMimeType());
                            item.setAudioMimeType(video.getAudioMimeType());
                            if (video.getThumbnailFilePath() != null) {
                                item.setThumbnailUrl("file://" + video.getThumbnailFilePath());
                            }
                            localShorts.add(item);
                        }
                    }
                }
            }

            runOnUiThread(() -> {
                isLoading = false;
                if (loadingSpinner != null) loadingSpinner.setVisibility(View.GONE);

                if (localShorts.isEmpty()) {
                    Toast.makeText(this, "No local Shorts found", Toast.LENGTH_SHORT).show();
                    return;
                }

                videoList.clear();
                videoList.addAll(localShorts);
                adapter.notifyDataSetChanged();

                if (!videoList.isEmpty() && player != null) {
                    updatePlayerPlaylist(videoList);
                    recyclerView.postDelayed(this::playCurrentVisibleItem, 200);
                }
            });
        });
    }

    private void loadOnlineVideos(boolean reset) {
        if (reset) {
            List<ShortItem> prefetched = PrefetchManager.getAndClearPrefetchedShorts();
            if (prefetched != null && !prefetched.isEmpty()) {
                Log.i(TAG, "Using prefetched shorts");
                videoList.clear();
                videoList.addAll(prefetched);
                adapter.notifyDataSetChanged();
                
                if (player != null) {
                    updatePlayerPlaylist(prefetched);
                    recyclerView.postDelayed(this::playCurrentVisibleItem, 200);
                }
                
                prefetchUrls(layoutManager.findFirstVisibleItemPosition());
                isLoading = false;
                return;
            }
        }

        isLoading = true;
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);

        if (reset && !swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }

        executorService.execute(() -> {
            try {
                YoutubeEngine.getShortsBatch(getApplicationContext(), new YoutubeEngine.ShortsCallback() {
                    @Override
                    public void onResult(List<ShortItem> shorts) {
                        runOnUiThread(() -> {
                            isLoading = false;
                            progressBar.setVisibility(View.GONE);
                            swipeRefreshLayout.setRefreshing(false);
                            
                            if (reset) {
                                videoList.clear();
                            }
                            
                            if (shorts != null && !shorts.isEmpty()) {
                                int startPos = videoList.size();
                                videoList.addAll(shorts);
                                adapter.notifyItemRangeInserted(startPos, shorts.size());
                                
                                if (player != null) {
                                    updatePlayerPlaylist(shorts);
                                }
                                
                                prefetchUrls(layoutManager.findFirstVisibleItemPosition());

                                // If this was the first load, start playing
                                if (reset && player != null) {
                                    recyclerView.postDelayed(() -> playCurrentVisibleItem(), 200);
                                }
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "getShortsBatch() error: " + error);
                        runOnUiThread(() -> {
                            isLoading = false;
                            progressBar.setVisibility(View.GONE);
                            swipeRefreshLayout.setRefreshing(false);
                            Toast.makeText(ShortsActivity.this, "Error: " + error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Exception in loadVideos", e);
                runOnUiThread(() -> {
                    isLoading = false;
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                });
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.pause();
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bufferingRunnable != null) {
            mainHandler.removeCallbacks(bufferingRunnable);
        }
        executorService.shutdown();
        urlFetcherExecutor.shutdown();
    }
}