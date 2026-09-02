package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.VideoAdapter;
import xyz.sunkastudios.localtube.VideoFeed;
import xyz.sunkastudios.localtube.VideoItem;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.DownloadProgressManager;
import xyz.sunkastudios.localtube.util.UIUtil;

public class PlaylistActivity extends AppCompatActivity implements VideoAdapter.VideoAdapterListener {
    private List<VideoItem> videoList = new ArrayList<>();
    private RecyclerView recyclerView;
    private VideoAdapter adapter;
    private String playlistUrl;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);
        UIUtil.applyInsets(this);

        playlistUrl = getIntent().getStringExtra("video_uri");

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new VideoAdapter(videoList, this);
        recyclerView.setAdapter(adapter);

        DownloadProgressManager.attachProgressView(this, this, findViewById(R.id.layout_download_progress));

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(this::loadVideos);

        loadVideos();
    }

    private void loadVideos() {
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);

        if (!swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }

        executorService.execute(() -> {
            try {
                Log.d("PlaylistActivity", "loading entries for playlist: " + playlistUrl);
                final VideoFeed feed = YoutubeEngine.getPlaylistEntries(getApplicationContext(), playlistUrl);
                
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    
                    if (feed == null) {
                        Toast.makeText(PlaylistActivity.this, "Failed to load playlist", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    List<VideoItem> fetchedEntries = feed.getEntries();
                    videoList.clear();
                    if (fetchedEntries != null) {
                        videoList.addAll(fetchedEntries);
                    }
                    adapter.notifyDataSetChanged();
                    
                    TextView banner = findViewById(R.id.home_text_banner);
                    if (banner != null) {
                        banner.setText("Playlist (" + videoList.size() + ")");
                        banner.setTextColor(UIUtil.getAccentColor());
                    }
                });
            } catch (Exception e) {
                Log.e("PlaylistActivity", "Error loading playlist", e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(PlaylistActivity.this, "Error loading videos", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onVideoClick(VideoItem video) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("online", true);
        intent.putExtra("video_uri", playlistUrl);
        intent.putExtra("video_id", video.getId());

        // Guaranteed queuing via Service
        Intent serviceIntent = new Intent(this, xyz.sunkastudios.localtube.PlaybackService.class);
        serviceIntent.setAction("ACTION_PREPARE_PLAYLIST");
        serviceIntent.putExtras(intent);
        startService(serviceIntent);

        startActivity(intent);
    }

    @Override
    public void onDownloadClick(VideoItem video) {
        Intent intent = new Intent(this, DownloaderActivity.class);
        intent.putExtra("video_url", video.getUrl());
        intent.putExtra("video_id", video.getId());
        intent.putExtra("video_title", video.getTitle());
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
