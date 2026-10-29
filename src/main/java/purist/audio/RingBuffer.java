package purist.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A single-producer, single-consumer ring of already-packed driver-native samples, one off-heap region per
 * channel ("planar"). The decoder thread {@link #write writes} and the ASIO driver callback thread
 * {@link #read reads}; neither side ever blocks, takes a lock or allocates, so the consumer can run inside a
 * native upcall.
 *
 * <p>Frame {@code i} of channel {@code c} lives at byte {@code (i mod capacityFrames) * bytesPerSample} of
 * region {@code c}. Two monotonically increasing {@code long} positions are the only shared state: the
 * producer publishes {@code writePos} with release semantics after it has copied the samples in, and the
 * consumer publishes {@code readPos} with release semantics after it has copied them out. Each side reads
 * the other's position with acquire semantics, which orders the sample copies against the hand-over: the
 * consumer cannot see a frame before its bytes are in place, and the producer cannot overwrite a slot before
 * the consumer is done reading it. Positions never wrap in practice (2^63 frames), so {@code write - read}
 * is always the number of buffered frames.
 *
 * <p>Thread roles: {@link #write} and {@link #freeFrames} belong to the producer, {@link #read} to the
 * consumer. {@link #usedFrames} and {@link #freeFrames} are safe from any thread but are then only a snapshot.
 * {@link #clear} and {@link #close} are control operations; see their notes.
 */
public final class RingBuffer implements AutoCloseable {

    private static final VarHandle WRITE_POS;
    private static final VarHandle READ_POS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            WRITE_POS = lookup.findVarHandle(RingBuffer.class, "writePos", long.class);
            READ_POS = lookup.findVarHandle(RingBuffer.class, "readPos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int channels;
    private final int capacity;
    private final int bytesPerSample;
    private final Arena arena;
    private final MemorySegment[] regions;

    // Frames ever written / read. Touched only through WRITE_POS and READ_POS, never as plain fields.
    @SuppressWarnings("unused")
    private long writePos;
    @SuppressWarnings("unused")
    private long readPos;

    /**
     * @param channels       number of planar channels
     * @param capacityFrames ring size in frames; need not be a power of two
     * @param bytesPerSample bytes one sample occupies in the driver's container (2, 3, 4 for PCM; 1 for DSD)
     */
    public RingBuffer(int channels, int capacityFrames, int bytesPerSample) {
        if (channels < 1) {
            throw new IllegalArgumentException("channels must be at least 1: " + channels);
        }
        if (capacityFrames < 1) {
            throw new IllegalArgumentException("capacityFrames must be at least 1: " + capacityFrames);
        }
        if (bytesPerSample < 1) {
            throw new IllegalArgumentException("bytesPerSample must be at least 1: " + bytesPerSample);
        }
        this.channels = channels;
        this.capacity = capacityFrames;
        this.bytesPerSample = bytesPerSample;
        this.arena = Arena.ofShared();
        this.regions = new MemorySegment[channels];
        long regionBytes = (long) capacityFrames * bytesPerSample;
        try {
            for (int c = 0; c < channels; c++) {
                regions[c] = arena.allocate(regionBytes, 64);
            }
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
    }

    /** Frames that can be written right now. Exact on the producer thread; a conservative snapshot elsewhere. */
    public int freeFrames() {
        return capacity - used();
    }

    /** Frames that can be read right now. Exact on the consumer thread; a conservative snapshot elsewhere. */
    public int usedFrames() {
        return used();
    }

    /**
     * Buffered frames. {@code readPos} is read first: both positions only grow, so a {@code writePos} read
     * afterwards is never behind it and the difference cannot go negative. A concurrent snapshot can still
     * overshoot (the other side advanced in between), hence the clamp.
     */
    private int used() {
        long r = (long) READ_POS.getAcquire(this);
        long w = (long) WRITE_POS.getAcquire(this);
        return (int) Math.min(w - r, capacity);
    }

    /**
     * Appends {@code frames} frames, taking {@code frames * bytesPerSample} bytes from offset 0 of each
     * {@code planar[c]}. Producer thread only.
     *
     * @throws IllegalStateException    if {@code frames > freeFrames()}; nothing is written
     * @throws IllegalArgumentException if {@code frames} is negative, there are fewer than {@code channels}
     *                                  segments, or a segment is shorter than {@code frames} frames
     */
    public void write(MemorySegment[] planar, int frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("negative frame count: " + frames);
        }
        if (planar.length < channels) {
            throw new IllegalArgumentException(channels + " channel segments are required, got " + planar.length);
        }
        long bytes = (long) frames * bytesPerSample;
        for (int c = 0; c < channels; c++) {
            if (planar[c].byteSize() < bytes) {
                throw new IllegalArgumentException("channel " + c + " holds " + planar[c].byteSize()
                        + " bytes but " + frames + " frames need " + bytes);
            }
        }

        long w = (long) WRITE_POS.getOpaque(this); // only this thread ever writes it
        long r = (long) READ_POS.getAcquire(this);
        if (frames > capacity - (w - r)) {
            throw new IllegalStateException(
                    "ring buffer overflow: " + frames + " frames offered, " + (capacity - (w - r)) + " free");
        }
        if (frames == 0) {
            return;
        }

        int start = (int) (w % capacity);
        int firstFrames = Math.min(frames, capacity - start);
        long startBytes = (long) start * bytesPerSample;
        long firstBytes = (long) firstFrames * bytesPerSample;
        long restBytes = bytes - firstBytes; // > 0 only when the write wraps
        for (int c = 0; c < channels; c++) {
            MemorySegment src = planar[c];
            MemorySegment region = regions[c];
            MemorySegment.copy(src, 0, region, startBytes, firstBytes);
            if (restBytes > 0) {
                MemorySegment.copy(src, firstBytes, region, 0, restBytes);
            }
        }
        WRITE_POS.setRelease(this, w + frames);
    }

    /**
     * Moves up to {@code frames} frames into {@code dst[c]} starting at byte {@code dstOffsetBytes}, and
     * returns how many it moved: {@code min(frames, usedFrames())}, possibly 0. Consumer thread only; never
     * blocks, locks or allocates on the success path (the exceptions below are programming errors).
     *
     * @throws IllegalArgumentException if {@code frames} or {@code dstOffsetBytes} is negative, there are
     *                                  fewer than {@code channels} segments, or a segment is too short for
     *                                  the frames about to be copied; nothing is consumed
     */
    public int read(MemorySegment[] dst, long dstOffsetBytes, int frames) {
        if (frames < 0 || dstOffsetBytes < 0) {
            throw new IllegalArgumentException("negative frames (" + frames + ") or offset (" + dstOffsetBytes + ")");
        }
        if (dst.length < channels) {
            throw new IllegalArgumentException(channels + " channel segments are required, got " + dst.length);
        }
        long r = (long) READ_POS.getOpaque(this); // only this thread advances it
        long w = (long) WRITE_POS.getAcquire(this);
        int n = (int) Math.min(frames, w - r);
        if (n == 0) {
            return 0;
        }

        long bytes = (long) n * bytesPerSample;
        for (int c = 0; c < channels; c++) {
            if (dst[c].byteSize() - dstOffsetBytes < bytes) {
                throw new IllegalArgumentException("channel " + c + " has " + (dst[c].byteSize() - dstOffsetBytes)
                        + " bytes from offset " + dstOffsetBytes + " but " + n + " frames need " + bytes);
            }
        }

        int start = (int) (r % capacity);
        int firstFrames = Math.min(n, capacity - start);
        long startBytes = (long) start * bytesPerSample;
        long firstBytes = (long) firstFrames * bytesPerSample;
        long restBytes = bytes - firstBytes; // > 0 only when the read wraps
        for (int c = 0; c < channels; c++) {
            MemorySegment out = dst[c];
            MemorySegment region = regions[c];
            MemorySegment.copy(region, startBytes, out, dstOffsetBytes, firstBytes);
            if (restBytes > 0) {
                MemorySegment.copy(region, 0, out, dstOffsetBytes + firstBytes, restBytes);
            }
        }
        READ_POS.setRelease(this, r + n);
        return n;
    }

    /**
     * Discards every buffered frame. The consumer must be stopped, because this moves the consumer's
     * position, and the producer must not be inside {@link #write}: call it from the producer thread, or
     * while the producer is idle. The caller must also order this call before the consumer's restart (starting
     * the driver or its thread is enough); the buffer's memory is not zeroed.
     */
    public void clear() {
        READ_POS.setRelease(this, (long) WRITE_POS.getAcquire(this));
    }

    /**
     * Frees the off-heap regions; safe to call twice. Producer and consumer must both be finished first: a
     * {@link #read} or {@link #write} that runs after (or during) close fails with an exception.
     */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
