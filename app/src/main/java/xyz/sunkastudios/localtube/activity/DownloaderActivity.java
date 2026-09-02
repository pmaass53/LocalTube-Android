package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import xyz.sunkastudios.localtube.FormatItem;
import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.DownloadWorker;
import xyz.sunkastudios.localtube.VideoMetaData;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.UIUtil;

public class DownloaderActivity extends AppCompatActivity {

    private ProgressBar progressBar;
    private TextView percentText;
    private TextView infoText;
    private TextView statusText;
    private Button cancelBtn;
    private Button startDownloadBtn;
    private Spinner spinnerVideo;
    private Spinner spinnerAudio;
    private CheckBox checkAddToHistory;

    private UUID activeWorkId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private List<FormatItem> videoFormats = new ArrayList<>();
    private List<FormatItem> audioFormats = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloader);
        UIUtil.applyInsets(this);

        statusText = findViewById(R.id.downloadStatusText);
        progressBar = findViewById(R.id.downloadProgressBar);
        percentText = findViewById(R.id.downloadPercentText);
        infoText = findViewById(R.id.downloadInfoText);
        cancelBtn = findViewById(R.id.btnCancel);
        startDownloadBtn = findViewById(R.id.btnStartDownload);
        spinnerVideo = findViewById(R.id.spinnerVideo);
        spinnerAudio = findViewById(R.id.spinnerAudio);
        checkAddToHistory = findViewById(R.id.checkAddToHistory);

        String url = getIntent().getStringExtra("video_url");
        String id = getIntent().getStringExtra("video_id");
        String title = getIntent().getStringExtra("video_title");

        startDownloadBtn.setEnabled(false);
        if (NetworkManager.checkInternet(this)) {
            fetchFormats(url);
        } else {
            finish();
        }

        startDownloadBtn.setBackgroundColor(UIUtil.getAccentColor());
        startDownloadBtn.setOnClickListener(v -> {
            FormatItem selectedVideo = (FormatItem) spinnerVideo.getSelectedItem();
            FormatItem selectedAudio = (FormatItem) spinnerAudio.getSelectedItem();

            if (selectedVideo != null) {
                startDownloadBtn.setVisibility(View.GONE);
                spinnerVideo.setEnabled(false);
                spinnerAudio.setEnabled(false);
                
                progressBar.setVisibility(View.VISIBLE);
                percentText.setVisibility(View.VISIBLE);
                infoText.setVisibility(View.VISIBLE);
                statusText.setText("Downloading...");
                checkAddToHistory.setEnabled(false);

                startDownloadWorker(url, id, title, selectedVideo.getFormatId(), 
                        selectedAudio != null ? selectedAudio.getFormatId() : null,
                        checkAddToHistory.isChecked());
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

    private void fetchFormats(String url) {
        executor.execute(() -> {
            List<VideoMetaData> metaData = YoutubeEngine.getVideoFormats(this, url);
            if (metaData != null && !metaData.isEmpty()) {
                List<FormatItem> allFormats = metaData.get(0).getFormats();
                
                videoFormats = allFormats.stream()
                        .filter(f -> (f.isVideoOnly() || f.isCombined()) && f.isDirectStream())
                        .sorted((f1, f2) -> Integer.compare(f2.getHeight(), f1.getHeight()))
                        .collect(Collectors.toList());

                audioFormats = allFormats.stream()
                        .filter(f -> f.isAudioOnly() && f.isDirectStream())
                        .sorted((f1, f2) -> Double.compare(f2.getTbr(), f1.getTbr()))
                        .collect(Collectors.toList());

                runOnUiThread(() -> {
                    findViewById(R.id.formatLoadingSpinner).setVisibility(View.GONE);
                    ArrayAdapter<FormatItem> videoAdapter = new ArrayAdapter<>(this, 
                            android.R.layout.simple_spinner_item, videoFormats);
                    videoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerVideo.setAdapter(videoAdapter);

                    // Set default selection based on preferred quality
                    int preferredHeight = ConfigManager.getInt("preferred_quality");
                    int selection = 0;
                    for (int i = 0; i < videoFormats.size(); i++) {
                        if (videoFormats.get(i).getHeight() <= preferredHeight) {
                            selection = i;
                            break;
                        }
                    }
                    spinnerVideo.setSelection(selection);

                    ArrayAdapter<FormatItem> audioAdapter = new ArrayAdapter<>(this, 
                            android.R.layout.simple_spinner_item, audioFormats);
                    audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerAudio.setAdapter(audioAdapter);

                    // Set default audio selection based on language preference
                    FormatItem bestAudio = YoutubeEngine.findBestAudio(allFormats);
                    if (bestAudio != null) {
                        int audioIdx = audioFormats.indexOf(bestAudio);
                        if (audioIdx != -1) {
                            spinnerAudio.setSelection(audioIdx);
                        }
                    }

                    startDownloadBtn.setEnabled(true);
                });
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Failed to load formats", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void startDownloadWorker(String url, String id, String title, String videoFormatId, String audioFormatId, boolean addToHistory) {
        Data inputData = new Data.Builder()
                .putString("video_url", url)
                .putString("video_id", id)
                .putString("video_title", title)
                .putString("selected_video_format", videoFormatId)
                .putString("selected_audio_format", audioFormatId)
                .putBoolean("add_to_history", addToHistory)
                .build();

        OneTimeWorkRequest downloadWork = new OneTimeWorkRequest.Builder(DownloadWorker.class)
                .setInputData(inputData)
                .addTag("download_" + id)
                .addTag("download_task")
                .build();

        WorkManager.getInstance(this).enqueue(downloadWork);
        Toast.makeText(this, "Download started in background", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
