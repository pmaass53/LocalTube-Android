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
        startDownloadBtn.setVisibility(View.GONE);
        spinnerMode.setEnabled(false);
        spinnerQuality.setEnabled(false);

        progressBar.setVisibility(View.VISIBLE);
        percentText.setVisibility(View.VISIBLE);
        infoText.setVisibility(View.VISIBLE);
        statusText.setText("Downloading Anime...");

        // Reuse DownloadWorker logic but pass different params
        Data inputData = new Data.Builder()
                .putString("video_url", option.url)
                .putString("video_id", animeId + "_ep" + episodeId)
                .putString("video_title", animeTitle + " - Episode " + episodeId)
                .putString("thumbnail_url", thumbnailUri)
                .putString("headers_json", option.headers)
                .putBoolean("is_anime", true)
                .build();

        // I'll update DownloadWorker to handle "headers_json" and "is_anime"
        OneTimeWorkRequest downloadWork = new OneTimeWorkRequest.Builder(xyz.sunkastudios.localtube.DownloadWorker.class)
                .setInputData(inputData)
                .addTag("download_anime_" + animeId + "_" + episodeId)
                .build();

        activeWorkId = downloadWork.getId();
        WorkManager workManager = WorkManager.getInstance(this);
        workManager.enqueue(downloadWork);

        workManager.getWorkInfoByIdLiveData(activeWorkId)
                .observe(this, workInfo -> {
                    if (workInfo == null) return;

                    int progress = workInfo.getProgress().getInt("progress", 0);
                    long totalSize = workInfo.getProgress().getLong("totalSize", 0);
                    long totalDownloaded = workInfo.getProgress().getLong("totalDownloaded", 0);

                    progressBar.setProgress(progress);
                    percentText.setText(String.format(Locale.getDefault(), "%d%%", progress));

                    double downloadedMb = totalDownloaded / 1048576.0;
                    double totalMb = totalSize / 1048576.0;
                    infoText.setText(String.format(Locale.getDefault(), "%.1f / %.1f MB", downloadedMb, totalMb));

                    if (workInfo.getState().isFinished()) {
                        if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                            Toast.makeText(this, "Anime download complete!", Toast.LENGTH_SHORT).show();
                            startActivity(new Intent(this, DownloadsActivity.class));
                            finish();
                        } else if (workInfo.getState() == WorkInfo.State.FAILED) {
                            String err = workInfo.getOutputData().getString("error");
                            Toast.makeText(this, "Failed: " + err, Toast.LENGTH_LONG).show();
                            finish();
                        }
                    }
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
