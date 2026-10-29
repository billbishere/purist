package purist.decode.wav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static purist.testutil.WavWriter.RF64;
import static purist.testutil.WavWriter.declaredDataSize;
import static purist.testutil.WavWriter.extensible;
import static purist.testutil.WavWriter.floatFormat;
import static purist.testutil.WavWriter.formatTag;
import static purist.testutil.WavWriter.id3;
import static purist.testutil.WavWriter.id3Upper;
import static purist.testutil.WavWriter.info;
import static purist.testutil.WavWriter.junkBeforeData;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
import purist.decode.io.FileSource;
import purist.testutil.Id3Writer;
import purist.testutil.WavWriter;

class WavHandlerTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final String FLOAT_MESSAGE = "32-bit float WAV is not bit-perfect to an integer DAC";

    @TempDir
    Path dir;

    private final WavHandler handler = new WavHandler();

    // ---- helpers ---------------------------------------------------------------------------------

    /** {@code n} random signed samples of {@code bits} bits, starting with both extremes. */
    private static int[] samples(int n, int bits, long seed) {
        Random r = new Random(seed);
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            s[i] = (int) (r.nextLong() >> (64 - bits));
        }
        if (n >= 4) {
            s[0] = bits == 32 ? Integer.MIN_VALUE : -(1 << (bits - 1));
            s[1] = bits == 32 ? Integer.MAX_VALUE : (1 << (bits - 1)) - 1;
            s[2] = 0;
            s[3] = -1;
        }
        return s;
    }

    /** Reads the decoder dry in steps of {@code step} frames and returns every int32 it produced. */
    private static int[] decodeAll(Decoder d, int step) throws IOException {
        int channels = d.format().channels();
        int[] out = new int[0];
        int used = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate((long) step * channels * 4, 4);
            while (true) {
                int frames = d.read(dst, step);
                if (frames == 0) {
                    break;
                }
                assertTrue(frames > 0 && frames <= step);
                if (used + frames * channels > out.length) {
                    out = Arrays.copyOf(out, Math.max(out.length * 2, used + frames * channels));
                }
                for (int i = 0; i < frames * channels; i++) {
                    out[used++] = dst.get(LE_INT, 4L * i);
                }
            }
        }
        return Arrays.copyOf(out, used);
    }

    private TrackInfo probeOne(Path p) throws IOException {
        List<TrackInfo> l = handler.probe(p);
        assertEquals(1, l.size());
        return l.get(0);
    }

    // ---- probe -----------------------------------------------------------------------------------

    @Test
    void probe16bitStereo() throws IOException {
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2000, 16, 1));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 2, 1000), t.format());
        assertEquals("WAV", t.codec());
        assertEquals(0, t.subsong());
        assertEquals(p, t.path());
        assertTrue(t.supported());
        assertNull(t.reason());
        assertEquals("WAV 16/44.1", t.badge());
        assertTrue(t.tags().isEmpty());
        assertEquals(List.of("wav"), handler.extensions());
    }

    @Test
    void notAWavIsRejectedWithADecodeException() throws IOException {
        Path junk = dir.resolve("junk.wav");
        Files.write(junk, "This is not a RIFF file at all, just text.".getBytes());
        assertThrows(DecodeException.class, () -> handler.probe(junk));
        assertThrows(DecodeException.class, () -> handler.open(junk, 0));

        Path riffNotWave = dir.resolve("avi.wav");
        byte[] b = new byte[64];
        System.arraycopy("RIFF".getBytes(), 0, b, 0, 4);
        System.arraycopy("AVI ".getBytes(), 0, b, 8, 4);
        Files.write(riffNotWave, b);
        assertThrows(DecodeException.class, () -> handler.probe(riffNotWave));
    }

    @Test
    void aFileShorterThanTheHeaderIsADecodeException() throws IOException {
        Path tiny = dir.resolve("tiny.wav");
        Files.write(tiny, new byte[] {'R', 'I', 'F', 'F', 1});
        assertThrows(DecodeException.class, () -> handler.probe(tiny));
        Path empty = dir.resolve("empty.wav");
        Files.write(empty, new byte[0]);
        assertThrows(DecodeException.class, () -> handler.probe(empty));
    }

    @Test
    void missingFileIsAnIoException() {
        assertThrows(IOException.class, () -> handler.probe(dir.resolve("absent.wav")));
        assertThrows(IOException.class, () -> handler.open(dir.resolve("absent.wav"), 0));
    }

    @Test
    void aSubsongOtherThanZeroIsRefused() throws IOException {
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(20, 16, 1));
        assertThrows(DecodeException.class, () -> handler.open(p, 1));
    }

    // ---- decoding, bit-exact ---------------------------------------------------------------------

    @Test
    void decodes24bitExact() throws IOException {
        int[] src = samples(2 * 5000, 24, 2);
        Path p = WavWriter.write(dir, "a.wav", 96000, 24, 2, src);
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(StreamFormat.pcm(96000, 24, 2, 5000), d.format());
            assertArrayEquals(src, decodeAll(d, 777));
        }
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 1000));
        }
    }

    @Test
    void decodes16bitExactIncludingExtremes() throws IOException {
        int[] src = samples(2 * 3000, 16, 3);
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src);
        try (Decoder d = handler.open(p, 0)) {
            int[] got = decodeAll(d, 512);
            assertArrayEquals(src, got);
            assertEquals(-32768, got[0]);
            assertEquals(32767, got[1]);
        }
    }

    @Test
    void decodes32bitExactIncludingExtremes() throws IOException {
        int[] src = samples(2 * 3000, 32, 4);
        Path p = WavWriter.write(dir, "a.wav", 192000, 32, 2, src);
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(32, d.format().bits());
            int[] got = decodeAll(d, 1024);
            assertArrayEquals(src, got);
            assertEquals(Integer.MIN_VALUE, got[0]);
            assertEquals(Integer.MAX_VALUE, got[1]);
        }
    }

    @Test
    void extensible24in32() throws IOException {
        int[] src = samples(2 * 2000, 24, 5);
        Path p = WavWriter.write(dir, "a.wav", 48000, 24, 2, src, extensible(24, 32));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(48000, 24, 2, 2000), t.format());
        assertEquals("WAV 24/48", t.badge());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 300));
        }
    }

    @Test
    void extensible20in24LeftJustified() throws IOException {
        int[] src = samples(2 * 1000, 20, 6);
        Path p = WavWriter.write(dir, "a.wav", 44100, 20, 2, src, extensible(20, 24));
        assertEquals(20, probeOne(p).format().bits());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 256));
        }
    }

    @Test
    void extensible16in16IsPlainPcm() throws IOException {
        int[] src = samples(2 * 500, 16, 7);
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src, extensible(16, 16));
        assertEquals(StreamFormat.pcm(44100, 16, 2, 500), probeOne(p).format());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
    }

    @Test
    void eightBitUnsignedRecentered() throws IOException {
        int[] raw = {0x00, 0x80, 0xFF, 0x01, 0x7F, 0x81, 0xFE, 0x40};
        int[] expected = {-128, 0, 127, -127, -1, 1, 126, -64};
        Path p = WavWriter.write(dir, "a.wav", 22050, 8, 2, raw);
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(22050, 8, 2, 4), t.format());
        assertEquals("WAV 8/22.05", t.badge());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(expected, decodeAll(d, 3));
        }
    }

    @Test
    void everyEightBitValueSurvives() throws IOException {
        int[] raw = new int[256];
        int[] expected = new int[256];
        for (int i = 0; i < 256; i++) {
            raw[i] = i;
            expected[i] = i - 128;
        }
        Path p = WavWriter.write(dir, "a.wav", 8000, 8, 1, raw);
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(expected, decodeAll(d, 64));
        }
    }

    @Test
    void rf64HeaderParsed() throws IOException {
        int[] src = samples(2 * 4000, 24, 8);
        Path p = WavWriter.write(dir, "a.wav", 96000, 24, 2, src, RF64);
        assertEquals("RF64", new String(Files.readAllBytes(p), 0, 4));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(96000, 24, 2, 4000), t.format());
        assertTrue(t.supported());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 999));
        }
    }

    @Test
    void rf64TakesTheDataSizeFromDs64() throws IOException {
        int[] src = samples(2 * 100, 16, 9);
        // ds64 says 40 frames (160 bytes of 16-bit stereo) although more data follows.
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src, RF64, declaredDataSize(160));
        assertEquals(40, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(Arrays.copyOf(src, 80), decodeAll(d, 64));
        }
    }

    @Test
    void rf64DataSizeBeyondTheFileIsClamped() throws IOException {
        int[] src = samples(2 * 100, 16, 10);
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src, RF64, declaredDataSize(6_000_000_000L));
        assertEquals(100, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 64));
        }
    }

    @Test
    void declaredSizeSmallerThanTheFileIsHonoured() throws IOException {
        int[] src = samples(2 * 100, 16, 11);
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src, declaredDataSize(160));
        assertEquals(40, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(Arrays.copyOf(src, 80), decodeAll(d, 64));
        }
    }

    @Test
    void largeFileDecodesExactlyAcrossInternalChunks() throws IOException {
        int frames = 200_000;
        int[] src = samples(2 * frames, 24, 12);
        Path p = WavWriter.write(dir, "a.wav", 96000, 24, 2, src);
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 50_000));
            assertEquals(frames, d.position());
        }
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 4096));
        }
    }

    @Test
    void monoFileFormat() throws IOException {
        int[] src = samples(300, 16, 13);
        Path p = WavWriter.write(dir, "m.wav", 44100, 16, 1, src);
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 1, 300), t.format());
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            assertEquals(1, d.format().channels());
            MemorySegment dst = arena.allocate(1000L * 4, 4); // zeroed
            assertEquals(100, d.read(dst, 100));
            for (int i = 0; i < 100; i++) {
                assertEquals(src[i], dst.get(LE_INT, 4L * i));
            }
            assertEquals(0, dst.get(LE_INT, 4L * 100), "wrote beyond frames * channels * 4 bytes");
        }
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 64));
        }
    }

    @Test
    void multichannelDecodesInterleaved() throws IOException {
        int[] src = samples(6 * 500, 24, 14);
        Path p = WavWriter.write(dir, "six.wav", 48000, 24, 6, src);
        assertEquals(6, probeOne(p).format().channels());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 128));
        }
    }

    @Test
    void readFillsTheRequestedFramesAndShortensOnlyAtTheEnd() throws IOException {
        int[] src = samples(2 * 1000, 16, 15);
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src);
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(40_000L * 4 * 2, 4);
            assertEquals(600, d.read(dst, 600));
            assertEquals(600, d.position());
            assertEquals(400, d.read(dst, 600));
            assertEquals(1000, d.position());
            assertEquals(0, d.read(dst, 600));
            assertEquals(0, d.read(dst, 600));
            assertEquals(1000, d.position());
            assertEquals(0, d.read(dst, 0));
        }
    }

    @Test
    void aTooSmallDestinationIsRefusedBeforeAnythingIsWritten() throws IOException {
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 16));
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            MemorySegment small = arena.allocate(10 * 8L - 1, 4);
            assertThrows(IllegalArgumentException.class, () -> d.read(small, 10));
            assertThrows(IllegalArgumentException.class, () -> d.read(arena.allocate(64, 4), -1));
            assertEquals(0, d.position());
            assertEquals(10, d.read(arena.allocate(10 * 8L, 4), 10));
        }
    }

    @Test
    void readDoesNotAllocateOnceWarm() throws IOException {
        int frames = 200_000;
        Path p = WavWriter.write(dir, "a.wav", 96000, 24, 2, samples(2 * frames, 24, 40));
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(4096L * 8, 4);
            for (int i = 0; i < 20; i++) { // warm up: class loading, JDK scratch buffers, JIT
                d.read(dst, 4096);
            }
            d.seek(0);
            long before = bean.getCurrentThreadAllocatedBytes();
            int reads = 0;
            while (d.read(dst, 4096) > 0) {
                reads++;
            }
            long allocated = bean.getCurrentThreadAllocatedBytes() - before;
            assertTrue(reads > 40);
            assertTrue(allocated < 1024, "read allocated " + allocated + " bytes over " + reads + " calls");
        }
    }

    // ---- seeking ---------------------------------------------------------------------------------

    @Test
    void seekThenRead() throws IOException {
        int[] src = samples(2 * 1000, 24, 17);
        Path p = WavWriter.write(dir, "a.wav", 44100, 24, 2, src);
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(200L * 8, 4);
            assertEquals(0, d.position());
            assertEquals(10, d.read(dst, 10));
            assertEquals(10, d.position());

            d.seek(500);
            assertEquals(500, d.position());
            assertEquals(100, d.read(dst, 100));
            assertEquals(600, d.position());
            for (int i = 0; i < 200; i++) {
                assertEquals(src[1000 + i], dst.get(LE_INT, 4L * i), "sample " + i);
            }

            d.seek(3); // backwards, into the span the file buffer already holds
            assertEquals(3, d.position());
            assertEquals(20, d.read(dst, 20));
            for (int i = 0; i < 40; i++) {
                assertEquals(src[6 + i], dst.get(LE_INT, 4L * i));
            }

            d.seek(0);
            assertEquals(5, d.read(dst, 5));
            for (int i = 0; i < 10; i++) {
                assertEquals(src[i], dst.get(LE_INT, 4L * i));
            }
        }
    }

    @Test
    void seekToTheLastFrameAndPastTheEnd() throws IOException {
        int[] src = samples(2 * 1000, 16, 18);
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src);
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(16L, 4);
            d.seek(999);
            assertEquals(1, d.read(dst, 2));
            assertEquals(src[1998], dst.get(LE_INT, 0));
            assertEquals(src[1999], dst.get(LE_INT, 4));
            assertEquals(0, d.read(dst, 2));

            d.seek(1000);
            assertEquals(1000, d.position());
            assertEquals(0, d.read(dst, 2));

            d.seek(1_000_000);
            assertEquals(1000, d.position(), "seeking past the end clamps to the length");
            assertEquals(0, d.read(dst, 2));

            d.seek(0); // and it recovers
            assertEquals(2, d.read(dst, 2));
            assertEquals(src[0], dst.get(LE_INT, 0));
            assertThrows(IllegalArgumentException.class, () -> d.seek(-1));
        }
    }

    // ---- damaged and truncated files -------------------------------------------------------------

    @Test
    void truncatedDataChunkStopsCleanly() throws IOException {
        int[] src = samples(2 * 100, 16, 19);
        // The header claims a megabyte but only 100 frames follow.
        Path p = WavWriter.write(dir, "t.wav", 44100, 16, 2, src, declaredDataSize(1_000_000));
        assertEquals(100, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(100, d.format().lengthFrames());
            assertArrayEquals(src, decodeAll(d, 64));
            assertEquals(100, d.position());
        }
    }

    @Test
    void aTruncatedLastFrameIsDropped() throws IOException {
        int[] src = samples(2 * 100, 16, 20);
        Path p = WavWriter.write(dir, "t.wav", 44100, 16, 2, src);
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, all.length - 3)); // cuts into the last frame
        assertEquals(99, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(Arrays.copyOf(src, 2 * 99), decodeAll(d, 64));
        }
    }

    @Test
    void aFileCutRightAfterTheDataHeaderHasNoFrames() throws IOException {
        Path p = WavWriter.write(dir, "t.wav", 44100, 16, 2, samples(2 * 100, 16, 21));
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, all.length - 400)); // exactly the sample bytes
        assertEquals(0, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            assertEquals(0, d.read(arena.allocate(64, 4), 8));
        }
    }

    @Test
    void aFileWithNoDataChunkIsADecodeException() throws IOException {
        Path p = WavWriter.write(dir, "t.wav", 44100, 16, 2, samples(2 * 100, 16, 22));
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, 12 + 8 + 16)); // header and fmt chunk, nothing else
        assertThrows(DecodeException.class, () -> handler.probe(p));
        assertThrows(DecodeException.class, () -> handler.open(p, 0));
    }

    @Test
    void aFileWithNoFmtChunkIsADecodeException() throws IOException {
        Path p = WavWriter.write(dir, "t.wav", 44100, 16, 2, samples(2 * 100, 16, 23));
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, 12)); // just RIFF....WAVE
        assertThrows(DecodeException.class, () -> handler.probe(p));
    }

    // ---- refused formats -------------------------------------------------------------------------

    @Test
    void floatWavRefused() throws IOException {
        Path p = WavWriter.write(dir, "f.wav", 44100, 32, 2, samples(2 * 100, 32, 24), floatFormat());
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals(FLOAT_MESSAGE, t.reason());
        assertNull(t.format());
        assertEquals("WAV", t.codec());
        assertEquals(p, t.path());
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0));
        assertEquals(FLOAT_MESSAGE, e.reason());
        assertEquals(FLOAT_MESSAGE, e.getMessage());
    }

    @Test
    void extensibleFloatWavRefused() throws IOException {
        Path p = WavWriter.write(dir, "f.wav", 48000, 32, 2, samples(2 * 100, 32, 25), extensible(32, 32),
                floatFormat());
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals(FLOAT_MESSAGE, t.reason());
        assertEquals(FLOAT_MESSAGE,
                assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0)).reason());
    }

    @Test
    void compressedWavRefusedWithItsFormatCode() throws IOException {
        Path mp3 = WavWriter.write(dir, "c.wav", 44100, 16, 2, samples(2 * 100, 16, 26), formatTag(0x0055));
        TrackInfo t = probeOne(mp3);
        assertFalse(t.supported());
        assertEquals("compressed WAV (format 0x0055)", t.reason());
        assertEquals("compressed WAV (format 0x0055)",
                assertThrows(UnsupportedFormatException.class, () -> handler.open(mp3, 0)).reason());

        Path adpcm = WavWriter.write(dir, "d.wav", 44100, 16, 2, samples(2 * 100, 16, 27), formatTag(2));
        assertEquals("compressed WAV (format 0x0002)", probeOne(adpcm).reason());
    }

    @Test
    void extensibleWithAnUnknownSubformatIsRefused() throws IOException {
        Path p = WavWriter.write(dir, "x.wav", 44100, 16, 2, samples(2 * 100, 16, 28), extensible(16, 16));
        byte[] b = Files.readAllBytes(p);
        // RIFF(12) + "fmt "(8) + 24 bytes of fixed fields, then the GUID; break the last GUID byte.
        b[12 + 8 + 24 + 15] ^= 1;
        Files.write(p, b);
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals("compressed WAV (format 0xFFFE)", t.reason());
    }

    // ---- chunks and tags -------------------------------------------------------------------------

    @Test
    void readsInfoTags() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("INAM", "A Song");
        m.put("IART", "Motörhead");
        m.put("IPRD", "An Album");
        m.put("ICRD", "2018");
        m.put("IGNR", "Rock");
        m.put("ITRK", "3");
        m.put("ICMT", "Hello");
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 29), info(m));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 2, 100), t.format());
        assertEquals(Optional.of("A Song"), t.tags().first("TITLE"));
        assertEquals(Optional.of("Motörhead"), t.tags().first("ARTIST"));
        assertEquals(Optional.of("An Album"), t.tags().first("ALBUM"));
        assertEquals(Optional.of("2018"), t.tags().first("DATE"));
        assertEquals(Optional.of("Rock"), t.tags().first("GENRE"));
        assertEquals(Optional.of("3"), t.tags().first("TRACKNUMBER"));
        assertEquals(Optional.of("Hello"), t.tags().first("COMMENT"));
        assertEquals("A Song", t.displayTitle());
        assertEquals("Motörhead", t.displayArtist());
        assertEquals("An Album", t.displayAlbum());
        assertEquals("Motörhead", t.albumArtist());
    }

    @Test
    void untaggedFallsBackToFilename() throws IOException {
        Path album = Files.createDirectories(dir.resolve("Some Album"));
        Path p = WavWriter.write(album, "01 Song.wav", 44100, 16, 2, samples(2 * 100, 16, 30));
        TrackInfo t = probeOne(p);
        assertEquals("01 Song", t.displayTitle());
        assertEquals("", t.displayArtist());
        assertEquals("Some Album", t.displayAlbum());
        assertEquals("Unknown artist", t.albumArtist());
    }

    @Test
    void unknownAndOddSizedChunksAreSkipped() throws IOException {
        int[] src = samples(2 * 300, 24, 31);
        Map<String, String> m = Map.of("INAM", "After the data");
        Path p = WavWriter.write(dir, "a.wav", 44100, 24, 2, src, junkBeforeData(7), info(m));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 24, 2, 300), t.format());
        assertEquals("After the data", t.displayTitle());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
    }

    @Test
    void junkBeforeDataIsFineForRf64Too() throws IOException {
        int[] src = samples(2 * 300, 24, 32);
        Path p = WavWriter.write(dir, "a.wav", 44100, 24, 2, src, RF64, junkBeforeData(32));
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
    }

    @Test
    void id3ChunkOffsetIsRecordedForLaterParsing() throws IOException {
        byte[] tag = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 1, 42}; // 11 bytes: odd, so the chunk is padded
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 33),
                info(Map.of("INAM", "T")), id3(tag));
        try (FileSource s = FileSource.open(p)) {
            WavHandler.WavFile w = WavHandler.parse(s);
            assertEquals(tag.length, w.id3Length());
            s.seek(w.id3Offset());
            assertArrayEquals(tag, s.bytes(tag.length));
            assertEquals(Optional.of("T"), w.tags().first("TITLE"));
        }
    }

    @Test
    void upperCaseId3ChunkIsRecordedToo() throws IOException {
        byte[] tag = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 34), id3Upper(tag));
        try (FileSource s = FileSource.open(p)) {
            WavHandler.WavFile w = WavHandler.parse(s);
            assertEquals(tag.length, w.id3Length());
            s.seek(w.id3Offset());
            assertArrayEquals(tag, s.bytes(tag.length));
        }
    }

    @Test
    void noId3ChunkIsReportedAsAbsent() throws IOException {
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 35));
        try (FileSource s = FileSource.open(p)) {
            WavHandler.WavFile w = WavHandler.parse(s);
            assertEquals(-1, w.id3Offset());
            assertEquals(0, w.id3Length());
        }
    }

    // ---- ID3 -------------------------------------------------------------------------------------

    private static final byte[] COVER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    @Test
    void wavId3ChunkOverridesInfo() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("INAM", "Info Title");
        m.put("IART", "Info Artist");
        m.put("IPRD", "Info Album");
        m.put("ICMT", "Info Comment");
        byte[] tag = Id3Writer.v24().text("TIT2", Id3Writer.UTF8, "ID3 Title")
                .text("TPE1", Id3Writer.UTF8, "First", "Second").build();
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 52), info(m), id3(tag));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 2, 100), t.format());
        assertEquals(Optional.of("ID3 Title"), t.tags().first("TITLE"));
        assertEquals(List.of("First", "Second"), t.tags().all("ARTIST")); // the ID3 key replaces the INFO one whole
        assertEquals(Optional.of("Info Album"), t.tags().first("ALBUM")); // INFO stays where ID3 is silent
        assertEquals(Optional.of("Info Comment"), t.tags().first("COMMENT"));
        assertEquals("ID3 Title", t.displayTitle());
    }

    @Test
    void upperCaseId3ChunkIsReadToo() throws IOException {
        byte[] tag = Id3Writer.v23().text("TIT2", Id3Writer.UTF16, "Mot\u00F6rhead").text("TRCK", Id3Writer.LATIN1, "3/12")
                .build();
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 53), id3Upper(tag));
        TrackInfo t = probeOne(p);
        assertEquals(Optional.of("Mot\u00F6rhead"), t.tags().first("TITLE"));
        assertEquals(Optional.of("3/12"), t.tags().first("TRACKNUMBER"));
    }

    @Test
    void aDamagedId3ChunkLeavesTheInfoTagsAndTheAudioAlone() throws IOException {
        int[] src = samples(2 * 100, 16, 54);
        byte[] garbage = new byte[60];
        new Random(1).nextBytes(garbage);
        System.arraycopy(new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 50}, 0, garbage, 0, 10);
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, src, info(Map.of("INAM", "Info Title")), id3(garbage));
        TrackInfo t = probeOne(p);
        assertEquals(Optional.of("Info Title"), t.tags().first("TITLE"));
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
        Path notId3 = WavWriter.write(dir, "b.wav", 44100, 16, 2, src, info(Map.of("INAM", "Info Title")),
                id3(new byte[] {1, 2, 3}));
        assertEquals(Optional.of("Info Title"), probeOne(notId3).tags().first("TITLE"));
    }

    @Test
    void embeddedArtComesFromTheId3FrontCover() throws IOException {
        byte[] other = {9, 9, 9};
        byte[] tag = Id3Writer.v24().apic("image/png", 0, "", Id3Writer.UTF8, other)
                .apic("image/jpeg", 3, "front", Id3Writer.UTF16, COVER).build();
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 55), id3(tag));
        assertArrayEquals(COVER, handler.embeddedArt(p, 0).orElseThrow());
        Path upper = WavWriter.write(dir, "b.wav", 44100, 16, 2, samples(2 * 100, 16, 56), id3Upper(tag));
        assertArrayEquals(COVER, handler.embeddedArt(upper, 0).orElseThrow());
    }

    @Test
    void embeddedArtIsAbsentWithoutAPicture() throws IOException {
        Path none = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 36));
        assertEquals(Optional.empty(), handler.embeddedArt(none, 0));
        byte[] textOnly = Id3Writer.v24().text("TIT2", Id3Writer.UTF8, "T").build();
        Path tagged = WavWriter.write(dir, "b.wav", 44100, 16, 2, samples(2 * 100, 16, 57), id3(textOnly));
        assertEquals(Optional.empty(), handler.embeddedArt(tagged, 0));
    }

    @Test
    void embeddedArtOfASubsongThatDoesNotExistIsAbsent() throws IOException {
        byte[] tag = Id3Writer.v24().apic("image/jpeg", 3, "", Id3Writer.UTF8, COVER).build();
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 100, 16, 58), id3(tag));
        assertEquals(Optional.empty(), handler.embeddedArt(p, 1));
    }

    @Test
    void embeddedArtOfAnUnsupportedWavIsAbsentAndOfAJunkFileIsADecodeException() throws IOException {
        byte[] tag = Id3Writer.v24().apic("image/jpeg", 3, "", Id3Writer.UTF8, COVER).build();
        Path f = WavWriter.write(dir, "f.wav", 44100, 32, 2, samples(2 * 100, 32, 59), floatFormat(), id3(tag));
        assertEquals(Optional.empty(), handler.embeddedArt(f, 0));
        Path junk = dir.resolve("junk.wav");
        Files.write(junk, "This is not a RIFF file at all, just text.".getBytes());
        assertThrows(DecodeException.class, () -> handler.embeddedArt(junk, 0));
        assertThrows(IOException.class, () -> handler.embeddedArt(dir.resolve("absent.wav"), 0));
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    @Test
    void closeIsIdempotentAndStopsReads() throws IOException {
        Path p = WavWriter.write(dir, "a.wav", 44100, 16, 2, samples(2 * 1000, 16, 37));
        Decoder d = handler.open(p, 0);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64, 4);
            assertEquals(8, d.read(dst, 8));
            d.close();
            d.close();
            assertThrows(IOException.class, () -> d.read(dst, 8));
            assertThrows(IOException.class, () -> d.seek(0));
        }
    }

    @Test
    void worksOnNonAsciiAndOverlongPaths() throws IOException {
        Path d = dir;
        StringBuilder seg = new StringBuilder();
        while (seg.length() < 60) {
            seg.append("Motörhead ");
        }
        while (d.toString().length() < 320) {
            d = d.resolve(seg.toString().trim());
        }
        Files.createDirectories(d);
        int[] src = samples(2 * 200, 16, 38);
        Path p = WavWriter.write(d, "日本語 ö.wav", 44100, 16, 2, src);
        assertTrue(p.toString().length() > 260);
        assertEquals(200, probeOne(p).format().lengthFrames());
        try (Decoder dec = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(dec, 64));
        }
    }
}
