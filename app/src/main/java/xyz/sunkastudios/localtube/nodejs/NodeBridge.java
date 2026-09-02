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
                
                // Force recopy if we want to ensure latest assets are used
                // In a production app, use version codes.
                boolean forceRecopy = true;
                
                if (forceRecopy || !nodeDir.exists()) {
                    Log.i(TAG, "Copying nodejs-project assets...");
                    if (nodeDir.exists()) deleteRecursive(nodeDir);
                    copyAssetFolder(context, "nodejs-project", nodeDir.getAbsolutePath());
                    Log.i(TAG, "Assets copied successfully.");
                } else {
                    Log.i(TAG, "nodejs-project already exists, skipping copy.");
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
