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

    /**
     * Works out what a downloaded file really is from its first bytes.
     *
     * <p>MediaStore refuses an entry whose MIME type it does not recognise, so
     * the extension has to be right or the save fails at the very last step.
     * Guessing from the temporary name is not enough: the name comes from a
     * pasted link or a page title and usually carries no extension at all.
     *
     * @return "mp4", "webm", "mkv", "ogv", or null when nothing matched
     */
    public static String sniffExtension(File f) {
        if (f == null || !f.isFile() || f.length() < 12) return null;
        InputStream in = null;
        try {
            byte[] head = new byte[64];
            int n = 0;
            in = new FileInputStream(f);
            int read;
            while (n < head.length
                    && (read = in.read(head, n, head.length - n)) > 0) {
                n += read;
            }
            if (n < 12) return null;

            if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                return "mp4";
            }
            if ((head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45 &&
                    (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
                // Matroska and WebM share a signature; the doc type tells them apart.
                for (int i = 0; i + 4 <= n; i++) {
                    if (head[i] == 'w' && head[i + 1] == 'e' && head[i + 2] == 'b'
                            && head[i + 3] == 'm') {
                        return "webm";
                    }
                }
                return "mkv";
            }
            if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
                return "ogv";
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** The extension carried by a URL's path, if it looks like one we know. */
    public static String extensionFromUrl(String url) {
        if (url == null) return null;
        int cut = url.length();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String path = url.substring(0, cut);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) return null;
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return mimeFor(ext) == null ? null : ext;
    }

    public static String mimeFor(String ext) {
        if (ext == null) return null;
        switch (ext.toLowerCase(java.util.Locale.ROOT)) {
            case "mp4":
            case "m4v":
            case "mov":
                return "video/mp4";
            case "webm":
                return "video/webm";
            case "mkv":
                return "video/x-matroska";
            case "ogv":
            case "ogg":
                return "video/ogg";
            case "srt":
                return "application/x-subrip";
            case "vtt":
                return "text/vtt";
            default:
                return null;
        }
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

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}