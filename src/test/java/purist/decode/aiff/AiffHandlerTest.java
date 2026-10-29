package purist.decode.aiff;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static purist.testutil.AiffWriter.aifc;
import static purist.testutil.AiffWriter.author;
import static purist.testutil.AiffWriter.commAfterSsnd;
import static purist.testutil.AiffWriter.declaredBits;
import static purist.testutil.AiffWriter.declaredChannels;
import static purist.testutil.AiffWriter.declaredFrames;
import static purist.testutil.AiffWriter.declaredSsndSize;
import static purist.testutil.AiffWriter.id3;
import static purist.testutil.AiffWriter.id3Lower;
import static purist.testutil.AiffWriter.junkBeforeSsnd;
import static purist.testutil.AiffWriter.name;
import static purist.testutil.AiffWriter.nameBytes;
import static purist.testutil.AiffWriter.omitComm;
import static purist.testutil.AiffWriter.omitSsnd;
import static purist.testutil.AiffWriter.rateBytes;
import static purist.testutil.AiffWriter.sowt;
import static purist.testutil.AiffWriter.ssndOffset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.decode.io.FileSource;
import purist.testutil.AiffWriter;
import purist.testutil.Id3Writer;

class AiffHandlerTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @TempDir
    Path dir;

    private final AiffHandler handler = new AiffHandler();

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

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
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

    // ---- the 80-bit sample rate ------------------------------------------------------------------

    @Test
    void extended80Values() {
        assertEquals(44100.0, Extended80.toDouble(bytes(0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0)));
        assertEquals(96000.0, Extended80.toDouble(bytes(0x40, 0x0F, 0xBB, 0x80, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void extended80IsExactForEveryStandardRate() {
        int[] rates = {8000, 11025, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000,
                705600, 768000};
        for (int rate : rates) {
            assertEquals((double) rate, Extended80.toDouble(AiffWriter.extended80(rate)), "rate " + rate);
        }
        assertEquals(48000.0, Extended80.toDouble(bytes(0x40, 0x0E, 0xBB, 0x80, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void extended80SpecialValues() {
        assertEquals(1.0, Extended80.toDouble(bytes(0x3F, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(0.5, Extended80.toDouble(bytes(0x3F, 0xFE, 0x80, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(-44100.0, Extended80.toDouble(bytes(0xC0, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0)));
        assertEquals(44100.5, Extended80.toDouble(
                ByteBuffer.allocate(10).putShort((short) (16383 + 15)).putLong(88201L << 47).array()));
        assertEquals(0.0, Extended80.toDouble(new byte[10]));
        assertEquals(Double.POSITIVE_INFINITY, Extended80.toDouble(bytes(0x7F, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(Double.NEGATIVE_INFINITY, Extended80.toDouble(bytes(0xFF, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 0)));
        assertTrue(Double.isNaN(Extended80.toDouble(bytes(0x7F, 0xFF, 0xC0, 0, 0, 0, 0, 0, 0, 0))));
        // A mantissa of 64 one bits is just under 2 and has to round (to even) to exactly 2.
        assertEquals(2.0, Extended80.toDouble(bytes(0x3F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)));
        // A mantissa with its top bit clear is an unnormalised value; it is still mantissa * 2^(exp - 16446).
        assertEquals(0.5, Extended80.toDouble(bytes(0x3F, 0xFF, 0x40, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    void extended80WantsExactlyTenBytes() {
        assertThrows(IllegalArgumentException.class, () -> Extended80.toDouble(new byte[9]));
        assertThrows(IllegalArgumentException.class, () -> Extended80.toDouble(new byte[11]));
        assertThrows(IllegalArgumentException.class, () -> Extended80.toDouble(null));
    }

    // ---- probe -----------------------------------------------------------------------------------

    @Test
    void probe16bitStereo() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2000, 16, 1));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 2, 1000), t.format());
        assertEquals("AIFF", t.codec());
        assertEquals(0, t.subsong());
        assertEquals(p, t.path());
        assertTrue(t.supported());
        assertNull(t.reason());
        assertEquals("AIFF 16/44.1", t.badge());
        assertTrue(t.tags().isEmpty());
        assertEquals(List.of("aif", "aiff", "aifc"), handler.extensions());
    }

    @Test
    void aifcIsBadgedAsAiff() throws IOException {
        Path none = AiffWriter.write(dir, "a.aifc", 96000, 24, 2, samples(2 * 50, 24, 2), aifc("NONE"));
        TrackInfo t = probeOne(none);
        assertEquals(StreamFormat.pcm(96000, 24, 2, 50), t.format());
        assertEquals("AIFF", t.codec());
        assertEquals("AIFF 24/96", t.badge());
        assertEquals("AIFF 16/44.1",
                probeOne(AiffWriter.write(dir, "b.aifc", 44100, 16, 2, samples(2 * 50, 16, 3), sowt())).badge());
    }

    @Test
    void everyStandardRateIsReadBack() throws IOException {
        int[] rates = {8000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000};
        for (int rate : rates) {
            Path p = AiffWriter.write(dir, "r" + rate + ".aif", rate, 24, 2, samples(2 * 8, 24, rate));
            assertEquals(rate, probeOne(p).format().sampleRate(), "rate " + rate);
        }
    }

    @Test
    void notAnAiffIsRejectedWithADecodeException() throws IOException {
        Path junk = dir.resolve("junk.aiff");
        Files.write(junk, "This is not an IFF file at all, just text.".getBytes());
        assertThrows(DecodeException.class, () -> handler.probe(junk));
        assertThrows(DecodeException.class, () -> handler.open(junk, 0));

        Path formNotAiff = dir.resolve("form.aiff");
        byte[] b = new byte[64];
        System.arraycopy("FORM".getBytes(), 0, b, 0, 4);
        System.arraycopy("8SVX".getBytes(), 0, b, 8, 4);
        Files.write(formNotAiff, b);
        assertThrows(DecodeException.class, () -> handler.probe(formNotAiff));

        Path riff = dir.resolve("riff.aiff");
        byte[] r = new byte[64];
        System.arraycopy("RIFF".getBytes(), 0, r, 0, 4);
        System.arraycopy("WAVE".getBytes(), 0, r, 8, 4);
        Files.write(riff, r);
        assertThrows(DecodeException.class, () -> handler.probe(riff));
    }

    @Test
    void aFileShorterThanTheHeaderIsADecodeException() throws IOException {
        Path tiny = dir.resolve("tiny.aiff");
        Files.write(tiny, new byte[] {'F', 'O', 'R', 'M', 1});
        assertThrows(DecodeException.class, () -> handler.probe(tiny));
        Path empty = dir.resolve("empty.aiff");
        Files.write(empty, new byte[0]);
        assertThrows(DecodeException.class, () -> handler.probe(empty));
    }

    @Test
    void missingFileIsAnIoException() {
        assertThrows(IOException.class, () -> handler.probe(dir.resolve("absent.aiff")));
        assertThrows(IOException.class, () -> handler.open(dir.resolve("absent.aiff"), 0));
    }

    @Test
    void aSubsongOtherThanZeroIsRefused() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(20, 16, 1));
        assertThrows(DecodeException.class, () -> handler.open(p, 1));
    }

    // ---- decoding, bit-exact ---------------------------------------------------------------------

    @Test
    void decodesAiff16BigEndianExact() throws IOException {
        int[] src = samples(2 * 3000, 16, 3);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, src);
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(StreamFormat.pcm(44100, 16, 2, 3000), d.format());
            int[] got = decodeAll(d, 512);
            assertArrayEquals(src, got);
            assertEquals(-32768, got[0]);
            assertEquals(32767, got[1]);
        }
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 777));
        }
    }

    @Test
    void decodesAifcSowtExact() throws IOException {
        int[] src = samples(2 * 3000, 16, 4);
        Path p = AiffWriter.write(dir, "a.aifc", 44100, 16, 2, src, sowt());
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(StreamFormat.pcm(44100, 16, 2, 3000), d.format());
            int[] got = decodeAll(d, 512);
            assertArrayEquals(src, got);
            assertEquals(-32768, got[0]);
            assertEquals(32767, got[1]);
        }
        int[] src24 = samples(2 * 2000, 24, 5);
        Path p24 = AiffWriter.write(dir, "b.aifc", 96000, 24, 2, src24, sowt());
        try (Decoder d = handler.open(p24, 0)) {
            assertArrayEquals(src24, decodeAll(d, 300));
        }
    }

    @Test
    void aiff20bitLeftJustified() throws IOException {
        int[] src = samples(2 * 1000, 20, 6);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 20, 2, src);
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 20, 2, 1000), t.format());
        assertEquals("AIFF 20/44.1", t.badge());
        try (Decoder d = handler.open(p, 0)) {
            int[] got = decodeAll(d, 256);
            assertArrayEquals(src, got);
            assertEquals(-(1 << 19), got[0]);
            assertEquals((1 << 19) - 1, got[1]);
        }
        Path ps = AiffWriter.write(dir, "b.aifc", 44100, 20, 2, src, sowt());
        try (Decoder d = handler.open(ps, 0)) {
            assertArrayEquals(src, decodeAll(d, 256));
        }
    }

    @Test
    void decodes24bitExact() throws IOException {
        int[] src = samples(2 * 5000, 24, 7);
        Path p = AiffWriter.write(dir, "a.aiff", 96000, 24, 2, src);
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(StreamFormat.pcm(96000, 24, 2, 5000), d.format());
            int[] got = decodeAll(d, 777);
            assertArrayEquals(src, got);
            assertEquals(-(1 << 23), got[0]);
            assertEquals((1 << 23) - 1, got[1]);
        }
    }

    @Test
    void decodes32bitExactIncludingExtremes() throws IOException {
        int[] src = samples(2 * 3000, 32, 8);
        Path big = AiffWriter.write(dir, "big.aiff", 192000, 32, 2, src);
        Path little = AiffWriter.write(dir, "little.aifc", 192000, 32, 2, src, sowt());
        for (Path p : List.of(big, little)) {
            try (Decoder d = handler.open(p, 0)) {
                assertEquals(32, d.format().bits());
                int[] got = decodeAll(d, 1024);
                assertArrayEquals(src, got);
                assertEquals(Integer.MIN_VALUE, got[0]);
                assertEquals(Integer.MAX_VALUE, got[1]);
            }
        }
    }

    @Test
    void eightBitSamplesAreSignedAndNotRecentred() throws IOException {
        int[] src = new int[256];
        for (int i = 0; i < 256; i++) {
            src[i] = i - 128;
        }
        Path p = AiffWriter.write(dir, "a.aiff", 8000, 8, 1, src);
        assertEquals("AIFF 8/8", probeOne(p).badge());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 64));
        }
        // And the same for the little-endian flavour, where one byte has no endianness.
        Path ps = AiffWriter.write(dir, "b.aifc", 8000, 8, 1, src, sowt());
        try (Decoder d = handler.open(ps, 0)) {
            assertArrayEquals(src, decodeAll(d, 64));
        }
    }

    @Test
    void everySampleSizeFromOneTo32RoundTrips() throws IOException {
        for (int bits = 1; bits <= 32; bits++) {
            int[] src = samples(2 * 64, bits, 100 + bits);
            for (int variant = 0; variant < 3; variant++) {
                AiffWriter.AiffOpt[] opts = switch (variant) {
                    case 0 -> new AiffWriter.AiffOpt[0];
                    case 1 -> new AiffWriter.AiffOpt[] {aifc("twos")};
                    default -> new AiffWriter.AiffOpt[] {sowt()};
                };
                Path p = AiffWriter.write(dir, "d" + bits + "_" + variant + ".aif", 44100, bits, 2, src, opts);
                try (Decoder d = handler.open(p, 0)) {
                    assertEquals(bits, d.format().bits());
                    assertArrayEquals(src, decodeAll(d, 50), "bits " + bits + " variant " + variant);
                }
            }
        }
    }

    /**
     * The writer and the decoder both come from the same reading of the format, so check them both against the
     * JDK's own, independent AIFF codec: its files must decode to the samples that went in, and its reader must
     * see the same samples, rate and length in the files the writer produces.
     */
    @Test
    void agreesWithTheJdksOwnAiffCodec() throws Exception {
        for (int bits : new int[] {8, 16, 24, 32}) {
            int frames = 500;
            int[] src = samples(2 * frames, bits, 60 + bits);
            int cbytes = bits / 8;
            byte[] pcm = new byte[src.length * cbytes]; // big-endian two's complement, built without AiffWriter
            for (int i = 0; i < src.length; i++) {
                for (int k = 0; k < cbytes; k++) {
                    pcm[i * cbytes + k] = (byte) (src[i] >> (8 * (cbytes - 1 - k)));
                }
            }
            AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 48000f, bits, 2, 2 * cbytes, 48000f, true);

            Path jdk = dir.resolve("jdk" + bits + ".aiff");
            try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, frames)) {
                AudioSystem.write(in, AudioFileFormat.Type.AIFF, jdk.toFile());
            }
            assertEquals(StreamFormat.pcm(48000, bits, 2, frames), probeOne(jdk).format(), "bits " + bits);
            try (Decoder d = handler.open(jdk, 0)) {
                assertArrayEquals(src, decodeAll(d, 128), "JDK file, bits " + bits);
            }

            Path ours = AiffWriter.write(dir, "ours" + bits + ".aiff", 48000, bits, 2, src);
            try (AudioInputStream a = AudioSystem.getAudioInputStream(ours.toFile())) {
                assertEquals(48000f, a.getFormat().getSampleRate());
                assertEquals(bits, a.getFormat().getSampleSizeInBits());
                assertEquals(2, a.getFormat().getChannels());
                assertEquals(frames, a.getFrameLength());
                assertArrayEquals(pcm, a.readAllBytes(), "our file, bits " + bits);
            }
        }
    }

    @Test
    void noneAndTwosAreBigEndianAndSowtIsNot() throws IOException {
        int[] src = samples(2 * 100, 16, 9);
        Path none = AiffWriter.write(dir, "none.aifc", 44100, 16, 2, src, aifc("NONE"));
        Path twos = AiffWriter.write(dir, "twos.aifc", 44100, 16, 2, src, aifc("twos"));
        Path little = AiffWriter.write(dir, "sowt.aifc", 44100, 16, 2, src, sowt());
        for (Path p : List.of(none, twos, little)) {
            try (Decoder d = handler.open(p, 0)) {
                assertArrayEquals(src, decodeAll(d, 64), p.getFileName().toString());
            }
        }
        // The test would pass vacuously if sowt files were not really byte-swapped, so check the bytes.
        byte[] a = Files.readAllBytes(none);
        byte[] b = Files.readAllBytes(little);
        assertEquals(a.length, b.length);
        int dataStart = a.length - 400;
        assertEquals(a[dataStart], b[dataStart + 1]);
        assertEquals(a[dataStart + 1], b[dataStart]);
    }

    @Test
    void largeFileDecodesExactlyAcrossInternalChunks() throws IOException {
        int frames = 200_000;
        int[] src = samples(2 * frames, 24, 12);
        Path p = AiffWriter.write(dir, "a.aiff", 96000, 24, 2, src);
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
        Path p = AiffWriter.write(dir, "m.aiff", 44100, 16, 1, src);
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 1, 300), t.format());
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(1000L * 4, 4); // zeroed
            assertEquals(100, d.read(dst, 100));
            for (int i = 0; i < 100; i++) {
                assertEquals(src[i], dst.get(LE_INT, 4L * i));
            }
            assertEquals(0, dst.get(LE_INT, 4L * 100), "wrote beyond frames * channels * 4 bytes");
        }
    }

    @Test
    void multichannelDecodesInterleaved() throws IOException {
        int[] src = samples(6 * 500, 24, 14);
        Path p = AiffWriter.write(dir, "six.aiff", 48000, 24, 6, src);
        assertEquals(6, probeOne(p).format().channels());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 128));
        }
    }

    @Test
    void readFillsTheRequestedFramesAndShortensOnlyAtTheEnd() throws IOException {
        int[] src = samples(2 * 1000, 16, 15);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, src);
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
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 16));
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
        Path p = AiffWriter.write(dir, "a.aiff", 96000, 24, 2, samples(2 * frames, 24, 40));
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
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 24, 2, src);
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
    void seekIsExactWithAnSsndOffset() throws IOException {
        int[] src = samples(2 * 1000, 16, 41);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, src, ssndOffset(6));
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(16L, 4);
            d.seek(321);
            assertEquals(2, d.read(dst, 2));
            assertEquals(src[642], dst.get(LE_INT, 0));
            assertEquals(src[643], dst.get(LE_INT, 4));
            assertEquals(src[644], dst.get(LE_INT, 8));
            assertEquals(src[645], dst.get(LE_INT, 12));
        }
    }

    @Test
    void seekToTheLastFrameAndPastTheEnd() throws IOException {
        int[] src = samples(2 * 1000, 16, 18);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, src);
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

    // ---- chunk layout ----------------------------------------------------------------------------

    @Test
    void ssndOffsetShiftsWhereTheSamplesStart() throws IOException {
        int[] src = samples(2 * 400, 24, 42);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 24, 2, src, ssndOffset(10));
        assertEquals(400, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
    }

    @Test
    void ssndOffsetBeyondItsChunkIsADecodeException() throws IOException {
        // The chunk claims 20 bytes (12 of data after its header) but the offset says to skip 50.
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 43), ssndOffset(50),
                declaredSsndSize(20));
        assertThrows(DecodeException.class, () -> handler.probe(p));
        assertThrows(DecodeException.class, () -> handler.open(p, 0));
    }

    @Test
    void commMayFollowSsnd() throws IOException {
        int[] src = samples(2 * 300, 16, 44);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, src, commAfterSsnd());
        assertEquals(StreamFormat.pcm(44100, 16, 2, 300), probeOne(p).format());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
    }

    @Test
    void unknownAndOddSizedChunksAreSkipped() throws IOException {
        int[] src = samples(2 * 300, 24, 45);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 24, 2, src, junkBeforeSsnd(7), name("After the junk"));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 24, 2, 300), t.format());
        assertEquals("After the junk", t.displayTitle());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
    }

    @Test
    void aFileWithoutCommIsADecodeException() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 46), omitComm());
        assertThrows(DecodeException.class, () -> handler.probe(p));
        assertThrows(DecodeException.class, () -> handler.open(p, 0));
    }

    @Test
    void aFileWithoutSsndIsADecodeExceptionUnlessItIsEmpty() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 47), omitSsnd());
        assertThrows(DecodeException.class, () -> handler.probe(p));
        assertThrows(DecodeException.class, () -> handler.open(p, 0));

        // Zero frames need no sound data chunk at all.
        Path empty = AiffWriter.write(dir, "e.aiff", 44100, 16, 2, new int[0], omitSsnd());
        assertEquals(0, probeOne(empty).format().lengthFrames());
        try (Decoder d = handler.open(empty, 0); Arena arena = Arena.ofConfined()) {
            assertEquals(0, d.read(arena.allocate(64, 4), 8));
        }
    }

    // ---- damaged and truncated files -------------------------------------------------------------

    @Test
    void ssndSizeBeyondTheFileIsClamped() throws IOException {
        int[] src = samples(2 * 100, 16, 19);
        // The chunk header claims almost 4 GB but only 100 frames follow.
        Path p = AiffWriter.write(dir, "t.aiff", 44100, 16, 2, src, declaredSsndSize(0xFFFFFFF0L));
        assertEquals(100, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(100, d.format().lengthFrames());
            assertArrayEquals(src, decodeAll(d, 64));
            assertEquals(100, d.position());
        }
    }

    @Test
    void commFramesBeyondTheDataAreClamped() throws IOException {
        int[] src = samples(2 * 100, 16, 20);
        Path p = AiffWriter.write(dir, "t.aiff", 44100, 16, 2, src, declaredFrames(1_000_000));
        assertEquals(100, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 64));
        }
    }

    @Test
    void commFramesFewerThanTheDataAreHonoured() throws IOException {
        int[] src = samples(2 * 100, 16, 21);
        Path p = AiffWriter.write(dir, "t.aiff", 44100, 16, 2, src, declaredFrames(40));
        assertEquals(40, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(Arrays.copyOf(src, 80), decodeAll(d, 64));
        }
    }

    @Test
    void aTruncatedLastFrameIsDropped() throws IOException {
        int[] src = samples(2 * 100, 16, 22);
        Path p = AiffWriter.write(dir, "t.aiff", 44100, 16, 2, src);
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, all.length - 3)); // cuts into the last frame
        assertEquals(99, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(Arrays.copyOf(src, 2 * 99), decodeAll(d, 64));
        }
    }

    @Test
    void aFileCutRightAfterTheSsndHeaderHasNoFrames() throws IOException {
        Path p = AiffWriter.write(dir, "t.aiff", 44100, 16, 2, samples(2 * 100, 16, 23));
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, all.length - 400)); // exactly the sample bytes
        assertEquals(0, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0); Arena arena = Arena.ofConfined()) {
            assertEquals(0, d.read(arena.allocate(64, 4), 8));
        }
    }

    @Test
    void aFileCutInsideTheSsndHeaderIsADecodeException() throws IOException {
        Path p = AiffWriter.write(dir, "t.aiff", 44100, 16, 2, new int[0]);
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, all.length - 4)); // half of offset + blockSize
        assertThrows(DecodeException.class, () -> handler.probe(p));
    }

    @Test
    void aTruncatedCommChunkIsADecodeException() throws IOException {
        Path p = AiffWriter.write(dir, "t.aiff", 44100, 16, 2, samples(2 * 100, 16, 24));
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, 12 + 8 + 10)); // FORM header, COMM header, ten of its 18 bytes
        assertThrows(DecodeException.class, () -> handler.probe(p));
        assertThrows(DecodeException.class, () -> handler.open(p, 0));
    }

    // ---- sample rate and format validation -------------------------------------------------------

    @Test
    void aFractionalRateIsADecodeException() throws IOException {
        byte[] rate = ByteBuffer.allocate(10).putShort((short) (16383 + 15)).putLong(88201L << 47).array(); // 44100.5
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 10, 16, 25), rateBytes(rate));
        assertThrows(DecodeException.class, () -> handler.probe(p));
        assertThrows(DecodeException.class, () -> handler.open(p, 0));
    }

    @Test
    void zeroNegativeHugeAndNonNumericRatesAreDecodeExceptions() throws IOException {
        byte[][] bad = {
                new byte[10], // zero
                bytes(0xC0, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0), // -44100
                bytes(0x40, 0x1E, 0x80, 0, 0, 0, 0, 0, 0, 0), // 2^31, one more than an int holds
                bytes(0x7F, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 0), // infinity
                bytes(0x7F, 0xFF, 0xC0, 0, 0, 0, 0, 0, 0, 0), // NaN
                bytes(0x3F, 0xFE, 0x80, 0, 0, 0, 0, 0, 0, 0), // 0.5
        };
        for (int i = 0; i < bad.length; i++) {
            Path p = AiffWriter.write(dir, "bad" + i + ".aiff", 44100, 16, 2, samples(2 * 10, 16, 26), rateBytes(bad[i]));
            assertThrows(DecodeException.class, () -> handler.probe(p), "rate " + i);
        }
    }

    @Test
    void anAiffWithNoChannelsOrNoSampleSizeIsADecodeException() throws IOException {
        Path noChannels = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 10, 16, 27), declaredChannels(0));
        assertThrows(DecodeException.class, () -> handler.probe(noChannels));
        // 0x8000 is -32768 as the signed 16-bit field the format defines.
        Path negative = AiffWriter.write(dir, "b.aiff", 44100, 16, 2, samples(2 * 10, 16, 27), declaredChannels(0x8000));
        assertThrows(DecodeException.class, () -> handler.probe(negative));
        Path noBits = AiffWriter.write(dir, "c.aiff", 44100, 16, 2, samples(2 * 10, 16, 27), declaredBits(0));
        assertThrows(DecodeException.class, () -> handler.probe(noBits));
    }

    @Test
    void sampleSizesAbove32BitsAreRefused() throws IOException {
        for (int bits : new int[] {33, 64}) {
            Path p = AiffWriter.write(dir, "w" + bits + ".aiff", 44100, bits, 2, samples(2 * 10, 32, 28));
            TrackInfo t = probeOne(p);
            String reason = "AIFF with " + bits + "-bit samples is not supported";
            assertFalse(t.supported());
            assertEquals(reason, t.reason());
            assertNull(t.format());
            assertEquals(reason, assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0)).reason());
        }
    }

    // ---- refused formats -------------------------------------------------------------------------

    @Test
    void compressedRefused() throws IOException {
        Path p = AiffWriter.write(dir, "f.aifc", 44100, 32, 2, samples(2 * 100, 32, 29), aifc("fl32"));
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals("compressed AIFF-C (fl32)", t.reason());
        assertNull(t.format());
        assertEquals("AIFF", t.codec());
        assertEquals(p, t.path());
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0));
        assertEquals("compressed AIFF-C (fl32)", e.reason());
        assertEquals("compressed AIFF-C (fl32)", e.getMessage());
    }

    @Test
    void everyOtherCompressionTypeIsRefusedWithItsFourcc() throws IOException {
        String[] fourccs = {"ulaw", "alaw", "ima4", "fl64", "ULAW", "ACE2", "MAC3"};
        for (int i = 0; i < fourccs.length; i++) {
            String fourcc = fourccs[i];
            Path p = AiffWriter.write(dir, "c" + i + ".aifc", 44100, 16, 2, samples(2 * 100, 16, 30), aifc(fourcc));
            assertEquals("compressed AIFF-C (" + fourcc + ")", probeOne(p).reason(), fourcc);
            assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0));
        }
    }

    // ---- tags ------------------------------------------------------------------------------------

    @Test
    void readsNameAndAuthorAsTitleAndArtist() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 31), name("A Song"),
                author("Motörhead"));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 2, 100), t.format());
        assertEquals(Optional.of("A Song"), t.tags().first("TITLE"));
        assertEquals(Optional.of("Motörhead"), t.tags().first("ARTIST"));
        assertEquals("A Song", t.displayTitle());
        assertEquals("Motörhead", t.displayArtist());
        assertEquals("Motörhead", t.albumArtist());
    }

    @Test
    void oddLengthTextIsPaddedAndDoesNotDisturbTheNextChunk() throws IOException {
        // "Odd" is 3 bytes, so a pad byte follows it; AUTH and the samples must still be found.
        int[] src = samples(2 * 100, 16, 32);
        Path p = AiffWriter.write(dir, "a.aifc", 44100, 16, 2, src, aifc("NONE"), name("Odd"), author("Even"));
        TrackInfo t = probeOne(p);
        assertEquals("Odd", t.displayTitle());
        assertEquals("Even", t.displayArtist());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 64));
        }
    }

    @Test
    void textEndsAtTheFirstNulIsTrimmedAndFallsBackToWindows1252() throws IOException {
        Path nul = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 10, 16, 33),
                nameBytes(bytes('A', ' ', 'S', 'o', 'n', 'g', ' ', 0, 'j', 'u', 'n', 'k')));
        assertEquals("A Song", probeOne(nul).displayTitle());
        Path ansi = AiffWriter.write(dir, "b.aiff", 44100, 16, 2, samples(2 * 10, 16, 34),
                nameBytes(bytes('M', 'o', 't', 0xF6, 'r', 'h', 'e', 'a', 'd')));
        assertEquals("Motörhead", probeOne(ansi).displayTitle());
    }

    @Test
    void theFirstNameChunkWins() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 10, 16, 35), name("First"),
                nameBytes("Second".getBytes()));
        assertEquals(List.of("First"), probeOne(p).tags().all("TITLE"));
    }

    @Test
    void untaggedFallsBackToFilename() throws IOException {
        Path album = Files.createDirectories(dir.resolve("Some Album"));
        Path p = AiffWriter.write(album, "01 Song.aiff", 44100, 16, 2, samples(2 * 100, 16, 36));
        TrackInfo t = probeOne(p);
        assertEquals("01 Song", t.displayTitle());
        assertEquals("", t.displayArtist());
        assertEquals("Some Album", t.displayAlbum());
        assertEquals("Unknown artist", t.albumArtist());
    }

    @Test
    void id3ChunkOffsetIsRecordedForLaterParsing() throws IOException {
        byte[] tag = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 1, 42}; // 11 bytes: odd, so the chunk is padded
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 37), name("T"), id3(tag));
        try (FileSource s = FileSource.open(p)) {
            AiffHandler.AiffFile a = AiffHandler.parse(s);
            assertEquals(tag.length, a.id3Length());
            s.seek(a.id3Offset());
            assertArrayEquals(tag, s.bytes(tag.length));
            assertEquals(Optional.of("T"), a.tags().first("TITLE"));
        }
    }

    @Test
    void lowerCaseId3ChunkIsRecordedToo() throws IOException {
        byte[] tag = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
        Path p = AiffWriter.write(dir, "a.aifc", 44100, 16, 2, samples(2 * 100, 16, 38), aifc("NONE"), id3Lower(tag));
        try (FileSource s = FileSource.open(p)) {
            AiffHandler.AiffFile a = AiffHandler.parse(s);
            assertEquals(tag.length, a.id3Length());
            s.seek(a.id3Offset());
            assertArrayEquals(tag, s.bytes(tag.length));
        }
    }

    @Test
    void id3LengthIsClampedToTheFile() throws IOException {
        byte[] tag = new byte[40];
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 39), id3(tag));
        byte[] all = Files.readAllBytes(p);
        Files.write(p, Arrays.copyOf(all, all.length - 15)); // the tag is cut short
        try (FileSource s = FileSource.open(p)) {
            AiffHandler.AiffFile a = AiffHandler.parse(s);
            assertEquals(25, a.id3Length());
            assertEquals(100, a.format().lengthFrames());
        }
    }

    @Test
    void noId3ChunkIsReportedAsAbsent() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 48));
        try (FileSource s = FileSource.open(p)) {
            AiffHandler.AiffFile a = AiffHandler.parse(s);
            assertEquals(-1, a.id3Offset());
            assertEquals(0, a.id3Length());
        }
    }

    // ---- ID3 -------------------------------------------------------------------------------------

    private static final byte[] COVER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    @Test
    void aiffId3ChunkOverridesNameAndAuthor() throws IOException {
        byte[] tag = Id3Writer.v23().text("TIT2", Id3Writer.LATIN1, "ID3 T\u00EDtle").text("TALB", Id3Writer.UTF16, "An Album")
                .build();
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 60), name("Old Title"),
                author("Old Artist"), id3(tag));
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.pcm(44100, 16, 2, 100), t.format());
        assertEquals(Optional.of("ID3 T\u00EDtle"), t.tags().first("TITLE"));
        assertEquals(Optional.of("An Album"), t.tags().first("ALBUM"));
        assertEquals(Optional.of("Old Artist"), t.tags().first("ARTIST")); // ID3 is silent on it, AUTH stays
    }

    @Test
    void aifcWithALowerCaseId3ChunkIsReadToo() throws IOException {
        byte[] tag = Id3Writer.v24().text("TPE1", Id3Writer.UTF8, "One", "Two").build();
        Path p = AiffWriter.write(dir, "a.aifc", 44100, 16, 2, samples(2 * 100, 16, 61), aifc("NONE"),
                author("Old Artist"), id3Lower(tag));
        assertEquals(List.of("One", "Two"), probeOne(p).tags().all("ARTIST"));
    }

    @Test
    void aDamagedId3ChunkLeavesTheNameTagAndTheAudioAlone() throws IOException {
        int[] src = samples(2 * 100, 16, 62);
        byte[] garbage = new byte[60];
        new Random(2).nextBytes(garbage);
        System.arraycopy(new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 50}, 0, garbage, 0, 10);
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, src, name("Old Title"), id3(garbage));
        assertEquals(Optional.of("Old Title"), probeOne(p).tags().first("TITLE"));
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(d, 100));
        }
    }

    @Test
    void embeddedArtComesFromTheId3FrontCover() throws IOException {
        byte[] other = {9, 9, 9};
        byte[] tag = Id3Writer.v23().apic("image/png", 4, "", Id3Writer.LATIN1, other)
                .apic("image/jpeg", 3, "front", Id3Writer.LATIN1, COVER).build();
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 63), id3(tag));
        assertArrayEquals(COVER, handler.embeddedArt(p, 0).orElseThrow());
        Path lower = AiffWriter.write(dir, "b.aiff", 44100, 16, 2, samples(2 * 100, 16, 64), id3Lower(tag));
        assertArrayEquals(COVER, handler.embeddedArt(lower, 0).orElseThrow());
    }

    @Test
    void embeddedArtIsAbsentWithoutAPicture() throws IOException {
        Path none = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 49));
        assertEquals(Optional.empty(), handler.embeddedArt(none, 0));
        byte[] textOnly = Id3Writer.v24().text("TIT2", Id3Writer.UTF8, "T").build();
        Path tagged = AiffWriter.write(dir, "b.aiff", 44100, 16, 2, samples(2 * 100, 16, 65), id3(textOnly));
        assertEquals(Optional.empty(), handler.embeddedArt(tagged, 0));
    }

    @Test
    void embeddedArtOfASubsongThatDoesNotExistIsAbsent() throws IOException {
        byte[] tag = Id3Writer.v24().apic("image/jpeg", 3, "", Id3Writer.UTF8, COVER).build();
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 100, 16, 66), id3(tag));
        assertEquals(Optional.empty(), handler.embeddedArt(p, 1));
    }

    @Test
    void embeddedArtOfACompressedAifcIsAbsentAndOfAJunkFileIsADecodeException() throws IOException {
        byte[] tag = Id3Writer.v24().apic("image/jpeg", 3, "", Id3Writer.UTF8, COVER).build();
        Path c = AiffWriter.write(dir, "c.aifc", 44100, 16, 2, samples(2 * 100, 16, 67), aifc("fl32"), id3(tag));
        assertEquals(Optional.empty(), handler.embeddedArt(c, 0));
        Path junk = dir.resolve("junk.aiff");
        Files.write(junk, "This is not a FORM file at all, just text.".getBytes());
        assertThrows(DecodeException.class, () -> handler.embeddedArt(junk, 0));
        assertThrows(IOException.class, () -> handler.embeddedArt(dir.resolve("absent.aiff"), 0));
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    @Test
    void closeIsIdempotentAndStopsReads() throws IOException {
        Path p = AiffWriter.write(dir, "a.aiff", 44100, 16, 2, samples(2 * 1000, 16, 50));
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
        int[] src = samples(2 * 200, 16, 51);
        Path p = AiffWriter.write(d, "日本語 ö.aiff", 44100, 16, 2, src);
        assertTrue(p.toString().length() > 260);
        assertEquals(200, probeOne(p).format().lengthFrames());
        try (Decoder dec = handler.open(p, 0)) {
            assertArrayEquals(src, decodeAll(dec, 64));
        }
    }
}
