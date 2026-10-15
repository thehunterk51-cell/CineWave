package com.cinewave.app.media;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Soft-muxes a subtitle track into an existing MP4.
 *
 * <p>Nothing is decoded or re-encoded: the audio and video payloads are copied
 * across untouched and a 3GPP timed text ({@code tx3g}) track is appended, the
 * same result {@code ffmpeg -c:s mov_text} produces. That keeps the original
 * quality and finishes in seconds instead of hours.
 *
 * <p>The trick that keeps this cheap is not moving anything: the source file is
 * copied byte for byte, so every existing chunk offset stays valid. The new
 * samples are appended after the media data and a rebuilt index is written at
 * the end. When the original index sat at the end of the file it is dropped and
 * its space reused; when it sat at the front (faststart) its header is turned
 * into a {@code free} box so no parser ever sees a second {@code moov}.
 */
public final class Tx3gMuxer {

    /** Cue durations are stored in milliseconds, so this is the track timescale. */
    private static final long TIMESCALE = 1000L;
    private static final byte[] FONT = {'A', 'r', 'i', 'a', 'l', 0};
    private static final int MAX_MOOV = 64 * 1024 * 1024;
    /** The tx3g length prefix is 16 bits wide. */
    private static final int MAX_SAMPLE = 0xFFFF;

    private Tx3gMuxer() {
    }

