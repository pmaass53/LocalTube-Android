package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.engine.PythonEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.UIUtil;

public class AnimeDownloaderActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private TextView percentText;
    private TextView infoText;
    private TextView statusText;
    private Button cancelBtn;
    private Button startDownloadBtn;
    private Spinner spinnerMode;
    private Spinner spinnerQuality;

    private UUID activeWorkId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String animeId;
    private String episodeId;
    private String animeTitle;
    private String thumbnailUri;

    private static class AnimeStreamOption {
        String quality;
        String url;
        String headers;

        @Override
        public String toString() {
            return quality;
        }
    }

    private final List<AnimeStreamOption> qualityOptions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_anime_downloader);
        UIUtil.applyInsets(this);

        animeId = getIntent().getStringExtra("anime_id");
        episodeId = getIntent().getStringExtra("episode_id");
        animeTitle = getIntent().getStringExtra("anime_title");
        thumbnailUri = getIntent().getStringExtra("thumbnail_url");

        statusText = findViewById(R.id.downloadStatusText);
        progressBar = findViewById(R.id.downloadProgressBar);
        percentText = findViewById(R.id.downloadPercentText);
        infoText = findViewById(R.id.downloadInfoText);
        cancelBtn = findViewById(R.id.btnCancel);
        startDownloadBtn = findViewById(R.id.btnStartDownload);
        spinnerMode = findViewById(R.id.spinnerMode);
        spinnerQuality = findViewById(R.id.spinnerQuality);

        startDownloadBtn.setEnabled(false);

        setupModeSpinner();

        if (NetworkManager.checkInternet(this)) {
            fetchQualities();
        } else {
            finish();
        }

        startDownloadBtn.setBackgroundColor(UIUtil.getAccentColor());
        startDownloadBtn.setOnClickListener(v -> {
            AnimeStreamOption selected = (AnimeStreamOption) spinnerQuality.getSelectedItem();
            if (selected != null) {
                startDownload(selected);
            }
        });

        cancelBtn.setOnClickListener(v -> {
            if (activeWorkId != null) {
                WorkManager.getInstance(this).cancelWorkById(activeWorkId);
            } else {
                finish();
            }
        });
    }

    private void setupModeSpinner() {
        String[] modes = {"sub", "dub"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(adapter);

        String currentMode = ConfigManager.getString("anime_mode");
        if ("dub".equals(currentMode)) {
            spinnerMode.setSelection(1);
        }

        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                fetchQualities();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void fetchQualities() {
        String mode = spinnerMode.getSelectedItem().toString();
        startDownloadBtn.setEnabled(false);
        findViewById(R.id.formatLoadingSpinner).setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                String json = PythonEngine.getAvailableStreams(animeId, episodeId, mode);
                JSONArray array = new JSONArray(json);
                qualityOptions.clear();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    AnimeStreamOption opt = new AnimeStreamOption();
                    opt.quality = obj.getString("quality");
                    opt.url = obj.getString("url");
                    opt.headers = obj.getJSONObject("headers").toString();
                    qualityOptions.add(opt);
                }

                runOnUiThread(() -> {
                    findViewById(R.id.formatLoadingSpinner).setVisibility(View.GONE);
                    ArrayAdapter<AnimeStreamOption> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, qualityOptions);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerQuality.setAdapter(adapter);
                    
                    if (!qualityOptions.isEmpty()) {
                        startDownloadBtn.setEnabled(true);

                        // Set default selection based on preferred quality
                        int preferredHeight = ConfigManager.getInt("preferred_quality");
                        int selection = 0;
                        for (int i = 0; i < qualityOptions.size(); i++) {
                            String q = qualityOptions.get(i).quality.toLowerCase();
                            int height = 0;
                            try {
                                String hStr = q.replaceAll("[^0-9]", "");
                                if (!hStr.isEmpty()) height = Integer.parseInt(hStr);
                            } catch (Exception ignored) {}

                            if (height > 0 && height <= preferredHeight) {
                                selection = i;
                                // qualityOptions are likely sorted from high to low or low to high.
                                // If they are sorted high to low, the first one <= pref is the best.
                                break;
                            }
                        }
                        spinnerQuality.setSelection(selection);
                    } else {
                        Toast.makeText(this, "No streams found for this mode", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    findViewById(R.id.formatLoadingSpinner).setVisibility(View.GONE);
                    Toast.makeText(this, "Error fetching streams", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void startDownload(AnimeStreamOption option) {
        // Reuse DownloadWorker logic but pass different params
        Data inputData = new Data.Builder()
                .putString("video_url", option.url)
                .putString("video_id", animeId + "_ep" + episodeId)
                .putString("video_title", animeTitle + " - Episode " + episodeId)
                .putString("thumbnail_url", thumbnailUri)
                .putString("headers_json", option.headers)
                .putBoolean("is_anime", true)
                .build();

        OneTimeWorkRequest downloadWork = new OneTimeWorkRequest.Builder(xyz.sunkastudios.localtube.DownloadWorker.class)
                .setInputData(inputData)
                .addTag("download_anime_" + animeId + "_" + episodeId)
                .addTag("download_task")
                .build();

        WorkManager.getInstance(this).enqueue(downloadWork);
        Toast.makeText(this, "Anime download started in background", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
