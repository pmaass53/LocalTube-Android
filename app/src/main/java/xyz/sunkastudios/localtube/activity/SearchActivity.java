package xyz.sunkastudios.localtube.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.VideoAdapter;
import xyz.sunkastudios.localtube.VideoFeed;
import xyz.sunkastudios.localtube.VideoItem;
import xyz.sunkastudios.localtube.engine.PythonEngine;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.UIUtil;

public class SearchActivity extends AppCompatActivity {
    private static final String TAG = "SearchActivity";
    private final List<VideoItem> videoList = new ArrayList<>();
    private RecyclerView recyclerView;
    private EditText searchBar;
    private MaterialButton searchBtn;
    private VideoAdapter adapter;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();

    private String currentService = "YOUTUBE";

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        UIUtil.applyInsets(this);

        if (getIntent() != null) {
            currentService = getIntent().getStringExtra("service");
            if (currentService == null) currentService = "YOUTUBE";
        }

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new VideoAdapter(videoList);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.bottomMenuBar).setBackground(new ColorDrawable(ConfigManager.getColor("navbar_background_color", "#777777")));
        findViewById(R.id.btnActionSearch).setBackgroundTintList(android.content.res.ColorStateList.valueOf(UIUtil.getAccentColor()));

        searchBar = findViewById(R.id.searchBar);
        searchBtn = findViewById(R.id.btnActionSearch);

        String initialQuery = getIntent().getStringExtra("query");
        if (initialQuery != null) {
            searchBar.setText(initialQuery);
        }

        findViewById(R.id.btnYoutubeHome).setOnClickListener(v -> {
            Intent intent = new Intent(SearchActivity.this, HomeActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.btnYoutubeShorts).setOnClickListener(new View.OnClickListener() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SearchActivity.this, ShortsActivity.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.btnAniHome).setOnClickListener(v -> {
            Intent intent = new Intent(SearchActivity.this, AniHomeActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.btnDownloads).setOnClickListener(v -> {
            Intent intent = new Intent(SearchActivity.this, DownloadsActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            Intent intent = new Intent(SearchActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                View currentFocus = getCurrentFocus();
                if (currentFocus != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                    }
                }
                if (NetworkManager.checkInternet(SearchActivity.this)) {
                    loadVideos();
                }
            }
        });

        if (initialQuery != null && NetworkManager.isOnline(this)) {
            loadVideos();
        }

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                View currentFocus = getCurrentFocus();
                if (currentFocus != null) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                    }
                }
                if (NetworkManager.checkInternet(SearchActivity.this)) {
                    loadVideos();
                } else {
                    swipeRefreshLayout.setRefreshing(false);
                }
            }
        });
    }
    private void loadVideos() {
        String query = searchBar.getText().toString().trim();
        if (query.isEmpty()) return;
        if (!NetworkManager.checkInternet(this)) return;
        
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);

        if (!swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<VideoItem> fetchedEntries;
                    if ("ANIME".equals(currentService)) {
                        fetchedEntries = PythonEngine.searchAnime(query);
                    } else {
                        VideoFeed feed = YoutubeEngine.getSearchResults(getApplicationContext(), query);
                        fetchedEntries = feed != null ? feed.getEntries() : null;
                    }

                    if (fetchedEntries == null) {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            swipeRefreshLayout.setRefreshing(false);
                            Toast.makeText(SearchActivity.this, "Failed to load results", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    // update ui
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            progressBar.setVisibility(View.GONE);
                            swipeRefreshLayout.setRefreshing(false);
                            videoList.clear();
                            videoList.addAll(fetchedEntries);
                            adapter.notifyDataSetChanged();
                            
                            if ("ANIME".equals(currentService)) {
                                fetchThumbnails(new ArrayList<>(videoList));
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error loading videos", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setVisibility(View.GONE);
                            swipeRefreshLayout.setRefreshing(false);
                            Toast.makeText(SearchActivity.this, "Error loading videos", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
        thumbnailExecutor.shutdown();
    }
}