    /** Cheap sniff so the caller can explain the failure before doing any work. */
    public static boolean looksLikeMp4(File f) {
        if (f == null || !f.isFile() || f.length() < 12) return false;
        RandomAccessFile in = null;
        try {
            in = new RandomAccessFile(f, "r");
            byte[] head = new byte[12];
            in.readFully(head);
            return head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    public static void mux(File src, File dst, List<SubtitleCue> cues) throws IOException {
        if (src == null || !src.isFile()) throw new IOException("video file is missing");
        if (cues == null || cues.isEmpty()) throw new IOException("the subtitle has no cues");
        if (!looksLikeMp4(src)) throw new IOException("the video is not an MP4 file");
        if (src.equals(dst)) throw new IOException("source and destination are the same file");

        RandomAccessFile in = new RandomAccessFile(src, "r");
        RandomAccessFile out = new RandomAccessFile(dst, "rw");
        try {
            long length = in.length();
            out.setLength(0);
            copy(in, out, length);

            List<Box> top = scanTop(out, length);
            Box moov = null;
            Box last = null;
            for (Box b : top) {
                if ("moof".equals(b.type) || "mvex".equals(b.type)) {
                    throw new IOException("this MP4 is fragmented and cannot be merged");
                }
                if (b.openEnded) closeOpenSize(out, b);
                if ("moov".equals(b.type)) moov = b;
                last = b;
            }
            if (moov == null) throw new IOException("the video has no index (moov) box");
            if (moov.size > MAX_MOOV) throw new IOException("the video index is too large");

            /* Read the index before touching anything: the common layout ends
             * with it, and the next step reuses exactly that space. */
            byte[] oldMoov = readRange(out, moov.start, (int) moov.size);
            long trackId = Math.max(1L, nextTrackId(oldMoov));
            long movieTimescale = movieTimescale(oldMoov);
            if (movieTimescale <= 0) movieTimescale = TIMESCALE;

            /* Where the subtitle payload and the rebuilt index will start. */
            long insertAt;
            if (last != null && "moov".equals(last.type)) {
                insertAt = moov.start;
                out.setLength(insertAt);
            } else {
                out.seek(moov.start + 4);
                out.write(new byte[]{'f', 'r', 'e', 'e'});
                insertAt = out.length();
            }

            Samples samples = buildSamples(cues);

            out.seek(insertAt);
            writeU32(out, samples.data.length + 8L);
            out.write(new byte[]{'m', 'd', 'a', 't'});
            out.write(samples.data);

            long trackDuration = totalMs(cues) * movieTimescale / TIMESCALE;
            byte[] subTrak = buildTrak(trackId, trackDuration, samples.timing, insertAt + 8);
            byte[] newMoov = rebuildMoov(oldMoov, subTrak, trackId + 1);

            out.seek(insertAt + 8L + samples.data.length);
            out.write(newMoov);
            out.setLength(out.getFilePointer());
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    // ------------------------------------------------------------- sampling

    private static final class Timing {
        final List<long[]> stts = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();
        long totalDuration;

        void add(int size, long deltaMs) {
            if (deltaMs < 1) deltaMs = 1;
            sizes.add(size);
            if (!stts.isEmpty()) {
                long[] last = stts.get(stts.size() - 1);
                if (last[1] == deltaMs) {
                    last[0]++;
                } else {
                    stts.add(new long[]{1, deltaMs});
                }
            } else {
                stts.add(new long[]{1, deltaMs});
            }
            totalDuration += deltaMs;
        }
    }

    private static final class Samples {
        final byte[] data;
        final Timing timing;

        Samples(byte[] data, Timing timing) {
            this.data = data;
            this.timing = timing;
        }
    }

    private static Samples buildSamples(List<SubtitleCue> cues) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Timing timing = new Timing();
        long cursor = 0;

        for (SubtitleCue cue : cues) {
            long start = Math.max(0L, cue.startMs);
            long end = Math.max(start + 1L, cue.endMs);
            /* An empty sample covers a silence, which is how the gap before the
             * first line and the gaps between lines are expressed in a
             * subtitle track. */
            if (start > cursor) {
                data.write(0);
                data.write(0);
                timing.add(2, start - cursor);
            }
            byte[] payload = encodeSample(cue.text);
            data.write(payload, 0, payload.length);
            timing.add(payload.length, end - start);
            cursor = end;
        }
        return new Samples(data.toByteArray(), timing);
    }

    /** A tx3g sample is a big-endian byte count followed by the escaped text. */
    private static byte[] encodeSample(String text) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        /* The length field is 16 bits, so a runaway line is clipped rather than
         * allowed to wrap around and describe the wrong number of bytes. */
        if (raw.length > MAX_SAMPLE) {
            raw = new String(raw, 0, MAX_SAMPLE, StandardCharsets.UTF_8)
                    .getBytes(StandardCharsets.UTF_8);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(raw.length + 16);
        for (byte b : raw) {
            if (b == '\n') {
                body.write('\\');
                body.write('n');
            } else {
                body.write(b);
            }
        }
        byte[] escaped = body.toByteArray();
        ByteArrayOutputStream sample = new ByteArrayOutputStream(escaped.length + 2);
        sample.write((escaped.length >>> 8) & 0xFF);
        sample.write(escaped.length & 0xFF);
        sample.write(escaped, 0, escaped.length);
        return sample.toByteArray();
    }

    private static long totalMs(List<SubtitleCue> cues) {
        long end = 0;
        for (SubtitleCue cue : cues) {
            end = Math.max(end, cue.endMs);
        }
        return end;
    }

    // ------------------------------------------------------------ track box

    private static byte[] buildTrak(long trackId, long movieDuration, Timing timing,
                                       long dataOffset) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        u32(p, 0);                    // creation
        u32(p, 0);                    // modification
        u32(p, trackId);
        u32(p, 0);                    // reserved
        u32(p, movieDuration);
        zeros(p, 8);                  // reserved
        u16(p, 0);                    // layer
        u16(p, 0);                    // alternate group
        u16(p, 0);                    // volume: subtitles are silent
        u16(p, 0);                    // reserved
        unityMatrix(p);
        u32(p, 0);                    // width
        u32(p, 0);                    // height
        byte[] tkhd = fullBox("tkhd", 0, 0x000007, p.toByteArray());

        ByteArrayOutputStream m = new ByteArrayOutputStream();
        u32(m, 0);
        u32(m, 0);
        u32(m, TIMESCALE);
        u32(m, timing.totalDuration);
        u16(m, 0x55C4);               // language "und"
        u16(m, 0);                    // pre-defined
        byte[] mdhd = fullBox("mdhd", 0, 0, m.toByteArray());

        ByteArrayOutputStream h = new ByteArrayOutputStream();
        u32(h, 0);                    // pre-defined
        h.write(new byte[]{'t', 'e', 'x', 't'}, 0, 4);
        zeros(h, 12);                 // reserved
        byte[] handlerName = "CineWave subtitles".getBytes(StandardCharsets.ISO_8859_1);
        h.write(handlerName, 0, handlerName.length);
        h.write(0);
        byte[] hdlr = fullBox("hdlr", 0, 0, h.toByteArray());

        byte[] url = fullBox("url ", 0, 1, new byte[0]);
        ByteArrayOutputStream dr = new ByteArrayOutputStream();
        u32(dr, 1);
        dr.write(url, 0, url.length);
        byte[] dinf = box("dinf", fullBox("dref", 0, 0, dr.toByteArray()));

        byte[] minf = box("minf", concat(
                fullBox("nmhd", 0, 0, new byte[0]),
                dinf,
                box("stbl", concat(
                        fullBox("stsd", 0, 0, concat(u32b(1), tx3gSampleEntry())),
                        fullBox("stts", 0, 0, sttsBody(timing)),
                        fullBox("stsc", 0, 0, concat(u32b(1), u32b(1),
                                u32b(timing.sizes.size()), u32b(1))),
                        fullBox("stsz", 0, 0, stszBody(timing)),
                        chunkOffsetBox(timing, dataOffset)))));

        return box("trak", concat(tkhd, box("mdia", concat(mdhd, hdlr, minf))));
    }

    private static byte[] tx3gSampleEntry() {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        zeros(p, 6);                  // reserved
        u16(p, 1);                    // data reference index
        u32(p, 0x00000001);           // display flags: UTF-8 text
        u8(p, 1);                     // horizontal justification: centre
        u8(p, -1);                    // vertical justification: bottom
        u8(p, 0);                     // background colour
        u16(p, 0);                    // box record
        u16(p, 0);                    // style record
        u8(p, 0);                     // style record size
        u8(p, 1);                     // font record count
        u16(p, 1);                    // font id
        u8(p, FONT.length);
        p.write(FONT, 0, FONT.length);

        ByteArrayOutputStream f = new ByteArrayOutputStream();
        u32(f, 1);                    // entry count
        u16(f, 1);                    // font id
        u16(f, FONT.length);
        f.write(FONT, 0, FONT.length);
        byte[] ftab = box("ftab", f.toByteArray());
        p.write(ftab, 0, ftab.length);

        return box("tx3g", p.toByteArray());
    }

    private static byte[] sttsBody(Timing timing) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        u32(p, timing.stts.size());
        for (long[] entry : timing.stts) {
            u32(p, entry[0]);
            u32(p, entry[1]);
        }
        return p.toByteArray();
    }

    private static byte[] stszBody(Timing timing) {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        u32(p, 0);                    // sample size 0: sizes follow individually
        u32(p, timing.sizes.size());
        for (Integer size : timing.sizes) u32(p, size);
        return p.toByteArray();
    }

    private static byte[] chunkOffsetBox(Timing timing, long dataOffset) {
        if (dataOffset <= 0xFFFFFFFFL) {
            return fullBox("stco", 0, 0, concat(u32b(1), u32b(dataOffset)));
        }
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        u32(p, 1);
        for (int i = 0; i < 8; i++) u8(p, (int) ((dataOffset >>> (8 * (7 - i))) & 0xFF));
        return fullBox("co64", 0, 0, p.toByteArray());
    }

    // --------------------------------------------------------------- moov

    /** Keeps every original box, refreshes the index header and adds the new track. */
    private static byte[] rebuildMoov(byte[] oldMoov, byte[] subTrak, long newNextTrackId)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int offset = new MemBox(oldMoov, 0).header;
        int end = oldMoov.length;

        while (offset < end) {
            MemBox child = new MemBox(oldMoov, offset);
            byte[] raw = Arrays.copyOfRange(oldMoov, offset, offset + child.size);
            byte[] bytes = "mvhd".equals(child.type)
                    ? patchNextTrackId(raw, newNextTrackId) : raw;
            body.write(bytes, 0, bytes.length);
            offset += child.size;
        }

        body.write(subTrak, 0, subTrak.length);
        return box("moov", body.toByteArray());
    }

