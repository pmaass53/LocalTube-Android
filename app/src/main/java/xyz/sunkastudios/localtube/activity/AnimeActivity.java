package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
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
import xyz.sunkastudios.localtube.util.UIUtil;

public class AnimeActivity extends AppCompatActivity implements VideoAdapter.VideoAdapterListener {

    private static final String TAG = "AnimeActivity";
    private String animeId;
    private final List<VideoItem> episodeList = new ArrayList<>();
    private VideoAdapter adapter;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private ImageView bannerView;
    private TextView descriptionView;
    private String resolvedThumbnailUrl;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_anime);
        UIUtil.applyInsets(this);

        animeId = getIntent().getStringExtra("anime_id");
        String animeTitle = getIntent().getStringExtra("anime_title");

        bannerView = findViewById(R.id.animeBanner);
        descriptionView = findViewById(R.id.animeDescription);
        TextView titleView = findViewById(R.id.animeTitle);
        titleView.setText(animeTitle);
        TextView epLabel = findViewById(R.id.episodesLabel);
        if (epLabel != null) epLabel.setTextColor(UIUtil.getAccentColor());

        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VideoAdapter(episodeList, this);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.bottomMenuBar).setBackground(new ColorDrawable(ConfigManager.getColor("navbar_background_color", "#777777")));
        
        DownloadProgressManager.attachProgressView(this, this, findViewById(R.id.layout_download_progress));

        setupNavigation();

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(() -> loadData(animeTitle));

        loadData(animeTitle);
    }

    private void loadData(String title) {
        loadDetails(title);
        loadEpisodes();
    }

    private void loadDetails(String title) {
        executorService.execute(() -> {
            try {
                Map<String, String> details = PythonEngine.getAnimeDetails(animeId, title);
                if (details != null) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        
                        String bannerUrl = details.get("banner");
                        String description = details.get("description");
                        resolvedThumbnailUrl = details.get("cover_large");

                        if (bannerUrl != null && !bannerUrl.isEmpty()) {
                            Glide.with(this).load(bannerUrl).centerCrop().into(bannerView);
                        }
                        if (description != null && !description.isEmpty()) {
                            descriptionView.setText(android.text.Html.fromHtml(description, android.text.Html.FROM_HTML_MODE_COMPACT));
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading details", e);
            }
        });
    }

    private void loadEpisodes() {
        if (!NetworkManager.isOnline(this)) return;

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);

        if (!swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }

        String lang = ConfigManager.getString("anime_mode");
        if (lang.isEmpty()) lang = "sub";

        String finalLang = lang;
        executorService.execute(() -> {
            try {
                List<Integer> episodes = PythonEngine.getEpisodesList(animeId, finalLang);

                List<VideoItem> items = new ArrayList<>();
                for (Integer ep : episodes) {
                    VideoItem item = new VideoItem();
                    item.setTitle("Episode " + ep);
                    item.setId(String.valueOf(ep));
                    item.setUrl("anime://" + animeId + "/ep/" + ep);
                    items.add(item);
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    episodeList.clear();
                    
                    if (items.isEmpty()) {
                        Toast.makeText(this, "No episodes found for this show", Toast.LENGTH_LONG).show();
                    } else {
                        episodeList.addAll(items);
                    }
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading episodes", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(this, "Error loading episodes: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onVideoClick(VideoItem video) {
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);
        progressBar.setVisibility(View.VISIBLE);

        String lang = ConfigManager.getString("anime_mode");
        if (lang.isEmpty()) lang = "sub";

        final String finalLang = lang;
        executorService.execute(() -> {
            try {
                // Fetch all available streams instead of a single URL
                String jsonResult = PythonEngine.getAvailableStreams(animeId, video.getId(), finalLang);
                Log.d(TAG, "Streams result: " + jsonResult);

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressBar.setVisibility(View.GONE);
                    
                    try {
                        org.json.JSONArray array = new org.json.JSONArray(jsonResult);
                        if (array.length() == 0) {
                            Toast.makeText(this, "No playable streams found", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        int preferredHeight = ConfigManager.getInt("preferred_quality");
                        JSONObject bestStream = null;
                        int bestHeight = 0;

                        // Find the best stream that doesn't exceed preferred quality
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject stream = array.getJSONObject(i);
                            String q = stream.optString("quality", "").toLowerCase();
                            int height = 0;
                            try {
                                String hStr = q.replaceAll("[^0-9]", "");
                                if (!hStr.isEmpty()) height = Integer.parseInt(hStr);
                            } catch (Exception ignored) {}

                            if (height > 0 && height <= preferredHeight) {
                                if (height > bestHeight) {
                                    bestHeight = height;
                                    bestStream = stream;
                                }
                            }
                        }

                        // Fallback: pick the first one (usually highest or lowest depending on provider)
                        if (bestStream == null) {
                            bestStream = array.getJSONObject(0);
                        }

                        String streamUrl = bestStream.getString("url");
                        JSONObject headersJson = bestStream.optJSONObject("headers");

                        Intent intent = new Intent(this, PlayerActivity.class);
                        intent.putExtra("online", true);
                        intent.putExtra("is_direct", true);
                        intent.putExtra("video_uri", streamUrl);
                        intent.putExtra("video_id", animeId + "_ep" + video.getId());
                        
                        if (headersJson != null) {
                            Bundle headersBundle = new Bundle();
                            Iterator<String> keys = headersJson.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                headersBundle.putString(key, headersJson.getString(key));
                            }
                            intent.putExtra("headers", headersBundle);
                        }
                        
                        startActivity(intent);

                    } catch (Exception e) {
                        Log.e(TAG, "Error selecting best stream", e);
                        Toast.makeText(this, "Stream resolution error", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error in onVideoClick", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Python Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onDownloadClick(VideoItem video) {
        Intent intent = new Intent(this, AnimeDownloaderActivity.class);
        intent.putExtra("anime_id", animeId);
        intent.putExtra("episode_id", video.getId());
        intent.putExtra("anime_title", getIntent().getStringExtra("anime_title"));
        
        String thumb = (resolvedThumbnailUrl != null && !resolvedThumbnailUrl.isEmpty()) 
                ? resolvedThumbnailUrl 
                : getIntent().getStringExtra("thumbnail_url");
        
        intent.putExtra("thumbnail_url", thumb);
        startActivity(intent);
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
                startActivity(new Intent(AnimeActivity.this, ShortsActivity.class));
            }
        });
        findViewById(R.id.btnAniHome).setOnClickListener(v -> {
            startActivity(new Intent(this, AniHomeActivity.class));
            finish();
        });
        findViewById(R.id.btnDownloads).setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
