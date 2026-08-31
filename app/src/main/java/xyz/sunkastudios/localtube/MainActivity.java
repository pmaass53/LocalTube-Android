package xyz.sunkastudios.localtube;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import xyz.sunkastudios.localtube.activity.HomeActivity;
import xyz.sunkastudios.localtube.activity.LoginActivity;
import xyz.sunkastudios.localtube.engine.PythonEngine;
import xyz.sunkastudios.localtube.engine.YoutubeEngine;
import xyz.sunkastudios.localtube.util.FileLoader;
import xyz.sunkastudios.localtube.util.NetworkManager;
import xyz.sunkastudios.localtube.util.PrefetchManager;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile boolean isUserAuthenticated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Window UI initialization
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // run async tasks
        runInitializationAsync().thenRunAsync(() -> {
            if (isUserAuthenticated) {
                displayMessage("Cookies file found and valid.\nLaunching HomeActivity...\n");
                navigateTo(HomeActivity.class);
            } else {
                displayMessage("Cookies file missing or empty.\nLaunching LoginActivity...\n");
                navigateTo(LoginActivity.class);
            }
        }, this::runOnUiThread);
    }

    private CompletableFuture<Void> runInitializationAsync() {
        return CompletableFuture.runAsync(() -> {
            if (NetworkManager.isOnline(getApplicationContext())) {
                triggerPrefetch();
            } else {
                displayMessage("No internet connection. Skip updating engine.\n");
            }

            if (FileLoader.exists(getApplicationContext(), "cookies.txt")) {
                displayMessage("Checking cookies.txt file size...\n");
                byte[] cookieBytes = FileLoader.readFile(getApplicationContext(),"cookies.txt");

                // Set authentication status true if file bytes exist and are not empty
                if (cookieBytes != null && cookieBytes.length > 0) {
                    isUserAuthenticated = true;
                } else {
                    displayMessage("Cookies file was found but empty\n");
                }
            } else {
                displayMessage("Cookies file does not exist\n");
            }
        }, executor);
    }

    private void triggerPrefetch() {
        // Prefetch Shorts
        YoutubeEngine.getShortsBatch(getApplicationContext(), new YoutubeEngine.ShortsCallback() {
            @Override
            public void onResult(List<ShortItem> shorts) {
                PrefetchManager.setPrefetchedShorts(shorts);
            }
            @Override
            public void onError(String error) {}
        });

        // Prefetch Anime Home
        executor.execute(() -> {
            try {
                List<VideoItem> anime = PythonEngine.searchAnime("Popular");
                PrefetchManager.setPrefetchedAnimeHome(anime);
            } catch (Exception ignored) {}
        });
    }

    public void navigateTo(Class<?> targetActivity) {
        Intent intent = new Intent(MainActivity.this, targetActivity);
        startActivity(intent);
        finish();
    }

    private void displayMessage(String message) {
        TextView messageBox = findViewById(R.id.messageBox);
        messageBox.append(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
