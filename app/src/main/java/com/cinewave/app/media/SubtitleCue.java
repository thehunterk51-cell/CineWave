package com.cinewave.app.media;

/** One subtitle entry: a start time, an end time and the text to display. */
public final class SubtitleCue {

    public final long startMs;
    public final long endMs;
    public final String text;

    public SubtitleCue(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text == null ? "" : text;
    }

    public long durationMs() {
        return endMs - startMs;
    }
}