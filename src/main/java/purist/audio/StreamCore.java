package purist.audio;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * The seam between the decoder and the driver, shared by the real ASIO sink and the in-memory
 * {@link CaptureSink}. The decoder thread {@link #write writes} decoded frames in; they are packed losslessly
 * (by {@link Packer}) into a {@link RingBuffer} of driver-native samples; the driver callback calls
 * {@link #fill} to copy exactly one driver buffer out.
 *
 * <p>Thread roles. The <b>producer</b> (the decoder thread) owns {@link #setSource}, {@link #write} and
 * {@link #endOfInput()}. The <b>consumer</b> (the driver callback, possibly a native upcall) owns {@link #fill}
 * and nothing else. Everything else, including the accessors, is safe from any thread.
 * {@link #reset} is a control operation that is only legal while the consumer is stopped.
 *
 * <p>{@link #fill} never blocks, locks or allocates. It reads the ring once, then pads whatever the ring could
 * not supply with the sample type's true silence (see {@link #silenceByte}), so the driver always gets a whole
 * buffer.
 *
 * <p>A <em>frame</em> is one sample of each channel: for DSD that is one byte per channel, i.e. 8 DSD bits.
 */
public final class StreamCore implements AutoCloseable {

    /** The most frames packed and queued per step; also the size of the producer's staging buffers. */
    static final int CHUNK_FRAMES = 4096;

    /** The source and the packer made for it, replaced together so a reader never sees a mismatched pair. */
    private record Active(StreamFormat source, Packer packer) {}

    private final SampleType outType;
    private final int maxSourceBits;
    private final int maxFillFrames;
    private final int containerBytes;
    private final byte silence;
    private final int chunkFrames;
    private final RingBuffer ring;
    private final Arena arena = Arena.ofShared();
    private final MemorySegment[] staging = new MemorySegment[2];
    private final MemorySegment silenceBlock;

    private volatile Active active;
    private volatile boolean paused;
    private volatile boolean endOfInput;
    /** Set by {@link #cancelWrite()}, consumed by the producer's {@link #write}. */
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    /** True exactly while the consumer is inside {@link #fill}; read by {@link #reset} and {@link #drained()}. */
    private volatile boolean inFill;
    // Counters. Only the consumer increments them (framesPlayed is also zeroed by reset(), which runs while the
    // consumer is stopped), so the read-modify-write needs no atomicity; volatile publishes the values to the
    // producer, the control thread and the UI.
    private volatile long framesPlayed;
    private volatile long dropouts;

    /**
     * @param outType       the driver's sample type; fixes the container size, the silence byte and which
     *                      sources are bit-perfect
     * @param maxFillFrames the largest {@code frames} a single {@link #fill} will be asked for (the driver's
     *                      buffer size)
     * @param ringFrames    capacity of the ring between producer and consumer, in frames
     */
    public StreamCore(SampleType outType, int maxFillFrames, int ringFrames) {
        this(outType, maxFillFrames, ringFrames, Objects.requireNonNull(outType, "outType").validBits());
    }

    /**
     * As {@link #StreamCore(SampleType, int, int)}, for a device whose real sample depth is smaller than the
     * type's: a WASAPI exclusive stream in a 32-bit container initialised with 24 valid bits is packed as
     * {@code INT32_LSB} (the sample left-justified) but the device only keeps 24 of the 32 bits.
     *
     * @param maxSourceBits the deepest PCM source {@link #setSource} accepts: any deeper would lose its low bits
     *                      in the device (ignored for DSD)
     */
    public StreamCore(SampleType outType, int maxFillFrames, int ringFrames, int maxSourceBits) {
        this.outType = Objects.requireNonNull(outType, "outType");
        if (maxSourceBits < 1) {
            throw new IllegalArgumentException("maxSourceBits must be at least 1: " + maxSourceBits);
        }
        this.maxSourceBits = maxSourceBits;
        if (maxFillFrames < 1) {
            throw new IllegalArgumentException("maxFillFrames must be at least 1: " + maxFillFrames);
        }
        if (ringFrames < 1) {
            throw new IllegalArgumentException("ringFrames must be at least 1: " + ringFrames);
        }
        this.maxFillFrames = maxFillFrames;
        this.containerBytes = outType.containerBytes();
        this.silence = silenceFor(outType);
        // A chunk must fit the ring or write() could never find room for it.
        this.chunkFrames = Math.min(CHUNK_FRAMES, ringFrames);
        this.ring = new RingBuffer(2, ringFrames, containerBytes);
        try {
            staging[0] = arena.allocate((long) CHUNK_FRAMES * containerBytes);
            staging[1] = arena.allocate((long) CHUNK_FRAMES * containerBytes);
            silenceBlock = arena.allocate((long) maxFillFrames * containerBytes);
        } catch (Throwable t) {
            ring.close();
            arena.close();
            throw t;
        }
        silenceBlock.fill(silence);
        try {
            warmUp();
        } catch (Throwable t) {
            close();
            throw t;
        }
    }

    /** The byte that is silence for {@code type}: 0x00 for PCM, the DSD idle pattern 0x69 / 0x96 for DSD. */
    private static byte silenceFor(SampleType type) {
        if (!type.isDsd()) {
            return 0x00;
        }
        // 0x69 = 01101001 is the DSD idle (silence) pattern, played MSB first; LSB first it is 0x96.
        return type.bitOrder() == BitOrder.MSB_FIRST ? (byte) 0x69 : (byte) 0x96;
    }

    /**
     * Runs the consumer path once on this (non-driver) thread: the ring's VarHandles, MemorySegment.copy, the
     * silence padding and the paused path are linked here, so that linking never happens for the first time
     * inside a native upcall. Leaves no trace: the ring is emptied and the counters are zero.
     */
    private void warmUp() {
        try (Arena scratchArena = Arena.ofConfined()) {
            int frames = Math.min(2, maxFillFrames);
            MemorySegment[] scratch = {
                scratchArena.allocate((long) frames * containerBytes),
                scratchArena.allocate((long) frames * containerBytes)
            };
            ring.write(staging, 1);
            fill(scratch, frames); // reads the frame; pads the rest when frames == 2
            fill(scratch, frames); // an empty ring: all padding, counted as a dropout
            paused = true;
            fill(scratch, frames);
            paused = false;
        }
        ring.clear();
        framesPlayed = 0;
        dropouts = 0;
    }

    public SampleType outType() {
        return outType;
    }

    /** The byte that is silence in this stream's sample type. */
    public byte silenceByte() {
        return silence;
    }

    // ---- producer ----------------------------------------------------------------------------------------

    /**
     * Declares the format of the frames passed to the following {@link #write} calls, and makes the
     * {@link Packer} for it. Producer thread. Call it for every track: a gapless join between two tracks of
     * the same stream is just a {@code setSource} between their writes.
     *
     * @throws NotBitPerfectException if the source cannot reach this sample type unchanged (or is deeper than the
     *         device's valid bits); the previous source stays in effect
     */
    public void setSource(StreamFormat src) throws NotBitPerfectException {
        if (src.kind() == Kind.PCM && !outType.isDsd() && maxSourceBits < outType.validBits() && src.bits() > maxSourceBits) {
            throw new NotBitPerfectException(src.bits() + "-bit source is deeper than the device's " + maxSourceBits
                    + " valid bits and would lose its low bits");
        }
        active = new Active(src, Packer.create(src, outType));
    }

    /**
     * The source most recently accepted by {@link #setSource}.
     *
     * @throws IllegalStateException if there is none yet
     */
    public StreamFormat source() {
        return current().source();
    }

    /**
     * The transform in effect for the source most recently accepted by {@link #setSource}.
     *
     * @throws IllegalStateException if there is none yet
     */
    public Transform transform() {
        return current().packer().transform();
    }

    /**
     * The signal path of the source most recently accepted by {@link #setSource}, with the device details the
     * sink knows. The source and its transform are read together, so they always belong to the same track.
     *
     * @throws IllegalStateException if {@link #setSource} has not been called
     */
    public SignalPath signalPath(String deviceName, double deviceRate, int bufferFrames) {
        return signalPath(deviceName, deviceRate, bufferFrames, null);
    }

    /** {@link #signalPath(String, double, int)} with the sink's own description of the link (see {@link SignalPath#link}). */
    public SignalPath signalPath(String deviceName, double deviceRate, int bufferFrames, String link) {
        Active a = current();
        return new SignalPath(deviceName, a.source(), outType, deviceRate, bufferFrames, a.packer().transform(), link);
    }

    private Active current() {
        Active a = active;
        if (a == null) {
            throw new IllegalStateException("setSource has not been called");
        }
        return a;
    }

    /**
     * Packs {@code frames} frames of {@code decoded} (the decoders' output format for the current source) and
     * queues them for the consumer. Producer thread only. Works in chunks of at most {@value #CHUNK_FRAMES}
     * frames and, while the ring lacks room for the next chunk, parks for 1 ms and tries again.
     *
     * <p>A {@link #cancelWrite() cancel} ends the call early, before its next chunk or while it waits for room,
     * and the call returns how many frames it queued: always whole chunks, in order, so the caller can write the
     * rest later without losing or repeating a frame.
     *
     * @return the frames queued: {@code frames}, or fewer if the write was cancelled
     * @throws InterruptedException     if the thread is interrupted, whether before a chunk or while waiting
     *                                  for room. Chunks queued by this call before that stay queued; the
     *                                  caller is stopping and should {@link #reset}.
     * @throws IllegalArgumentException if {@code frames} is negative, {@code decoded} is too short, or a PCM
     *                                  sample does not fit the bit depth the source declared (see
     *                                  {@link Packer#pack}); this is a decoder bug and the Player decides what
     *                                  to do. The chunk that failed was packed into scratch and never queued,
     *                                  but chunks before it in the same call were.
     * @throws IllegalStateException    if {@link #setSource} has not been called
     */
    public int write(MemorySegment decoded, int frames) throws InterruptedException {
        Active a = current();
        if (frames < 0) {
            throw new IllegalArgumentException("negative frame count: " + frames);
        }
        long frameBytes = a.source().bytesPerDecodedFrame();
        if (decoded.byteSize() < frames * frameBytes) {
            throw new IllegalArgumentException("source holds " + decoded.byteSize() + " bytes but " + frames
                    + " frames need " + frames * frameBytes);
        }
        int done = 0;
        while (done < frames) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (takeCancel()) {
                return done;
            }
            int n = Math.min(chunkFrames, frames - done);
            a.packer().pack(decoded.asSlice(done * frameBytes, n * frameBytes), n, staging);
            if (!awaitRoom(n)) {
                return done; // cancelled while waiting; the packed chunk was never queued
            }
            ring.write(staging, n);
            done += n;
        }
        return done;
    }

    /**
     * Makes the producer's {@link #write} return early: the one waiting for room now, or else the next one, which
     * then returns before queuing anything. Any thread. This is how a control command reaches a decoder thread
     * that is parked on a full ring without {@link Thread#interrupt()} (which would close the decoder's
     * interruptible file channel). One cancel ends at most one write, and a cancel nobody needed is harmless:
     * the caller just writes the rest.
     */
    public void cancelWrite() {
        cancelRequested.set(true);
    }

    /** Consumes a pending cancel; the plain read first keeps the common path free of an atomic write. */
    private boolean takeCancel() {
        return cancelRequested.get() && cancelRequested.getAndSet(false);
    }

    /** Waits until the ring has room for {@code frames}; false if a cancel arrived first. */
    private boolean awaitRoom(int frames) throws InterruptedException {
        while (ring.freeFrames() < frames) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (takeCancel()) {
                return false;
            }
            LockSupport.parkNanos(1_000_000L);
        }
        return true;
    }

    /**
     * Declares that nothing more will be written. From then on running dry is the natural end, not a dropout,
     * and {@link #drained()} becomes true once the consumer has played everything. Producer thread: call it
     * right after the last {@link #write}.
     *
     * <p>{@link #fill} takes a snapshot of this flag when a callback begins, so a callback that started before
     * this call and ran dry is a dropout even if the call lands while that callback is still padding: silence
     * really was inserted ahead of the last chunk. A producer that is a hair late calling this can therefore
     * cost one spurious dropout, which errs in the safe direction (never an uncounted gap).
     */
    public void endOfInput() {
        endOfInput = true;
    }

    // ---- consumer ----------------------------------------------------------------------------------------

    /**
     * Copies exactly {@code frames} frames into offset 0 of each of the first two {@code channelBuffers}:
     * whatever the ring holds, then silence for the remainder. Consumer thread (the driver callback) only.
     * Allocates nothing and takes no lock, so it is safe inside a native upcall.
     *
     * <ul>
     *   <li>While {@linkplain #paused() paused}: the whole buffer is silence, the ring is left alone and
     *       nothing is counted.
     *   <li>Otherwise {@link #framesPlayed()} grows by the real data frames copied, and if the ring could not
     *       supply all {@code frames} and {@link #endOfInput()} had not been called, that is a dropout.
     * </ul>
     *
     * <p>{@code paused} and the end-of-input flag are each read once, at the very start of the call, before the
     * ring is touched. The flag is a snapshot on purpose: if it were read after the ring, a producer could
     * complete its last write and call {@code endOfInput()} between a short read and that check, and a gap
     * that really preceded the end of the data would go uncounted. The cost is that a producer that sets the
     * flag a hair late can cause one spurious dropout; that is the conservative direction.
     *
     * <p>The silence is written from a preallocated block initialised with {@link MemorySegment#fill}, because
     * slicing the remainder of the caller's segment in order to fill it would allocate.
     *
     * @throws IllegalArgumentException if {@code frames} is negative or above {@code maxFillFrames}, fewer than
     *         two buffers are given, or a buffer is too short (the caller's callback must catch it)
     */
    public void fill(MemorySegment[] channelBuffers, int frames) {
        fill(channelBuffers, frames, null);
    }

    /**
     * {@link #fill(MemorySegment[], int)} with a test seam. {@code afterRead}, when not null, runs on the
     * consumer thread right after the ring was read and before any padding or accounting: exactly where a
     * producer on another thread can land its final write and {@link #endOfInput()} between a short read and
     * the dropout decision. Production code passes null; the seam costs one null check and allocates nothing.
     */
    void fill(MemorySegment[] channelBuffers, int frames, Runnable afterRead) {
        if (frames < 0 || frames > maxFillFrames) {
            throw new IllegalArgumentException("frames " + frames + " is outside 0.." + maxFillFrames);
        }
        if (channelBuffers.length < 2) {
            throw new IllegalArgumentException("two channel buffers are required, got " + channelBuffers.length);
        }
        inFill = true;
        try {
            // Snapshots, before the ring is read (see the Javadoc of fill and endOfInput).
            final boolean isPaused = paused;
            final boolean ended = endOfInput;
            if (isPaused) {
                padSilence(channelBuffers, 0, frames);
                return;
            }
            int got = ring.read(channelBuffers, 0, frames);
            if (afterRead != null) {
                afterRead.run();
            }
            if (got > 0) {
                framesPlayed += got;
            }
            if (got < frames) {
                padSilence(channelBuffers, got, frames - got);
                if (!ended) {
                    dropouts++;
                }
            }
        } finally {
            inFill = false;
        }
    }

    private void padSilence(MemorySegment[] channelBuffers, int fromFrame, int count) {
        long offset = (long) fromFrame * containerBytes;
        long length = (long) count * containerBytes;
        MemorySegment.copy(silenceBlock, 0, channelBuffers[0], offset, length);
        MemorySegment.copy(silenceBlock, 0, channelBuffers[1], offset, length);
    }

    // ---- control -------------------------------------------------------------------------------------------

    /** Pauses or resumes. While paused, {@link #fill} outputs silence and leaves the buffered audio in place. */
    public void setPaused(boolean p) {
        paused = p;
    }

    public boolean paused() {
        return paused;
    }

    /** Real data frames the consumer has copied out since construction or the last {@link #reset}. */
    public long framesPlayed() {
        return framesPlayed;
    }

    /**
     * Callbacks that ran dry while not paused and before {@link #endOfInput()}: audible gaps. Cumulative for the
     * life of the core ({@link #reset} does not clear it); the Player subtracts a per-track baseline.
     */
    public long dropouts() {
        return dropouts;
    }

    /**
     * Counts one audible gap that the consumer did not see itself: the sink rebuilt the driver's buffers (a reset
     * request, a buffer-size or rate change), which drops up to two driver halves. Call it only while the
     * consumer is stopped, as the counters are single-writer: the rebuild runs on the control thread after
     * {@code releaseBuffers} has waited out any callback in flight.
     */
    public void recordDropout() {
        dropouts++;
    }

    /**
     * True when {@link #endOfInput()} was called and the consumer has played every frame. Once it returns true,
     * {@link #framesPlayed()} is final: the check also waits out a callback that is still finishing its
     * bookkeeping.
     */
    public boolean drained() {
        // The order matters: if the ring is seen empty, then any fill that emptied it began before that, so its
        // inFill marker is already raised; seeing it down afterwards means that fill has completed.
        return endOfInput && ring.usedFrames() == 0 && !inFill;
    }

    /**
     * Starts a new play session: discards everything buffered, clears the end-of-input mark and zeroes
     * {@link #framesPlayed()}. Used for seeking and for starting a different track.
     *
     * <p><b>The consumer must be stopped and the producer idle</b> (call it from the producer thread, or while
     * that thread is parked or finished): stop the driver first ({@link SinkStream#stop()} returns only when no
     * callback is running), reset, refill, then start it again. The volatile {@code inFill} marker that
     * {@link #fill} raises gives the happens-before edge from the consumer's last callback to this call; the
     * caller's restart of the driver gives the edge back.
     *
     * @throws IllegalStateException if the consumer is inside {@link #fill}: nothing was changed
     */
    public void reset() {
        if (inFill) {
            throw new IllegalStateException("reset() while the consumer is inside fill(): stop the driver first");
        }
        ring.clear();
        endOfInput = false;
        framesPlayed = 0;
    }

    /** Frees the off-heap buffers; safe to call twice. Producer and consumer must both be finished. */
    @Override
    public void close() {
        ring.close();
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
