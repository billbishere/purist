package purist.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RingBufferTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    // ---- helpers -----------------------------------------------------------------------------------

    private static MemorySegment[] planar(int channels, long bytesEach) {
        MemorySegment[] segs = new MemorySegment[channels];
        for (int c = 0; c < channels; c++) {
            segs[c] = MemorySegment.ofArray(new byte[(int) bytesEach]);
        }
        return segs;
    }

    /** The byte that frame {@code frame}, byte {@code b} of channel {@code c} holds in these tests. */
    private static byte pattern(int c, long frame, int b) {
        return (byte) (frame * 31 + b * 7 + c * 101 + 13);
    }

    /** Fills {@code frames} frames of every channel, offset 0, with the pattern starting at frame {@code first}. */
    private static void fill(MemorySegment[] planar, long first, int frames, int bps) {
        for (int c = 0; c < planar.length; c++) {
            for (int i = 0; i < frames; i++) {
                for (int b = 0; b < bps; b++) {
                    planar[c].set(BYTE, (long) i * bps + b, pattern(c, first + i, b));
                }
            }
        }
    }

    /** Asserts every channel holds the pattern for frames {@code first..first+frames-1} from {@code offset}. */
    private static void assertPattern(MemorySegment[] planar, long offset, long first, int frames, int bps) {
        for (int c = 0; c < planar.length; c++) {
            byte[] expected = new byte[frames * bps];
            for (int i = 0; i < frames; i++) {
                for (int b = 0; b < bps; b++) {
                    expected[i * bps + b] = pattern(c, first + i, b);
                }
            }
            byte[] actual = planar[c].asSlice(offset, expected.length).toArray(BYTE);
            assertArrayEquals(expected, actual, "channel " + c);
        }
    }

    // ---- single-thread behaviour --------------------------------------------------------------------

    @Test
    void writeReadRoundTrip() {
        try (RingBuffer rb = new RingBuffer(2, 16, 4)) {
            assertEquals(16, rb.freeFrames());
            assertEquals(0, rb.usedFrames());

            MemorySegment[] in = planar(2, 5 * 4);
            fill(in, 0, 5, 4);
            rb.write(in, 5);
            assertEquals(11, rb.freeFrames());
            assertEquals(5, rb.usedFrames());

            MemorySegment[] out = planar(2, 5 * 4);
            assertEquals(5, rb.read(out, 0, 5));
            assertPattern(out, 0, 0, 5, 4);
            assertEquals(16, rb.freeFrames());
            assertEquals(0, rb.usedFrames());
        }
    }

    @Test
    void wrapAround() {
        // 24-bit samples so a frame is not a power of two bytes; 7 frames into a 10-frame ring wraps on the
        // second write (3 frames to the end, 4 to the start).
        try (RingBuffer rb = new RingBuffer(2, 10, 3)) {
            MemorySegment[] in = planar(2, 7 * 3);
            MemorySegment[] out = planar(2, 7 * 3);

            fill(in, 0, 7, 3);
            rb.write(in, 7);
            assertEquals(7, rb.read(out, 0, 7));
            assertPattern(out, 0, 0, 7, 3);

            fill(in, 7, 7, 3);
            rb.write(in, 7);
            assertEquals(7, rb.usedFrames());
            assertEquals(7, rb.read(out, 0, 7));
            assertPattern(out, 0, 7, 7, 3);
            assertEquals(10, rb.freeFrames());
        }
    }

    @Test
    void readWrapsWhileWriteDoesNot() {
        // Leave the read position mid-ring, then write exactly up to the end and read across it in one call.
        try (RingBuffer rb = new RingBuffer(2, 10, 2)) {
            MemorySegment[] in = planar(2, 8 * 2);
            MemorySegment[] out = planar(2, 8 * 2);

            fill(in, 0, 6, 2);
            rb.write(in, 6);
            assertEquals(6, rb.read(out, 0, 6));

            fill(in, 6, 8, 2); // positions 6..9 then 0..3
            rb.write(in, 8);
            assertEquals(8, rb.read(out, 0, 8));
            assertPattern(out, 0, 6, 8, 2);
        }
    }

    @Test
    void partialReadWhenShort() {
        try (RingBuffer rb = new RingBuffer(2, 16, 4)) {
            MemorySegment[] in = planar(2, 3 * 4);
            fill(in, 0, 3, 4);
            rb.write(in, 3);

            MemorySegment[] out = planar(2, 8 * 4);
            for (MemorySegment seg : out) {
                seg.fill((byte) 0x7E);
            }
            assertEquals(3, rb.read(out, 0, 8));
            assertPattern(out, 0, 0, 3, 4);
            for (MemorySegment seg : out) { // nothing past the copied frames may be touched
                for (long i = 3 * 4; i < 8 * 4; i++) {
                    assertEquals((byte) 0x7E, seg.get(BYTE, i));
                }
            }
            assertEquals(0, rb.read(out, 0, 8));
            assertEquals(0, rb.usedFrames());
        }
    }

    @Test
    void readHonoursDestinationOffset() {
        try (RingBuffer rb = new RingBuffer(2, 8, 4)) {
            MemorySegment[] in = planar(2, 4 * 4);
            fill(in, 0, 4, 4);
            rb.write(in, 4);

            MemorySegment[] out = planar(2, 12 + 4 * 4);
            for (MemorySegment seg : out) {
                seg.fill((byte) 0x55);
            }
            assertEquals(4, rb.read(out, 12, 4));
            assertPattern(out, 12, 0, 4, 4);
            for (MemorySegment seg : out) {
                for (long i = 0; i < 12; i++) {
                    assertEquals((byte) 0x55, seg.get(BYTE, i));
                }
            }
        }
    }

    @Test
    void fillsToExactCapacity() {
        try (RingBuffer rb = new RingBuffer(2, 8, 2)) {
            MemorySegment[] in = planar(2, 8 * 2);
            fill(in, 0, 8, 2);
            rb.write(in, 8);
            assertEquals(0, rb.freeFrames());
            assertEquals(8, rb.usedFrames());

            assertThrows(IllegalStateException.class, () -> rb.write(in, 1));

            MemorySegment[] out = planar(2, 8 * 2);
            assertEquals(8, rb.read(out, 0, 8));
            assertPattern(out, 0, 0, 8, 2);
        }
    }

    @Test
    void writeBeyondFreeThrowsAndChangesNothing() {
        try (RingBuffer rb = new RingBuffer(2, 8, 2)) {
            MemorySegment[] in = planar(2, 9 * 2);
            fill(in, 0, 5, 2);
            rb.write(in, 5);

            assertThrows(IllegalStateException.class, () -> rb.write(in, 4)); // only 3 free
            assertEquals(5, rb.usedFrames());
            assertEquals(3, rb.freeFrames());

            MemorySegment[] out = planar(2, 5 * 2);
            assertEquals(5, rb.read(out, 0, 5));
            assertPattern(out, 0, 0, 5, 2);
        }
    }

    @Test
    void zeroFrameCallsAreNoOps() {
        try (RingBuffer rb = new RingBuffer(2, 8, 2)) {
            MemorySegment[] seg = planar(2, 0);
            rb.write(seg, 0);
            assertEquals(0, rb.read(seg, 0, 0));
            assertEquals(8, rb.freeFrames());
        }
    }

    @Test
    void clearDropsBufferedFramesAndKeepsWorking() {
        try (RingBuffer rb = new RingBuffer(2, 10, 2)) {
            MemorySegment[] in = planar(2, 6 * 2);
            MemorySegment[] out = planar(2, 6 * 2);
            fill(in, 0, 6, 2);
            rb.write(in, 6);
            assertEquals(2, rb.read(out, 0, 2));

            rb.clear();
            assertEquals(0, rb.usedFrames());
            assertEquals(10, rb.freeFrames());
            assertEquals(0, rb.read(out, 0, 6));

            // Positions are mid-ring now; a wrapping write after clear() must still round-trip.
            fill(in, 100, 6, 2);
            rb.write(in, 6);
            rb.write(in, 4);
            assertEquals(10, rb.usedFrames());
            assertEquals(6, rb.read(out, 0, 6));
            assertPattern(out, 0, 100, 6, 2);
        }
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer(0, 8, 4));
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer(2, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer(2, 8, 0));

        try (RingBuffer rb = new RingBuffer(2, 8, 4)) {
            MemorySegment[] in = planar(2, 4 * 4);
            fill(in, 0, 4, 4);

            assertThrows(IllegalArgumentException.class, () -> rb.write(in, -1));
            assertThrows(IllegalArgumentException.class, () -> rb.write(planar(1, 4 * 4), 4)); // too few channels
            assertThrows(IllegalArgumentException.class, () -> rb.write(planar(2, 3 * 4), 4)); // too short
            assertEquals(0, rb.usedFrames());

            rb.write(in, 4);
            assertThrows(IllegalArgumentException.class, () -> rb.read(planar(2, 4 * 4), 0, -1));
            assertThrows(IllegalArgumentException.class, () -> rb.read(planar(2, 4 * 4), -1, 4));
            assertThrows(IllegalArgumentException.class, () -> rb.read(planar(1, 4 * 4), 0, 4)); // too few channels
            assertThrows(IllegalArgumentException.class, () -> rb.read(planar(2, 3 * 4), 0, 4)); // too short
            assertThrows(IllegalArgumentException.class, () -> rb.read(planar(2, 4 * 4), 4, 4)); // offset leaves 12
            assertEquals(4, rb.usedFrames()); // a refused read consumes nothing
        }
    }

    @Test
    void closeIsIdempotent() {
        RingBuffer rb = new RingBuffer(2, 8, 4);
        rb.close();
        assertDoesNotThrow(rb::close);
    }

    // ---- the real-time contract ---------------------------------------------------------------------

    /**
     * A tripwire, not a proof: after warm-up it fails on any allocation the JIT cannot scalar-replace (an
     * escaping object, a boxed value, an array, an exception). A short-lived object that escape analysis
     * removes is invisible to it; the absence of those is a matter of reading the code.
     *
     * <p>When other test classes have run first in the same JVM, C2 may still be compiling or recompiling
     * these methods after the warm-up, and that transition can allocate a few hundred bytes once. So, as in
     * {@code StreamCoreTest.fillAllocatesNothing}, it measures successive windows and passes as soon as one is
     * allocation-free; a real per-call allocation dirties every window.
     */
    @Test
    void readAndWriteAllocateNothing() {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        try (RingBuffer rb = new RingBuffer(2, 64, 4)) {
            MemorySegment[] in = planar(2, 24 * 4);
            MemorySegment[] out = planar(2, 24 * 4);
            fill(in, 0, 24, 4);

            // 24 frames into 64 wraps often. Warm up so the JIT and VarHandle linkage have run.
            for (int i = 0; i < 300_000; i++) {
                rb.write(in, 24);
                rb.read(out, 0, 24);
            }

            long smallest = Long.MAX_VALUE;
            for (int window = 0; window < 20 && smallest != 0; window++) {
                long before = threads.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < 200_000; i++) {
                    rb.write(in, 24);
                    rb.read(out, 0, 24);
                }
                smallest = Math.min(smallest, threads.getCurrentThreadAllocatedBytes() - before);
            }
            assertEquals(0, smallest, "fewest bytes allocated by 200000 write+read cycles in any window");
        }
    }

    // ---- two threads --------------------------------------------------------------------------------

    private static void backoff(int emptySpins) {
        if ((emptySpins & 0x3F) == 0x3F) {
            Thread.yield();
        } else {
            Thread.onSpinWait();
        }
    }

    @Test
    void concurrentProducerConsumer() throws InterruptedException {
        final int total = 1_000_000;
        final int capacity = 4096;
        final long staging = 8; // consumer reads into a non-zero offset to exercise dstOffsetBytes
        final RingBuffer rb = new RingBuffer(2, capacity, 4);

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong produced = new AtomicLong();
        final AtomicLong consumed = new AtomicLong();
        final AtomicBoolean stop = new AtomicBoolean();

        // Channel 0 carries the frame index, channel 1 its bitwise complement, both as little-endian int32,
        // so a lost, repeated, reordered or torn frame is visible on either channel.
        Thread producer = new Thread(() -> {
            try {
                SplittableRandom rnd = new SplittableRandom(1);
                MemorySegment[] buf = planar(2, 300 * 4);
                int next = 0;
                while (next < total) {
                    int n = Math.min(total - next, 1 + rnd.nextInt(300));
                    for (int i = 0; i < n; i++) {
                        buf[0].set(LE_INT, i * 4L, next + i);
                        buf[1].set(LE_INT, i * 4L, ~(next + i));
                    }
                    int spins = 0;
                    while (rb.freeFrames() < n) {
                        if (stop.get()) {
                            return;
                        }
                        backoff(++spins);
                    }
                    rb.write(buf, n);
                    next += n;
                    produced.set(next);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                stop.set(true);
            }
        }, "ring-producer");

        Thread consumer = new Thread(() -> {
            try {
                SplittableRandom rnd = new SplittableRandom(2);
                MemorySegment[] buf = planar(2, staging + 257 * 4);
                int expected = 0;
                int spins = 0;
                while (expected < total) {
                    int got = rb.read(buf, staging, 1 + rnd.nextInt(257));
                    if (got == 0) {
                        if (stop.get()) {
                            return;
                        }
                        backoff(++spins);
                        continue;
                    }
                    for (int i = 0; i < got; i++) {
                        int a = buf[0].get(LE_INT, staging + i * 4L);
                        int b = buf[1].get(LE_INT, staging + i * 4L);
                        if (a != expected + i || b != ~(expected + i)) {
                            throw new AssertionError("frame " + (expected + i) + " arrived as ch0=" + a
                                    + " ch1=" + b + " (expected " + (expected + i) + " / " + ~(expected + i) + ")");
                        }
                    }
                    expected += got;
                    consumed.set(expected);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                stop.set(true);
            }
        }, "ring-consumer");

        producer.setDaemon(true);
        consumer.setDaemon(true);
        producer.start();
        consumer.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        producer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        consumer.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
        boolean timedOut = producer.isAlive() || consumer.isAlive();
        if (timedOut) {
            stop.set(true);
            producer.join(5_000);
            consumer.join(5_000);
        }

        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertFalse(timedOut, () -> "timed out after 60 s: produced " + produced + ", consumed " + consumed);
        assertEquals(total, produced.get());
        assertEquals(total, consumed.get());
        assertEquals(0, rb.usedFrames());
        assertEquals(capacity, rb.freeFrames());
        rb.close();
    }
}
