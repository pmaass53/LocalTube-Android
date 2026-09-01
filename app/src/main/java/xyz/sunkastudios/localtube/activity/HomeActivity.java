package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
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
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.FileLoader;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.UIUtil;
import xyz.sunkastudios.localtube.util.UpdateManager;

public class HomeActivity extends AppCompatActivity {
    private final List<VideoItem> videoList = new ArrayList<>();
    private RecyclerView recyclerView;
    private VideoAdapter adapter;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        UIUtil.applyInsets(this);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(videoList);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.bottomMenuBar).setBackground(new ColorDrawable(ConfigManager.getColor("navbar_background_color", "#777777")));
        findViewById(R.id.btnActionSearch).setBackgroundTintList(android.content.res.ColorStateList.valueOf(UIUtil.getAccentColor()));

        setupSearch();
        setupNavigation();

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (NetworkManager.checkInternet(this)) {
                FileLoader.delete(getApplicationContext(), "homepage.txt");
                triggerStreamingRefresh(true);
            } else {
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        loadInitialVideos();
        checkForUpdates();
    }

    private void checkForUpdates() {
        UpdateManager.checkForUpdates(this, new UpdateManager.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String latestVersion, String downloadUrl, String body) {
                UpdateManager.showUpdateDialog(HomeActivity.this, latestVersion, downloadUrl, body);
            }

            @Override
            public void onNoUpdate() {}

            @Override
            public void onError(Exception e) {
                Log.e("HomeActivity", "Update check failed", e);
            }
        });
    }

    private void setupSearch() {
        EditText searchBar = findViewById(R.id.searchBar);
        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = searchBar.getText().toString().trim();
                if (!query.isEmpty()) {
                    Intent intent = new Intent(HomeActivity.this, SearchActivity.class);
                    intent.putExtra("service", "YOUTUBE");
                    intent.putExtra("query", query);
                    startActivity(intent);
                }
                return true;
            }
            return false;
        });

        findViewById(R.id.btnActionSearch).setOnClickListener(v -> {
            String query = searchBar.getText().toString().trim();
            if (!query.isEmpty()) {
                Intent intent = new Intent(HomeActivity.this, SearchActivity.class);
                intent.putExtra("service", "YOUTUBE");
                intent.putExtra("query", query);
                startActivity(intent);
            }
        });
    }

    private void setupNavigation() {
        findViewById(R.id.btnYoutubeHome).setOnClickListener(v -> {
            FileLoader.delete(getApplicationContext(), "homepage.txt");
            triggerStreamingRefresh(true);
        });
        findViewById(R.id.btnYoutubeShorts).setOnClickListener(new View.OnClickListener() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, ShortsActivity.class));
            }
        });
        findViewById(R.id.btnAniHome).setOnClickListener(v -> startActivity(new Intent(this, AniHomeActivity.class)));
        findViewById(R.id.btnDownloads).setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void loadInitialVideos() {
        executorService.execute(() -> {
            try {
                // ALWAYS load from cache first if it exists
                final VideoFeed cachedFeed = YoutubeEngine.getHomepageRecommendations(getApplicationContext(), 40);
                if (cachedFeed != null && cachedFeed.getEntries() != null && !cachedFeed.getEntries().isEmpty()) {
                    runOnUiThread(() -> {
                        videoList.clear();
                        videoList.addAll(cachedFeed.getEntries());
                        adapter.notifyDataSetChanged();
                    });
                    return; 
                }

                // ONLY trigger refresh if cache is completely missing
                triggerStreamingRefresh(false);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void triggerStreamingRefresh(boolean clearImmediately) {
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        runOnUiThread(() -> {
            if (clearImmediately) {
                videoList.clear();
                adapter.notifyDataSetChanged();
            }
            if (!swipeRefreshLayout.isRefreshing()) progressBar.setVisibility(View.VISIBLE);
        });

        YoutubeEngine.getHomepageRecommendationsStreamed(getApplicationContext(), 100, new YoutubeEngine.VideoStreamCallback() {
            private boolean firstItem = true;

            @Override
            public void onItemLoaded(VideoItem item) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    
                    if (firstItem) {
                        firstItem = false;
                        if (!clearImmediately) {
                            videoList.clear();
                            adapter.notifyDataSetChanged();
                        }
                        progressBar.setVisibility(View.GONE);
                        swipeRefreshLayout.setRefreshing(false);
                    }

                    videoList.add(item);
                    adapter.notifyItemInserted(videoList.size() - 1);
                });
            }

            @Override
            public void onFinished(VideoFeed fullFeed) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                });
                YoutubeEngine.saveHomepageCache(getApplicationContext(), fullFeed);
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(HomeActivity.this, "Update error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
