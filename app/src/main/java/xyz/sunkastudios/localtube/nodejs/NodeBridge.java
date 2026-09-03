package xyz.sunkastudios.localtube.nodejs;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class NodeBridge {
    private static final String TAG = "NodeBridge";
    
    static {
        System.loadLibrary("native-lib");
        System.loadLibrary("node");
    }

    public interface NodeMessageListener {
        void onMessage(String channel, String message);
    }

    private static final List<NodeMessageListener> listeners = new CopyOnWriteArrayList<>();
    private static boolean isNodeReady = false;
    private static boolean isStarting = false;
    private static final List<Runnable> pendingMessages = new ArrayList<>();

    public static void addListener(NodeMessageListener listener) {
        listeners.add(listener);
    }

    public static void removeListener(NodeMessageListener listener) {
        listeners.remove(listener);
    }

    // Native methods
    public static native int startNodeWithArguments(String[] arguments, String nodePath);
    public static native void sendMessageToNode(String channelName, String msg);

    // Called from JNI
    public static void onMessageReceived(String channel, String message) {
        Log.d(TAG, "Message from Node [" + channel + "]: " + message);
        if ("ready".equals(channel)) {
            synchronized (pendingMessages) {
                isStarting = false;
                isNodeReady = true;
                for (Runnable r : pendingMessages) {
                    r.run();
                }
                pendingMessages.clear();
            }
        }
        for (NodeMessageListener listener : listeners) {
            try {
                listener.onMessage(channel, message);
            } catch (Exception e) {
                Log.e(TAG, "Error in listener onMessage", e);
            }
        }
    }

    /**
     * Starts the Node.js project from assets in a background thread.
     */
    public static synchronized void startNode(Context context) {
        if (isNodeReady || isStarting) return;
        isStarting = true;
        
        new Thread(() -> {
            try {
                Log.i(TAG, "Preparing Node.js environment...");
                File nodeDir = new File(context.getFilesDir(), "nodejs-project");
                
                // Optimized Asset Preparation: Only copy if app version changed or missing
                android.content.pm.PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                long currentVersionCode;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    currentVersionCode = pInfo.getLongVersionCode();
                } else {
                    currentVersionCode = pInfo.versionCode;
                }
                
                android.content.SharedPreferences prefs = context.getSharedPreferences("node_bridge", Context.MODE_PRIVATE);
                long lastVersionCode = prefs.getLong("last_node_asset_version", -1);

                if (currentVersionCode != lastVersionCode || !nodeDir.exists()) {
                    Log.i(TAG, "New app version detected. Updating nodejs-project assets...");
                    if (nodeDir.exists()) deleteRecursive(nodeDir);
                    copyAssetFolder(context, "nodejs-project", nodeDir.getAbsolutePath());
                    prefs.edit().putLong("last_node_asset_version", currentVersionCode).apply();
                    Log.i(TAG, "Assets updated successfully.");
                } else {
                    Log.i(TAG, "Node assets already up-to-date.");
                }

                String mainJsPath = new File(nodeDir, "main.js").getAbsolutePath();
                String cookiesPath = new File(context.getFilesDir(), "cookies.txt").getAbsolutePath();
                Log.i(TAG, "Starting Node engine with: " + mainJsPath);
                
                int result = startNodeWithArguments(new String[]{"node", mainJsPath, cookiesPath}, nodeDir.getAbsolutePath());
                Log.i(TAG, "Node engine exited with code: " + result);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start Node", e);
            }
        }).start();
    }

    public static void sendMessage(String action, Object payload) {
        sendMessage(action, null, payload);
    }

    public static void sendMessage(String action, String reqId, Object payload) {
        Runnable r = () -> {
            try {
                JSONObject msg = new JSONObject();
                msg.put("action", action);
                if (reqId != null) msg.put("reqId", reqId);
                msg.put("payload", payload);
                sendMessageToNode("message", msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send message", e);
            }
        };

        synchronized (pendingMessages) {
            if (isNodeReady) {
                r.run();
            } else {
                Log.i(TAG, "Node not ready, queuing message: " + action);
                pendingMessages.add(r);
            }
        }
    }

    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    private static void copyAssetFolder(Context context, String assetName, String outputPath) throws IOException {
        String[] files = context.getAssets().list(assetName);
        if (files == null) return;
        
        File outDir = new File(outputPath);
        if (!outDir.exists()) outDir.mkdirs();

        for (String file : files) {
            String fullAssetPath = assetName + "/" + file;
            String fullOutputPath = outputPath + "/" + file;
            
            String[] subFiles = context.getAssets().list(fullAssetPath);
            if (subFiles != null && subFiles.length > 0) {
                // Directory
                copyAssetFolder(context, fullAssetPath, fullOutputPath);
            } else {
                // File
                try (InputStream in = context.getAssets().open(fullAssetPath);
                     OutputStream out = new FileOutputStream(fullOutputPath)) {
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }
}