    private static byte[] patchNextTrackId(byte[] mvhd, long value) {
        byte[] copy = mvhd.clone();
        if (copy.length < 108) return copy;
        int version = copy[8] & 0xFF;
        int at = version == 1 ? 116 : 104;
        if (at + 4 > copy.length) return copy;
        copy[at] = (byte) ((value >>> 24) & 0xFF);
        copy[at + 1] = (byte) ((value >>> 16) & 0xFF);
        copy[at + 2] = (byte) ((value >>> 8) & 0xFF);
        copy[at + 3] = (byte) (value & 0xFF);
        return copy;
    }

    private static long nextTrackId(byte[] moov) throws IOException {
        return readMvhdField(moov, true);
    }

    private static long movieTimescale(byte[] moov) throws IOException {
        return readMvhdField(moov, false);
    }

    private static long readMvhdField(byte[] moov, boolean wantNextId) throws IOException {
        int offset = new MemBox(moov, 0).header;
        int end = moov.length;
        while (offset < end) {
            MemBox child = new MemBox(moov, offset);
            if ("mvhd".equals(child.type)) {
                byte[] b = Arrays.copyOfRange(moov, offset, offset + child.size);
                if (b.length < 24) return wantNextId ? 1 : 0;
                int version = b[8] & 0xFF;
                if (wantNextId) {
                    int at = version == 1 ? 116 : 104;
                    return at + 4 <= b.length ? be32(b, at) : 1;
                }
                int at = version == 1 ? 28 : 20;
                return at + 4 <= b.length ? be32(b, at) : 0;
            }
            offset += child.size;
        }
        return wantNextId ? 1 : 0;
    }

