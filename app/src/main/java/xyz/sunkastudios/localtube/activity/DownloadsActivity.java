package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import xyz.sunkastudios.localtube.DownloadAdapter;
import xyz.sunkastudios.localtube.DownloadListItem;
import xyz.sunkastudios.localtube.DownloadedVideo;
import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.DownloadStore;
import xyz.sunkastudios.localtube.util.UIUtil;

public class DownloadsActivity extends AppCompatActivity implements DownloadAdapter.DownloadAdapterListener {
    private final List<DownloadedVideo> allDownloads = new ArrayList<>();
    private final List<DownloadListItem> displayList = new ArrayList<>();
    private RecyclerView recyclerView;
    private DownloadAdapter adapter;
    private String currentPath = ""; // Root is empty string

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // Drag & Drop tracking
    private DownloadListItem targetFolderItem = null;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_downloads);
        UIUtil.applyInsets(this);

        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new DownloadAdapter(displayList, this);
        recyclerView.setAdapter(adapter);

        findViewById(R.id.bottomMenuBar).setBackground(new ColorDrawable(ConfigManager.getColor("navbar_background_color", "#777777")));
        TextView banner = findViewById(R.id.home_text_banner);
        if (banner != null) banner.setTextColor(UIUtil.getAccentColor());

        findViewById(R.id.btnYoutubeHome).setOnClickListener(v -> {
            Intent intent = new Intent(DownloadsActivity.this, HomeActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        });
        findViewById(R.id.btnYoutubeShorts).setOnClickListener(new View.OnClickListener() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(DownloadsActivity.this, ShortsActivity.class);
                startActivity(intent);
            }
        });
        findViewById(R.id.btnAniHome).setOnClickListener(v -> {
            Intent intent = new Intent(DownloadsActivity.this, AniHomeActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.btnDownloads).setOnClickListener(v -> {
            if (!currentPath.isEmpty()) {
                currentPath = "";
                updateDisplayList();
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> {
            Intent intent = new Intent(DownloadsActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btnCreateFolder).setOnClickListener(v -> showCreateFolderDialog());
        findViewById(R.id.btnPlayFolder).setOnClickListener(v -> playCurrentFolderAsPlaylist());

        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        swipeRefreshLayout.setOnRefreshListener(this::loadVideos);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!currentPath.isEmpty()) {
                    navigateUp();
                } else {
                    setEnabled(false);
                    onBackPressed();
                }
            }
        });

        setupDragAndDrop();
        loadVideos();
    }

    private void navigateUp() {
        int lastSlash = currentPath.lastIndexOf('/');
        if (lastSlash == -1) currentPath = "";
        else currentPath = currentPath.substring(0, lastSlash);
        updateDisplayList();
    }

    private void showCreateFolderDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create Folder");
        final EditText input = new EditText(this);
        builder.setView(input);
        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();
            if (!folderName.isEmpty()) {
                String fullPath = currentPath.isEmpty() ? folderName : currentPath + "/" + folderName;
                DownloadStore.createFolderPlaceholder(this, fullPath);
                loadVideos();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void updateDisplayList() {
        displayList.clear();
        
        TextView banner = findViewById(R.id.home_text_banner);
        if (banner != null) {
            banner.setText(currentPath.isEmpty() ? "Downloads" : currentPath);
        }

        if (!currentPath.isEmpty()) {
            displayList.add(DownloadListItem.forFolder(".."));
        }

        Set<String> seenFolders = new HashSet<>();
        for (DownloadedVideo item : allDownloads) {
            String itemFolder = item.getFolder();
            if (itemFolder.equals(currentPath)) {
                if (!item.isPlaceholder()) {
                    displayList.add(DownloadListItem.forVideo(item));
                }
            } else if (itemFolder.startsWith(currentPath + (currentPath.isEmpty() ? "" : "/"))) {
                String relative = itemFolder.substring(currentPath.isEmpty() ? 0 : currentPath.length() + 1);
                String folderName = relative.split("/")[0];
                if (!seenFolders.contains(folderName)) {
                    displayList.add(DownloadListItem.forFolder(folderName));
                    seenFolders.add(folderName);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void setupDragAndDrop() {
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            private int fromPosition = -1;
            private int toPosition = -1;

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int from = viewHolder.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();

                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;

                // Handle ".." folder - don't allow moving it or moving anything above it
                if (displayList.get(from).getFolderName() != null && displayList.get(from).getFolderName().equals("..")) return false;
                if (displayList.get(to).getFolderName() != null && displayList.get(to).getFolderName().equals("..")) return false;

                // Always allow visual swap for better feedback
                java.util.Collections.swap(displayList, from, to);
                adapter.notifyItemMoved(from, to);

                toPosition = to;
                if (fromPosition == -1) fromPosition = from;

                // Detect if hovering over a folder for "move to folder" action
                // The item we just jumped over is now at the 'from' position after swap
                DownloadListItem jumpedOver = displayList.get(from);
                if (jumpedOver.getType() == DownloadListItem.TYPE_FOLDER) {
                    targetFolderItem = jumpedOver;
                } else {
                    targetFolderItem = null;
                }

                return true;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    if (viewHolder instanceof DownloadAdapter.VideoViewHolder) {
                        ((DownloadAdapter.VideoViewHolder) viewHolder).setDragging(true);
                    } else if (viewHolder instanceof DownloadAdapter.FolderViewHolder) {
                        ((DownloadAdapter.FolderViewHolder) viewHolder).setDragging(true);
                    }
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                
                if (viewHolder instanceof DownloadAdapter.VideoViewHolder) {
                    ((DownloadAdapter.VideoViewHolder) viewHolder).setDragging(false);
                } else if (viewHolder instanceof DownloadAdapter.FolderViewHolder) {
                    ((DownloadAdapter.FolderViewHolder) viewHolder).setDragging(false);
                }

                if (targetFolderItem != null && toPosition != -1) {
                    DownloadListItem movedItem = displayList.get(toPosition);
                    if (movedItem.getType() == DownloadListItem.TYPE_VIDEO) {
                        if (targetFolderItem.getFolderName().equals("..")) {
                            moveVideoUp(movedItem.getVideo());
                        } else {
                            moveVideoToFolder(movedItem.getVideo(), targetFolderItem.getFolderName());
                        }
                    } else {
                        loadVideos(); // Reset if folder-on-folder or other
                    }
                } else if (fromPosition != -1 && toPosition != -1 && fromPosition != toPosition) {
                    saveRearrangedOrder();
                }

                fromPosition = -1;
                toPosition = -1;
                targetFolderItem = null;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}
        };
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);
    }

    private void saveRearrangedOrder() {
        List<DownloadedVideo> finalOrder = new ArrayList<>();
        Set<String> processedVideoIds = new HashSet<>();

        for (DownloadListItem item : displayList) {
            if (item.getFolderName() != null && item.getFolderName().equals("..")) continue;

            if (item.getType() == DownloadListItem.TYPE_VIDEO) {
                finalOrder.add(item.getVideo());
                processedVideoIds.add(item.getVideo().getVideoId());
            } else if (item.getType() == DownloadListItem.TYPE_FOLDER) {
                String fullFolderPath = currentPath.isEmpty() ? item.getFolderName() : currentPath + "/" + item.getFolderName();
                for (DownloadedVideo v : allDownloads) {
                    if (v.getFolder().equals(fullFolderPath) || v.getFolder().startsWith(fullFolderPath + "/")) {
                        finalOrder.add(v);
                        processedVideoIds.add(v.getVideoId());
                    }
                }
            }
        }
        
        List<DownloadedVideo> result = new ArrayList<>();
        boolean insertedSet = false;
        
        for (DownloadedVideo v : allDownloads) {
            boolean inCurrentView = v.getFolder().equals(currentPath) || v.getFolder().startsWith(currentPath + (currentPath.isEmpty() ? "" : "/"));
            
            if (inCurrentView) {
                if (!insertedSet) {
                    result.addAll(finalOrder);
                    insertedSet = true;
                }
            } else {
                if (!processedVideoIds.contains(v.getVideoId())) {
                    result.add(v);
                }
            }
        }

        allDownloads.clear();
        allDownloads.addAll(result);
        DownloadStore.saveAll(this, allDownloads, false);
    }

    private void moveVideoToFolder(DownloadedVideo video, String targetSubFolder) {
        String newFolder = currentPath.isEmpty() ? targetSubFolder : currentPath + "/" + targetSubFolder;
        DownloadStore.updateFolder(this, video.getVideoId(), newFolder);
        Toast.makeText(this, "Moved to " + targetSubFolder, Toast.LENGTH_SHORT).show();
        loadVideos();
    }

    private void moveVideoUp(DownloadedVideo video) {
        int lastSlash = currentPath.lastIndexOf('/');
        String parentFolder = (lastSlash == -1) ? "" : currentPath.substring(0, lastSlash);
        DownloadStore.updateFolder(this, video.getVideoId(), parentFolder);
        Toast.makeText(this, "Moved up", Toast.LENGTH_SHORT).show();
        loadVideos();
    }

    private void loadVideos() {
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        ProgressBar progressBar = findViewById(R.id.loadingSpinner);

        if (!swipeRefreshLayout.isRefreshing()) {
            progressBar.setVisibility(View.VISIBLE);
        }

        executorService.execute(() -> {
            try {
                final List<DownloadedVideo> downloads = DownloadStore.getDownloads(getApplicationContext());
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    allDownloads.clear();
                    if (downloads != null) {
                        allDownloads.addAll(downloads);
                    }
                    updateDisplayList();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    swipeRefreshLayout.setRefreshing(false);
                    Toast.makeText(DownloadsActivity.this, "Error loading videos", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void playCurrentFolderAsPlaylist() {
        List<DownloadedVideo> playlist = allDownloads.stream()
                .filter(v -> v.getFolder().equals(currentPath) && !v.isPlaceholder())
                .collect(Collectors.toList());

        if (playlist.isEmpty()) {
            Toast.makeText(this, "No videos in this folder", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("online", false);
        intent.putExtra("video_uri", playlist.get(0).getVideoFilePath());
        intent.putExtra("audio_uri", playlist.get(0).getAudioFilePath());
        intent.putExtra("video_id", playlist.get(0).getVideoId());

        ArrayList<String> videoUris = new ArrayList<>();
        ArrayList<String> audioUris = new ArrayList<>();
        ArrayList<String> videoIds = new ArrayList<>();

        for (DownloadedVideo v : playlist) {
            videoUris.add(v.getVideoFilePath());
            audioUris.add(v.getAudioFilePath());
            videoIds.add(v.getVideoId());
        }

        intent.putStringArrayListExtra("playlist_video_uris", videoUris);
        intent.putStringArrayListExtra("playlist_audio_uris", audioUris);
        intent.putStringArrayListExtra("playlist_video_ids", videoIds);
        intent.putExtra("playlist_start_index", 0);

        // Guaranteed queuing via Service
        Intent serviceIntent = new Intent(this, xyz.sunkastudios.localtube.PlaybackService.class);
        serviceIntent.setAction("ACTION_PREPARE_PLAYLIST");
        Bundle extras = intent.getExtras();
        if (extras != null) serviceIntent.putExtras(extras);
        startService(serviceIntent);

        startActivity(intent);
    }

    @Override
    public void onFolderClick(String folderName) {
        if (folderName.equals("..")) {
            navigateUp();
        } else {
            if (currentPath.isEmpty()) currentPath = folderName;
            else currentPath += "/" + folderName;
            updateDisplayList();
        }
    }

    @Override
    public void onVideoClick(DownloadedVideo video) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("online", false);
        intent.putExtra("video_uri", video.getVideoFilePath());
        intent.putExtra("audio_uri", video.getAudioFilePath());
        intent.putExtra("video_id", video.getVideoId());
        startActivity(intent);
    }

    @Override
    public void onDeleteClick(DownloadedVideo video) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Video")
                .setMessage("Are you sure you want to delete this video?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    DownloadStore.deleteDownload(this, video.getVideoId());
                    loadVideos();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onFolderDeleteClick(String folderName) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Folder")
                .setMessage("Delete folder '" + folderName + "' and ALL its contents?")
                .setPositiveButton("Delete All", (dialog, which) -> {
                    String fullPath = currentPath.isEmpty() ? folderName : currentPath + "/" + folderName;
                    DownloadStore.deleteFolder(this, fullPath);
                    loadVideos();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadVideos();
    }
}
