package purist.testutil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes small synthetic SACD ISO images for tests, laid out from the spec's "SACD ISO layout" subsection (verified
 * on a real disc): master TOC in sector 510, master text in 511, a stereo area TOC at 544 with {@code SACDTRL1},
 * {@code SACDTRL2}, {@code SACD_IGL} and {@code SACDTTxt} sub-sectors (text entries start two sectors into the
 * {@code SACDTTxt} sector, as on the real disc), and the plain-DSD audio from sector 624.
 *
 * <p>The audio is a lead-in (default 150 frames, so track 1 starts at time code 150 as on a real disc) followed by
 * the tracks, one frame after the other; frame {@code f} is {@link #frame(int, int)}. A frame is {@code 4704 *
 * channels} bytes and is cut into 2016-byte audio packets, one per sector, beside a supplementary packet, so a
 * frame spans five sectors (2016 x 4 + 1344) and frame starts fall in the middle of sectors, as on a real disc.
 */
public final class SacdImageBuilder {

    public static final int SECTOR = 2048;
    public static final int FRAME_BYTES_PER_CHANNEL = 4704;
    public static final int MASTER_TOC = 510;
    public static final int MASTER_TEXT = 511;
    public static final int AREA_TOC = 544;
    public static final int AREA_SECTORS = 8;
    public static final int AREA_START = 624;
    /** Audio bytes per sector. */
    public static final int CHUNK = 2016;

    private record TrackSpec(String title, String performer, int frames, Map<Integer, String> extra) {}

    private String albumTitle = "Test Album";
    private String albumArtist = "Test Artist";
    private int year = 2014;
    private int month = 6;
    private int day = 10;
    private int sectorSize = SECTOR;
    private int fsCode = 4;
    private int frameFormat = 2;
    private int channels = 2;
    private boolean stereoArea = true;
    private boolean audio = true;
    private int leadInFrames = 150;
    private final List<TrackSpec> tracks = new ArrayList<>();

    public SacdImageBuilder albumTitle(String t) {
        albumTitle = t;
        return this;
    }

    public SacdImageBuilder albumArtist(String a) {
        albumArtist = a;
        return this;
    }

    /** {@code year == 0} leaves the date out. */
    public SacdImageBuilder date(int y, int m, int d) {
        year = y;
        month = m;
        day = d;
        return this;
    }

    /** 2064 writes raw 2064-byte sectors (12-byte header, 2048 data, 4 trailer bytes). */
    public SacdImageBuilder sectorSize(int size) {
        sectorSize = size;
        return this;
    }

    public SacdImageBuilder fsCode(int code) {
        fsCode = code;
        return this;
    }

    /** The area's frame format; 0 marks it DST (the audio sectors stay plain DSD). */
    public SacdImageBuilder frameFormat(int ff) {
        frameFormat = ff;
        return this;
    }

    public SacdImageBuilder channels(int c) {
        channels = c;
        return this;
    }

    /** No stereo area: the master TOC points at none. */
    public SacdImageBuilder noStereoArea() {
        stereoArea = false;
        return this;
    }

    /** Leaves the audio sectors out: the image ends after the area TOC (for tests that only read the TOC). */
    public SacdImageBuilder noAudio() {
        audio = false;
        return this;
    }

    public SacdImageBuilder leadInFrames(int frames) {
        leadInFrames = frames;
        return this;
    }

    /** Adds a track of {@code frames} frames; a null title and performer give it no text entry. */
    public SacdImageBuilder track(String title, String performer, int frames) {
        tracks.add(new TrackSpec(title, performer, frames, new LinkedHashMap<>()));
        return this;
    }

    /** Adds a text item of the given type (3 songwriter ... 6 message) to the last track. */
    public SacdImageBuilder textItem(int type, String text) {
        tracks.get(tracks.size() - 1).extra().put(type, text);
        return this;
    }

    // ---- layout, for the assertions of tests ------------------------------------------------------------------

    public int trackCount() {
        return tracks.size();
    }

    /** The absolute frame (time code) of the first frame of track {@code i} (0-based). */
    public int startFrame(int i) {
        int f = leadInFrames;
        for (int k = 0; k < i; k++) {
            f += tracks.get(k).frames();
        }
        return f;
    }

    public int lengthFrames(int i) {
        return tracks.get(i).frames();
    }

    private long frameBytes() {
        return (long) FRAME_BYTES_PER_CHANNEL * channels;
    }

    /** The sector holding the first byte of track {@code i}. */
    public long startLsn(int i) {
        return AREA_START + startFrame(i) * frameBytes() / CHUNK;
    }

    /** The sectors from {@link #startLsn} to the one holding the last byte of the track, inclusive. */
    public long lengthSectors(int i) {
        long end = (startFrame(i) + (long) lengthFrames(i)) * frameBytes();
        long lastSector = AREA_START + (end + CHUNK - 1) / CHUNK - 1;
        return lastSector - startLsn(i) + 1;
    }

