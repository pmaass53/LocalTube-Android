package xyz.sunkastudios.localtube.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateManager {
    private static final String TAG = "UpdateManager";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/pmaass53/LocalTube-Android/releases/latest";

    public interface UpdateCallback {
        void onUpdateAvailable(String latestVersion, String downloadUrl, String body);
        void onNoUpdate();
        void onError(Exception e);
    }

    public static void checkForUpdates(Context context, UpdateCallback callback) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(GITHUB_API_URL)
                .header("User-Agent", "LocalTube-Android")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (callback != null) callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    if (callback != null) callback.onError(new IOException("Unexpected code " + response));
                    return;
                }

                try {
                    String jsonData = Objects.requireNonNull(response.body()).string();
                    JSONObject release = new JSONObject(jsonData);
                    String latestVersion = release.getString("tag_name").replace("v", "");
                    String body = release.optString("body", "");
                    
                    JSONArray assets = release.getJSONArray("assets");
                    String downloadUrl = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (asset.getString("name").endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }

                    if (isNewerVersion(context, latestVersion) && downloadUrl != null) {
                        if (callback != null) callback.onUpdateAvailable(latestVersion, downloadUrl, body);
                    } else {
                        if (callback != null) callback.onNoUpdate();
                    }
                } catch (Exception e) {
                    if (callback != null) callback.onError(e);
                }
            }
        });
    }

    private static boolean isNewerVersion(Context context, String latestVersion) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String currentVersion = pInfo.versionName;
            
            if (currentVersion == null) return true;
            
            String[] currentParts = currentVersion.split("\\.");
            String[] latestParts = latestVersion.split("\\.");
            
            int length = Math.max(currentParts.length, latestParts.length);
            for (int i = 0; i < length; i++) {
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                int latestPart = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                
                if (latestPart > currentPart) return true;
                if (latestPart < currentPart) return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error comparing versions", e);
        }
        return false;
    }

    public static void showUpdateDialog(Activity activity, String latestVersion, String downloadUrl, String body) {
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                .setTitle("Update Available")
                .setMessage("A new version (" + latestVersion + ") is available.\n\nChanges:\n" + body)
                .setPositiveButton("Update", (dialog, which) -> downloadAndInstallApk(activity, downloadUrl))
                .setNegativeButton("Later", null)
                .show());
    }

    private static void downloadAndInstallApk(Activity activity, String downloadUrl) {
        ProgressDialog progressDialog = new ProgressDialog(activity);
        progressDialog.setTitle("Downloading Update");
        progressDialog.setMessage("Please wait...");
        progressDialog.setIndeterminate(false);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(downloadUrl).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(activity, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    activity.runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(activity, "Download failed: " + response.code(), Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                File apkFile = new File(activity.getExternalCacheDir(), "update.apk");
                long totalBytes = Objects.requireNonNull(response.body()).contentLength();
                
                try (InputStream is = response.body().byteStream();
                     FileOutputStream os = new FileOutputStream(apkFile)) {
                    
                    byte[] buffer = new byte[8192];
                    long bytesRead = 0;
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                        bytesRead += read;
                        
                        final int progress = (int) ((bytesRead * 100) / totalBytes);
                        activity.runOnUiThread(() -> progressDialog.setProgress(progress));
                    }
                }

                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    installApk(activity, apkFile);
                });
            }
        });
    }

    private static void installApk(Context context, File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                context.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + context.getPackageName())));
                Toast.makeText(context, "Please allow unknown app sources and try again", Toast.LENGTH_LONG).show();
                return;
            }
        }
        
        context.startActivity(intent);
    }
}
