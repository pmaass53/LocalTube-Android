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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.VideoAdapter;
import xyz.sunkastudios.localtube.VideoItem;
import xyz.sunkastudios.localtube.engine.PythonEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.DownloadProgressManager;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.PrefetchManager;
import xyz.sunkastudios.localtube.util.UIUtil;

public class AniHomeActivity extends AppCompatActivity {

    private static final String TAG = "AniHomeActivity";
    private final List<VideoItem> videoList = new ArrayList<>();
    private VideoAdapter adapter;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ani_home);
        UIUtil.applyInsets(this);

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(videoList);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.bottomMenuBar).setBackground(new ColorDrawable(ConfigManager.getColor("navbar_background_color", "#777777")));
        findViewById(R.id.btnActionSearch).setBackgroundTintList(android.content.res.ColorStateList.valueOf(UIUtil.getAccentColor()));

        DownloadProgressManager.attachProgressView(this, this, findViewById(R.id.layout_download_progress));

        setupNavigation();

        EditText searchBar = findViewById(R.id.searchBar);
        searchBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = searchBar.getText().toString().trim();
                if (!query.isEmpty()) {
                    Intent intent = new Intent(AniHomeActivity.this, SearchActivity.class);
                    intent.putExtra("service", "ANIME");
                    intent.putExtra("query", query);
                    startActivity(intent);
                }
                return true;
            }
            return false;
        });

        findViewById(R.id.btnActionSearch).setOnClickListener(v -> {
            String query = searchBar.getText().toString().trim();
            if (query.isEmpty()) return;

            Intent intent = new Intent(AniHomeActivity.this, SearchActivity.class);
            intent.putExtra("service", "ANIME");
            intent.putExtra("query", query);
            startActivity(intent);
        });

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (NetworkManager.checkInternet(this)) {
                loadTrending();
            } else {
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        loadTrending();
    }

    private void loadTrending() {
        List<VideoItem> prefetched = PrefetchManager.getAndClearPrefetchedAnimeHome();
        if (prefetched != null && !prefetched.isEmpty()) {
            videoList.clear();
            videoList.addAll(prefetched);
            adapter.notifyDataSetChanged();
            fetchThumbnails(new ArrayList<>(videoList));
            return;
        }

        if (!NetworkManager.isOnline(this)) return;

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);

        if (!swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }

        executorService.execute(() -> {
            try {
                List<VideoItem> animeItems = PythonEngine.searchAnime("Popular");

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    videoList.clear();
                    videoList.addAll(animeItems);
                    adapter.notifyDataSetChanged();
                    
                    fetchThumbnails(new ArrayList<>(videoList));
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading Anime", e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(AniHomeActivity.this, "Error loading Anime: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void fetchThumbnails(List<VideoItem> items) {
        thumbnailExecutor.execute(() -> {
            Log.d(TAG, "Fetching batch thumbnails for " + items.size() + " items");
            Map<String, Map<String, String>> allImages = PythonEngine.getAnimeImagesBatch(items);
            if (allImages != null) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    for (VideoItem item : videoList) {
                        Map<String, String> images = allImages.get(item.getId());
                        if (images != null) {
                            item.setBanner(images.get("banner"));
                            item.setCoverLarge(images.get("cover_large"));
                            item.setDescription(images.get("description"));
                        }
                    }
                    adapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void setupNavigation() {
        findViewById(R.id.btnYoutubeHome).setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
        });
        findViewById(R.id.btnYoutubeShorts).setOnClickListener(new View.OnClickListener() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AniHomeActivity.this, ShortsActivity.class));
            }
        });
        findViewById(R.id.btnAniHome).setOnClickListener(v -> {
            // Already here
        });
        findViewById(R.id.btnDownloads).setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
        thumbnailExecutor.shutdown();
    }
}
