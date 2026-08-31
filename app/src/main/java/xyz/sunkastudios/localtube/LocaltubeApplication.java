package xyz.sunkastudios.localtube;

import android.app.Application;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.ConfigManager;

public class LocaltubeApplication extends Application {
    private static final String TAG = "LocaltubeApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught exception in thread " + thread.getName(), throwable);
        });
        
        try {
            Log.i(TAG, "LocaltubeApplication.onCreate() started");
            
            ConfigManager.init(this);
            Log.i(TAG, "ConfigManager initialized.");
            
            Log.i(TAG, "Initializing Python environment...");
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            Log.i(TAG, "Python environment initialized.");
            
            // Initialize YoutubeEngine in background
            new Thread(() -> {
                Log.i(TAG, "Initializing YoutubeEngine in background...");
                YoutubeEngine.init(this);
                Log.i(TAG, "YoutubeEngine initialized.");
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "LocaltubeApplication.onCreate() failed!", e);
        }
    }
}