    // ------------------------------------------------------------ file i/o

    private static void copy(RandomAccessFile in, RandomAccessFile out, long length)
            throws IOException {
        byte[] buf = new byte[1 << 16];
        in.seek(0);
        long left = length;
        while (left > 0) {
            int n = (int) Math.min(buf.length, left);
            in.readFully(buf, 0, n);
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static byte[] readRange(RandomAccessFile f, long pos, int len) throws IOException {
        byte[] data = new byte[len];
        f.seek(pos);
        f.readFully(data);
        return data;
    }

    /** An open-ended box carries a size of zero; write the real length in place. */
    private static void closeOpenSize(RandomAccessFile f, Box b) throws IOException {
        long real = b.start + b.size;
        if (real - b.start - 8 > 0xFFFFFFFFL) {
            throw new IOException("the video is too large to merge");
        }
        f.seek(b.start);
        writeU32(f, b.size);
        b.header = 8;
    }

    private static List<Box> scanTop(RandomAccessFile f, long length) throws IOException {
        List<Box> boxes = new ArrayList<>();
        long pos = 0;
        while (pos + 8 <= length) {
            f.seek(pos);
            long size = readU32(f);
            byte[] typeBytes = new byte[4];
            f.readFully(typeBytes);
            String type = new String(typeBytes, StandardCharsets.ISO_8859_1);

            long header = 8;
            boolean open = false;
            if (size == 1) {
                if (pos + 16 > length) throw new IOException("truncated box header");
                size = 0;
                for (int i = 0; i < 8; i++) {
                    int b = f.read();
                    if (b < 0) throw new IOException("truncated box header");
                    size = (size << 8) | b;
                }
                header = 16;
            } else if (size == 0) {
                size = length - pos;
                open = true;
            }

            if (size < header || pos + size > length) {
                throw new IOException("malformed " + type + " box");
            }
            boxes.add(new Box(pos, size, header, type, open));
            pos += size;
        }
        if (pos != length) throw new IOException("unexpected data at the end of the video");
        return boxes;
    }

    private static void closeQuietly(RandomAccessFile f) {
        if (f == null) return;
        try {
            f.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    // -------------------------------------------------------------- helpers

    private static final class Box {
        final long start;
        final long size;
        final String type;
        long header;
        final boolean openEnded;

        Box(long start, long size, long header, String type, boolean openEnded) {
            this.start = start;
            this.size = size;
            this.header = header;
            this.type = type;
            this.openEnded = openEnded;
        }
    }

    /** A box located inside an in-memory byte array. */
    private static final class MemBox {
        final String type;
        final int size;
        final int header;

        MemBox(byte[] data, int at) throws IOException {
            if (at < 0 || at + 8 > data.length) throw new IOException("truncated box in the index");
            long value = be32(data, at);
            int head = 8;
            if (value == 1) {
                head = 16;
                value = 0;
                for (int i = 0; i < 8; i++) {
                    if (at + 8 + i >= data.length) throw new IOException("truncated box in the index");
                    value = (value << 8) | (data[at + 8 + i] & 0xFFL);
                }
            } else if (value == 0) {
                value = data.length - at;
            }
            if (value < head || at + value > data.length) {
                throw new IOException("malformed box in the index");
            }
            byte[] t = new byte[4];
            System.arraycopy(data, at + 4, t, 0, 4);
            this.type = new String(t, StandardCharsets.ISO_8859_1);
            this.size = (int) value;
            this.header = head;
        }
    }

    private static void u8(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void u32(ByteArrayOutputStream o, long v) {
        o.write((int) ((v >>> 24) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) (v & 0xFF));
    }

    private static void zeros(ByteArrayOutputStream o, int n) {
        for (int i = 0; i < n; i++) o.write(0);
    }

    private static void unityMatrix(ByteArrayOutputStream o) {
        u32(o, 0x00010000);
        u32(o, 0);
        u32(o, 0);
        u32(o, 0);
        u32(o, 0x00010000);
        u32(o, 0);
        u32(o, 0);
        u32(o, 0);
        u32(o, 0x40000000);
    }

    private static byte[] u32b(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(4);
        u32(o, v);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] part : parts) o.write(part, 0, part.length);
        return o.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(payload.length + 8);
        u32(o, payload.length + 8L);
        o.write(type.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        o.write(payload, 0, payload.length);
        return o.toByteArray();
    }

    private static byte[] fullBox(String type, int version, int flags, byte[] payload) {
        ByteArrayOutputStream p = new ByteArrayOutputStream(payload.length + 4);
        p.write(version & 0xFF);
        p.write((flags >>> 16) & 0xFF);
        p.write((flags >>> 8) & 0xFF);
        p.write(flags & 0xFF);
        p.write(payload, 0, payload.length);
        return box(type, p.toByteArray());
    }

    private static long be32(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24)
                | ((long) (data[at + 1] & 0xFF) << 16)
                | ((long) (data[at + 2] & 0xFF) << 8)
                | (data[at + 3] & 0xFF);
    }

    private static long readU32(RandomAccessFile f) throws IOException {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int b = f.read();
            if (b < 0) throw new IOException("unexpected end of file");
            v = (v << 8) | b;
        }
        return v;
    }

    private static void writeU32(RandomAccessFile f, long v) throws IOException {
        f.write(new byte[]{
                (byte) ((v >>> 24) & 0xFF), (byte) ((v >>> 16) & 0xFF),
                (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)});
    }
}