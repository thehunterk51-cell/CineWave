package com.cinewave.app.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turns SubRip ({@code .srt}) or WebVTT ({@code .vtt}) into a list of cues.
 *
 * <p>Both formats are line based and share the same shape, so one reader covers
 * them: the only real differences are the WEBVTT header, the note/style blocks
 * and cue settings sitting after the end timestamp.
 */
public final class SubtitleParser {

    /**
     * Collections.sort is used rather than List.sort because the latter only
     * arrived in Android 7 and this app still runs on Android 6.
     */
    private static final Comparator<SubtitleCue> START_ORDER =
            new Comparator<SubtitleCue>() {
                @Override
                public int compare(SubtitleCue a, SubtitleCue b) {
                    return a.startMs < b.startMs ? -1 : (a.startMs > b.startMs ? 1 : 0);
                }
            };

    private SubtitleParser() {
    }

    public static List<SubtitleCue> parse(String raw) {
        List<SubtitleCue> cues = new ArrayList<>();
        if (raw == null) return cues;

        String text = raw;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');

        String[] lines = text.split("\n", -1);
        int cursor = 0;

        while (cursor < lines.length) {
            String line = lines[cursor];
            int trimmed = line.trim().length();

            if (trimmed == 0) {
                cursor++;
                continue;
            }

            // WEBVTT header and non-cue blocks.
            if (startsWithWord(line, "WEBVTT") || startsWithWord(line, "NOTE")
                    || startsWithWord(line, "STYLE") || startsWithWord(line, "REGION")) {
                cursor++;
                while (cursor < lines.length && lines[cursor].trim().length() != 0) cursor++;
                cursor++;
                continue;
            }

            int arrow = line.indexOf("-->");
            if (arrow <= 0) {
                // A bare cue identifier, or junk. Either way it is not a cue.
                cursor++;
                continue;
            }

            long start = parseTimestamp(line.substring(0, arrow));
            String rest = line.substring(arrow + 3).trim();
            int space = rest.indexOf(' ');
            String endToken = space < 0 ? rest : rest.substring(0, space);
            long end = parseTimestamp(endToken);

            cursor++;
            StringBuilder body = new StringBuilder();
            while (cursor < lines.length && lines[cursor].trim().length() != 0) {
                if (body.length() > 0) body.append('\n');
                body.append(lines[cursor]);
                cursor++;
            }

            String cleaned = clean(body.toString());
            if (start >= 0 && end > start && !cleaned.isEmpty()) {
                cues.add(new SubtitleCue(start, end, cleaned));
            }
        }

        Collections.sort(cues, START_ORDER);
        return cues;
    }

    private static boolean startsWithWord(String line, String word) {
        if (line.length() < word.length()) return false;
        if (!line.substring(0, word.length()).toUpperCase(Locale.ROOT).equals(word)) return false;
        return line.length() == word.length() || line.charAt(word.length()) == ' '
                || line.charAt(word.length()) == '\t';
    }

    /** Accepts {@code HH:MM:SS,mmm}, {@code HH:MM:SS.mmm} and {@code MM:SS.mmm}. */
    private static long parseTimestamp(String token) {
        String s = token.trim();
        if (s.isEmpty()) return -1;

        long millis = 0;
        int comma = s.indexOf(',');
        int dot = s.indexOf('.');
        int fracAt = comma >= 0 ? comma : dot;
        if (fracAt >= 0) {
            String frac = s.substring(fracAt + 1);
            if (frac.isEmpty()) return -1;
            int millisPart = 0;
            int digits = 0;
            for (int i = 0; i < frac.length() && digits < 3; i++) {
                char c = frac.charAt(i);
                if (c < '0' || c > '9') return -1;
                millisPart = millisPart * 10 + (c - '0');
                digits++;
            }
            while (digits < 3) {
                millisPart *= 10;
                digits++;
            }
            millis = millisPart;
            s = s.substring(0, fracAt);
        }

        String[] parts = s.split(":");
        if (parts.length == 0 || parts.length > 3) return -1;

        long hours = 0;
        long minutes = 0;
        long seconds;
        try {
            if (parts.length == 3) {
                hours = Long.parseLong(parts[0]);
                minutes = Long.parseLong(parts[1]);
                seconds = Long.parseLong(parts[2]);
            } else if (parts.length == 2) {
                minutes = Long.parseLong(parts[0]);
                seconds = Long.parseLong(parts[1]);
            } else {
                seconds = Long.parseLong(parts[0]);
            }
        } catch (NumberFormatException e) {
            return -1;
        }

        if (seconds < 0 || minutes < 0 || hours < 0) return -1;
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis;
    }

    /** Drops markup and resolves the handful of entities the players emit. */
    private static String clean(String body) {
        if (body.isEmpty()) return "";
        String s = body;

        StringBuilder out = new StringBuilder(s.length());
        boolean inTag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                out.append(c);
            }
        }
        s = out.toString();

        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&amp;", "&");

        // Karaoke and positioning tags can survive a pass or two; cut the rest.
        s = s.replaceAll("\\{\\[^}]*\\}", "");

        StringBuilder trimmed = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (trimmed.length() > 0) trimmed.append('\n');
            trimmed.append(t);
        }
        return trimmed.toString();
    }
}