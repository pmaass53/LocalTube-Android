package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.DownloadWorker;
import xyz.sunkastudios.localtube.DownloadedVideo;
import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.ShortItem;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.DownloadStore;

public class DownloadedShortsActivity extends AppCompatActivity {

    private static final String TAG = "DownloadedShorts";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private TextView tvUnwatchedCount;
    private TextView tvWatchedCount;
    private ProgressBar pbBatchDownload;
    private TextView tvBatchProgressCount;
    private View layoutBatchProgress;

    private int totalTargetCount = 0;
    private String currentSessionTag = null;

    private List<DownloadedVideo> unwatchedShorts = new ArrayList<>();
    private List<DownloadedVideo> watchedShorts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceData) {
        super.onCreate(savedInstanceData);
        setContentView(R.layout.activity_downloaded_shorts);

        initViews();
        setupClickListeners();
        loadShorts();
    }

    private void initViews() {
        tvUnwatchedCount = findViewById(R.id.tvUnwatchedCount);
        tvWatchedCount = findViewById(R.id.tvWatchedCount);
        pbBatchDownload = findViewById(R.id.pbBatchDownload);
        tvBatchProgressCount = findViewById(R.id.tvBatchProgressCount);
        layoutBatchProgress = findViewById(R.id.layoutBatchProgress);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setupClickListeners() {
        findViewById(R.id.btnBatchDownload).setOnClickListener(v -> triggerBatchDownload());
        findViewById(R.id.btnDeleteAll).setOnClickListener(v -> deleteAllShorts());
        findViewById(R.id.btnDeleteUnwatchedFolder).setOnClickListener(v -> deleteShortSet(unwatchedShorts));
        findViewById(R.id.btnDeleteWatchedFolder).setOnClickListener(v -> deleteShortSet(watchedShorts));

        findViewById(R.id.cardUnwatched).setOnClickListener(v -> launchShortsPlayer("UNWATCHED"));
        findViewById(R.id.cardWatched).setOnClickListener(v -> launchShortsPlayer("WATCHED"));
    }

    @UnstableApi
    private void launchShortsPlayer(String folderType) {
        Intent intent = new Intent(this, ShortsActivity.class);
        intent.putExtra(ShortsActivity.EXTRA_MODE, ShortsActivity.MODE_LOCAL);
        intent.putExtra(ShortsActivity.EXTRA_FOLDER_TYPE, folderType);
        startActivity(intent);
    }

    private void loadShorts() {
        executorService.execute(() -> {
            try {
                List<DownloadedVideo> allShorts = DownloadStore.getShorts(getApplicationContext());

                List<DownloadedVideo> unwatchedTemp = new ArrayList<>();
                List<DownloadedVideo> watchedTemp = new ArrayList<>();

                if (allShorts != null) {
                    for (DownloadedVideo shortVideo : allShorts) {
                        if (shortVideo.isWatched()) {
                            watchedTemp.add(shortVideo);
                        } else {
                            unwatchedTemp.add(shortVideo);
                        }
                    }
                }

                runOnUiThread(() -> {
                    unwatchedShorts = unwatchedTemp;
                    watchedShorts = watchedTemp;
                    updateUI();
                });

            } catch (Exception e) {
                Log.e(TAG, "loadShorts() failed: ", e);
            }
        });
    }

    private void updateUI() {
        tvUnwatchedCount.setText(unwatchedShorts.size() + " Shorts");
        tvWatchedCount.setText(watchedShorts.size() + " Shorts");
    }

    private void deleteShortSet(List<DownloadedVideo> targetList) {
        executorService.execute(() -> {
            for (DownloadedVideo item : targetList) {
                DownloadStore.deleteItem(getApplicationContext(), item.getVideoId(), true);
            }
            runOnUiThread(this::loadShorts);
        });
    }

    private void deleteAllShorts() {
        executorService.execute(() -> {
            List<DownloadedVideo> allShorts = DownloadStore.getShorts(getApplicationContext());
            for (DownloadedVideo item : allShorts) {
                DownloadStore.deleteItem(getApplicationContext(), item.getVideoId(), true);
            }
            runOnUiThread(this::loadShorts);
        });
    }

    private void triggerBatchDownload() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Number of Shorts to Download:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String strValue = input.getText().toString().trim();
            if (strValue.isEmpty()) return;

            try {
                int targetCount = Integer.parseInt(strValue);
                if (targetCount <= 0) return;

                totalTargetCount = targetCount;
                currentSessionTag = "batch_" + System.currentTimeMillis();

                layoutBatchProgress.setVisibility(View.VISIBLE);
                pbBatchDownload.setMax(totalTargetCount);
                pbBatchDownload.setProgress(0);
                tvBatchProgressCount.setText("0/" + totalTargetCount);

                observeBatchProgress();

                Toast.makeText(this, "Starting batch download…", Toast.LENGTH_SHORT).show();
                fetchAndQueueNextBatch(totalTargetCount, 0);

            } catch (NumberFormatException nfe) {
                Toast.makeText(getApplicationContext(), "Not a valid number", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void observeBatchProgress() {
        if (currentSessionTag == null) return;
        
        WorkManager.getInstance(getApplicationContext())
                .getWorkInfosByTagLiveData(currentSessionTag)
                .observe(this, workInfos -> {
                    if (workInfos == null) return;
                    
                    int finishedCount = 0;
                    for (androidx.work.WorkInfo info : workInfos) {
                        if (info.getState().isFinished()) {
                            finishedCount++;
                        }
                    }
                    
                    final int finalFinished = finishedCount;
                    runOnUiThread(() -> {
                        pbBatchDownload.setProgress(finalFinished);
                        tvBatchProgressCount.setText(finalFinished + "/" + totalTargetCount);
                        
                        if (finalFinished >= totalTargetCount && totalTargetCount > 0) {
                            layoutBatchProgress.postDelayed(() -> {
                                if (finalFinished >= totalTargetCount) {
                                    layoutBatchProgress.setVisibility(View.GONE);
                                    loadShorts();
                                }
                            }, 3000);
                        }
                    });
                });
    }

    private void fetchAndQueueNextBatch(final int targetCount, final int totalQueuedSoFar) {
        if (totalQueuedSoFar >= targetCount || isFinishing() || isDestroyed()) {
            return;
        }

        int remainingNeeded = targetCount - totalQueuedSoFar;

        executorService.execute(() -> {
            YoutubeEngine.getShortsBatch(getApplicationContext(), new YoutubeEngine.ShortsCallback() {
                @Override
                public void onResult(List<ShortItem> shorts) {
                    if (shorts == null || shorts.isEmpty()) {
                        runOnUiThread(() -> {
                            Toast.makeText(getApplicationContext(), "No more Shorts returned from engine.", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    List<String> currentBatchTags = new ArrayList<>();
                    int batchCount = 0;

                    for (ShortItem item : shorts) {
                        if (batchCount >= remainingNeeded) break;

                        String workTag = "batch_short_" + item.getVideoId();
                        currentBatchTags.add(workTag);

                        Data inputData = new Data.Builder()
                                .putString("video_url", item.getUrl())
                                .putString("video_id", item.getVideoId())
                                .putString("video_title", item.getTitle())
                                .putBoolean("is_short", true)
                                .putBoolean("add_to_history", true)
                                .build();

                        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(DownloadWorker.class)
                                .setInputData(inputData)
                                .addTag(workTag)
                                .addTag("batch_download_group")
                                .addTag("download_task")
                                .addTag(currentSessionTag)
                                .build();

                        WorkManager.getInstance(getApplicationContext()).enqueue(workRequest);
                        batchCount++;
                    }

                    final int newTotalQueued = totalQueuedSoFar + batchCount;
                    if (newTotalQueued < targetCount) {
                        waitForBatchCompletionAndContinue(currentBatchTags, targetCount, newTotalQueued);
                    }
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                            "Batch request failed: " + error, Toast.LENGTH_LONG).show());
                    Log.e(TAG, "getShortsBatch failed: " + error);
                }
            });
        });
    }

    private void waitForBatchCompletionAndContinue(List<String> workTags, int targetCount, int currentQueuedCount) {
        WorkManager workManager = WorkManager.getInstance(getApplicationContext());

        executorService.execute(() -> {
            boolean allFinished = false;

            while (!allFinished && !isFinishing() && !isDestroyed()) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                allFinished = true;
                for (String tag : workTags) {
                    try {
                        var workInfos = workManager.getWorkInfosByTag(tag).get();
                        if (workInfos != null && !workInfos.isEmpty()) {
                            var state = workInfos.get(0).getState();
                            if (!state.isFinished()) {
                                allFinished = false;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error checking work status", e);
                    }
                }
            }
            if (!isFinishing() && !isDestroyed()) {
                fetchAndQueueNextBatch(targetCount, currentQueuedCount);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadShorts();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