    public int totalFrames() {
        return startFrame(tracks.size());
    }

    /** The last sector of the track area (inclusive). */
    public long areaEnd() {
        long bytes = totalFrames() * frameBytes();
        return AREA_START + (bytes + CHUNK - 1) / CHUNK - 1;
    }

    /** Where sector {@code lsn} starts in the built image. */
    public long offsetOf(long lsn) {
        return lsn * sectorSize + (sectorSize == 2064 ? 12 : 0);
    }

    /** The audio of track {@code i} exactly as a decoder must give it back. */
    public byte[] trackAudio(int i) {
        byte[] out = new byte[(int) (lengthFrames(i) * frameBytes())];
        for (int f = 0; f < lengthFrames(i); f++) {
            System.arraycopy(frame(startFrame(i) + f, channels), 0, out, (int) (f * frameBytes()), (int) frameBytes());
        }
        return out;
    }

    /** The content of absolute frame {@code f}: deterministic, and different for every frame. */
    public static byte[] frame(int f, int channels) {
        byte[] b = new byte[FRAME_BYTES_PER_CHANNEL * channels];
        new Random(f * 7919L + 13).nextBytes(b);
        return b;
    }

    // ---- building ---------------------------------------------------------------------------------------------

    public Path write(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, build());
        return p;
    }

    public byte[] build() {
        long lastSector = stereoArea && audio ? areaEnd() : AREA_TOC + AREA_SECTORS - 1;
        byte[] image = new byte[(int) ((lastSector + 1) * sectorSize)];
        if (sectorSize == 2064) {
            for (long s = 0; s <= lastSector; s++) {
                for (int i = 0; i < 12; i++) {
                    image[(int) (s * sectorSize) + i] = (byte) 0xEE; // raw-sector header, to be skipped
                }
            }
        }
        ByteBuffer master = sector();
        master.put(0, "SACDMTOC".getBytes(StandardCharsets.US_ASCII));
        master.putShort(8, (short) 0x0114);
        master.putInt(0x40, stereoArea ? AREA_TOC : 0);
        master.putInt(0x48, 0);
        if (year != 0) {
            master.putShort(0x78, (short) year);
            master.put(0x7A, (byte) month);
            master.put(0x7B, (byte) day);
        }
        store(image, MASTER_TOC, master);

        ByteBuffer text = sector();
        text.put(0, "SACDText".getBytes(StandardCharsets.US_ASCII));
        int at = 0x40;
        if (albumTitle != null) {
            text.putShort(0x10, (short) at);
            at = putString(text, at, albumTitle);
        }
        if (albumArtist != null) {
            text.putShort(0x12, (short) at);
            at = putString(text, at, albumArtist);
        }
        store(image, MASTER_TEXT, text);

        if (stereoArea) {
            buildArea(image);
        }
        return image;
    }

    private void buildArea(byte[] image) {
        int n = tracks.size();
        ByteBuffer toc = sector();
        toc.put(0, "TWOCHTOC".getBytes(StandardCharsets.US_ASCII));
        toc.putShort(8, (short) 0x0114);
        toc.putShort(0x0A, (short) AREA_SECTORS);
        toc.put(0x14, (byte) fsCode);
        toc.put(0x15, (byte) frameFormat);
        toc.put(0x20, (byte) channels);
        int total = 0;
        for (TrackSpec t : tracks) {
            total += t.frames();
        }
        putTime(toc, 0x40, total);
        toc.put(0x44, (byte) 0);
        toc.put(0x45, (byte) n);
        toc.putInt(0x48, AREA_START);
        toc.putInt(0x4C, (int) areaEnd());
        store(image, AREA_TOC, toc);

        ByteBuffer trl1 = sector();
        trl1.put(0, "SACDTRL1".getBytes(StandardCharsets.US_ASCII));
        ByteBuffer trl2 = sector();
        trl2.put(0, "SACDTRL2".getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < n; i++) {
            trl1.putInt(8 + 4 * i, (int) startLsn(i));
            trl1.putInt(8 + 4 * 255 + 4 * i, (int) lengthSectors(i));
            putTime(trl2, 8 + 4 * i, startFrame(i));
            putTime(trl2, 8 + 4 * 255 + 4 * i, lengthFrames(i));
        }
        store(image, AREA_TOC + 1, trl1);
        store(image, AREA_TOC + 2, trl2);

        ByteBuffer igl = sector();
        igl.put(0, "SACD_IGL".getBytes(StandardCharsets.US_ASCII));
        store(image, AREA_TOC + 3, igl);

        // SACDTTxt at AREA_TOC + 5; entries start 4096 bytes in, so they live in the sector two after it
        ByteBuffer ttxt = ByteBuffer.allocate(3 * SECTOR);
        ttxt.put(0, "SACDTTxt".getBytes(StandardCharsets.US_ASCII));
        int off = 4096;
        for (int i = 0; i < n; i++) {
            TrackSpec t = tracks.get(i);
            Map<Integer, String> items = new LinkedHashMap<>();
            if (t.title() != null) {
                items.put(1, t.title());
            }
            if (t.performer() != null) {
                items.put(2, t.performer());
            }
            items.putAll(t.extra());
            if (items.isEmpty()) {
                continue;
            }
            ttxt.putShort(8 + 2 * i, (short) off);
            ttxt.put(off, (byte) items.size());
            int p = off + 4;
            for (Map.Entry<Integer, String> e : items.entrySet()) {
                ttxt.put(p, e.getKey().byteValue());
                ttxt.put(p + 1, (byte) 0x20);
                byte[] s = e.getValue().getBytes(StandardCharsets.ISO_8859_1);
                for (int k = 0; k < s.length; k++) {
                    ttxt.put(p + 2 + k, s[k]);
                }
                p += (2 + s.length + 1 + 3) & ~3; // string + NUL, padded to a 4-byte boundary
            }
            off = p;
        }
        if (off > 3 * SECTOR) {
            throw new IllegalStateException("track text does not fit in the area's text sectors");
        }
        for (int s = 0; s < 3; s++) {
            ByteBuffer one = ByteBuffer.wrap(ttxt.array(), s * SECTOR, SECTOR);
            store(image, AREA_TOC + 5 + s, one);
        }

        if (audio) {
            writeAudio(image);
        }
    }

    private void writeAudio(byte[] image) {
        long fb = frameBytes();
        long totalBytes = totalFrames() * fb;
        byte[] stream = new byte[(int) totalBytes];
        for (int f = 0; f < totalFrames(); f++) {
            System.arraycopy(frame(f, channels), 0, stream, (int) (f * fb), (int) fb);
        }
        long sectors = (totalBytes + CHUNK - 1) / CHUNK;
        for (long i = 0; i < sectors; i++) {
            long from = i * CHUNK;
            int len = (int) Math.min(CHUNK, totalBytes - from);
            int boundary = -1;
            for (int b = 0; b < len; b++) {
                if ((from + b) % fb == 0) {
                    boundary = b;
                    break;
                }
            }
            List<int[]> packets = new ArrayList<>(); // {start, type, length}
            packets.add(new int[] {0, 3, 0}); // supplementary, length filled in below
            if (boundary == 0) {
                packets.add(new int[] {1, 2, len});
            } else if (boundary > 0) {
                packets.add(new int[] {0, 2, boundary});
                packets.add(new int[] {1, 2, len - boundary});
            } else {
                packets.add(new int[] {0, 2, len});
            }
            if (len < CHUNK) {
                packets.add(new int[] {0, 7, CHUNK - len}); // padding after the last audio
            }
            int frameInfos = boundary >= 0 ? 1 : 0;
            int header = 1 + 2 * packets.size() + 3 * frameInfos;
            packets.get(0)[2] = SECTOR - header - CHUNK;

            byte[] data = new byte[SECTOR];
            data[0] = (byte) (packets.size() << 5 | frameInfos << 2);
            int p = 1;
            for (int[] pk : packets) {
                int v = pk[0] << 15 | pk[1] << 11 | pk[2];
                data[p++] = (byte) (v >>> 8);
                data[p++] = (byte) v;
            }
            if (frameInfos == 1) {
                int frame = (int) ((from + boundary) / fb);
                int sec = frame / 75;
                data[p++] = (byte) (sec / 60);
                data[p++] = (byte) (sec % 60);
                data[p++] = (byte) (frame % 75);
            }
            int audioAt = (int) from;
            for (int[] pk : packets) {
                for (int k = 0; k < pk[2]; k++) {
                    data[p++] = pk[1] == 2 ? stream[audioAt++] : (byte) (pk[1] == 3 ? 0xA5 : 0);
                }
            }
            if (p != SECTOR) {
                throw new IllegalStateException("sector " + i + " is " + p + " bytes, not " + SECTOR);
            }
            store(image, AREA_START + i, ByteBuffer.wrap(data));
        }
    }

    private static ByteBuffer sector() {
        return ByteBuffer.allocate(SECTOR);
    }

    private void store(byte[] image, long lsn, ByteBuffer sector) {
        System.arraycopy(sector.array(), sector.position(), image, (int) offsetOf(lsn), SECTOR);
    }

    private static void putTime(ByteBuffer b, int at, int frames) {
        int sec = frames / 75;
        b.put(at, (byte) (sec / 60));
        b.put(at + 1, (byte) (sec % 60));
        b.put(at + 2, (byte) (frames % 75));
    }

    /** NUL-terminated ISO-8859-1 string at {@code at}; returns the offset after it. */
    private static int putString(ByteBuffer b, int at, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < bytes.length; i++) {
            b.put(at + i, bytes[i]);
        }
        return at + bytes.length + 1;
    }
}
