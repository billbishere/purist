package purist.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class StreamCoreTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    /** What a driver buffer holds before fill() touches it: neither 0x00, 0x69 nor 0x96. */
    private static final byte POISON = (byte) 0xA5;

    // ---- helpers -----------------------------------------------------------------------------------

    private static final StreamFormat PCM24 = StreamFormat.pcm(44100, 24, 2, -1);

    /** Left sample of frame {@code i}: positive and never zero, so data cannot be mistaken for silence. */
    private static int left(long i) {
        return (int) (1000 + i * 3) & 0x7FFFFF | 1;
    }

    /** Right sample of frame {@code i}: negative 24-bit. */
    private static int right(long i) {
        return ~left(i);
    }

    /** Interleaved little-endian int32 for frames {@code first .. first+frames-1}, as a decoder emits them. */
    private static MemorySegment decoded(long first, int frames) {
        MemorySegment seg = MemorySegment.ofArray(new byte[frames * 8]);
        for (int i = 0; i < frames; i++) {
            seg.set(LE_INT, i * 8L, left(first + i));
            seg.set(LE_INT, i * 8L + 4, right(first + i));
        }
        return seg;
    }

    /** INT32_LSB bytes of {@code frames} frames of 24-bit samples, computed without the Packer. */
    private static byte[] expected(boolean leftChannel, long first, int frames) {
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            int container = (leftChannel ? left(first + i) : right(first + i)) << 8;
            for (int b = 0; b < 4; b++) {
                out[i * 4 + b] = (byte) (container >> (8 * b));
            }
        }
        return out;
    }

    private static MemorySegment[] poisoned(int bytes) {
        MemorySegment[] segs = new MemorySegment[2];
        for (int c = 0; c < 2; c++) {
            segs[c] = MemorySegment.ofArray(new byte[bytes]);
            segs[c].fill(POISON);
        }
        return segs;
    }

    private static byte[] bytes(MemorySegment seg, long from, long to) {
        return seg.asSlice(from, to - from).toArray(BYTE);
    }

    private static void assertAll(byte expected, MemorySegment seg, long from, long to, String what) {
        byte[] actual = bytes(seg, from, to);
        byte[] want = new byte[actual.length];
        Arrays.fill(want, expected);
        assertArrayEquals(want, actual, what);
    }

    private static void awaitState(Thread t, Thread.State state) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (t.getState() != state) {
            if (System.nanoTime() > deadline) {
                fail("thread " + t.getName() + " is " + t.getState() + ", expected " + state);
            }
            Thread.sleep(1);
        }
    }

    // ---- underflow, end of input, pause ---------------------------------------------------------------

    @Test
    void underflowCountsDropoutAndPadsSilence() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 4096)) {
            core.setSource(PCM24);
            core.write(decoded(0, 100), 100);

            MemorySegment[] out = poisoned(256 * 4);
            core.fill(out, 256);

            assertArrayEquals(expected(true, 0, 100), bytes(out[0], 0, 400));
            assertArrayEquals(expected(false, 0, 100), bytes(out[1], 0, 400));
            assertAll((byte) 0x00, out[0], 400, 1024, "left tail");
            assertAll((byte) 0x00, out[1], 400, 1024, "right tail");
            assertEquals(1, core.dropouts());
            assertEquals(100, core.framesPlayed());
        }
    }

    @Test
    void anEmptyRingIsAWholeBufferOfSilenceAndOneDropout() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 4096)) {
            core.setSource(PCM24);
            MemorySegment[] out = poisoned(64 * 4);
            core.fill(out, 64);

            assertAll((byte) 0x00, out[0], 0, 256, "left");
            assertAll((byte) 0x00, out[1], 0, 256, "right");
            assertEquals(1, core.dropouts());
            assertEquals(0, core.framesPlayed());
        }
    }

    @Test
    void aFullBufferIsNotADropout() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 4096)) {
            core.setSource(PCM24);
            core.write(decoded(0, 256), 256);
            MemorySegment[] out = poisoned(256 * 4);
            core.fill(out, 256);

            assertArrayEquals(expected(true, 0, 256), out[0].toArray(BYTE));
            assertEquals(0, core.dropouts());
            assertEquals(256, core.framesPlayed());
        }
    }

    @Test
    void afterEndOfInputNoDropout() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 4096)) {
            core.setSource(PCM24);
            core.write(decoded(0, 100), 100);
            assertFalse(core.drained(), "not drained before endOfInput");
            core.endOfInput();
            assertFalse(core.drained(), "not drained while data is buffered");

            MemorySegment[] out = poisoned(256 * 4);
            core.fill(out, 256);

            assertArrayEquals(expected(true, 0, 100), bytes(out[0], 0, 400));
            assertAll((byte) 0x00, out[0], 400, 1024, "tail");
            assertEquals(0, core.dropouts());
            assertEquals(100, core.framesPlayed());
            assertTrue(core.drained());

            core.fill(out, 256); // already finished: pure silence, still no dropout
            assertAll((byte) 0x00, out[1], 0, 1024, "after the end");
            assertEquals(0, core.dropouts());
            assertEquals(100, core.framesPlayed());
        }
    }

    /**
     * The boundary of the dropout rule, pinned without timing. The callback's read comes up short; only then does
     * the producer land its final chunk and call endOfInput() (the {@code afterRead} seam runs at exactly that
     * point on the callback's own thread). Silence was inserted ahead of data that arrived late, so this callback
     * is a dropout: the end-of-input flag must be the snapshot taken when the callback began, not a re-read.
     */
    @Test
    void endOfInputArrivingMidCallbackDoesNotHideTheGapBeforeIt() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 4096)) {
            core.setSource(PCM24);
            core.write(decoded(0, 100), 100);

            MemorySegment[] out = poisoned(256 * 4);
            core.fill(out, 256, () -> {
                try {
                    core.write(decoded(100, 50), 50); // the producer's last chunk, a moment too late
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                core.endOfInput();
            });

            assertArrayEquals(expected(true, 0, 100), bytes(out[0], 0, 400));
            assertAll((byte) 0x00, out[0], 400, 1024, "silence inserted before the late chunk");
            assertEquals(1, core.dropouts(), "a fill that padded before end of input is a dropout");
            assertEquals(100, core.framesPlayed());
            assertFalse(core.drained(), "the late chunk is still buffered");

            // The next callback sees the end of input from its first instruction: it plays the late chunk and
            // the padding after it is the natural end, not a second dropout.
            core.fill(out, 256);
            assertArrayEquals(expected(true, 100, 50), bytes(out[0], 0, 200));
            assertAll((byte) 0x00, out[0], 200, 1024, "natural end");
            assertEquals(1, core.dropouts());
            assertEquals(150, core.framesPlayed());
            assertTrue(core.drained());
        }
    }

    @Test
    void pausedOutputsSilenceWithoutDropout() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 4096)) {
            core.setSource(PCM24);
            core.write(decoded(0, 100), 100);

            assertFalse(core.paused());
            core.setPaused(true);
            assertTrue(core.paused());
            MemorySegment[] out = poisoned(100 * 4);
            core.fill(out, 100);
            core.fill(out, 100);

            assertAll((byte) 0x00, out[0], 0, 400, "left while paused");
            assertAll((byte) 0x00, out[1], 0, 400, "right while paused");
            assertEquals(0, core.dropouts(), "pause is not a dropout");
            assertEquals(0, core.framesPlayed(), "silence is not played data");
            assertFalse(core.drained());

            core.setPaused(false);
            core.fill(out, 100); // the ring was left alone: the 100 frames are still there, in order
            assertArrayEquals(expected(true, 0, 100), out[0].toArray(BYTE));
            assertArrayEquals(expected(false, 0, 100), out[1].toArray(BYTE));
            assertEquals(0, core.dropouts());
            assertEquals(100, core.framesPlayed());
        }
    }

    @Test
    void pausedAfterEndOfInputIsStillNotDrained() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 4096)) {
            core.setSource(PCM24);
            core.write(decoded(0, 10), 10);
            core.endOfInput();
            core.setPaused(true);
            core.fill(poisoned(40), 10);
            assertFalse(core.drained());
            core.setPaused(false);
            core.fill(poisoned(40), 10);
            assertTrue(core.drained());
        }
    }

    // ---- silence bytes ----------------------------------------------------------------------------------

    @Test
    void dsdSilenceBytes() throws Exception {
        assertEquals(0x69, silenceOf(SampleType.DSD_INT8_MSB1) & 0xFF);
        assertEquals(0x96, silenceOf(SampleType.DSD_INT8_LSB1) & 0xFF);
        for (SampleType pcm : new SampleType[] {SampleType.INT16_LSB, SampleType.INT24_LSB, SampleType.INT32_LSB,
                SampleType.INT32_LSB16, SampleType.INT32_LSB18, SampleType.INT32_LSB20, SampleType.INT32_LSB24}) {
            assertEquals(0x00, silenceOf(pcm), pcm.name());
        }
    }

    private static byte silenceOf(SampleType type) {
        try (StreamCore core = new StreamCore(type, 16, 64)) {
            assertEquals(type, core.outType());
            return core.silenceByte();
        }
    }

    @Test
    void dsdUnderflowPadsWithTheTypesOwnSilenceNotZeros() throws Exception {
        for (SampleType type : new SampleType[] {SampleType.DSD_INT8_MSB1, SampleType.DSD_INT8_LSB1}) {
            BitOrder order = type.bitOrder();
            byte silence = type == SampleType.DSD_INT8_MSB1 ? (byte) 0x69 : (byte) 0x96;
            try (StreamCore core = new StreamCore(type, 64, 256)) {
                core.setSource(StreamFormat.dsd(2822400, 2, -1, order));
                MemorySegment src = MemorySegment.ofArray(new byte[] {
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20});
                core.write(src, 10); // 10 DSD frames = 20 interleaved bytes

                MemorySegment[] out = poisoned(32);
                core.fill(out, 32);

                assertArrayEquals(new byte[] {1, 3, 5, 7, 9, 11, 13, 15, 17, 19}, bytes(out[0], 0, 10), type.name());
                assertArrayEquals(new byte[] {2, 4, 6, 8, 10, 12, 14, 16, 18, 20}, bytes(out[1], 0, 10), type.name());
                assertAll(silence, out[0], 10, 32, type.name() + " left tail");
                assertAll(silence, out[1], 10, 32, type.name() + " right tail");
                assertEquals(1, core.dropouts());
                assertEquals(10, core.framesPlayed());

                core.setPaused(true);
                core.fill(out, 32);
                assertAll(silence, out[0], 0, 32, type.name() + " paused");
                assertAll(silence, out[1], 0, 32, type.name() + " paused");
            }
        }
    }

    // ---- write: backpressure and interruption -----------------------------------------------------------

    @Test
    @Timeout(30)
    void writeBlocksWhenFullAndResumes() throws Exception {
        final int total = 3000;
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 256, 1024)) {
            core.setSource(PCM24);
            AtomicBoolean done = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    core.write(decoded(0, total), total);
                    done.set(true);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-producer");
            producer.start();

            // 1024 of the 3000 frames fit; the producer must park for the rest while nobody consumes.
            awaitState(producer, Thread.State.TIMED_WAITING);
            Thread.sleep(50);
            assertFalse(done.get(), "write returned although the ring cannot hold the data");
            assertEquals(Thread.State.TIMED_WAITING, producer.getState());

            // The consumer drains: the producer resumes and every frame arrives in order.
            byte[] gotLeft = new byte[total * 4];
            byte[] gotRight = new byte[total * 4];
            MemorySegment[] out = poisoned(256 * 4);
            int consumed = 0;
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (consumed < total) {
                if (System.nanoTime() > deadline) {
                    fail("consumed only " + consumed + " of " + total + " frames");
                }
                long before = core.framesPlayed();
                core.fill(out, 256);
                int got = (int) (core.framesPlayed() - before);
                System.arraycopy(bytes(out[0], 0, got * 4L), 0, gotLeft, consumed * 4, got * 4);
                System.arraycopy(bytes(out[1], 0, got * 4L), 0, gotRight, consumed * 4, got * 4);
                consumed += got;
                if (got == 0) {
                    Thread.sleep(1);
                }
            }
            producer.join(5000);
            assertFalse(producer.isAlive());
            assertNull(failure.get());
            assertTrue(done.get());
            assertArrayEquals(expected(true, 0, total), gotLeft);
            assertArrayEquals(expected(false, 0, total), gotRight);
        }
    }

    @Test
    @Timeout(30)
    void writeRespondsToInterruption() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 256, 1024)) {
            core.setSource(PCM24);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    core.write(decoded(0, 3000), 3000);
                } catch (Throwable t) {
                    thrown.set(t);
                }
            }, "test-producer");
            producer.start();
            awaitState(producer, Thread.State.TIMED_WAITING);

            producer.interrupt();
            producer.join(5000);

            assertFalse(producer.isAlive(), "a parked write must wake up when interrupted");
            assertTrue(thrown.get() instanceof InterruptedException, String.valueOf(thrown.get()));
        }
    }

    @Test
    @Timeout(30)
    void cancelWriteEndsABlockedWriteAndReportsWhatWasQueued() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 256, 1024)) {
            core.setSource(PCM24);
            AtomicReference<Object> result = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    result.set(core.write(decoded(0, 3000), 3000));
                } catch (Throwable t) {
                    result.set(t);
                }
            }, "test-producer");
            producer.start();
            awaitState(producer, Thread.State.TIMED_WAITING); // 1024 frames queued, waiting for room

            core.cancelWrite();
            producer.join(5000);

            assertFalse(producer.isAlive(), "a write waiting for room must return when cancelled");
            assertEquals(1024, result.get(), "the frames queued before the cancel");
            // Nothing is lost: the caller writes the rest itself, and the consumer sees one unbroken stream.
            MemorySegment[] out = poisoned(256 * 4);
            for (int first = 0; first < 1024; first += 256) {
                core.fill(out, 256);
                assertArrayEquals(expected(true, first, 256), out[0].toArray(BYTE), "left from " + first);
                assertArrayEquals(expected(false, first, 256), out[1].toArray(BYTE), "right from " + first);
            }
            assertEquals(976, core.write(decoded(1024, 976), 976), "the cancel was consumed");
            for (int first = 1024; first < 2000; first += 244) {
                core.fill(out, 244);
                assertArrayEquals(expected(true, first, 244), bytes(out[0], 0, 976), "left from " + first);
                assertArrayEquals(expected(false, first, 244), bytes(out[1], 0, 976), "right from " + first);
            }
            assertEquals(0, core.dropouts());
        }
    }

    @Test
    void aCancelWithNoWriteWaitingEndsTheNextWriteBeforeItQueuesAnything() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 256, 4096)) {
            core.setSource(PCM24);
            core.cancelWrite();
            assertEquals(0, core.write(decoded(0, 100), 100), "returns before its first chunk");
            MemorySegment[] out = poisoned(256 * 4);
            core.fill(out, 100);
            assertEquals(0, core.framesPlayed(), "nothing was queued");

            assertEquals(100, core.write(decoded(0, 100), 100), "the next write runs normally");
            core.fill(out, 100);
            assertArrayEquals(expected(true, 0, 100), bytes(out[0], 0, 400));
        }
    }

    @Test
    void writeLargerThanTheStagingChunkSplitsAndStaysInOrder() throws Exception {
        final int total = 10_000; // > 4096 frames per chunk
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 512, 16384)) {
            core.setSource(PCM24);
            core.write(decoded(0, total), total);
            core.endOfInput();

            byte[] gotLeft = new byte[total * 4];
            MemorySegment[] out = poisoned(512 * 4);
            int pos = 0;
            while (!core.drained()) {
                long before = core.framesPlayed();
                core.fill(out, 512);
                int got = (int) (core.framesPlayed() - before);
                System.arraycopy(bytes(out[0], 0, got * 4L), 0, gotLeft, pos * 4, got * 4);
                pos += got;
            }
            assertEquals(total, pos);
            assertArrayEquals(expected(true, 0, total), gotLeft);
            assertEquals(0, core.dropouts());
        }
    }

    @Test
    @Timeout(60)
    void concurrentProducerAndConsumerPreserveEveryFrame() throws Exception {
        final long total = 120_000;
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 256, 1000)) {
            core.setSource(PCM24);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    SplittableRandom rnd = new SplittableRandom(7);
                    long next = 0;
                    while (next < total) {
                        int n = (int) Math.min(total - next, 1 + rnd.nextInt(3000));
                        core.write(decoded(next, n), n);
                        next += n;
                    }
                    core.endOfInput();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "test-producer");
            producer.start();

            // The consumer runs flat out, so it underruns whenever the producer is behind. Only the frames
            // fill() reports as played are checked: they must be exactly frame 0, 1, 2, ... in order.
            MemorySegment[] out = poisoned(256 * 4);
            long expectedFrame = 0;
            long deadline = System.nanoTime() + 50_000_000_000L;
            while (!core.drained()) {
                if (System.nanoTime() > deadline) {
                    fail("not drained after " + expectedFrame + " frames; producer failure " + failure.get());
                }
                long before = core.framesPlayed();
                core.fill(out, 256);
                int got = (int) (core.framesPlayed() - before);
                for (int i = 0; i < got; i++, expectedFrame++) {
                    if (out[0].get(LE_INT, i * 4L) != left(expectedFrame) << 8
                            || out[1].get(LE_INT, i * 4L) != right(expectedFrame) << 8) {
                        fail("frame " + expectedFrame + " arrived out of order, damaged, or repeated");
                    }
                }
                if (got == 0) {
                    Thread.onSpinWait();
                }
            }
            producer.join(5000);
            assertNull(failure.get());
            assertEquals(total, expectedFrame);
            assertEquals(total, core.framesPlayed());
        }
    }

    // ---- sources and transforms -------------------------------------------------------------------------

    @Test
    void transformFollowsTheLatestSource() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(StreamFormat.pcm(44100, 16, 2, -1));
            assertEquals(16, core.transform().shift());
            core.setSource(StreamFormat.pcm(44100, 24, 2, -1));
            assertEquals(8, core.transform().shift());
            assertEquals(24, core.source().bits());
        }
    }

    @Test
    void aDeviceWithFewerValidBitsThanItsTypeRefusesDeeperSources() throws Exception {
        // INT32_LSB packs left-justified into 32 bits; a device initialised with 24 valid bits keeps only 24 of them.
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256, 24)) {
            core.setSource(StreamFormat.pcm(44100, 24, 2, -1));
            NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                    () -> core.setSource(StreamFormat.pcm(44100, 32, 2, -1)));
            assertEquals("32-bit source is deeper than the device's 24 valid bits and would lose its low bits",
                    e.getMessage());
            assertEquals(24, core.source().bits(), "the previous source stays in effect");
            core.setSource(StreamFormat.pcm(44100, 16, 2, -1));
            assertEquals(16, core.transform().shift());
        }
    }

    @Test
    void withoutAnExplicitLimitTheSampleTypesOwnValidBitsApply() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB24, 64, 256)) {
            core.setSource(StreamFormat.pcm(44100, 24, 2, -1));
            assertThrows(NotBitPerfectException.class, () -> core.setSource(StreamFormat.pcm(44100, 32, 2, -1)));
        }
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(StreamFormat.pcm(44100, 32, 2, -1));
            assertEquals(0, core.transform().shift());
        }
        assertThrows(IllegalArgumentException.class, () -> new StreamCore(SampleType.INT32_LSB, 64, 256, 0));
    }

    @Test
    void aRefusedSourceLeavesThePreviousOneInPlace() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT24_LSB, 64, 256)) {
            core.setSource(StreamFormat.pcm(44100, 16, 2, -1));
            NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                    () -> core.setSource(StreamFormat.pcm(44100, 32, 2, -1)));
            assertEquals("32-bit source does not fit INT24_LSB", e.getMessage());
            assertEquals(16, core.source().bits());
            assertEquals(8, core.transform().shift());
        }
    }

    @Test
    void writeAndTransformNeedASource() {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            assertThrows(IllegalStateException.class, () -> core.write(decoded(0, 4), 4));
            assertThrows(IllegalStateException.class, core::transform);
            assertThrows(IllegalStateException.class, core::source);
        }
    }

    @Test
    void writeValidatesItsArguments() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(PCM24);
            assertThrows(IllegalArgumentException.class, () -> core.write(decoded(0, 4), -1));
            assertThrows(IllegalArgumentException.class, () -> core.write(decoded(0, 4), 5)); // short source
            core.write(decoded(0, 4), 0); // a zero-frame write is a no-op
            MemorySegment[] out = poisoned(16);
            core.fill(out, 4);
            assertEquals(0, core.framesPlayed(), "rejected writes put nothing in the ring");
        }
    }

    @Test
    void aSampleBeyondTheDeclaredDepthIsRejectedAndNeverReachesTheRing() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(PCM24);
            MemorySegment bad = decoded(0, 8);
            bad.set(LE_INT, 5 * 8L + 4, 1 << 24); // right channel of frame 5 needs 26 bits
            assertThrows(IllegalArgumentException.class, () -> core.write(bad, 8));

            MemorySegment[] out = poisoned(32);
            core.fill(out, 8);
            assertEquals(0, core.framesPlayed(), "the chunk that failed to pack was not queued");
        }
    }

    // ---- reset ---------------------------------------------------------------------------------------------

    @Test
    void resetDiscardsBufferedAudioAndStartsANewSession() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(PCM24);
            core.write(decoded(0, 100), 100);
            MemorySegment[] out = poisoned(64 * 4);
            core.fill(out, 40);
            core.endOfInput();
            core.fill(out, 64); // 60 left: plays them, no dropout after the end
            assertTrue(core.drained());
            core.write(decoded(0, 10), 10);
            assertEquals(100, core.framesPlayed());

            core.reset();

            assertFalse(core.drained(), "end of input is cleared");
            assertEquals(0, core.framesPlayed(), "position restarts");
            core.fill(out, 8);
            assertAll((byte) 0x00, out[0], 0, 32, "the 10 queued frames are gone");
            assertEquals(1, core.dropouts(), "an empty ring after reset underruns again");

            core.write(decoded(500, 8), 8);
            core.fill(out, 8);
            assertArrayEquals(expected(true, 500, 8), bytes(out[0], 0, 32));
        }
    }

    @Test
    void dropoutsAreCumulativeAcrossReset() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(PCM24);
            core.fill(poisoned(16), 4);
            core.fill(poisoned(16), 4);
            assertEquals(2, core.dropouts());
            core.reset();
            assertEquals(2, core.dropouts(), "the player diffs this against its own per-track baseline");
        }
    }

    @Test
    void resetIsRefusedWhileTheConsumerIsInsideFill() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(PCM24);
            // Simulate a driver callback in flight by raising the marker fill() raises around its work.
            Field inFill = StreamCore.class.getDeclaredField("inFill");
            inFill.setAccessible(true);
            inFill.setBoolean(core, true);
            assertThrows(IllegalStateException.class, core::reset);
            inFill.setBoolean(core, false);
            core.reset(); // the consumer has stopped: now legal
        }
    }

    // ---- construction and fill arguments --------------------------------------------------------------------

    @Test
    void constructionWarmsTheConsumerPathWithoutLeavingATrace() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            assertEquals(0, core.dropouts());
            assertEquals(0, core.framesPlayed());
            assertFalse(core.paused());
            assertFalse(core.drained());

            // The warm-up's frame of silence must not be left in the ring ahead of real data.
            core.setSource(PCM24);
            core.write(decoded(0, 4), 4);
            MemorySegment[] out = poisoned(16);
            core.fill(out, 4);
            assertArrayEquals(expected(true, 0, 4), out[0].toArray(BYTE));
            assertEquals(0, core.dropouts());
        }
    }

    @Test
    void fillValidatesItsArguments() throws Exception {
        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256)) {
            core.setSource(PCM24);
            assertThrows(IllegalArgumentException.class, () -> core.fill(poisoned(65 * 4), 65)); // > maxFillFrames
            assertThrows(IllegalArgumentException.class, () -> core.fill(poisoned(16), -1));
            assertThrows(IllegalArgumentException.class,
                    () -> core.fill(new MemorySegment[] {MemorySegment.ofArray(new byte[16])}, 4)); // one channel
        }
    }

    @Test
    void constructorValidatesItsArguments() {
        assertThrows(NullPointerException.class, () -> new StreamCore(null, 64, 256));
        assertThrows(IllegalArgumentException.class, () -> new StreamCore(SampleType.INT32_LSB, 0, 256));
        assertThrows(IllegalArgumentException.class, () -> new StreamCore(SampleType.INT32_LSB, 64, 0));
    }

    @Test
    void closeIsIdempotent() {
        StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 256);
        core.close();
        core.close();
    }

    // ---- no allocation in fill -----------------------------------------------------------------------------------

    /**
     * A tripwire, not a proof (see RingBufferTest): fill() must allocate nothing on its full,
     * underflow-with-padding and paused paths. The JIT is still compiling during the first stretch, and
     * interpreted or C1 code may box a value, so the test measures successive windows and passes as soon as
     * one is allocation-free. A fill() that really allocated would dirty every window.
     */
    @Test
    void fillAllocatesNothing() throws Exception {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        try (StreamCore core = new StreamCore(SampleType.INT32_LSB, 64, 4096)) {
            core.setSource(PCM24);
            MemorySegment src = decoded(0, 40);
            MemorySegment[] out = poisoned(64 * 4);

            // write() is the producer's and may allocate, so only the fills are inside a measured window.
            for (int i = 0; i < 300_000; i++) { // warm-up: JIT and linkage
                core.write(src, 40);
                fillThreeWays(core, out);
            }
            long smallest = Long.MAX_VALUE;
            for (int window = 0; window < 30 && smallest != 0; window++) {
                long allocated = 0;
                for (int i = 0; i < 100_000; i++) {
                    core.write(src, 40);
                    long before = threads.getCurrentThreadAllocatedBytes();
                    fillThreeWays(core, out);
                    allocated += threads.getCurrentThreadAllocatedBytes() - before;
                }
                smallest = Math.min(smallest, allocated);
            }
            assertEquals(0, smallest, "fewest bytes allocated by 100000 rounds of three fill() calls in any window");
        }
    }

    private static void fillThreeWays(StreamCore core, MemorySegment[] out) {
        core.fill(out, 40);  // exactly what was written: the full-buffer path
        core.fill(out, 64);  // ring empty: the underflow and padding path
        core.setPaused(true);
        core.fill(out, 64);  // the paused path
        core.setPaused(false);
    }
}
