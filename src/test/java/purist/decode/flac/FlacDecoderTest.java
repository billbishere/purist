package purist.decode.flac;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static purist.testutil.FlacWriter.ChannelMode.INDEPENDENT;
import static purist.testutil.FlacWriter.ChannelMode.LEFT_SIDE;
import static purist.testutil.FlacWriter.ChannelMode.MID_SIDE;
import static purist.testutil.FlacWriter.ChannelMode.SIDE_RIGHT;
import static purist.testutil.FlacWriter.SubframeKind.CONSTANT;
import static purist.testutil.FlacWriter.SubframeKind.FIXED;
import static purist.testutil.FlacWriter.SubframeKind.LPC;
import static purist.testutil.FlacWriter.SubframeKind.VERBATIM;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.decode.flac.FlacDamageException.Kind;
import purist.decode.io.FileSource;
import purist.testutil.BitWriter;
import purist.testutil.FlacWriter;
import purist.testutil.FlacWriter.ChannelMode;
import purist.testutil.FlacWriter.FrameSpec;
import purist.testutil.FlacWriter.SubframeKind;
import purist.testutil.Id3Writer;

/**
 * Whole-file behaviour of the FLAC reader: metadata, decoding, seeking, damage, the handler and the MD5 verifier.
 * Files come from the independent {@link FlacWriter}, or are assembled by hand here when a test needs a frame
 * or a block that no real encoder would write.
 */
class FlacDecoderTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 9, 8, 7, 6};

    @TempDir
    Path dir;

    private final FlacHandler handler = new FlacHandler();

    // ---- helpers: samples and files ----------------------------------------------------------------

    /** {@code n} random signed samples of {@code bits} bits, full scale. */
    private static int[] noise(int n, int bits, long seed) {
        Random r = new Random(seed);
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            s[i] = (int) (r.nextLong() >> (64 - bits));
        }
        return s;
    }

    /** A random walk, which the predictors can follow: small steps, clamped to the bit depth. */
    private static int[] walk(int n, int bits, long seed) {
        Random r = new Random(seed);
        long max = (1L << (bits - 1)) - 1;
        long min = -(1L << (bits - 1));
        double scale = Math.max(2, Math.pow(2, bits - 8));
        long v = 0;
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            v = Math.max(min, Math.min(max, v + Math.round(r.nextGaussian() * scale)));
            s[i] = (int) v;
        }
        return s;
    }

    private Path flacFile(String name, int rate, int bps, int channels, int[] samples, int block, SubframeKind kind,
            ChannelMode mode, boolean seekTable, Map<String, String> tags, byte[] picture) throws IOException {
        return FlacWriter.writeFile(dir, name, rate, bps, channels, samples, block, kind, mode, seekTable, tags,
                picture);
    }

    /** A 16-bit 44.1 kHz stereo file of {@code frames} frames, VERBATIM, no seek table. */
    private Path plainFile(String name, int[] samples, int block) throws IOException {
        return flacFile(name, 44100, 16, 2, samples, block, VERBATIM, INDEPENDENT, false, null, null);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static byte[] be(long v, int bytes) {
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (v >>> (8 * (bytes - 1 - i)));
        }
        return out;
    }

    private static byte[] le32(long v) {
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    // ---- helpers: hand-assembled files ---------------------------------------------------------------

    private record Block(int type, byte[] payload) {}

    private static byte[] header(int type, boolean last, int length) {
        return cat(new byte[] {(byte) ((last ? 0x80 : 0) | type)}, be(length, 3));
    }

    /** The marker, STREAMINFO and any further blocks the test wants, then the frames. */
    private static final class FileSpec {
        int minBlock = 4096;
        int maxBlock = 4096;
        int rate = 44100;
        int channels = 2;
        int bps = 16;
        int minFrame = -1; // -1: the smallest of the frames
        int maxFrame = -1; // -1: the largest of the frames
        long total;
        byte[] md5 = new byte[16];
        final List<Block> blocks = new ArrayList<>();
        byte[] prefix = new byte[0];

        FileSpec(int rate, int bps, int channels) {
            this.rate = rate;
            this.bps = bps;
            this.channels = channels;
        }

        FileSpec blocks(int min, int max) {
            minBlock = min;
            maxBlock = max;
            return this;
        }

        FileSpec total(long t) {
            total = t;
            return this;
        }

        FileSpec md5(byte[] m) {
            md5 = m;
            return this;
        }

        FileSpec frameSizes(int min, int max) {
            minFrame = min;
            maxFrame = max;
            return this;
        }

        FileSpec with(int type, byte[] payload) {
            blocks.add(new Block(type, payload));
            return this;
        }

        byte[] streamInfo(byte[]... frames) {
            int lo = Integer.MAX_VALUE;
            int hi = 0;
            for (byte[] f : frames) {
                lo = Math.min(lo, f.length);
                hi = Math.max(hi, f.length);
            }
            if (frames.length == 0) {
                lo = 0;
            }
            return new BitWriter().unsigned(minBlock, 16).unsigned(maxBlock, 16)
                    .unsigned(minFrame >= 0 ? minFrame : lo, 24).unsigned(maxFrame >= 0 ? maxFrame : hi, 24)
                    .unsigned(rate, 20).unsigned(channels - 1, 3).unsigned(bps - 1, 5).unsigned(total, 36)
                    .bytes(md5).toByteArray();
        }

        byte[] build(byte[]... frames) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(prefix);
            o.writeBytes(new byte[] {'f', 'L', 'a', 'C'});
            byte[] si = streamInfo(frames);
            o.writeBytes(header(0, blocks.isEmpty(), si.length));
            o.writeBytes(si);
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                o.writeBytes(header(b.type(), i == blocks.size() - 1, b.payload().length));
                o.writeBytes(b.payload());
            }
            for (byte[] f : frames) {
                o.writeBytes(f);
            }
            return o.toByteArray();
        }
    }

    private static int[][] channelsOf(int[] interleaved, int channels, int from, int n) {
        int[][] ch = new int[channels][n];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < channels; c++) {
                ch[c][i] = interleaved[(from + i) * channels + c];
            }
        }
        return ch;
    }

    private static FrameSpec spec(int rate, int bps, int channels, int[] inter, int from, int n, SubframeKind kind,
            ChannelMode mode) {
        return FrameSpec.of(bps, channelsOf(inter, channels, from, n)).rate(rate).kind(kind).mode(mode);
    }

    /** A fixed-blocksize frame holding samples {@code from} to {@code from + n}, numbered {@code number}. */
    private static byte[] fixedFrame(int rate, int bps, int channels, int[] inter, int from, int n, long number) {
        return FlacWriter.frame(spec(rate, bps, channels, inter, from, n, VERBATIM, INDEPENDENT).number(number));
    }

    /** A variable-blocksize frame whose coded number is its first sample. */
    private static byte[] variableFrame(int rate, int bps, int channels, int[] inter, int from, int n,
            SubframeKind kind) {
        return FlacWriter.frame(spec(rate, bps, channels, inter, from, n, kind, INDEPENDENT).variable(from));
    }

    /** All the frames of a 16-bit stereo 44.1 kHz stream, {@code block} samples each, fixed blocking. */
    private static List<byte[]> fixedFrames(int[] inter, int block) {
        List<byte[]> frames = new ArrayList<>();
        int total = inter.length / 2;
        for (int from = 0, number = 0; from < total; from += block, number++) {
            frames.add(fixedFrame(44100, 16, 2, inter, from, Math.min(block, total - from), number));
        }
        return frames;
    }

    private static byte[][] array(List<byte[]> frames) {
        return frames.toArray(new byte[0][]);
    }

    private static byte[] seekTablePayload(long[]... points) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (long[] p : points) {
            o.writeBytes(be(p[0], 8));
            o.writeBytes(be(p[1], 8));
            o.writeBytes(be(p[2], 2));
        }
        return o.toByteArray();
    }

    private static byte[] vorbisPayload(String... comments) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] vendor = "test".getBytes(StandardCharsets.UTF_8);
        o.writeBytes(le32(vendor.length));
        o.writeBytes(vendor);
        o.writeBytes(le32(comments.length));
        for (String c : comments) {
            byte[] b = c.getBytes(StandardCharsets.UTF_8);
            o.writeBytes(le32(b.length));
            o.writeBytes(b);
        }
        return o.toByteArray();
    }

    private static byte[] picturePayload(int type, String mime, byte[] data) {
        byte[] m = mime.getBytes(StandardCharsets.US_ASCII);
        return cat(be(type, 4), be(m.length, 4), m, be(0, 4), be(1, 4), be(1, 4), be(24, 4), be(0, 4),
                be(data.length, 4), data);
    }

    private Path put(String name, byte[] bytes) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    private static FlacMetadata metadata(Path p) throws IOException {
        try (FileSource src = FileSource.open(p)) {
            return FlacMetadata.read(src);
        }
    }

    /** The offset of every frame of the file, then its end, found by decoding. */
    private static int[] frameOffsets(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        FlacMetadata m = metadata(p);
        FlacFrameDecoder fd = new FlacFrameDecoder(m.info());
        int[] out = new int[65535 * m.info().channels()];
        List<Integer> offsets = new ArrayList<>();
        int pos = (int) m.firstFrameOffset();
        while (pos < bytes.length) {
            offsets.add(pos);
            pos += fd.decode(bytes, pos, bytes.length - pos, out);
        }
        offsets.add(pos);
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---- helpers: reading ----------------------------------------------------------------------------

    /** Reads once, asking for {@code max} frames, and returns the interleaved samples of the frames returned. */
    private static int[] readFrames(Decoder d, int max) throws IOException {
        int ch = d.format().channels();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate((long) max * ch * 4, 4);
            int n = d.read(dst, max);
            int[] out = new int[n * ch];
            for (int i = 0; i < out.length; i++) {
                out[i] = dst.get(LE_INT, i * 4L);
            }
            return out;
        }
    }

    /**
     * Reads, {@code step} frames at a time, until a read returns fewer frames than asked for, and returns what was
     * read. For a healthy stream that is the end of it. For a damaged one a short read is not the end: it holds the
     * good frames before the damage and the next read throws, so a test of damage reads on until the exception
     * (see {@link #readFrames}) rather than relying on this.
     */
    private static int[] decodeAll(Decoder d, int step) throws IOException {
        int ch = d.format().channels();
        int[] out = new int[1 << 16];
        int n = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate((long) step * ch * 4, 4);
            while (true) {
                int frames = d.read(dst, step);
                for (int i = 0; i < frames * ch; i++) {
                    if (n == out.length) {
                        out = Arrays.copyOf(out, n * 2);
                    }
                    out[n++] = dst.get(LE_INT, i * 4L);
                }
                if (frames < step) {
                    break;
                }
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static int[] slice(int[] src, int channels, long from, long frames) {
        return Arrays.copyOfRange(src, (int) (from * channels), (int) ((from + frames) * channels));
    }

    /** Seeks to each target and checks the position, then the next 3000 frames against the source. */
    private static void assertSeeksExact(Path p, int[] src, int channels, long... targets) throws IOException {
        long total = src.length / channels;
        try (FlacDecoder d = FlacDecoder.open(p)) {
            for (long t : targets) {
                d.seek(t);
                assertEquals(t, d.position(), "position after seek(" + t + ")");
                long want = Math.min(3000, total - t);
                int[] got = readFrames(d, 3000);
                assertArrayEquals(slice(src, channels, t, want), got, "samples after seek(" + t + ")");
                assertEquals(t + want, d.position());
            }
        }
    }

    // =================================================================================================
    // The brief's tests
    // =================================================================================================

    @Test
    void decodeWholeFileMatchesSource() throws IOException {
        int[] src = noise(3 * 96000 * 2, 24, 1);
        Path p = flacFile("whole.flac", 96000, 24, 2, src, 4096, VERBATIM, INDEPENDENT, true, null, null);
        try (FlacDecoder d = FlacDecoder.open(p); Arena arena = Arena.ofConfined()) {
            assertEquals(StreamFormat.pcm(96000, 24, 2, 288000), d.format());
            assertEquals(0, d.position());
            assertArrayEquals(src, decodeAll(d, 1000));
            assertEquals(288000, d.position());
            MemorySegment dst = arena.allocate(64, 4);
            assertEquals(0, d.read(dst, 8));
            assertEquals(0, d.read(dst, 8), "the end stays the end");
        }
        FlacVerify.Result r = FlacVerify.verify(p);
        assertEquals(FlacVerify.Status.OK, r.status(), r.detail());
    }

    @Test
    void seekWithSeekTable() throws IOException {
        int[] src = noise(3 * 96000 * 2, 24, 2);
        Path p = flacFile("table.flac", 96000, 24, 2, src, 4096, VERBATIM, INDEPENDENT, true, null, null);
        assertFalse(metadata(p).seekPoints().isEmpty(), "the fixture must carry a seek table");
        try (FlacDecoder d = FlacDecoder.open(p)) {
            d.seek(100000);
            assertEquals(100000, d.position());
            int[] first = readFrames(d, 1);
            assertEquals(src[200000], first[0]);
            assertEquals(src[200001], first[1]);
        }
        assertSeeksExact(p, src, 2, 100000, 0, 96000, 98304, 98303, 192511, 287999, 5, 250000);
    }

    @Test
    void seekWithoutSeekTable() throws IOException {
        int[] src = noise(3 * 96000 * 2, 24, 3);
        Path p = flacFile("notable.flac", 96000, 24, 2, src, 4096, VERBATIM, INDEPENDENT, false, null, null);
        assertTrue(metadata(p).seekPoints().isEmpty());
        try (FlacDecoder d = FlacDecoder.open(p)) {
            d.seek(100000);
            assertEquals(100000, d.position());
            int[] first = readFrames(d, 1);
            assertEquals(src[200000], first[0]);
            assertEquals(src[200001], first[1]);
        }
        assertSeeksExact(p, src, 2, 100000, 0, 96000, 98304, 98303, 192511, 287999, 5, 250000);
    }

    @Test
    void vorbisTagsRead() throws IOException {
        int[] src = walk(8000, 16, 4);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("TITLE", "Echoes");
        tags.put("ARTIST", "Pink Floyd\0Roger Waters"); // two ARTIST comments
        tags.put("Album Artist", "Pink Floyd");
        tags.put("TRACKNUMBER", "1/6");
        tags.put("DATE", "1971");
        tags.put("COMMENT", "日本語 é");
        Path p = flacFile("tags.flac", 44100, 16, 2, src, 1000, FIXED(1), INDEPENDENT, false, tags, null);

        List<TrackInfo> tracks = handler.probe(p);
        assertEquals(1, tracks.size());
        TrackInfo t = tracks.get(0);
        assertEquals("FLAC", t.codec());
        assertEquals(0, t.subsong());
        assertTrue(t.supported());
        assertEquals(List.of("Pink Floyd", "Roger Waters"), t.tags().all("ARTIST"));
        assertEquals(Optional.of("Echoes"), t.tags().first("TITLE"));
        assertEquals(Optional.of("Pink Floyd"), t.tags().first("ALBUMARTIST"));
        assertEquals(Optional.of("1/6"), t.tags().first("TRACKNUMBER"));
        assertEquals(Optional.of("1971"), t.tags().first("DATE"));
        assertEquals(Optional.of("日本語 é"), t.tags().first("COMMENT"));
        assertEquals("Echoes", t.displayTitle());
        assertEquals(StreamFormat.pcm(44100, 16, 2, 4000), t.format());
        assertEquals("FLAC 16/44.1", t.badge());
        assertEquals(t.tags(), metadata(p).tags());
    }

    @Test
    void pictureBlockFrontCover() throws IOException {
        int[] src = walk(4000, 16, 5);
        Path png = flacFile("png.flac", 44100, 16, 2, src, 1000, VERBATIM, INDEPENDENT, false, null, PNG);
        assertArrayEquals(PNG, handler.embeddedArt(png, 0).orElseThrow());
        assertArrayEquals(PNG, metadata(png).frontCover().orElseThrow());

        Path jpg = flacFile("jpg.flac", 44100, 16, 2, src, 1000, VERBATIM, INDEPENDENT, false, null, JPEG);
        assertArrayEquals(JPEG, handler.embeddedArt(jpg, 0).orElseThrow());

        Path none = flacFile("none.flac", 44100, 16, 2, src, 1000, VERBATIM, INDEPENDENT, false, null, null);
        assertEquals(Optional.empty(), handler.embeddedArt(none, 0));
        assertEquals(Optional.empty(), metadata(none).frontCover());
        assertEquals(Optional.empty(), handler.embeddedArt(png, 1), "there is only track 0");
    }

    @Test
    void truncatedFileThrowsAfterGoodFrames() throws IOException {
        int[] src = walk(20 * 1024 * 2, 16, 6);
        Path p = flacFile("whole.flac", 44100, 16, 2, src, 1024, FIXED(1), INDEPENDENT, false, null, null);
        byte[] bytes = Files.readAllBytes(p);
        int[] offs = frameOffsets(p);
        assertEquals(21, offs.length);

        // cut at several points inside the sixth frame (index 5): every byte count past the frame start
        for (int cut : new int[] {offs[5] + 1, offs[5] + 3, offs[5] + 6, (offs[5] + offs[6]) / 2, offs[6] - 1}) {
            Path q = put("cut" + cut + ".flac", Arrays.copyOf(bytes, cut));
            try (FlacDecoder d = FlacDecoder.open(q)) {
                int[] got = readFrames(d, 100000);
                assertArrayEquals(slice(src, 2, 0, 5 * 1024), got, "the five good frames, cut at " + cut);
                assertEquals(5 * 1024, d.position());
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment dst = arena.allocate(8 * 1024 * 4, 4);
                    DecodeException e = assertThrows(DecodeException.class, () -> d.read(dst, 1024));
                    assertEquals("truncated frame at byte " + offs[5], e.getMessage());
                    DecodeException again = assertThrows(DecodeException.class, () -> d.read(dst, 1024));
                    assertEquals(e.getMessage(), again.getMessage(), "the error is sticky");
                }
                assertEquals(5 * 1024, d.position());

                d.seek(1024); // seeking away from the damage clears it
                assertArrayEquals(slice(src, 2, 1024, 1024), readFrames(d, 1024));
            }
            FlacVerify.Result r = FlacVerify.verify(q);
            assertEquals(FlacVerify.Status.ERROR, r.status());
            assertTrue(r.detail().contains("truncated frame at byte " + offs[5]), r.detail());
        }
    }

    @Test
    void aFileCutBetweenFramesIsAlsoTruncated() throws IOException {
        int[] src = walk(10 * 1000 * 2, 16, 7);
        Path p = plainFile("whole.flac", src, 1000);
        int[] offs = frameOffsets(p);
        Path q = put("cut.flac", Arrays.copyOf(Files.readAllBytes(p), offs[4]));
        try (FlacDecoder d = FlacDecoder.open(q)) {
            assertArrayEquals(slice(src, 2, 0, 4000), readFrames(d, 100000));
            DecodeException e = assertThrows(DecodeException.class, () -> readFrames(d, 10));
            assertTrue(e.getMessage().startsWith("truncated"), e.getMessage());
            assertTrue(e.getMessage().contains("byte " + offs[4]), e.getMessage());
        }
    }

    @Test
    void md5MismatchDetected() throws IOException {
        int[] src = noise(20000 * 2, 16, 8);
        int[] altered = src.clone();
        altered[12345] ^= 1; // one VERBATIM sample, one bit
        byte[] file = new FileSpec(44100, 16, 2).blocks(1000, 1000).total(20000).md5(FlacWriter.md5(src, 16))
                .build(array(fixedFrames(altered, 1000)));
        Path p = put("mismatch.flac", file);

        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(altered, decodeAll(d, 4096), "the frames themselves are fine");
        }
        FlacVerify.Result r = FlacVerify.verify(p);
        assertEquals(FlacVerify.Status.MISMATCH, r.status(), r.detail());
        assertEquals(p, r.path());
        assertTrue(r.detail().contains(HexFormat.of().formatHex(FlacWriter.md5(src, 16))), r.detail());
        assertTrue(r.detail().contains(HexFormat.of().formatHex(FlacWriter.md5(altered, 16))), r.detail());

        // the same file with the right MD5 is OK
        Path good = put("good.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(20000)
                .md5(FlacWriter.md5(altered, 16)).build(array(fixedFrames(altered, 1000))));
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(good).status());
    }

    // =================================================================================================
    // Decoding
    // =================================================================================================

    @Test
    void readSizesDoNotChangeTheSamples() throws IOException {
        int[] src = walk(20000 * 2, 16, 10);
        Path p = flacFile("steps.flac", 44100, 16, 2, src, 1000, FIXED(1), INDEPENDENT, false, null, null);
        try (FlacDecoder d = FlacDecoder.open(p)) {
            for (int step : new int[] {1, 7, 999, 1000, 1001, 4096, 100000}) {
                d.seek(0);
                assertArrayEquals(src, decodeAll(d, step), "step " + step);
            }
        }
    }

    private record Case(int rate, int bps, int channels, SubframeKind kind, ChannelMode mode, boolean noise) {}

    @Test
    void formatsRoundTrip() throws IOException {
        List<Case> cases = List.of(
                new Case(8000, 8, 1, FIXED(1), INDEPENDENT, false),
                new Case(11025, 12, 1, FIXED(3), INDEPENDENT, false),
                new Case(22050, 4, 2, VERBATIM, INDEPENDENT, true),
                new Case(44100, 16, 2, FIXED(2), LEFT_SIDE, false),
                new Case(44100, 16, 2, LPC(2, new int[] {2, -1}, 4, 0), SIDE_RIGHT, false),
                new Case(48000, 20, 2, FIXED(4), MID_SIDE, false),
                new Case(96000, 24, 2, LPC(3, new int[] {3, -3, 1}, 4, 0), MID_SIDE, false),
                new Case(96000, 24, 2, VERBATIM, INDEPENDENT, true),
                new Case(192000, 24, 6, FIXED(1), INDEPENDENT, false),
                new Case(32000, 24, 8, VERBATIM, INDEPENDENT, true),
                new Case(176400, 32, 2, VERBATIM, MID_SIDE, true),
                new Case(88200, 32, 1, FIXED(1), INDEPENDENT, false),
                new Case(352800, 16, 2, FIXED(1), INDEPENDENT, false),
                new Case(1000, 16, 1, FIXED(1), INDEPENDENT, false),
                new Case(44100, 16, 2, CONSTANT, INDEPENDENT, false));
        int index = 0;
        for (Case c : cases) {
            int n = 9000 * c.channels();
            int[] src = c.kind().equals(CONSTANT) ? new int[n] : c.noise() ? noise(n, c.bps(), index)
                    : walk(n, c.bps(), index);
            String label = c + "";
            Path p = flacFile("case" + index++ + ".flac", c.rate(), c.bps(), c.channels(), src, 2500, c.kind(),
                    c.mode(), false, null, null);
            try (FlacDecoder d = FlacDecoder.open(p)) {
                assertEquals(StreamFormat.pcm(c.rate(), c.bps(), c.channels(), 9000), d.format(), label);
                assertArrayEquals(src, decodeAll(d, 3333), label);
            }
            FlacVerify.Result r = FlacVerify.verify(p);
            assertEquals(FlacVerify.Status.OK, r.status(), label + ": " + r.detail());
        }
    }

    @Test
    void aFrameLargerThanStreamInfoSaysIsStillDecoded() throws IOException {
        // STREAMINFO claims 4096-sample blocks; the first frame holds 65535. Nothing about that changes the samples.
        int[] src = noise(70000 * 2, 16, 11);
        byte[] first = variableFrame(44100, 16, 2, src, 0, 65535, VERBATIM);
        byte[] second = variableFrame(44100, 16, 2, src, 65535, 4465, VERBATIM);
        Path p = put("big.flac", new FileSpec(44100, 16, 2).blocks(4096, 4096).total(70000)
                .md5(FlacWriter.md5(src, 16)).build(first, second));
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(src, decodeAll(d, 5000));
        }
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
        assertSeeksExact(p, src, 2, 0, 65534, 65535, 69999);
    }

    @Test
    void aFrameWithAnotherSampleRateIsRefused() throws IOException {
        int[] src = noise(6000 * 2, 16, 12);
        byte[] f0 = fixedFrame(44100, 16, 2, src, 0, 1000, 0);
        byte[] f1 = fixedFrame(44100, 16, 2, src, 1000, 1000, 1);
        byte[] f2 = FlacWriter.frame(spec(48000, 16, 2, src, 2000, 1000, VERBATIM, INDEPENDENT).number(2));
        byte[] f3 = fixedFrame(44100, 16, 2, src, 3000, 1000, 3);
        Path p = put("rate.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(4000)
                .md5(FlacWriter.md5(Arrays.copyOf(src, 8000), 16)).build(f0, f1, f2, f3));
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(slice(src, 2, 0, 2000), readFrames(d, 10000));
            DecodeException e = assertThrows(DecodeException.class, () -> readFrames(d, 10));
            assertTrue(e.getMessage().contains("48000") && e.getMessage().contains("44100"), e.getMessage());
            assertEquals(2000, d.position());
        }
        assertEquals(FlacVerify.Status.ERROR, FlacVerify.verify(p).status());
    }

    @Test
    void aFrameWithRateCodeZeroTakesTheStreamRate() throws IOException {
        int[] src = noise(2000 * 2, 16, 13);
        byte[] f0 = FlacWriter.frame(spec(44100, 16, 2, src, 0, 1000, VERBATIM, INDEPENDENT).number(0).rateCode(0));
        byte[] f1 = FlacWriter.frame(spec(44100, 16, 2, src, 1000, 1000, VERBATIM, INDEPENDENT).number(1)
                .rateCode(0));
        Path p = put("rate0.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(2000)
                .md5(FlacWriter.md5(src, 16)).build(f0, f1));
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
    }

    @Test
    void anOverlongRunThatNeverEndsIsDamageNotTruncation() throws IOException {
        // A frame with a valid header whose Rice data is endless zeros: the bit reader runs out of bytes, which the
        // frame decoder reports as "incomplete". With plenty of file left that is damage, and it must be decided
        // after reading a bounded amount, not by growing a buffer to the end of the file.
        int[] src = walk(5000 * 2, 16, 14);
        List<byte[]> good = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            good.add(FlacWriter.frame(spec(44100, 16, 2, src, i * 1000, 1000, FIXED(1), INDEPENDENT).number(i)));
        }
        FlacStreamInfo info = new FlacStreamInfo(1000, 1000, 0, 0, 44100, 2, 16, 0, new byte[16]);
        int headerLength = FlacFrameHeader.parse(good.get(0), 0, good.get(0).length, info).headerLength();
        byte[] headerOfNext = FlacWriter.frame(spec(44100, 16, 2, src, 3000, 1000, FIXED(1), INDEPENDENT).number(3));
        byte[] bad = new byte[headerLength + 2_000_000];
        System.arraycopy(headerOfNext, 0, bad, 0, headerLength);
        bad[headerLength] = 0x12; // subframe: padding 0, type FIXED order 1, no wasted bits; the rest is zeros
        int maxGood = good.stream().mapToInt(f -> f.length).max().orElseThrow();
        int minGood = good.stream().mapToInt(f -> f.length).min().orElseThrow();
        byte[] file = new FileSpec(44100, 16, 2).blocks(1000, 1000).total(5000).frameSizes(minGood, maxGood)
                .build(good.get(0), good.get(1), good.get(2), bad);
        Path p = put("endless.flac", file);
        int badOffset = (int) metadata(p).firstFrameOffset() + good.get(0).length + good.get(1).length
                + good.get(2).length;

        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(slice(src, 2, 0, 3000), readFrames(d, 100000));
            DecodeException e = assertThrows(DecodeException.class, () -> readFrames(d, 10));
            assertTrue(e.getMessage().contains("damaged frame at byte " + badOffset), e.getMessage());
            assertFalse(e.getMessage().contains("truncated"), e.getMessage());
        }
        FlacVerify.Result r = FlacVerify.verify(p);
        assertEquals(FlacVerify.Status.ERROR, r.status());
        assertTrue(r.detail().contains("damaged frame at byte " + badOffset), r.detail());
    }

    @Test
    void aBadCrcStopsTheStreamAtThatFrame() throws IOException {
        int[] src = walk(10 * 1000 * 2, 16, 15);
        Path p = flacFile("crc.flac", 44100, 16, 2, src, 1000, FIXED(1), INDEPENDENT, false, null, null);
        int[] offs = frameOffsets(p);
        byte[] bytes = Files.readAllBytes(p);
        bytes[(offs[3] + offs[4]) / 2] ^= 0x40; // inside frame 3
        Path q = put("crc-bad.flac", bytes);
        try (FlacDecoder d = FlacDecoder.open(q)) {
            assertArrayEquals(slice(src, 2, 0, 3000), readFrames(d, 100000));
            DecodeException e = assertThrows(DecodeException.class, () -> readFrames(d, 10));
            assertTrue(e.getMessage().contains("byte " + offs[3]), e.getMessage());
        }
        FlacVerify.Result r = FlacVerify.verify(q);
        assertEquals(FlacVerify.Status.ERROR, r.status());
    }

    @Test
    void aDamagedHeaderStopsTheStreamToo() throws IOException {
        int[] src = walk(10 * 1000 * 2, 16, 16);
        Path p = plainFile("hdr.flac", src, 1000);
        int[] offs = frameOffsets(p);
        byte[] bytes = Files.readAllBytes(p);
        bytes[offs[2] + 5] ^= 0x01; // the CRC-8 of frame 2
        try (FlacDecoder d = FlacDecoder.open(put("hdr-bad.flac", bytes))) {
            assertArrayEquals(slice(src, 2, 0, 2000), readFrames(d, 100000));
            assertThrows(DecodeException.class, () -> readFrames(d, 10));
        }
    }

    @Test
    void bytesAfterTheLastFrameAreIgnored() throws IOException {
        int[] src = noise(6000 * 2, 16, 17);
        byte[] id3v1 = new byte[128];
        id3v1[0] = 'T';
        id3v1[1] = 'A';
        id3v1[2] = 'G';
        byte[] file = cat(new FileSpec(44100, 16, 2).blocks(1000, 1000).total(6000).md5(FlacWriter.md5(src, 16))
                .build(array(fixedFrames(src, 1000))), id3v1);
        Path p = put("trailer.flac", file);
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(src, decodeAll(d, 2048));
        }
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
    }

    /** What some taggers leave behind a metadata block that is marked last: another padding block header and its data. */
    private static byte[] strayPadding() {
        return cat(new byte[] {(byte) 0x81, 0x00, 0x04, 0x00}, new byte[1024]);
    }

    @Test
    void strayBytesBetweenTheMetadataAndTheFirstFrameAreSkipped() throws IOException {
        int[] src = noise(8000 * 2, 16, 19);
        List<byte[]> frames = fixedFrames(src, 1000);
        byte[] head = new FileSpec(44100, 16, 2).blocks(1000, 1000).total(8000).md5(FlacWriter.md5(src, 16))
                .frameSizes(frames.get(0).length, frames.get(0).length).build();
        Path p = put("stray.flac", cat(head, strayPadding(), cat(array(frames))));

        assertEquals(head.length, metadata(p).firstFrameOffset(), "the metadata says the frames start at its end");
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(src, decodeAll(d, 3000));
        }
        assertSeeksExact(p, src, 2, 0, 1, 999, 1000, 7999, 4321);
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
    }

    @Test
    void strayBytesBeforeALaterFrameAreNotSkipped() throws IOException {
        // The first frame found after the stray bytes is frame 3, so skipping to it would drop audio.
        int[] src = noise(8000 * 2, 16, 19);
        List<byte[]> frames = fixedFrames(src, 1000);
        byte[] head = new FileSpec(44100, 16, 2).blocks(1000, 1000).total(8000).build();
        byte[] rest = cat(array(frames.subList(3, 8)));
        try (FlacDecoder d = FlacDecoder.open(put("cut.flac", cat(head, strayPadding(), rest))); Arena arena =
                Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(8000, 4);
            assertThrows(DecodeException.class, () -> d.read(dst, 100));
            assertEquals(0, d.position());
        }
    }

    @Test
    void aDamagedFirstFrameIsNotSkipped() throws IOException {
        int[] src = noise(4000 * 2, 16, 19);
        List<byte[]> frames = fixedFrames(src, 1000);
        byte[] bad = frames.get(0).clone();
        bad[bad.length / 2] ^= 0x20;
        byte[] head = new FileSpec(44100, 16, 2).blocks(1000, 1000).total(4000).build();
        Path p = put("first.flac", cat(head, bad, cat(array(frames.subList(1, 4)))));
        try (FlacDecoder d = FlacDecoder.open(p); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(8000, 4);
            DecodeException e = assertThrows(DecodeException.class, () -> d.read(dst, 100));
            assertTrue(e.getMessage().contains("CRC"), e.getMessage());
            assertEquals(0, d.position());
        }
    }

    @Test
    void unknownTotalSamplesReadsToTheEndOfTheFile() throws IOException {
        int[] src = noise(12345 * 2, 16, 18);
        Path p = put("unknown.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(0)
                .md5(FlacWriter.md5(src, 16)).build(array(fixedFrames(src, 1000))));
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertTrue(d.format().lengthFrames() < 0, "the length is unknown");
            assertArrayEquals(src, decodeAll(d, 700));
            assertEquals(12345, d.position());
        }
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
        assertSeeksExact(p, src, 2, 0, 1, 999, 1000, 12000, 12344);
        try (FlacDecoder d = FlacDecoder.open(p)) {
            d.seek(Long.MAX_VALUE / 4); // beyond the end: stops where the data stops
            assertEquals(12345, d.position());
            assertEquals(0, readFrames(d, 10).length);
        }
    }

    @Test
    void anEmptyStreamHasNoFrames() throws IOException {
        Path p = put("empty.flac", new FileSpec(44100, 16, 2).blocks(4096, 4096).total(0).build());
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertEquals(0, readFrames(d, 10).length);
            d.seek(0);
            d.seek(100);
            assertEquals(0, d.position());
        }
    }

    // =================================================================================================
    // Seeking
    // =================================================================================================

    @Test
    void seekEdgeCases() throws IOException {
        int[] src = noise(12345 * 2, 16, 20); // 12 full frames and a last one of 345
        for (boolean table : new boolean[] {false, true}) {
            Path p = flacFile("edges" + table + ".flac", 44100, 16, 2, src, 1000, VERBATIM, INDEPENDENT, table, null,
                    null);
            assertSeeksExact(p, src, 2, 0, 1, 999, 1000, 1001, 11999, 12000, 12001, 12344, 5000, 5000, 3);
            try (FlacDecoder d = FlacDecoder.open(p); Arena arena = Arena.ofConfined()) {
                MemorySegment dst = arena.allocate(4000, 4);
                d.seek(12345); // exactly the end
                assertEquals(12345, d.position());
                assertEquals(0, d.read(dst, 100));
                d.seek(12346); // beyond it: clamped
                assertEquals(12345, d.position());
                assertEquals(0, d.read(dst, 100));
                d.seek(Long.MAX_VALUE);
                assertEquals(12345, d.position());
                assertEquals(0, d.read(dst, 100));

                d.seek(0); // seek after EOF
                assertEquals(0, d.position());
                assertArrayEquals(slice(src, 2, 0, 500), readFrames(d, 500));
                assertEquals(500, d.position());
                assertThrows(IllegalArgumentException.class, () -> d.seek(-1));
                assertEquals(500, d.position(), "a refused seek changes nothing");
            }
        }
    }

    @Test
    void seekInTheLastShortFrame() throws IOException {
        int[] src = noise(12345 * 2, 16, 21);
        Path p = plainFile("last.flac", src, 1000);
        try (FlacDecoder d = FlacDecoder.open(p)) {
            d.seek(12100);
            assertArrayEquals(slice(src, 2, 12100, 245), readFrames(d, 1000), "the rest of the last frame, no more");
            assertEquals(12345, d.position());
            assertEquals(0, readFrames(d, 10).length);
        }
    }

    @Test
    void seekPointsListSkipsPlaceholders() throws IOException {
        int[] src = walk(250000 * 2, 16, 22); // 2.6 s at 96 kHz
        Path p = flacFile("points.flac", 96000, 16, 2, src, 4096, FIXED(1), INDEPENDENT, true, null, null);
        FlacMetadata m = metadata(p);
        int[] offs = frameOffsets(p);
        assertEquals(List.of(new SeekPoint(0, 0, 4096), new SeekPoint(98304, offs[24] - m.firstFrameOffset(), 4096),
                new SeekPoint(192512, offs[47] - m.firstFrameOffset(), 4096)), m.seekPoints());
        assertTrue(m.firstFrameOffset() > 42);
        assertEquals(96000, m.info().sampleRate());
        assertEquals(250000, m.info().totalSamples());
        assertSeeksExact(p, src, 2, 98304, 98305, 98303, 192512, 249999, 0, 150000);
    }

    @Test
    void aSeekTableThatLiesIsNotTrusted() throws IOException {
        int[] src = walk(20 * 1000 * 2, 16, 23);
        List<byte[]> frames = fixedFrames(src, 1000);
        int[] frameOffset = new int[21];
        for (int i = 0; i < 20; i++) {
            frameOffset[i + 1] = frameOffset[i] + frames.get(i).length;
        }
        long[][][] tables = {
            // right sample, offset of another frame
            {{0, 0, 1000}, {10000, frameOffset[3], 1000}, {-1L, 0, 0}},
            // right frame, offset in the middle of a frame
            {{0, 0, 1000}, {10000, frameOffset[10] + 7, 1000}},
            // offset beyond the end of the file
            {{0, 0, 1000}, {10000, 1L << 40, 1000}},
            // out of order and duplicated points
            {{0, 0, 1000}, {15000, frameOffset[15], 1000}, {5000, frameOffset[5], 1000},
                {15000, frameOffset[15], 1000}},
            // a sample the stream does not have, and a huge offset with the sign bit set
            {{0, 0, 1000}, {1L << 50, frameOffset[10], 1000}, {10000, -5L, 1000}},
        };
        int index = 0;
        for (long[][] table : tables) {
            Path p = put("lying" + index++ + ".flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(20000)
                    .md5(FlacWriter.md5(src, 16)).with(3, seekTablePayload(table)).build(array(frames)));
            assertSeeksExact(p, src, 2, 10000, 10500, 0, 19999, 14999, 5000, 15000, 19000);
            assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
        }
    }

    @Test
    void aSeekTableWhosePartialLastEntryIsIgnored() throws IOException {
        int[] src = walk(10 * 1000 * 2, 16, 24);
        List<byte[]> frames = fixedFrames(src, 1000);
        byte[] table = cat(seekTablePayload(new long[] {0, 0, 1000}, new long[] {-1L, 0, 0}), new byte[] {1, 2, 3, 4,
            5});
        Path p = put("partial.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(10000).with(3, table)
                .build(array(frames)));
        assertEquals(List.of(new SeekPoint(0, 0, 1000)), metadata(p).seekPoints());
    }

    @Test
    void bisectionFindsFramesOfVaryingSizeWithoutASeekTable() throws IOException {
        // Variable blocking, block sizes 200 to 6000, wildly different byte sizes. ~2.4 MB, so the search must really
        // jump around the file (the scan window is far smaller than the file).
        Random r = new Random(30);
        int total = 600000;
        int[] src = noise(total * 2, 16, 31);
        List<byte[]> frames = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        for (int from = 0; from < total; ) {
            int n = Math.min(total - from, 200 + r.nextInt(5800));
            starts.add(from);
            frames.add(variableFrame(44100, 16, 2, src, from, n, VERBATIM));
            from += n;
        }
        Path p = put("variable.flac", new FileSpec(44100, 16, 2).blocks(200, 6000).total(total)
                .md5(FlacWriter.md5(src, 16)).build(array(frames)));

        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
        List<Long> targets = new ArrayList<>(List.of(0L, 1L, (long) total - 1, (long) total - 2));
        for (int k : new int[] {1, 2, 50, starts.size() / 2, starts.size() - 2, starts.size() - 1}) {
            long s = starts.get(k);
            targets.addAll(List.of(s - 1, s, s + 1));
        }
        for (int i = 0; i < 40; i++) {
            targets.add((long) r.nextInt(total));
        }
        assertSeeksExact(p, src, 2, targets.stream().mapToLong(Long::longValue).toArray());
    }

    @Test
    void bisectionWorksWithPredictedFramesAndMonoStreams() throws IOException {
        Random r = new Random(32);
        int total = 400000;
        int[] src = walk(total, 24, 33);
        List<byte[]> frames = new ArrayList<>();
        for (int from = 0; from < total; ) {
            int n = Math.min(total - from, 100 + r.nextInt(8000));
            frames.add(variableFrame(96000, 24, 1, src, from, n, FIXED(2)));
            from += n;
        }
        Path p = put("mono.flac", new FileSpec(96000, 24, 1).blocks(100, 8100).total(total)
                .md5(FlacWriter.md5(src, 24)).build(array(frames)));
        long[] targets = new long[30];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = r.nextInt(total);
        }
        assertSeeksExact(p, src, 1, targets);
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
    }

    @Test
    void seekingIntoADamagedFrameLeavesTheErrorForTheNextRead() throws IOException {
        int[] src = walk(10 * 1000 * 2, 16, 25);
        Path p = flacFile("damage.flac", 44100, 16, 2, src, 1000, FIXED(1), INDEPENDENT, false, null, null);
        int[] offs = frameOffsets(p);
        byte[] bytes = Files.readAllBytes(p);
        bytes[(offs[6] + offs[7]) / 2] ^= 0x10;
        try (FlacDecoder d = FlacDecoder.open(put("damage-bad.flac", bytes))) {
            d.seek(6500); // the frame holding sample 6500 is the broken one
            assertEquals(6000, d.position(), "at the start of the frame that cannot be decoded");
            assertThrows(DecodeException.class, () -> readFrames(d, 10));
            d.seek(2500); // before the damage, everything is fine again
            assertArrayEquals(slice(src, 2, 2500, 1000), readFrames(d, 1000));
        }
    }

    // =================================================================================================
    // Metadata
    // =================================================================================================

    @Test
    void anId3v2TagBeforeTheMarkerIsSkipped() throws IOException {
        int[] src = noise(3 * 44100 * 2, 16, 40);
        Path plain = flacFile("plain.flac", 44100, 16, 2, src, 4096, VERBATIM, INDEPENDENT, true,
                Map.of("TITLE", "Inside"), PNG);
        byte[] id3 = Id3Writer.v24().text("TIT2", Id3Writer.UTF8, "Outside").padding(300).build();
        byte[] twice = cat(id3, Id3Writer.v23().text("TALB", Id3Writer.LATIN1, "Album").build(),
                Files.readAllBytes(plain));
        Path p = put("id3.flac", twice);

        FlacMetadata before = metadata(plain);
        FlacMetadata after = metadata(p);
        assertEquals(before.firstFrameOffset() + twice.length - Files.size(plain), after.firstFrameOffset());
        assertEquals(before.info(), after.info());
        assertEquals(before.seekPoints(), after.seekPoints());
        assertEquals(Optional.of("Inside"), after.tags().first("TITLE"));
        assertArrayEquals(PNG, after.frontCover().orElseThrow());
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(src, decodeAll(d, 5000));
        }
        assertSeeksExact(p, src, 2, 100000, 0, 132299, 44100);
        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(p).status());
    }

    @Test
    void unknownAndReservedBlocksAreSkipped() throws IOException {
        int[] src = noise(4000 * 2, 16, 41);
        byte[] file = new FileSpec(44100, 16, 2).blocks(1000, 1000).total(4000).md5(FlacWriter.md5(src, 16))
                .with(2, "APPLtest data".getBytes(StandardCharsets.US_ASCII)) // APPLICATION
                .with(1, new byte[200]) // PADDING
                .with(5, new byte[60]) // CUESHEET (not parsed)
                .with(9, new byte[17]) // reserved
                .with(126, new byte[3]) // reserved
                .with(4, vorbisPayload("TITLE=After the oddities"))
                .with(1, new byte[0]) // an empty padding block
                .build(array(fixedFrames(src, 1000)));
        Path p = put("odd.flac", file);
        assertEquals(Optional.of("After the oddities"), metadata(p).tags().first("TITLE"));
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(src, decodeAll(d, 1500));
        }
    }

    @Test
    void theFirstVorbisCommentBlockWins() throws IOException {
        byte[] file = new FileSpec(44100, 16, 2).with(4, vorbisPayload("TITLE=First", "ARTIST=A"))
                .with(4, vorbisPayload("TITLE=Second", "ALBUM=B")).build();
        FlacMetadata m = metadata(put("two.flac", file));
        assertEquals(Optional.of("First"), m.tags().first("TITLE"));
        assertEquals(Optional.of("A"), m.tags().first("ARTIST"));
        assertEquals(Optional.empty(), m.tags().first("ALBUM"));
    }

    @Test
    void theFrontCoverIsPreferredAndAnyPictureIsBetterThanNone() throws IOException {
        byte[] other = {1, 1, 1};
        byte[] front = {2, 2, 2};
        byte[] back = {3, 3, 3};
        FlacMetadata both = metadata(put("pic1.flac", new FileSpec(44100, 16, 2)
                .with(6, picturePayload(0, "image/png", other)).with(6, picturePayload(3, "image/jpeg", front))
                .with(6, picturePayload(4, "image/png", back)).build()));
        assertArrayEquals(front, both.frontCover().orElseThrow());

        FlacMetadata backOnly = metadata(put("pic2.flac", new FileSpec(44100, 16, 2)
                .with(6, picturePayload(4, "image/png", back)).with(6, picturePayload(0, "image/png", other))
                .build()));
        assertArrayEquals(back, backOnly.frontCover().orElseThrow(), "the first picture when none is a front cover");

        FlacMetadata url = metadata(put("pic3.flac", new FileSpec(44100, 16, 2)
                .with(6, picturePayload(3, "-->", "http://example.com/a.jpg".getBytes(StandardCharsets.US_ASCII)))
                .with(6, picturePayload(4, "image/png", back)).build()));
        assertArrayEquals(back, url.frontCover().orElseThrow(), "a link is not an image");

        FlacMetadata empty = metadata(put("pic4.flac", new FileSpec(44100, 16, 2)
                .with(6, picturePayload(3, "image/png", new byte[0])).build()));
        assertEquals(Optional.empty(), empty.frontCover());
    }

    @Test
    void aDamagedPictureBlockIsNotAnError() throws IOException {
        byte[] good = picturePayload(3, "image/png", PNG);
        for (int cut = 0; cut < good.length - PNG.length; cut += 3) {
            FlacMetadata m = metadata(put("pic" + cut + ".flac", new FileSpec(44100, 16, 2)
                    .with(6, Arrays.copyOf(good, cut)).build()));
            assertEquals(Optional.empty(), m.frontCover(), "cut at " + cut);
        }
        // a data length that claims more than the block holds
        byte[] lying = good.clone();
        System.arraycopy(be(1L << 30, 4), 0, lying, lying.length - PNG.length - 4, 4);
        assertEquals(Optional.empty(), metadata(put("lie.flac", new FileSpec(44100, 16, 2).with(6, lying).build()))
                .frontCover());
        // a MIME length beyond the block
        byte[] longMime = good.clone();
        System.arraycopy(be(0xFFFFFFF0L, 4), 0, longMime, 4, 4);
        assertEquals(Optional.empty(), metadata(put("mime.flac", new FileSpec(44100, 16, 2).with(6, longMime)
                .build())).frontCover());
    }

    @Test
    void streamInfoFieldsAreReadFromTheirBits() throws IOException {
        byte[] md5 = FlacWriter.md5(new int[] {1, 2, 3}, 16);
        byte[] file = new FileSpec(655350, 24, 6).blocks(16, 65535).frameSizes(11, 1234567).total(0xABCDEF123L)
                .md5(md5).build();
        FlacMetadata m = metadata(put("si.flac", file));
        assertEquals(new FlacStreamInfo(16, 65535, 11, 1234567, 655350, 6, 24, 0xABCDEF123L, md5), m.info());
        assertEquals(4 + 4 + 34, m.firstFrameOffset());
        assertTrue(m.seekPoints().isEmpty());
        assertTrue(m.tags().isEmpty());
        assertEquals(Optional.empty(), m.frontCover());
    }

    @Test
    void notFlacIsADecodeException() throws IOException {
        for (byte[] bytes : new byte[][] {new byte[0], {'f', 'L', 'a'}, "RIFFxxxxWAVE".getBytes(), new byte[100],
            "ID3".getBytes()}) {
            Path p = put("junk.flac", bytes);
            DecodeException e = assertThrows(DecodeException.class, () -> metadata(p));
            assertFalse(e.getMessage().isBlank());
            assertThrows(DecodeException.class, () -> FlacDecoder.open(p));
            assertThrows(DecodeException.class, () -> handler.probe(p));
        }
    }

    @Test
    void oggFlacIsRefusedWithAReason() throws IOException {
        byte[] ogg = cat("OggS".getBytes(), new byte[60]);
        Path p = put("ogg.flac", ogg);
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> metadata(p));
        assertTrue(e.reason().toLowerCase().contains("ogg"), e.reason());
        List<TrackInfo> tracks = handler.probe(p);
        assertEquals(1, tracks.size());
        assertFalse(tracks.get(0).supported());
        assertEquals("FLAC", tracks.get(0).codec());
        assertEquals(e.reason(), tracks.get(0).reason());
        assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0));
    }

    @Test
    void brokenMetadataIsADecodeException() throws IOException {
        byte[] marker = {'f', 'L', 'a', 'C'};
        FileSpec ok = new FileSpec(44100, 16, 2);
        byte[] good = ok.build();
        List<byte[]> bad = new ArrayList<>();
        // cut anywhere inside the metadata
        for (int cut = 4; cut < good.length; cut++) {
            bad.add(Arrays.copyOf(good, cut));
        }
        // STREAMINFO is not first
        bad.add(cat(marker, header(1, false, 4), new byte[4], header(0, true, 34), ok.streamInfo()));
        // STREAMINFO with the wrong length
        bad.add(cat(marker, header(0, true, 33), new byte[33]));
        bad.add(cat(marker, header(0, true, 35), new byte[35]));
        // the forbidden block type 127
        bad.add(cat(marker, header(0, false, 34), ok.streamInfo(), header(127, true, 0)));
        // a block that is longer than the file
        bad.add(cat(marker, header(0, false, 34), ok.streamInfo(), header(1, true, 100000), new byte[10]));
        // the last-block flag never comes, and the file ends
        bad.add(cat(marker, header(0, false, 34), ok.streamInfo(), header(1, false, 4), new byte[4]));
        // a stream that cannot be right: 0 Hz, 3 bits
        bad.add(new FileSpec(0, 16, 2).build());
        bad.add(new FileSpec(44100, 3, 2).build());
        int i = 0;
        for (byte[] b : bad) {
            Path p = put("bad" + i++ + ".flac", b);
            assertThrows(DecodeException.class, () -> metadata(p), "case " + p.getFileName());
            assertThrows(DecodeException.class, () -> FlacDecoder.open(p), "case " + p.getFileName());
        }
    }

    // =================================================================================================
    // Handler and decoder contract
    // =================================================================================================

    @Test
    void handlerBasics() throws IOException {
        assertEquals(List.of("flac"), handler.extensions());
        int[] src = walk(5000 * 2, 24, 50);
        Path p = flacFile("h.flac", 96000, 24, 2, src, 1000, FIXED(1), INDEPENDENT, false, null, null);
        List<TrackInfo> tracks = handler.probe(p);
        assertEquals(1, tracks.size());
        assertEquals(p, tracks.get(0).path());
        assertEquals("FLAC 24/96", tracks.get(0).badge());
        assertEquals(StreamFormat.pcm(96000, 24, 2, 5000), tracks.get(0).format());
        assertTrue(tracks.get(0).tags().isEmpty());
        assertEquals("h", tracks.get(0).displayTitle());

        try (Decoder d = handler.open(p, 0)) {
            assertInstanceOf(FlacDecoder.class, d);
            assertArrayEquals(src, decodeAll(d, 800));
        }
        DecodeException e = assertThrows(DecodeException.class, () -> handler.open(p, 1));
        assertTrue(e.getMessage().contains("subsong 1"), e.getMessage());
        assertThrows(IOException.class, () -> handler.probe(dir.resolve("missing.flac")));
        assertThrows(IOException.class, () -> handler.open(dir.resolve("missing.flac"), 0));
        assertThrows(IOException.class, () -> handler.embeddedArt(dir.resolve("missing.flac"), 0));
    }

    @Test
    void decoderContract() throws IOException {
        int[] src = noise(3000 * 2, 16, 51);
        Path p = plainFile("c.flac", src, 1000);
        FlacDecoder d = FlacDecoder.open(p);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(1000 * 8, 4);
            dst.fill((byte) 0x5A);
            assertThrows(IllegalArgumentException.class, () -> d.read(dst, -1));
            assertThrows(IllegalArgumentException.class, () -> d.read(dst, 1001), "1001 frames need 8008 bytes");
            for (long i = 0; i < dst.byteSize(); i++) {
                assertEquals((byte) 0x5A, dst.get(ValueLayout.JAVA_BYTE, i), "nothing is written before the check");
            }
            assertEquals(0, d.position());
            assertEquals(0, d.read(dst, 0));
            assertEquals(0, d.position());
            assertEquals(1000, d.read(dst, 1000));
            assertEquals(1000, d.position());
            assertEquals(src[0], dst.get(LE_INT, 0));
            assertEquals(src[1999], dst.get(LE_INT, 1999 * 4L));
        }
        assertThrows(IllegalArgumentException.class, () -> d.seek(-5));
        assertNotNull(d.streamInfo());
        assertEquals(3000, d.streamInfo().totalSamples());
        d.close();
        d.close(); // idempotent
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(80, 4);
            assertThrows(IOException.class, () -> d.read(dst, 10));
            assertThrows(IOException.class, () -> d.seek(0));
        }
        assertEquals(StreamFormat.pcm(44100, 16, 2, 3000), d.format());
    }

    @Test
    void readingOneFrameAtATimeCrossesFrameBoundaries() throws IOException {
        int[] src = noise(3500 * 2, 16, 52);
        Path p = plainFile("one.flac", src, 1000);
        try (FlacDecoder d = FlacDecoder.open(p)) {
            int[] got = new int[src.length];
            for (int i = 0; i < 3500; i++) {
                int[] one = readFrames(d, 1);
                assertEquals(2, one.length);
                got[2 * i] = one[0];
                got[2 * i + 1] = one[1];
                assertEquals(i + 1, d.position());
            }
            assertArrayEquals(src, got);
            assertEquals(0, readFrames(d, 1).length);
        }
    }

    @Test
    void readingAllocatesOnlyTheFrameHeaders() throws IOException {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(threads.isThreadAllocatedMemoryEnabled(), "this JVM does not count allocated bytes");
        int[] src = walk(600 * 1024, 16, 53);
        Path p = flacFile("alloc.flac", 44100, 16, 1, src, 1024, FIXED(1), INDEPENDENT, false, null, null);
        try (FlacDecoder d = FlacDecoder.open(p); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(1024 * 4, 4);
            for (int i = 0; i < 5; i++) { // warm up the JIT
                d.seek(0);
                while (d.read(dst, 1024) > 0) {
                    // read it all
                }
            }
            long fewest = Long.MAX_VALUE;
            for (int attempt = 0; attempt < 5; attempt++) {
                d.seek(0);
                long before = threads.getCurrentThreadAllocatedBytes();
                while (d.read(dst, 1024) > 0) {
                    // read it all
                }
                fewest = Math.min(fewest, threads.getCurrentThreadAllocatedBytes() - before);
            }
            assertTrue(fewest <= 600 * 64 + 2048, "allocated " + fewest + " bytes over 600 frames");
        }
    }

    // =================================================================================================
    // FlacVerify
    // =================================================================================================

    @Test
    void verifyClassifiesFiles() throws IOException {
        int[] src = noise(5000 * 2, 16, 60);
        byte[][] frames = array(fixedFrames(src, 1000));
        Path ok = put("ok.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(5000)
                .md5(FlacWriter.md5(src, 16)).build(frames));
        Path noMd5 = put("nomd5.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(5000).build(frames));
        byte[] wrong = FlacWriter.md5(src, 16);
        wrong[0] ^= 1;
        Path mismatch = put("mismatch.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(5000).md5(wrong)
                .build(frames));
        Path garbage = put("garbage.flac", new byte[500]);
        Path brokenNoMd5 = put("broken.flac", Arrays.copyOf(new FileSpec(44100, 16, 2).blocks(1000, 1000)
                .total(5000).build(frames), 300));

        assertEquals(FlacVerify.Status.OK, FlacVerify.verify(ok).status());
        FlacVerify.Result n = FlacVerify.verify(noMd5);
        assertEquals(FlacVerify.Status.NO_MD5, n.status());
        assertEquals(noMd5, n.path());
        assertEquals(FlacVerify.Status.MISMATCH, FlacVerify.verify(mismatch).status());
        assertEquals(FlacVerify.Status.ERROR, FlacVerify.verify(garbage).status());
        assertFalse(FlacVerify.verify(garbage).detail().isBlank());
        assertEquals(FlacVerify.Status.ERROR, FlacVerify.verify(brokenNoMd5).status(),
                "a file with no MD5 still has to decode");
        FlacVerify.Result missing = FlacVerify.verify(dir.resolve("absent.flac"));
        assertEquals(FlacVerify.Status.ERROR, missing.status());
        assertFalse(missing.detail().isBlank());
    }

    @Test
    void scanCountsEverythingAndNeverOpensCloudOnlyFiles() throws IOException {
        int[] src = noise(3000 * 2, 16, 61);
        byte[][] frames = array(fixedFrames(src, 1000));
        Path root = Files.createDirectories(dir.resolve("lib/album"));
        Files.write(root.resolve("a.flac"), new FileSpec(44100, 16, 2).blocks(1000, 1000).total(3000)
                .md5(FlacWriter.md5(src, 16)).build(frames));
        Files.write(root.resolve("B.FLAC"), new FileSpec(44100, 16, 2).blocks(1000, 1000).total(3000)
                .md5(FlacWriter.md5(src, 16)).build(frames));
        Files.write(root.resolve("nomd5.flac"), new FileSpec(44100, 16, 2).blocks(1000, 1000).total(3000)
                .build(frames));
        byte[] wrong = FlacWriter.md5(src, 16);
        wrong[15] ^= 0x80;
        Files.write(root.resolve("wrong.flac"), new FileSpec(44100, 16, 2).blocks(1000, 1000).total(3000).md5(wrong)
                .build(frames));
        Files.write(root.resolve("junk.flac"), new byte[64]);
        Files.write(root.resolve("notes.txt"), new byte[] {1});
        Files.write(root.resolve("cloud.flac"), new byte[64]); // would be an ERROR if it were opened
        Path single = Files.write(dir.resolve("single.flac"), new FileSpec(44100, 16, 2).blocks(1000, 1000)
                .total(3000).md5(FlacWriter.md5(src, 16)).build(frames));

        List<Path> opened = new ArrayList<>();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        FlacVerify.Summary s;
        try (java.io.PrintStream out = new java.io.PrintStream(captured, true, StandardCharsets.UTF_8)) {
            s = FlacVerify.scan(List.of(dir.resolve("lib"), single, dir.resolve("does-not-exist")), out, p -> {
                boolean cloud = p.getFileName().toString().equals("cloud.flac");
                if (!cloud) {
                    opened.add(p);
                }
                return cloud;
            });
        }
        assertEquals(3, s.ok(), s.problems().toString()); // a.flac, B.FLAC, single.flac
        assertEquals(1, s.mismatch());
        assertEquals(1, s.noMd5());
        assertEquals(2, s.error(), "junk.flac and the root that does not exist");
        assertEquals(1, s.cloudOnly());
        assertEquals("3 OK, 1 MISMATCH, 1 NO_MD5, 2 ERROR", s.line());
        assertEquals(4, s.problems().size());
        FlacVerify.Result absent = s.problems().stream()
                .filter(r -> r.path().equals(dir.resolve("does-not-exist"))).findFirst().orElseThrow();
        assertEquals(FlacVerify.Status.ERROR, absent.status(), "a missing root is an ERROR");
        assertTrue(absent.detail().contains("not found"), absent.detail());
        assertNull(absent.damage(), "a missing root is not damage in a file");
        FlacVerify.Result junk = s.problems().stream()
                .filter(r -> r.path().getFileName().toString().equals("junk.flac")).findFirst().orElseThrow();
        assertEquals(Kind.NOT_FLAC, junk.damage());
        assertTrue(s.problems().stream().noneMatch(r -> r.status() == FlacVerify.Status.OK));
        assertTrue(s.problems().stream().noneMatch(r -> r.path().getFileName().toString().equals("cloud.flac")));

        String text = captured.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("3 OK, 1 MISMATCH, 1 NO_MD5, 2 ERROR"), text);
        assertTrue(text.contains("does-not-exist"), text);
        assertTrue(text.contains("ERROR [NOT_FLAC]"), text);
        assertTrue(text.contains("1 cloud-only skipped"), text);
        assertTrue(text.indexOf("1 cloud-only skipped") > text.indexOf("3 OK, 1 MISMATCH"), "after the four counts");
        assertTrue(text.contains("wrong.flac") && text.contains("junk.flac") && text.contains("nomd5.flac"), text);
        assertFalse(text.contains("a.flac"), "OK files are not listed");
        assertFalse(opened.stream().anyMatch(p -> p.getFileName().toString().equals("cloud.flac")),
                "a cloud-only file was opened");
    }

    // =================================================================================================
    // Damage kinds and the library gate
    // =================================================================================================

    /** Reads until the damage and returns the exception, after checking the good frames before it. */
    private static FlacDamageException damageOnRead(Path p, int[] src, int goodFrames) throws IOException {
        try (FlacDecoder d = FlacDecoder.open(p)) {
            assertArrayEquals(slice(src, 2, 0, goodFrames), readFrames(d, 100000));
            FlacDamageException e = assertThrows(FlacDamageException.class, () -> readFrames(d, 10));
            assertSame(e, assertThrows(DecodeException.class, () -> readFrames(d, 10)), "sticky, the same exception");
            return e;
        }
    }

    /** The verifier's answer for a file that is damaged: an ERROR whose kind is the one the exception carried. */
    private static void assertVerifyReports(Path p, Kind kind) {
        FlacVerify.Result r = FlacVerify.verify(p);
        assertEquals(FlacVerify.Status.ERROR, r.status(), r.detail());
        assertEquals(kind, r.damage(), r.detail());
        assertFalse(r.detail().isBlank());
    }

    @Test
    void notFlacIsReportedAsNotFlac() throws IOException {
        for (byte[] bytes : new byte[][] {new byte[500], {'f', 'L', 'a'}, "RIFFxxxxWAVE".getBytes(), new byte[0]}) {
            Path p = put("notflac" + bytes.length + ".flac", bytes);
            assertEquals(Kind.NOT_FLAC, assertThrows(FlacDamageException.class, () -> metadata(p)).kind());
            assertEquals(Kind.NOT_FLAC, assertThrows(FlacDamageException.class, () -> FlacDecoder.open(p)).kind());
            assertVerifyReports(p, Kind.NOT_FLAC);
        }
        Path zeros = put("zeros.flac", new byte[500]);
        assertEquals("not a FLAC file (no fLaC marker)",
                assertThrows(DecodeException.class, () -> metadata(zeros)).getMessage());
    }

    @Test
    void aBadCrcIsReportedAsCrcMismatch() throws IOException {
        int[] src = walk(10 * 1000 * 2, 16, 70);
        Path p = plainFile("crc.flac", src, 1000); // VERBATIM: a flipped sample bit changes nothing but the CRC-16
        int[] offs = frameOffsets(p);
        byte[] bytes = Files.readAllBytes(p);

        bytes[(offs[3] + offs[4]) / 2] ^= 0x40; // inside frame 3
        Path body = put("crc16.flac", bytes);
        FlacDamageException e = damageOnRead(body, src, 3000);
        assertEquals(Kind.CRC_MISMATCH, e.kind());
        assertTrue(e.getMessage().startsWith("CRC mismatch at frame 3")
                && e.getMessage().endsWith("(at byte " + offs[3] + ")"),
                "the message is the user's, unchanged: " + e.getMessage());
        assertVerifyReports(body, Kind.CRC_MISMATCH);

        byte[] other = Files.readAllBytes(p);
        other[offs[2] + 5] ^= 0x01; // the CRC-8 of frame 2's header
        Path header = put("crc8.flac", other);
        assertEquals(Kind.CRC_MISMATCH, damageOnRead(header, src, 2000).kind());
        assertVerifyReports(header, Kind.CRC_MISMATCH);
    }

    @Test
    void aFrameTooLongOrALostSyncIsReportedAsOversizedFrame() throws IOException {
        // a valid header whose Rice data is endless zeros: longer than the cap
        int[] src = walk(5000 * 2, 16, 71);
        List<byte[]> good = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            good.add(FlacWriter.frame(spec(44100, 16, 2, src, i * 1000, 1000, FIXED(1), INDEPENDENT).number(i)));
        }
        FlacStreamInfo info = new FlacStreamInfo(1000, 1000, 0, 0, 44100, 2, 16, 0, new byte[16]);
        int headerLength = FlacFrameHeader.parse(good.get(0), 0, good.get(0).length, info).headerLength();
        byte[] headerOfNext = FlacWriter.frame(spec(44100, 16, 2, src, 3000, 1000, FIXED(1), INDEPENDENT).number(3));
        byte[] bad = new byte[headerLength + 2_000_000];
        System.arraycopy(headerOfNext, 0, bad, 0, headerLength);
        bad[headerLength] = 0x12;
        int maxGood = good.stream().mapToInt(f -> f.length).max().orElseThrow();
        int minGood = good.stream().mapToInt(f -> f.length).min().orElseThrow();
        Path endless = put("endless.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(5000)
                .frameSizes(minGood, maxGood).build(good.get(0), good.get(1), good.get(2), bad));
        FlacDamageException e = damageOnRead(endless, src, 3000);
        assertEquals(Kind.OVERSIZED_FRAME, e.kind());
        assertTrue(e.getMessage().startsWith("damaged frame at byte "), e.getMessage());
        assertVerifyReports(endless, Kind.OVERSIZED_FRAME);

        // zeros where the next frame must start: sync is lost
        int[] walked = walk(10 * 1000 * 2, 16, 72);
        Path p = plainFile("sync.flac", walked, 1000);
        int[] offs = frameOffsets(p);
        byte[] bytes = Files.readAllBytes(p);
        Arrays.fill(bytes, offs[2], offs[2] + 64, (byte) 0);
        Path lost = put("lost-sync.flac", bytes);
        e = damageOnRead(lost, walked, 2000);
        assertEquals(Kind.OVERSIZED_FRAME, e.kind());
        assertTrue(e.getMessage().startsWith("no frame sync (at byte " + offs[2] + ")"), e.getMessage());
        assertVerifyReports(lost, Kind.OVERSIZED_FRAME);
    }

    @Test
    void aFileThatEndsTooSoonIsReportedAsTruncated() throws IOException {
        int[] src = walk(10 * 1000 * 2, 16, 73);
        Path p = plainFile("whole.flac", src, 1000);
        byte[] bytes = Files.readAllBytes(p);
        int[] offs = frameOffsets(p);

        Path inFrame = put("in-frame.flac", Arrays.copyOf(bytes, offs[4] + 10));
        assertEquals(Kind.TRUNCATED, damageOnRead(inFrame, src, 4000).kind());
        assertVerifyReports(inFrame, Kind.TRUNCATED);

        Path between = put("between.flac", Arrays.copyOf(bytes, offs[4]));
        FlacDamageException e = damageOnRead(between, src, 4000);
        assertEquals(Kind.TRUNCATED, e.kind());
        assertTrue(e.getMessage().startsWith("truncated stream at byte " + offs[4]), e.getMessage());
        assertVerifyReports(between, Kind.TRUNCATED);

        // cut inside the metadata
        int first = (int) metadata(p).firstFrameOffset();
        for (int cut : new int[] {4, 5, 10, first - 1}) {
            Path q = put("meta" + cut + ".flac", Arrays.copyOf(bytes, cut));
            assertEquals(Kind.TRUNCATED, assertThrows(FlacDamageException.class, () -> metadata(q)).kind(), "" + cut);
            assertVerifyReports(q, Kind.TRUNCATED);
        }
    }

    @Test
    void whatTheBytesDoNotProveIsNotDamage() throws IOException {
        // a frame that decodes but has another sample rate: refused, but the decoder claims nothing about the file
        int[] src = noise(6000 * 2, 16, 74);
        byte[] f0 = fixedFrame(44100, 16, 2, src, 0, 1000, 0);
        byte[] f1 = fixedFrame(44100, 16, 2, src, 1000, 1000, 1);
        byte[] f2 = FlacWriter.frame(spec(48000, 16, 2, src, 2000, 1000, VERBATIM, INDEPENDENT).number(2));
        Path rate = put("rate.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(3000).build(f0, f1, f2));
        try (FlacDecoder d = FlacDecoder.open(rate)) {
            assertArrayEquals(slice(src, 2, 0, 2000), readFrames(d, 10000));
            DecodeException e = assertThrows(DecodeException.class, () -> readFrames(d, 10));
            assertFalse(e instanceof FlacDamageException, e.getMessage());
        }
        FlacVerify.Result r = FlacVerify.verify(rate);
        assertEquals(FlacVerify.Status.ERROR, r.status());
        assertNull(r.damage());

        // a file that is not there, and a folder that is not a file: I/O faults
        assertNull(FlacVerify.verify(dir.resolve("absent.flac")).damage());
        assertNull(FlacVerify.verify(dir).damage());
        // NO_MD5 has no kind either
        int[] s2 = noise(2000 * 2, 16, 75);
        Path nomd5 = put("nomd5.flac", new FileSpec(44100, 16, 2).blocks(1000, 1000).total(2000)
                .build(array(fixedFrames(s2, 1000))));
        assertEquals(FlacVerify.Status.NO_MD5, FlacVerify.verify(nomd5).status());
        assertNull(FlacVerify.verify(nomd5).damage());
    }

    // ---- the gate of FlacLibraryCheckTest ------------------------------------------------------------

    private static FlacVerify.Summary summary(int ok, int noMd5, int mismatch, FlacVerify.Result... problems) {
        int errors = (int) Arrays.stream(problems).filter(r -> r.status() == FlacVerify.Status.ERROR).count();
        return new FlacVerify.Summary(ok, mismatch, noMd5, errors, 0, List.of(problems));
    }

    private static FlacVerify.Result error(String name, Kind kind) {
        return new FlacVerify.Result(Path.of(name), FlacVerify.Status.ERROR, "detail", kind);
    }

    @Test
    void theLibraryGatePassesProvenDamageAndNothingElse() {
        List<FlacVerify.Result> problems = new ArrayList<>();
        for (Kind k : Kind.values()) {
            problems.add(error(k + ".flac", k));
        }
        problems.add(new FlacVerify.Result(Path.of("n.flac"), FlacVerify.Status.NO_MD5, ""));
        FlacVerify.Result[] ps = problems.toArray(new FlacVerify.Result[0]);
        assertEquals(List.of(), FlacLibraryCheckTest.gateFailures(summary(1185, 1, 0, ps), 1185));

        List<String> few = FlacLibraryCheckTest.gateFailures(summary(1184, 1, 0, ps), 1185);
        assertEquals(1, few.size(), few.toString());
        assertTrue(few.get(0).contains("1184 OK") && few.get(0).contains("1185"), few.toString());
        assertEquals(List.of(), FlacLibraryCheckTest.gateFailures(summary(1184, 1, 0, ps), 1000),
                "the minimum is a knob");
    }

    @Test
    void theLibraryGateFailsOnAnythingElse() {
        // a MISMATCH
        FlacVerify.Result mismatch = new FlacVerify.Result(Path.of("m.flac"), FlacVerify.Status.MISMATCH, "md5");
        assertEquals(1, FlacLibraryCheckTest.gateFailures(summary(2000, 0, 1, mismatch), 1185).size());

        // an ERROR without a kind: a decoder fault, an I/O fault, a RuntimeException (which can only come without
        // one) or a root that does not exist
        List<String> unexplained = FlacLibraryCheckTest.gateFailures(
                summary(2000, 0, 0, error("fault.flac", null), error("unmounted-root", null)), 1185);
        assertEquals(1, unexplained.size(), unexplained.toString());
        assertTrue(unexplained.get(0).contains("fault.flac") && unexplained.get(0).contains("unmounted-root"));
        // proven damage next to an unexplained error does not excuse it
        assertEquals(1, FlacLibraryCheckTest.gateFailures(
                summary(2000, 0, 0, error("a.flac", Kind.CRC_MISMATCH), error("b.flac", null)), 1185).size());

        // scanning nothing (unmounted drive, wrong folder) must not pass, whatever the minimum
        List<String> nothing = FlacLibraryCheckTest.gateFailures(summary(0, 0, 0), 0);
        assertEquals(1, nothing.size(), nothing.toString());
        assertTrue(nothing.get(0).contains("no file was checked"), nothing.toString());
        assertFalse(FlacLibraryCheckTest.gateFailures(summary(0, 0, 0), 1185).isEmpty());
        // only damaged files found: nothing proves the decoder works
        assertFalse(FlacLibraryCheckTest.gateFailures(summary(0, 0, 0, error("a.flac", Kind.NOT_FLAC)), 1185)
                .isEmpty());
    }
}
