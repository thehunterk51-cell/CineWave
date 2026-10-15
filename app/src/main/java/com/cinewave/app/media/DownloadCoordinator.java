package com.cinewave.app.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a save in the background: download the stream, optionally merge the
 * subtitle into it, then publish both files.
 *
 * <p>Everything happens on one background thread and reports back through
 * {@link Ui}. Only one job runs at a time, which keeps the progress dialog
 * honest and stops two merges from competing for the same disk space.
 */
public final class DownloadCoordinator {

    public interface Ui {
        void onStage(String stage);

        /** Negative means the size is unknown, so the bar should spin instead. */
        void onProgress(int percent);

        void onDone(Result result);

        void onFailed(String message);

        void onCancelled();
    }

    public static final class Request {
        public String videoUrl;
        public String referer = "";
        public String userAgent = "";
        public String fileName = "video";
        public MediaInfo.Sub subtitle;
    }

    public static final class Result {
        public Uri videoUri;
        public String videoName = "";
        public Uri subtitleUri;
        public String subtitleName = "";
        public String localPath = "";
        public String note = "";
        public boolean merged;
    }

    private static final AtomicBoolean CANCELLED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    private DownloadCoordinator() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static void cancel() {
        CANCELLED.set(true);
    }

    /**
 * Collapses the per-chunk callbacks into at most one update a fraction of a
 * second, so a fast connection cannot bury the main thread under thousands of
 * queued runnables.
 */
    private static final class ProgressReporter implements MediaFetcher.Progress {
        private final Ui ui;
        private int lastPercent = -1;
        private long lastAt;

        ProgressReporter(Ui ui) {
            this.ui = ui;
        }

        @Override
        public void onProgress(long done, long total) {
            if (total > 0) {
                int percent = (int) Math.min(100, done * 100L / total);
                if (percent == lastPercent) return;
                lastPercent = percent;
            } else {
                long now = System.currentTimeMillis();
                if (lastPercent < 0 && now - lastAt < 400) return;
                lastPercent = -1;
                lastAt = now;
            }
            MAIN.post(() -> ui.onProgress(lastPercent));
        }
    }

