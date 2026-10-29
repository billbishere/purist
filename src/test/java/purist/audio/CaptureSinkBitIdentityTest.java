package purist.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the whole decoder-to-driver pipeline (StreamCore + Packer + RingBuffer + CaptureSink's emulated ASIO
 * double buffer) is bit-identical, and exercises CaptureSink itself. The expected bytes are computed here with
 * plain arithmetic, not with Packer, so the test cannot agree with a wrong Packer by construction.
 */
class CaptureSinkBitIdentityTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private static final Set<Integer> RATES = Set.of(44100, 48000, 96000, 2822400, 5644800);
    private static final int FRAMES = 10_000;
    private static final SampleType[] PCM_TYPES = {
        SampleType.INT32_LSB, SampleType.INT24_LSB, SampleType.INT32_LSB24, SampleType.INT16_LSB
    };

    private static CaptureSink sink(SampleType pcm, SampleType dsd, int bufferFrames) {
        return new CaptureSink("Capture", pcm, dsd, bufferFrames, RATES);
    }

    private static CaptureSink pcmSink(SampleType pcm, int bufferFrames) {
        return sink(pcm, SampleType.DSD_INT8_MSB1, bufferFrames);
    }

    // ---- PCM test data ---------------------------------------------------------------------------------

    /** Seeded signed {@code bits}-bit samples, right-aligned as decoders emit them, edges first. */
    private static int[] randomSamples(int bits, int frames, long seed) {
        int min = bits == 32 ? Integer.MIN_VALUE : -(1 << (bits - 1));
        int max = ~min;
        int[] edges = {min, max, 0, -1, 1, min + 1, max - 1};
        SplittableRandom rnd = new SplittableRandom(seed);
        int[] s = new int[frames];
        for (int i = 0; i < frames; i++) {
            s[i] = i < edges.length ? edges[i] : rnd.nextInt() >> (32 - bits);
        }
        return s;
    }

    /** Interleaved little-endian int32, the decoders' output; {@code right == null} means a mono source. */
    private static MemorySegment interleaved(int[] left, int[] right) {
        int ch = right == null ? 1 : 2;
        MemorySegment seg = MemorySegment.ofArray(new byte[left.length * ch * 4]);
        for (int i = 0; i < left.length; i++) {
            seg.set(LE_INT, (long) i * ch * 4, left[i]);
            if (right != null) {
                seg.set(LE_INT, (long) i * ch * 4 + 4, right[i]);
            }
        }
        return seg;
    }

    /** What the driver buffer must hold: each sample shifted into the container, little-endian. */
    private static byte[] reference(int[] samples, int bits, SampleType out) {
        int cb = out.containerBytes();
        int shift = out.validBits() - bits;
        byte[] r = new byte[samples.length * cb];
        for (int i = 0; i < samples.length; i++) {
            long container = ((long) samples[i]) << shift;
            for (int b = 0; b < cb; b++) {
                r[i * cb + b] = (byte) (container >> (8 * b));
            }
        }
        return r;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void assertPrefixThenSilence(String what, byte[] captured, byte[] expected, byte silence) {
        assertTrue(captured.length >= expected.length,
                what + ": captured " + captured.length + " bytes, expected at least " + expected.length);
        assertArrayEquals(expected, Arrays.copyOf(captured, expected.length), what + ": data");
        for (int i = expected.length; i < captured.length; i++) {
            if (captured[i] != silence) {
                fail(what + ": byte " + i + " after the data is " + (captured[i] & 0xFF) + ", not silence "
                        + (silence & 0xFF));
            }
        }
    }

    // ---- PCM matrix -----------------------------------------------------------------------------------------

    static Stream<Arguments> fittingPcmPairs() {
        List<Arguments> list = new ArrayList<>();
        for (int bits : new int[] {16, 24, 32}) {
            for (SampleType out : PCM_TYPES) {
                if (out.validBits() >= bits) {
                    for (int buffer : new int[] {512, 333}) {
                        list.add(Arguments.of(bits, out, buffer));
                    }
                }
            }
        }
        return list.stream();
    }

    static Stream<Arguments> nonFittingPcmPairs() {
        List<Arguments> list = new ArrayList<>();
        for (int bits : new int[] {16, 24, 32}) {
            for (SampleType out : PCM_TYPES) {
                if (out.validBits() < bits) {
                    list.add(Arguments.of(bits, out));
                }
            }
        }
        return list.stream();
    }

    @ParameterizedTest(name = "{0}-bit into {1}, {2}-frame buffers")
    @MethodSource("fittingPcmPairs")
    void pcmPipelineIsBitIdentical(int bits, SampleType out, int bufferFrames) throws Exception {
        int[] left = randomSamples(bits, FRAMES, 1000L + bits);
        int[] right = randomSamples(bits, FRAMES, 2000L + bits);
        StreamFormat fmt = StreamFormat.pcm(96000, bits, 2, FRAMES);

        try (CaptureSink sink = pcmSink(out, bufferFrames)) {
            SinkStream stream = sink.open(fmt);
            StreamCore core = stream.core();
            core.setSource(fmt);
            core.write(interleaved(left, right), FRAMES);
            core.endOfInput();
            stream.start();
            sink.pumpUntilDrained(10_000);

            assertPrefixThenSilence("left", sink.captured(0), reference(left, bits, out), (byte) 0);
            assertPrefixThenSilence("right", sink.captured(1), reference(right, bits, out), (byte) 0);

            int callbacks = (FRAMES + bufferFrames - 1) / bufferFrames;
            int deliveredBytes = callbacks * bufferFrames * out.containerBytes();
            assertEquals(deliveredBytes, sink.captured(0).length, "it stops as soon as the stream is drained");
            assertEquals(deliveredBytes, sink.captured(1).length);
            assertEquals(FRAMES, core.framesPlayed());
            assertEquals(0, core.dropouts());
            assertTrue(core.drained());
        }
    }

    @ParameterizedTest(name = "{0}-bit into {1}")
    @MethodSource("nonFittingPcmPairs")
    void aSourceDeeperThanTheContainerIsRefusedNotConverted(int bits, SampleType out) {
        try (CaptureSink sink = pcmSink(out, 512)) {
            NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                    () -> sink.open(StreamFormat.pcm(96000, bits, 2, FRAMES)));
            assertEquals(bits + "-bit source does not fit " + out.name(), e.getMessage());
            assertEquals(0, sink.opens());
        }
    }

    @Test
    void aMonoSourceReachesBothChannelsUnchanged() throws Exception {
        int[] mono = randomSamples(24, FRAMES, 77);
        StreamFormat fmt = StreamFormat.pcm(48000, 24, 1, FRAMES);
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512)) {
            SinkStream stream = sink.open(fmt);
            stream.core().setSource(fmt);
            assertTrue(stream.core().transform().monoDuplicate());
            stream.core().write(interleaved(mono, null), FRAMES);
            stream.core().endOfInput();
            stream.start();
            sink.pumpUntilDrained(10_000);

            byte[] expected = reference(mono, 24, SampleType.INT32_LSB);
            assertPrefixThenSilence("left", sink.captured(0), expected, (byte) 0);
            assertPrefixThenSilence("right", sink.captured(1), expected, (byte) 0);
        }
    }

    // ---- DSD ------------------------------------------------------------------------------------------------------

    private static int reverse8(int b) {
        int r = 0;
        for (int k = 0; k < 8; k++) {
            if ((b & (1 << k)) != 0) {
                r |= 1 << (7 - k);
            }
        }
        return r;
    }

    @Test
    void dsdBothOrders() throws Exception {
        for (BitOrder srcOrder : BitOrder.values()) {
            for (SampleType sinkType : new SampleType[] {SampleType.DSD_INT8_MSB1, SampleType.DSD_INT8_LSB1}) {
                dsdPipeline(srcOrder, sinkType);
            }
        }
    }

    private void dsdPipeline(BitOrder srcOrder, SampleType sinkType) throws Exception {
        String what = srcOrder + " source into " + sinkType;
        boolean reversed = srcOrder != sinkType.bitOrder();
        byte silence = sinkType == SampleType.DSD_INT8_MSB1 ? (byte) 0x69 : (byte) 0x96;

        // Every byte value appears early on both channels (left ascending, right descending), then random bytes.
        SplittableRandom rnd = new SplittableRandom(99);
        byte[] left = new byte[FRAMES];
        byte[] right = new byte[FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            left[i] = i < 256 ? (byte) i : (byte) rnd.nextInt(256);
            right[i] = i < 256 ? (byte) (255 - i) : (byte) rnd.nextInt(256);
        }
        MemorySegment src = MemorySegment.ofArray(new byte[FRAMES * 2]);
        byte[] expLeft = new byte[FRAMES];
        byte[] expRight = new byte[FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            src.set(BYTE, i * 2L, left[i]);
            src.set(BYTE, i * 2L + 1, right[i]);
            expLeft[i] = reversed ? (byte) reverse8(left[i] & 0xFF) : left[i];
            expRight[i] = reversed ? (byte) reverse8(right[i] & 0xFF) : right[i];
        }

        StreamFormat fmt = StreamFormat.dsd(2822400, 2, FRAMES, srcOrder);
        try (CaptureSink sink = sink(SampleType.INT32_LSB, sinkType, 512)) {
            SinkStream stream = sink.open(fmt);
            assertEquals(sinkType, stream.signalPath().outType(), what);
            stream.core().setSource(fmt);
            assertEquals(reversed, stream.core().transform().bitReverse(), what);
            stream.core().write(src, FRAMES);
            stream.core().endOfInput();
            stream.start();
            sink.pumpUntilDrained(10_000);

            assertPrefixThenSilence(what + " left", sink.captured(0), expLeft, silence);
            assertPrefixThenSilence(what + " right", sink.captured(1), expRight, silence);
            // Hand-checked: byte 0x01 played first-bit-first is 0x80 when the order is flipped.
            assertEquals(reversed ? 0x80 : 0x01, sink.captured(0)[1] & 0xFF, what);
            assertEquals(0, stream.core().dropouts(), what);
            assertEquals(FRAMES, stream.core().framesPlayed(), what);
        }
    }

    // ---- gapless ----------------------------------------------------------------------------------------------------

    @Test
    void gaplessJoin16then24() throws Exception {
        final int framesA = 6000;
        final int framesB = 4000;
        StreamFormat fa = StreamFormat.pcm(44100, 16, 2, framesA);
        StreamFormat fb = StreamFormat.pcm(44100, 24, 2, framesB);
        int[] aLeft = randomSamples(16, framesA, 11);
        int[] aRight = randomSamples(16, framesA, 12);
        int[] bLeft = randomSamples(24, framesB, 13);
        int[] bRight = randomSamples(24, framesB, 14);

        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512)) {
            SinkStream stream = sink.open(fa);
            StreamCore core = stream.core();
            core.setSource(fa);
            assertEquals(16, core.transform().shift());
            core.write(interleaved(aLeft, aRight), framesA);

            assertTrue(stream.accepts(fb), "same rate and channels, and 24 bits fit INT32_LSB");
            core.setSource(fb);
            assertEquals(8, core.transform().shift());
            assertEquals(fb, stream.signalPath().source(), "the signal path follows the track now being decoded");
            assertEquals("24-bit 44.1 kHz → INT32_LSB @ 44100 Hz", stream.signalPath().describe());
            core.write(interleaved(bLeft, bRight), framesB);

            core.endOfInput();
            stream.start();
            sink.pumpUntilDrained(10_000);

            SampleType out = SampleType.INT32_LSB;
            byte[] expLeft = concat(reference(aLeft, 16, out), reference(bLeft, 24, out));
            byte[] expRight = concat(reference(aRight, 16, out), reference(bRight, 24, out));
            assertPrefixThenSilence("left", sink.captured(0), expLeft, (byte) 0);
            assertPrefixThenSilence("right", sink.captured(1), expRight, (byte) 0);
            assertEquals(1, sink.opens(), "gapless: one stream for both tracks");
            assertEquals(framesA + framesB, core.framesPlayed());
            assertEquals(0, core.dropouts(), "no silence was inserted at the join");
        }
    }

    // ---- CaptureSink behaviour --------------------------------------------------------------------------------

    @Test
    void anUnsupportedRateIsRefusedNamingTheSink() {
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512)) {
            NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                    () -> sink.open(StreamFormat.pcm(352800, 24, 2, -1)));
            assertEquals("rate 352800 Hz not supported by Capture", e.getMessage());
            e = assertThrows(NotBitPerfectException.class,
                    () -> sink.open(StreamFormat.dsd(22579200, 2, -1, BitOrder.MSB_FIRST)));
            assertEquals("rate 22579200 Hz not supported by Capture", e.getMessage());
            assertEquals(0, sink.opens());
            assertEquals("Capture", sink.name());
        }
    }

    @Test
    void opensCountsOnlySuccessfulOpens() throws Exception {
        try (CaptureSink sink = pcmSink(SampleType.INT16_LSB, 512)) {
            assertEquals(0, sink.opens());
            sink.open(StreamFormat.pcm(44100, 16, 2, -1));
            assertEquals(1, sink.opens());
            assertThrows(NotBitPerfectException.class, () -> sink.open(StreamFormat.pcm(352800, 16, 2, -1)));
            assertThrows(NotBitPerfectException.class, () -> sink.open(StreamFormat.pcm(44100, 24, 2, -1)));
            assertEquals(1, sink.opens());
            sink.open(StreamFormat.pcm(96000, 16, 2, -1));
            assertEquals(2, sink.opens());
        }
    }

    @Test
    void openAlwaysDisposesThePreviousStream() throws Exception {
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512)) {
            SinkStream first = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
            first.start();
            SinkStream second = sink.open(StreamFormat.pcm(96000, 24, 2, -1));
            assertThrows(IllegalStateException.class, first::start);

            assertThrows(NotBitPerfectException.class, () -> sink.open(StreamFormat.pcm(352800, 24, 2, -1)));
            assertThrows(IllegalStateException.class, second::start); // a refused open still tears the old one down
        }
    }

    @Test
    void closingTheSinkDisposesItsStream() throws Exception {
        CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512);
        SinkStream stream = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        sink.close();
        assertThrows(IllegalStateException.class, stream::start);
        sink.close(); // idempotent
    }

    @Test
    void pumpOnlyRunsWhileTheStreamIsStarted() throws Exception {
        StreamFormat fmt = StreamFormat.pcm(44100, 24, 2, 100);
        int[] left = randomSamples(24, 100, 5);
        int[] right = randomSamples(24, 100, 6);
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 64)) {
            SinkStream stream = sink.open(fmt);
            stream.core().setSource(fmt);
            stream.core().write(interleaved(left, right), 100);
            stream.core().endOfInput();

            sink.pump(3); // opened but not started: the driver is not calling back
            sink.pumpUntilDrained(3);
            assertEquals(0, sink.captured(0).length);
            assertEquals(0, stream.core().framesPlayed());

            stream.start();
            sink.pump(1);
            assertEquals(64 * 4, sink.captured(0).length, "one callback delivers exactly one buffer");
            assertEquals(64, stream.core().framesPlayed());

            stream.stop();
            sink.pump(5);
            assertEquals(64 * 4, sink.captured(0).length, "stopped: no more callbacks");
            assertEquals(64, stream.core().framesPlayed());

            stream.start();
            sink.pumpUntilDrained(10);
            byte[] expected = reference(left, 24, SampleType.INT32_LSB);
            assertPrefixThenSilence("left", sink.captured(0), expected, (byte) 0);
            assertEquals(2 * 64 * 4, sink.captured(0).length);
        }
    }

    @Test
    void pumpWithNoStreamIsANoOp() {
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512)) {
            sink.pump(3);
            sink.pumpUntilDrained(3);
            assertEquals(0, sink.captured(0).length);
            assertEquals(0, sink.captured(1).length);
            assertEquals(0, sink.opens());
        }
    }

    @Test
    void pumpUntilDrainedFailsLoudlyWhenTheStreamNeverEnds() throws Exception {
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 64)) {
            SinkStream stream = sink.open(StreamFormat.pcm(44100, 24, 2, -1));
            stream.core().setSource(StreamFormat.pcm(44100, 24, 2, -1));
            stream.start();
            assertThrows(IllegalStateException.class, () -> sink.pumpUntilDrained(5)); // no endOfInput
            assertEquals(5 * 64 * 4, sink.captured(0).length);
        }
    }

    // 700 frames in 512-frame buffers: buffer 1 full, buffer 2 partly data, buffers 3..8 pure silence. The driver
    // halves alternate, so buffer 3 reuses the memory buffer 1 filled: leftover audio would show in the tail.

    @Test
    void pcmSilenceTailNeverShowsStaleBufferContents() throws Exception {
        int[] left = randomSamples(24, 700, 21);
        int[] right = randomSamples(24, 700, 22);
        StreamFormat fmt = StreamFormat.pcm(44100, 24, 2, 700);
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512)) {
            SinkStream stream = sink.open(fmt);
            stream.core().setSource(fmt);
            stream.core().write(interleaved(left, right), 700);
            stream.core().endOfInput();
            stream.start();
            sink.pump(8);

            assertEquals(8 * 512 * 4, sink.captured(0).length, "pump(8) is eight callbacks, silent ones included");
            assertPrefixThenSilence("left", sink.captured(0), reference(left, 24, SampleType.INT32_LSB), (byte) 0);
            assertPrefixThenSilence("right", sink.captured(1), reference(right, 24, SampleType.INT32_LSB), (byte) 0);
        }
    }

    @Test
    void dsdSilenceTailIsTheTypesSilenceNeverStaleBufferContents() throws Exception {
        for (SampleType dsd : new SampleType[] {SampleType.DSD_INT8_MSB1, SampleType.DSD_INT8_LSB1}) {
            StreamFormat fmt = StreamFormat.dsd(2822400, 2, 700, dsd.bitOrder());
            byte silence = dsd == SampleType.DSD_INT8_MSB1 ? (byte) 0x69 : (byte) 0x96;
            byte[] interleavedDsd = new byte[1400];
            byte[] want = new byte[700];
            for (int i = 0; i < 700; i++) {
                want[i] = (byte) (i % 250 + 1);
                interleavedDsd[2 * i] = want[i];
                interleavedDsd[2 * i + 1] = want[i];
            }
            try (CaptureSink sink = sink(SampleType.INT32_LSB, dsd, 512)) {
                SinkStream stream = sink.open(fmt);
                stream.core().setSource(fmt);
                stream.core().write(MemorySegment.ofArray(interleavedDsd), 700);
                stream.core().endOfInput();
                stream.start();
                sink.pump(8);

                assertEquals(8 * 512, sink.captured(0).length);
                assertPrefixThenSilence(dsd + " left", sink.captured(0), want, silence);
                assertPrefixThenSilence(dsd + " right", sink.captured(1), want, silence);
            }
        }
    }

    @Test
    void anUnderrunBeforeDataArrivesIsCapturedAsSilenceAndCounted() throws Exception {
        StreamFormat fmt = StreamFormat.pcm(44100, 24, 2, 32);
        int[] left = randomSamples(24, 32, 31);
        int[] right = randomSamples(24, 32, 32);
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 32)) {
            SinkStream stream = sink.open(fmt);
            stream.core().setSource(fmt);
            stream.start();
            sink.pump(2); // two callbacks with nothing buffered
            assertEquals(2, stream.core().dropouts());

            stream.core().write(interleaved(left, right), 32);
            sink.pump(1);

            byte[] cap = sink.captured(0);
            assertEquals(3 * 32 * 4, cap.length);
            assertArrayEquals(new byte[2 * 32 * 4], Arrays.copyOf(cap, 2 * 32 * 4));
            assertArrayEquals(reference(left, 24, SampleType.INT32_LSB),
                    Arrays.copyOfRange(cap, 2 * 32 * 4, cap.length));
            assertEquals(2, stream.core().dropouts());
        }
    }

    @Test
    void signalPathReportsTheOpenedStream() throws Exception {
        try (CaptureSink sink = sink(SampleType.INT32_LSB, SampleType.DSD_INT8_MSB1, 512)) {
            SinkStream pcm = sink.open(StreamFormat.pcm(96000, 24, 2, -1));
            SignalPath p = pcm.signalPath();
            assertEquals("Capture", p.deviceName());
            assertEquals(SampleType.INT32_LSB, p.outType());
            assertEquals(96000.0, p.deviceRate());
            assertEquals(512, p.bufferFrames());
            assertTrue(p.rateMatches());
            assertEquals("24-bit 96 kHz → INT32_LSB @ 96000 Hz", p.describe());
            assertEquals(Packer.create(StreamFormat.pcm(96000, 24, 2, -1), SampleType.INT32_LSB).transform(),
                    p.transform());
            assertEquals(Optional.empty(), p.notBitPerfectReason(0));

            SinkStream dsd = sink.open(StreamFormat.dsd(5644800, 2, -1, BitOrder.LSB_FIRST));
            SignalPath d = dsd.signalPath();
            assertEquals(SampleType.DSD_INT8_MSB1, d.outType());
            assertEquals(5644800.0, d.deviceRate());
            assertEquals("DSD128 → native DSD (DSD_INT8_MSB1) @ 5.6448 MHz", d.describe());
            assertTrue(d.transform().bitReverse());
        }
    }

    @Test
    void acceptsOnlyWhatTheOpenStreamCanCarryBitPerfectly() throws Exception {
        try (CaptureSink sink = pcmSink(SampleType.INT16_LSB, 512)) {
            SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
            assertTrue(s.accepts(StreamFormat.pcm(44100, 16, 2, 1000)));
            assertFalse(s.accepts(StreamFormat.pcm(44100, 24, 2, -1)), "24 bits do not fit INT16_LSB");
            assertFalse(s.accepts(StreamFormat.pcm(48000, 16, 2, -1)), "other rate: a new stream is needed");
            assertFalse(s.accepts(StreamFormat.pcm(44100, 16, 1, -1)), "other channel count");
            assertFalse(s.accepts(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST)));
        }
        try (CaptureSink sink = sink(SampleType.INT32_LSB, SampleType.DSD_INT8_MSB1, 512)) {
            SinkStream s = sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST));
            assertTrue(s.accepts(StreamFormat.dsd(2822400, 2, -1, BitOrder.LSB_FIRST)), "reversal is lossless");
            assertFalse(s.accepts(StreamFormat.dsd(5644800, 2, -1, BitOrder.MSB_FIRST)));
            assertFalse(s.accepts(StreamFormat.pcm(44100, 16, 2, -1)));
        }
    }

    @Test
    void constructionAndListenersValidateTheirArguments() {
        assertThrows(NullPointerException.class,
                () -> new CaptureSink(null, SampleType.INT32_LSB, SampleType.DSD_INT8_MSB1, 512, RATES));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureSink("c", SampleType.DSD_INT8_MSB1, SampleType.DSD_INT8_MSB1, 512, RATES));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureSink("c", SampleType.INT32_LSB, SampleType.INT32_LSB, 512, RATES));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureSink("c", SampleType.INT32_LSB, SampleType.DSD_INT8_MSB1, 0, RATES));
        try (CaptureSink sink = pcmSink(SampleType.INT32_LSB, 512)) {
            sink.addDeviceListener(msg -> fail("a capture device cannot be lost"));
            assertThrows(NullPointerException.class, () -> sink.addDeviceListener(null));
            assertThrows(IndexOutOfBoundsException.class, () -> sink.captured(2));
        }
    }
}
