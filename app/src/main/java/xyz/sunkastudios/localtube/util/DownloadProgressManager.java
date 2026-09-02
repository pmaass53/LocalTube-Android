package xyz.sunkastudios.localtube.util;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.lifecycle.LifecycleOwner;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;

import xyz.sunkastudios.localtube.R;

public class DownloadProgressManager {
    private static final String TAG = "DownloadProgressManager";

    public static void attachProgressView(Context context, LifecycleOwner owner, View progressLayout) {
        if (progressLayout == null) return;

        ProgressBar progressBar = progressLayout.findViewById(R.id.download_global_progress);
        TextView statusText = progressLayout.findViewById(R.id.download_global_status);

        if (progressBar == null || statusText == null) return;

        WorkManager.getInstance(context)
                .getWorkInfosByTagLiveData("download_task")
                .observe(owner, workInfos -> {
                    if (workInfos == null || workInfos.isEmpty()) {
                        progressLayout.setVisibility(View.GONE);
                        return;
                    }

                    boolean isAnyRunning = false;
                    int totalProgress = 0;
                    int runningCount = 0;

                    for (WorkInfo info : workInfos) {
                        if (info.getState() == WorkInfo.State.RUNNING || info.getState() == WorkInfo.State.ENQUEUED) {
                            isAnyRunning = true;
                            runningCount++;
                            int progress = info.getProgress().getInt("progress", 0);
                            if (progress >= 0) totalProgress += progress;
                        }
                    }

                    if (isAnyRunning) {
                        progressLayout.setVisibility(View.VISIBLE);
                        int avgProgress = runningCount > 0 ? totalProgress / runningCount : 0;
                        progressBar.setProgress(avgProgress);
                        statusText.setText("Downloading " + runningCount + " item(s)... " + avgProgress + "%");
                    } else {
                        progressLayout.setVisibility(View.GONE);
                    }
                });
    }
}
