package purist.audio;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An {@link AudioSink} with no hardware: the ASIO double-buffer callbacks are emulated synchronously, on
 * whichever thread calls {@link #pump}, and every buffer the "driver" is handed is appended to an in-memory
 * capture. It runs the same {@link StreamCore} the real sink does, so tests can prove the whole pipeline is
 * bit-identical without a DAC.
 *
 * <p>Like a real driver, a stream only calls back between {@link SinkStream#start()} and
 * {@link SinkStream#stop()}; {@code pump} outside that window does nothing. {@code stop} waits for a pump that
 * is running, so the "no callback is in flight after stop returns" guarantee holds here too.
 *
 * <p>A frame is the unit {@link StreamCore} counts: for DSD, one byte per channel. {@code bufferFrames} is
 * therefore bytes per channel per callback for DSD, not DSD bits as in a real driver.
 *
 * <p>The capture accumulates across streams, never clears, and includes the silence delivered when the ring
 * ran dry.
 */
public final class CaptureSink implements AudioSink {

    /** What the emulated driver buffers hold until a callback writes them: neither PCM nor DSD silence. */
    private static final byte POISON = (byte) 0xA5;

    private final String name;
    private final SampleType pcmType;
    private final SampleType dsdType;
    private final int bufferFrames;
    private final Set<Integer> supportedRates;

    private final Object captureLock = new Object();
    private final ByteArrayOutputStream[] capture = {new ByteArrayOutputStream(), new ByteArrayOutputStream()};
    private volatile CaptureStream current;
    private volatile int opens;

    /**
     * @param name           the device name shown in the signal path and in refusals
     * @param pcmType        the sample type PCM sources are written as
     * @param dsdType        the sample type DSD sources are written as
     * @param bufferFrames   frames per emulated driver callback
     * @param supportedRates the rates in Hz the device accepts, PCM sample rates and DSD bit rates alike
     */
    public CaptureSink(String name, SampleType pcmType, SampleType dsdType, int bufferFrames,
            Set<Integer> supportedRates) {
        this.name = Objects.requireNonNull(name, "name");
        if (Objects.requireNonNull(pcmType, "pcmType").isDsd()) {
            throw new IllegalArgumentException("pcmType must be a PCM type: " + pcmType);
        }
        if (!Objects.requireNonNull(dsdType, "dsdType").isDsd()) {
            throw new IllegalArgumentException("dsdType must be a DSD type: " + dsdType);
        }
        if (bufferFrames < 1) {
            throw new IllegalArgumentException("bufferFrames must be at least 1: " + bufferFrames);
        }
        this.pcmType = pcmType;
        this.dsdType = dsdType;
        this.bufferFrames = bufferFrames;
        this.supportedRates = Set.copyOf(supportedRates);
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Opens a stream at the source's own rate, after disposing the previous stream (even if this open is then
     * refused). Refuses with {@code "rate %d Hz not supported by %s"} for a rate outside
     * {@code supportedRates}, or with {@link Packer}'s reason when the sample type cannot carry the source.
     */
    @Override
    public synchronized SinkStream open(StreamFormat f) throws NotBitPerfectException {
        Objects.requireNonNull(f, "f");
        disposeCurrent();
        if (!supportedRates.contains(f.sampleRate())) {
            throw new NotBitPerfectException("rate " + f.sampleRate() + " Hz not supported by " + name);
        }
        StreamCore core = new StreamCore(f.kind() == Kind.DSD ? dsdType : pcmType, bufferFrames, ringFrames(f));
        try {
            core.setSource(f);
        } catch (NotBitPerfectException | RuntimeException e) {
            core.close();
            throw e;
        }
        CaptureStream stream = new CaptureStream(core, f.sampleRate());
        current = stream;
        opens++;
        return stream;
    }

    /** About 2 s of audio, and never less than 4 callbacks: the real sink's ring size. */
    private int ringFrames(StreamFormat f) {
        long perSecond = f.kind() == Kind.DSD ? f.sampleRate() / 8L : f.sampleRate();
        return Math.toIntExact(Math.max(2 * perSecond, 4L * bufferFrames));
    }

    /** Never called: a capture device cannot be lost. Accepted so the sink can stand in for a real one. */
    @Override
    public void addDeviceListener(Consumer<String> onDeviceLost) {
        Objects.requireNonNull(onDeviceLost, "onDeviceLost");
    }

    @Override
    public synchronized void close() {
        disposeCurrent();
    }

    private void disposeCurrent() {
        CaptureStream old = current;
        if (old != null) {
            current = null;
            old.dispose();
        }
    }

    /** The number of {@link #open} calls that succeeded. */
    public int opens() {
        return opens;
    }

    /**
     * Emulates {@code callbacks} driver callbacks on the calling thread, alternating the two driver buffer
     * halves like ASIO. Does nothing unless a stream is open and started.
     */
    public void pump(int callbacks) {
        CaptureStream s = current;
        if (s != null) {
            s.pump(callbacks);
        }
    }

    /**
     * Pumps until the open stream is drained, which is as soon as its end of input has been played out.
     * Does nothing unless a stream is open and started.
     *
     * @throws IllegalStateException if it is still not drained after {@code maxCallbacks}: the stream never
     *         ended, or the producer is not feeding it
     */
    public void pumpUntilDrained(int maxCallbacks) {
        CaptureStream s = current;
        if (s != null) {
            s.pumpUntilDrained(maxCallbacks);
        }
    }

    /**
     * Everything the driver was handed for {@code channel} (0 or 1) since this sink was created, silence
     * included, in order.
     */
    public byte[] captured(int channel) {
        Objects.checkIndex(channel, 2);
        synchronized (captureLock) {
            return capture[channel].toByteArray();
        }
    }

    private final class CaptureStream implements SinkStream {

        private final StreamCore core;
        private final double deviceRate;
        private final Arena arena = Arena.ofShared();
        /** The ASIO double buffer: {@code halves[h][c]} is channel {@code c} of half {@code h}. */
        private final MemorySegment[][] halves = new MemorySegment[2][2];
        private final byte[] scratch;
        /** Held for the whole of a pump, a start, a stop and a dispose, so a stop cannot overtake a callback. */
        private final Object lock = new Object();
        private int nextHalf;       // guarded by lock
        private boolean started;    // guarded by lock
        private boolean disposed;   // guarded by lock

        CaptureStream(StreamCore core, double deviceRate) {
            this.core = core;
            this.deviceRate = deviceRate;
            long bytes = (long) bufferFrames * core.outType().containerBytes();
            this.scratch = new byte[Math.toIntExact(bytes)];
            for (MemorySegment[] half : halves) {
                for (int c = 0; c < half.length; c++) {
                    half[c] = arena.allocate(bytes);
                    half[c].fill(POISON);
                }
            }
        }

        @Override
        public StreamCore core() {
            return core;
        }

        @Override
        public SignalPath signalPath() {
            return core.signalPath(name, deviceRate, bufferFrames);
        }

        @Override
        public boolean accepts(StreamFormat next) {
            if (!next.sameStream(core.source())) {
                return false;
            }
            try {
                Packer.create(next, core.outType());
                return true;
            } catch (NotBitPerfectException e) {
                return false;
            }
        }

        @Override
        public void start() {
            synchronized (lock) {
                if (disposed) {
                    throw new IllegalStateException("this stream was disposed when the sink opened another");
                }
                started = true;
            }
        }

        @Override
        public void stop() {
            synchronized (lock) {
                started = false;
            }
        }

        void pump(int callbacks) {
            synchronized (lock) {
                if (!started) {
                    return;
                }
                for (int i = 0; i < callbacks; i++) {
                    callback();
                }
            }
        }

        void pumpUntilDrained(int maxCallbacks) {
            synchronized (lock) {
                if (!started) {
                    return;
                }
                for (int i = 0; i < maxCallbacks && !core.drained(); i++) {
                    callback();
                }
                if (!core.drained()) {
                    throw new IllegalStateException("stream not drained after " + maxCallbacks + " callbacks");
                }
            }
        }

        /** One driver callback: fill the next half, then record what the driver would now play. */
        private void callback() {
            MemorySegment[] half = halves[nextHalf];
            nextHalf ^= 1;
            core.fill(half, bufferFrames);
            synchronized (captureLock) {
                for (int c = 0; c < 2; c++) {
                    MemorySegment.copy(half[c], ValueLayout.JAVA_BYTE, 0, scratch, 0, scratch.length);
                    capture[c].write(scratch, 0, scratch.length);
                }
            }
        }

        /** Waits for a callback in flight, then frees everything. */
        void dispose() {
            synchronized (lock) {
                started = false;
                disposed = true;
            }
            core.close();
            arena.close();
        }
    }
}
