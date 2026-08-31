package xyz.sunkastudios.localtube.util;

import android.content.Context;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import xyz.sunkastudios.localtube.DownloadedVideo;

public class DownloadStore {
    private static final String VIDEO_STORE = "downloads.ndjson";
    private static final String SHORTS_STORE = "shorts.ndjson";

    private static String getFileName(boolean isShort) {
        return isShort ? SHORTS_STORE : VIDEO_STORE;
    }
    private static File getStoreFile(Context context, boolean isShort) {
        return new File(context.getFilesDir(), getFileName(isShort));
    }

    public static synchronized List<DownloadedVideo> getItems(Context context, boolean isShort) {
        List<DownloadedVideo> list = new ArrayList<>();
        File file = getStoreFile(context, isShort);
        if (!file.exists()) return list;

        Gson gson = new Gson();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    DownloadedVideo item = gson.fromJson(line, DownloadedVideo.class);
                    list.add(item);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }
    public static synchronized void addItem(Context context, DownloadedVideo item, boolean isShort) {
        File file = getStoreFile(context, isShort);
        Gson gson = new Gson();
        String jsonLine = gson.toJson(item) + "\n";
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(jsonLine);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static synchronized void deleteItem(Context context, String videoId, boolean isShort) {
        List<DownloadedVideo> currentList = getItems(context, isShort);
        List<DownloadedVideo> updatedList = new ArrayList<>();

        for (DownloadedVideo item : currentList) {
            if (item.getVideoId().equals(videoId)) {
                cleanUpFiles(context, item);
            } else {
                updatedList.add(item);
            }
        }
        saveAll(context, updatedList, isShort);
    }

    public static synchronized void deleteFolder(Context context, String folderPath, boolean isShort) {
        List<DownloadedVideo> currentList = getItems(context, isShort);
        List<DownloadedVideo> updatedList = new ArrayList<>();
        String prefix = folderPath + "/";

        for (DownloadedVideo item : currentList) {
            String videoFolder = item.getFolder();
            if (videoFolder.equals(folderPath) || videoFolder.startsWith(prefix)) {
                cleanUpFiles(context, item);
            } else {
                updatedList.add(item);
            }
        }
        saveAll(context, updatedList, isShort);
    }
    public static synchronized void updateFolder(Context context, String videoId, String newFolder, boolean isShort) {
        List<DownloadedVideo> currentList = getItems(context, isShort);
        for (DownloadedVideo item : currentList) {
            if (item.getVideoId().equals(videoId)) {
                item.folder = newFolder;
                break;
            }
        }
        saveAll(context, currentList, isShort);
    }
    public static synchronized void createFolderPlaceholder(Context context, String folderPath, boolean isShort) {
        DownloadedVideo placeholder = new DownloadedVideo();
        placeholder.videoId = "folder_" + System.currentTimeMillis();
        placeholder.title = "Placeholder for " + folderPath;
        placeholder.folder = folderPath;
        placeholder.isPlaceholder = true;
        addItem(context, placeholder, isShort);
    }

    public static synchronized void markAsWatched(Context context, String videoId, boolean isShort) {
        List<DownloadedVideo> currentList = getItems(context, isShort);
        for (DownloadedVideo item : currentList) {
            if (item.getVideoId().equals(videoId)) {
                item.setWatched(true);
                break;
            }
        }
        saveAll(context, currentList, isShort);
    }

    public static synchronized void saveAll(Context context, List<DownloadedVideo> list, boolean isShort) {
        File file = getStoreFile(context, isShort);
        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(file, false)) {
            for (DownloadedVideo item : list) {
                writer.write(gson.toJson(item) + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private static void cleanUpFiles(Context context, DownloadedVideo item) {
        if (!item.isPlaceholder()) {
            Context appContext = context.getApplicationContext();
            FileLoader.delete(appContext, item.getVideoFilePath());
            FileLoader.delete(appContext, item.getAudioFilePath());
            FileLoader.delete(appContext, item.getThumbnailFilePath());
        }
    }

    public static List<DownloadedVideo> getDownloads(Context context) {
        return getItems(context, false);
    }

    public static List<DownloadedVideo> getShorts(Context context) {
        return getItems(context, true);
    }

    public static void addDownload(Context context, DownloadedVideo item) {
        addItem(context, item, false);
    }

    public static void addShort(Context context, DownloadedVideo item) {
        addItem(context, item, true);
    }

    public static void deleteDownload(Context context, String videoId) {
        deleteItem(context, videoId, false);
    }

    public static void deleteFolder(Context context, String folderPath) {
        deleteFolder(context, folderPath, false);
    }

    public static void markAsWatched(Context context, String videoId) {
        markAsWatched(context, videoId, true);
    }

    public static void updateFolder(Context context, String videoId, String newFolder) {
        updateFolder(context, videoId, newFolder, false);
    }

    public static void createFolderPlaceholder(Context context, String folderPath) {
        createFolderPlaceholder(context, folderPath, false);
    }
}