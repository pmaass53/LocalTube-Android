package xyz.sunkastudios.localtube.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class FileLoader {

    private static File getFile(Context context, String path) {
        if (path == null) return null;
        File file = new File(path);
        if (file.isAbsolute()) {
            return file;
        }
        return new File(context.getFilesDir(), path);
    }

    public static void writeFile(Context context, String fileName, byte[] data) {
        if (context == null || fileName == null) {
            Log.d("FileLoader", "Context or fileName is null.");
            return;
        }
        File file = getFile(context, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        } catch (IOException e) {
            Log.d("FileLoader", "Failed to writeFile: '" + file.getAbsolutePath() + "'");
            e.printStackTrace();
        }
    }
    public static byte[] readFile(Context context, String fileName) {
        if (context == null || fileName == null) {
            Log.d("FileLoader", "Context or fileName is null.");
            return null;
        }
        File file = getFile(context, fileName);
        if (file == null || !file.exists()) {
            Log.d("FileLoader", "File does not exist or is null");
            return null;
        }
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int bytesRead = fis.read(data);
            if (bytesRead != data.length) {
                Log.d("FileLoader", "Warning: Could not read the entire file into memory");
            }
            return data;
        } catch (IOException e) {
            Log.d("FileLoader", "Failed to readFile: '" + file.getAbsolutePath() + "'");
            e.printStackTrace();
            return null;
        }
    }
    public static boolean exists(Context context, String fileName) {
        if (context == null || fileName == null) return false;
        File file = getFile(context, fileName);
        return file != null && file.exists();
    }
    public static void delete(Context context, String filename) {
        if (context == null || filename == null) {
            Log.d("FileLoader", "Context or filename is null.");
            return;
        }
        File file = getFile(context, filename);
        if (file != null && file.exists()) {
            if (!file.delete()) {
                Log.d("FileLoader", "Failed to delete file: " + file.getAbsolutePath());
            }
        } else {
            Log.d("FileLoader", "Delete failed: file does not exist: " + (file != null ? file.getAbsolutePath() : "null"));
        }
    }
}