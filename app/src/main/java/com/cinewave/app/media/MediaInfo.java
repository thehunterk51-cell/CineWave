package com.cinewave.app.media;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What {@code media_probe.js} found on the current page. */
public final class MediaInfo {

    public String pageUrl = "";
    public String title = "";
    /** Addresses the player holds that cannot be fetched from outside the page. */
    public int undownloadable;
    public final List<Stream> streams = new ArrayList<>();
    public final List<Sub> subs = new ArrayList<>();

    public static final class Stream {
        public String url = "";
        public String label = "";
        public String kind = "";

        public boolean isAdaptive() {
            String path = pathOf(url).toLowerCase(Locale.ROOT);
            return path.endsWith(".m3u8") || url.contains("format=m3u8")
                    || url.contains(".m3u8?") || path.endsWith(".mpd");
        }
    }

    public static final class Sub {
        public String url = "";
        public String label = "";
        public String lang = "";
        public boolean embedded;
        public String vtt = "";

        public String tag() {
            String t = lang == null ? "" : lang.toLowerCase(Locale.ROOT);
            t = t.replaceAll("[^a-z0-9]+", "");
            if (t.isEmpty() || t.length() > 5) t = "sub";
            return t;
        }
    }

    public static MediaInfo from(String json) throws JSONException {
        MediaInfo info = new MediaInfo();
        if (json == null) throw new JSONException("empty response");

        JSONObject root = new JSONObject(json);
        info.pageUrl = root.optString("page", "");
        info.title = root.optString("title", "");
        info.undownloadable = root.optInt("undownloadable", 0);

        JSONArray streams = root.optJSONArray("streams");
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject o = streams.optJSONObject(i);
                if (o == null) continue;
                Stream s = new Stream();
                s.url = o.optString("url", "");
                s.label = o.optString("label", "");
                s.kind = o.optString("kind", "");
                if (!s.url.isEmpty()) info.streams.add(s);
            }
        }

        JSONArray subs = root.optJSONArray("subs");
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject o = subs.optJSONObject(i);
                if (o == null) continue;
                Sub s = new Sub();
                s.url = o.optString("url", "");
                s.label = o.optString("label", "");
                s.lang = o.optString("lang", "");
                s.embedded = o.optBoolean("inline", false);
                s.vtt = o.optString("vtt", "");
                if (s.embedded || !s.url.isEmpty()) info.subs.add(s);
            }
        }

        return info;
    }

    /** A filesystem-safe name derived from the page title. */
    public String suggestedName() {
        String base = title;
        if (base == null) base = "";
        base = base.replaceAll("(?i)\\b(watch|streaming|full episode|hd|free)\\b", " ");
        base = base.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001F]", " ");
        base = base.replaceAll("\\s+", " ").trim();
        if (base.isEmpty()) {
            base = pageUrl.isEmpty() ? "video" : hostLabel(pageUrl);
        }
        if (base.length() > 90) base = base.substring(0, 90).trim();
        return base.isEmpty() ? "video" : base;
    }

    private static String hostLabel(String url) {
        try {
            String h = android.net.Uri.parse(url).getHost();
            if (h == null || h.isEmpty()) return "video";
            int dot = h.indexOf('.');
            return dot > 0 ? h.substring(0, dot) : h;
        } catch (Exception e) {
            return "video";
        }
    }

    private static String pathOf(String url) {
        if (url == null) return "";
        int cut = url.length();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        return url.substring(0, cut);
    }
}