    public static void start(final Context context, final Request request, final Ui ui) {
        if (running || request.videoUrl == null || request.videoUrl.isEmpty()) {
            ui.onFailed("another download is already running");
            return;
        }
        running = true;
        CANCELLED.set(false);

        final Context app = context.getApplicationContext();
        new Thread(() -> {
            File raw = null;
            try {
                final String cookie = cookieHeader(app, request.videoUrl);
                File dir = MediaSaver.workDir(app);
                final String stem = safeName(request.fileName);
                Result result = new Result();
                StringBuilder notes = new StringBuilder();

                stage(ui, "Downloading the video");
                raw = new File(dir, stem + ".source");
                raw.delete();

                MediaFetcher.download(request.videoUrl, request.referer, request.userAgent,
                        cookie, raw, new ProgressReporter(ui), CANCELLED::get);

                File finalVideo = raw;

                if (request.subtitle != null) {
                    File subFile = null;
                    String subExt = "vtt";
                    try {
                        stage(ui, "Reading the subtitle");
                        String text = request.subtitle.embedded
                                ? request.subtitle.vtt
                                : MediaFetcher.text(request.subtitle.url, request.referer,
                                request.userAgent, cookie, CANCELLED::get);

                        List<SubtitleCue> cues = SubtitleParser.parse(text);
                        if (cues.isEmpty()) {
                            notes.append("That subtitle had no readable lines, so it was skipped.");
                        } else if (!hasRoomToMerge(dir, raw.length())) {
                            notes.append("There is not enough free space to merge the subtitle, "
                                    + "so the video was saved without it.");
                        } else if (Tx3gMuxer.looksLikeMp4(raw)) {
                            stage(ui, "Merging the subtitle");
                            post(ui, -1);
                            File merged = new File(dir, stem + "."
                                    + request.subtitle.tag() + ".mp4");
                            merged.delete();
                            Tx3gMuxer.mux(raw, merged, cues);
                            finalVideo = merged;
                            raw.delete();
                            result.merged = true;
                        } else {
                            notes.append("The stream is not an MP4 file, so the subtitle "
                                    + "could not be merged into it.");
                        }

                        stage(ui, "Saving the subtitle");
                        subExt = looksLikeWebVtt(text) ? "vtt" : "srt";
                        subFile = new File(dir, stem + "."
                                + request.subtitle.tag() + "." + subExt);
                        writeText(subFile, text);
                        result.subtitleName = subFile.getName();
                    } catch (MediaFetcher.Cancelled e) {
                        throw e;
                    } catch (Exception e) {
                        /* A subtitle problem must never cost the user the video. */
                        notes.append("The subtitle could not be prepared (")
                                .append(e.getMessage() == null ? "unknown reason" : e.getMessage())
                                .append("), so it was skipped. The video was saved on its own.");
                    }

                    if (subFile != null) {
                        result.subtitleUri = publish(app, subFile,
                                "vtt".equals(subExt) ? "text/vtt" : "application/x-subrip",
                                result.subtitleName);
                        if (result.subtitleUri == null) {
                            result.subtitleUri = Uri.fromFile(subFile);
                            result.localPath = subFile.getAbsolutePath();
                        } else {
                            subFile.delete();
                        }
                    }
                }

                stage(ui, "Saving the video");
                post(ui, -1);
                result.videoName = finalVideo.getName();
                String mime = finalVideo.getName().endsWith(".mp4") ? "video/mp4" : "video/*";
                result.videoUri = publish(app, finalVideo, mime, result.videoName);
                if (result.videoUri == null) {
                    // The gallery turned it down, so the working copy stays put
                    // and its path is shown to the user.
                    result.videoUri = Uri.fromFile(finalVideo);
                    result.localPath = finalVideo.getAbsolutePath();
                } else {
                    if (finalVideo != raw) finalVideo.delete();
                    raw.delete();
                }
                result.note = notes.toString();

                MAIN.post(() -> {
                    running = false;
                    ui.onDone(result);
                });
            } catch (MediaFetcher.Cancelled e) {
                cleanup(raw);
                MAIN.post(() -> {
                    running = false;
                    ui.onCancelled();
                });
            } catch (Exception e) {
                cleanup(raw);
                String reason = e.getMessage();
                final String message = reason == null || reason.trim().isEmpty()
                        ? "the download failed" : reason;
                MAIN.post(() -> {
                    running = false;
                    ui.onFailed(message);
                });
            }
        }, "cinewave-download").start();
    }

    // ---------------------------------------------------------------- utils

    /**
     * Publishing is a convenience. When the gallery turns the file down the copy
     * inside the app folder is still perfectly playable, so the caller is told
     * where to find it instead of treating the whole save as a failure.
     */
    private static Uri publish(Context app, File file, String mime, String name) {
        try {
            return MediaSaver.publish(app, file, mime, name);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Merging writes a second copy of the whole film next to the first, so a
     * large download can run the device out of room halfway through.
     */
    private static boolean hasRoomToMerge(File dir, long videoBytes) {
        try {
            long needed = videoBytes + (videoBytes >> 4) + 4_000_000L;
            return dir.getUsableSpace() > needed;
        } catch (Exception e) {
            return true;
        }
    }

    private static void cleanup(File raw) {
        if (raw == null) return;
        try {
            raw.delete();
        } catch (SecurityException ignored) {
            // best effort
        }
    }

    private static void stage(Ui ui, String text) {
        post(ui, text);
    }

    private static void post(Ui ui, int percent) {
        MAIN.post(() -> ui.onProgress(percent));
    }

    private static void post(Ui ui, String stage) {
        MAIN.post(() -> ui.onStage(stage));
    }

    private static void writeText(File file, String text) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file),
                StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }

    /** WebVTT needs its own extension; anything else is saved as SubRip. */
    static boolean looksLikeWebVtt(String text) {
        if (text == null) return false;
        int i = 0;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') i = 1;
        return text.regionMatches(true, i, "WEBVTT", i, 6);
    }

    static String safeName(String raw) {
        if (raw == null) return "video";
        String s = raw.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001F]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s.isEmpty() ? "video" : s;
    }

    private static String cookieHeader(Context context, String url) {
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            return cookies == null ? "" : cookies.trim();
        } catch (Exception e) {
            return "";
        }
    }
}