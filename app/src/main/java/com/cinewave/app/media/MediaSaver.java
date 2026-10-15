package com.cinewave.app.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Puts finished files somewhere the user can actually find them.
 *
 * <p>Downloads land in the app's own folder first, because the subtitle track
 * has to be merged into the video before either is published. On Android 10 and
 * later the result is handed to MediaStore, so it shows up in the gallery and in
 * every player that reads it; older releases fall back to the public Movies
 * folder, which needs the legacy storage permission.
 */
public final class MediaSaver {

    public static final String FOLDER = "CineWave";

    private MediaSaver() {
    }

    /** Scratch space for downloads and merges. Needs no permission at all. */
    public static File workDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return context.getFilesDir();
        return dir;
    }

    public static boolean canWritePublic(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true;
        return context.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static Uri publish(Context context, File src, String mimeType, String displayName)
            throws IOException {
        if (!src.isFile()) throw new IOException("the finished file is missing");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishModern(context, src, mimeType, displayName);
        }
        if (!canWritePublic(context)) {
            throw new IOException("storage permission is missing");
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create the Movies folder");
        }
        File dest = unique(new File(dir, displayName));
        copy(src, dest);
        return Uri.fromFile(dest);
    }

    private static Uri publishModern(Context context, File src, String mimeType,
                                     String displayName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        boolean movie = mimeType.startsWith("video/");
        Uri collection = movie
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String folder = movie ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/" + FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = resolver.insert(collection, values);
        if (item == null) throw new IOException("the system gallery refused the file");

        try {
            try (OutputStream out = resolver.openOutputStream(item)) {
                if (out == null) throw new IOException("cannot open the gallery for writing");
                stream(src, out);
            }
        } catch (IOException | RuntimeException e) {
            resolver.delete(item, null, null);
            throw e instanceof IOException ? (IOException) e
                    : new IOException("the gallery refused the file");
        }

        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        return item;
    }

    public static Intent viewIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public static File unique(File target) {
        if (!target.exists()) return target;
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File folder = target.getParentFile();
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(folder, stem + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(folder, stem + " (" + System.currentTimeMillis() + ")" + ext);
    }

    public static void copy(File src, File dest) throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            stream(src, out);
        }
    }

    private static void stream(File src, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(src)) {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
    }
}