package com.cinewave.app.media;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * Minimal HTTP client for the two things the app pulls down: the video stream
 * and the subtitle file.
 *
 * <p>Stream hosts check the {@code Referer} and the session cookie, so both are
 * replayed from the WebView. The video is written to a {@code .part} file and
 * only renamed once the transfer finishes, so an interrupted download can never
 * be mistaken for a complete one.
 */
public final class MediaFetcher {

    public interface Progress {
        void onProgress(long done, long total);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    public static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }

    private MediaFetcher() {
    }

    public static long download(String url, String referer, String userAgent, String cookie,
                                File dest, Progress progress, Cancel cancel) throws IOException {
        File tmp = new File(dest.getPath() + ".part");

        /* Hosts disagree about whether they want a Referer: some serve only to a
         * page they recognise, others treat any Referer as suspicious. Trying the
         * second shape costs one extra request and rescues those that do. */
        IOException last = null;
        boolean hasReferer = referer != null && !referer.isEmpty();
        String[] referers = hasReferer ? new String[]{ referer, null } : new String[]{ null };
        for (String attempt : referers) {
            if (cancel != null && cancel.isCancelled()) throw new Cancelled();
            try {
                return fetchTo(url, attempt, userAgent, cookie, tmp, dest, progress, cancel);
            } catch (IOException e) {
                if (e instanceof Cancelled) throw e;
                last = e;
            }
        }
        tmp.delete();
        throw last == null ? new IOException("the download failed") : last;
    }

    private static long fetchTo(String url, String referer, String userAgent, String cookie,
                                File tmp, File target, Progress progress, Cancel cancel)
            throws IOException {
        long total = -1;
        long done = 0;

        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            c = open(url, referer, userAgent, cookie);
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                c.disconnect();
                c = null;
                throw new IOException("the server refused the download (HTTP " + code + ")");
            }
            total = c.getContentLengthLong();

            in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            out = new FileOutputStream(tmp);

            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.isCancelled()) throw new Cancelled();
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (c != null) c.disconnect();
        }

        if (done == 0) {
            tmp.delete();
            throw new IOException("the server sent an empty file");
        }
        if (target.exists() && !target.delete()) {
            tmp.delete();
            throw new IOException("cannot replace the existing file");
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("cannot move the download into place");
        }
        return done;
    }

    public static String text(String url, String referer, String userAgent, String cookie,
                              Cancel cancel) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (cancel != null && cancel.isCancelled()) throw new Cancelled();
            HttpURLConnection c = open(url, referer, userAgent, cookie);
            try {
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) continue;
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[1 << 15];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.isCancelled()) throw new Cancelled();
                        buffer.write(buf, 0, n);
                        if (buffer.size() > 8 << 20) break;
                    }
                }
                return new String(buffer.toByteArray(), charsetOf(c.getContentType()));
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("the subtitle could not be downloaded");
    }

    private static HttpURLConnection open(String url, String referer, String userAgent,
                                          String cookie) throws IOException {
        URL target = new URL(url);
        HttpURLConnection c = (HttpURLConnection) target.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        if (userAgent != null && !userAgent.isEmpty()) {
            c.setRequestProperty("User-Agent", userAgent);
        }
        if (referer != null && !referer.isEmpty()) {
            c.setRequestProperty("Referer", referer);
        }
        if (cookie != null && !cookie.isEmpty()) {
            c.setRequestProperty("Cookie", cookie);
        }
        return c;
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            int at = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (at >= 0) {
                String name = contentType.substring(at + 8).trim();
                int semi = name.indexOf(';');
                if (semi >= 0) name = name.substring(0, semi);
                name = name.replace("\"", "").trim();
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {
                    // fall through to UTF-8
                }
            }
        }
        return Charset.forName("UTF-8");
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