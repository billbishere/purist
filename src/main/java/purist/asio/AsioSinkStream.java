package purist.asio;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;
import purist.audio.AudioDeviceException;
import purist.audio.NotBitPerfectException;
import purist.audio.Packer;
import purist.audio.SampleType;
import purist.audio.SignalPath;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;

/**
 * One open ASIO stream of an {@link AsioSink}: a {@link StreamCore} at one fixed rate and sample type, the
 * driver's double buffers, and the callbacks that drain the core into them.
 *
 * <p><b>Threads.</b> Everything except the callbacks and the read-only accessors runs on the sink's
 * {@link AsioControlThread}. The driver's thread runs {@link Callbacks}, which allocate nothing, take no lock
 * and catch every {@link Throwable}. {@code bufferSwitch} never touches the control thread; it reads the four
 * buffer segments from an array fetched once after {@code createBuffers}. {@code event} only raises a
 * {@link AsioControlThread.Signal} made when the stream was built.
 *
 * <p><b>Stopping.</b> A callback only fills from the core while {@code pumping} is set. Stopping clears it,
 * stops the driver, then waits out a callback still in flight (a volatile {@code inCallback} flag; the control
 * thread spins, never the driver's). So when {@link #stop()} returns no callback is touching the core, even with
 * a driver that keeps calling back after {@code stop}: a late callback writes silence instead. Before the
 * driver's buffers are disposed the segments are dropped the same way, so a late callback writes nowhere.
 *
 * <p><b>Rebuilds.</b> A reset request, a buffer-size change or a sample-rate change is handed to the control
 * thread (requests coalesce while one is pending), which stops, disposes and re-creates the buffers at the
 * driver's new preferred size, then restarts if the stream was running. The core and everything queued in its
 * ring are kept. If that fails the stream is lost: the sink's device listeners are told why and {@link #start()}
 * refuses until the sink reopens.
 */
final class AsioSinkStream implements SinkStream {

    /** How long the control thread waits for a callback in flight to return; callbacks never block. */
    private static final long CALLBACK_GRACE_NANOS = 1_000_000_000L;

    /** The driver buffers of one {@code createBuffers}: {@code halves[h][c]} is channel {@code c} of half {@code h}. */
    private record Buffers(MemorySegment[][] halves, int fillFrames) {
    }

    private final AsioSink sink;
    private final AsioDriver driver;
    private final int rate;
    private final SampleType outType;
    private final int[] channels;
    private final int maxFillFrames;
    private final StreamCore core;
    private final byte silence;
    private final Callbacks callbacks = new Callbacks();
    /** Raised by the driver's thread, allocation-free; coalesces requests while a rebuild is pending. */
    private final AsioControlThread.Signal rebuildSignal;
    private final AtomicLong callbackErrors = new AtomicLong();

    // Shared with the callback thread.
    private volatile Buffers buffers;
    private volatile boolean pumping;
    private volatile boolean inCallback;

    // Written on the control thread, read anywhere.
    private volatile double deviceRate;
    private volatile int bufferSize;
    private volatile boolean disposed;
    private volatile String lostReason;

    // Confined to the control thread.
    private boolean hasBuffers;
    private boolean running;

    /**
     * @param maxFillFrames the most frames one callback may copy, for any buffer size a rebuild may bring
     * @param ringFrames    the core's ring capacity
     */
    AsioSinkStream(AsioSink sink, AsioDriver driver, StreamFormat format, SampleType outType, int[] channels,
            int maxFillFrames, int ringFrames) {
        this.sink = sink;
        this.driver = driver;
        this.rate = format.sampleRate();
        this.outType = outType;
        this.channels = channels.clone();
        this.maxFillFrames = maxFillFrames;
        this.core = new StreamCore(outType, maxFillFrames, ringFrames);
        this.silence = core.silenceByte();
        this.deviceRate = rate;
        this.rebuildSignal = sink.control().newSignal(this::rebuildOnControl);
    }

    // ---- SinkStream ----------------------------------------------------------------------------------------

    @Override
    public StreamCore core() {
        return core;
    }

    @Override
    public SignalPath signalPath() {
        return core.signalPath(sink.name(), deviceRate, bufferSize);
    }

    /** As in the brief: same kind, rate and channels, and the sample type carries it; never for a dead stream. */
    @Override
    public boolean accepts(StreamFormat next) {
        if (disposed || lostReason != null || !next.sameStream(core.source())) {
            return false;
        }
        try {
            Packer.create(next, outType);
            return true;
        } catch (NotBitPerfectException e) {
            return false;
        }
    }

    /**
     * Starts the driver calling back.
     *
     * @throws AudioDeviceException  if the driver will not start, or the stream was lost
     * @throws IllegalStateException if the sink has opened another stream since
     */
    @Override
    public void start() throws AudioDeviceException {
        sink.onControlDevice(() -> {
            startOnControl();
            return null;
        });
    }

    /** Stops the driver; when this returns no callback is running or will touch the core. Idempotent. */
    @Override
    public void stop() {
        if (disposed) {
            return;
        }
        try {
            sink.onControlDevice(() -> {
                stopOnControl();
                return null;
            });
        } catch (AudioDeviceException e) {
            throw new IllegalStateException(e); // stopOnControl reports trouble to the listeners instead
        }
    }

    /** How many callbacks threw (and were swallowed) on this stream. */
    long callbackErrors() {
        return callbackErrors.get();
    }

    /** The callbacks given to the driver, for tests that play a misbehaving driver. */
    AsioCallbacks callbacksForTest() {
        return callbacks;
    }

    // ---- control thread ------------------------------------------------------------------------------------

    /**
     * {@code createBuffers} at {@code preferred} samples, then: fetch the four segments once, fill them with
     * silence, call {@code outputReady} once so its downcall is linked here rather than on the driver's thread,
     * and read the device rate back.
     */
    void createBuffers(int preferred) throws AsioException {
        int fill = AsioSink.fillFrames(outType, preferred);
        if (fill < 1 || fill > maxFillFrames) {
            throw new IllegalStateException("%s wants buffers of %d samples, which this stream cannot use"
                    .formatted(sink.name(), preferred));
        }
        long bytes = AsioSink.bytesPerBuffer(outType, preferred);
        driver.createBuffers(channels, preferred, callbacks);
        hasBuffers = true;
        MemorySegment[][] halves = new MemorySegment[2][channels.length];
        for (int h = 0; h < 2; h++) {
            for (int c = 0; c < channels.length; c++) {
                halves[h][c] = driver.buffer(c, h, bytes);
                halves[h][c].fill(silence);
            }
        }
        driver.outputReady(); // warm-up; the result is ignored
        bufferSize = preferred;
        buffers = new Buffers(halves, fill);
        deviceRate = driver.sampleRate();
    }

    private void startOnControl() throws AudioDeviceException {
        if (disposed) {
            throw new IllegalStateException("this stream was disposed when the sink opened another");
        }
        String lost = lostReason;
        if (lost != null) {
            throw new AudioDeviceException(lost, null);
        }
        if (running) {
            return;
        }
        try {
            startDriver();
        } catch (AsioException e) {
            sink.markReloadNeeded();
            throw new AudioDeviceException("Could not start " + sink.name() + ": " + e.getMessage(), e);
        }
        try {
            deviceRate = driver.sampleRate(); // §6 step 7: what the device really runs at
        } catch (AsioException ignored) {
            // keep the value read after createBuffers
        }
    }

    private void startDriver() throws AsioException {
        pumping = true;
        try {
            driver.start();
        } catch (AsioException | RuntimeException e) {
            pumping = false;
            throw e;
        }
        running = true;
    }

    private void stopOnControl() {
        pumping = false;
        if (disposed || !running) {
            return;
        }
        running = false;
        AsioException failure = null;
        try {
            driver.stop();
        } catch (AsioException e) {
            failure = e;
        }
        awaitCallbacks();
        if (failure != null) {
            lose(failure.getMessage());
        }
    }

    /**
     * Stops the driver, drops the segments, waits out a callback in flight, then disposes the driver's buffers.
     *
     * @return the first driver failure, or null
     */
    private Exception releaseBuffers() {
        pumping = false;
        if (!hasBuffers) {
            running = false;
            return null;
        }
        Exception failure = null;
        try {
            driver.stop(); // even if not started: harmless, and makes sure
        } catch (AsioException | RuntimeException e) {
            if (running) {
                failure = e;
            }
        }
        running = false;
        buffers = null;
        awaitCallbacks();
        try {
            driver.disposeBuffers();
            hasBuffers = false;
        } catch (AsioException | RuntimeException e) {
            if (failure == null) {
                failure = e;
            }
        }
        return failure;
    }

    /** Ends this stream for good: the sink is opening another one, or closing. Driver errors are ignored. */
    void disposeOnControl() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (releaseBuffers() != null || hasBuffers) {
            sink.markReloadNeeded(); // the driver may still hold buffers: start over with a fresh instance
        }
        core.close();
    }

    /** The rebuild signal's action. The signal is no longer pending here, so an event meanwhile asks for another. */
    private void rebuildOnControl() {
        if (disposed || lostReason != null) {
            return;
        }
        boolean wasRunning = running;
        try {
            Exception failure = releaseBuffers();
            // The driver halves it was playing from are dropped now, which may be audible: count it, so the
            // BIT-PERFECT light goes out with a reason. No callback is touching the core here (§5, §6).
            core.recordDropout();
            if (failure != null) {
                throw failure;
            }
            if (driver.sampleRate() != rate) {
                driver.setSampleRate(rate); // the stream stays at its source's rate, or is lost
            }
            requireUnchangedType();
            createBuffers(driver.bufferSize().preferred());
            if (wasRunning) {
                startDriver();
            }
        } catch (Exception e) {
            lose(e.getMessage());
        }
    }

    /** The core packs for {@code outType}: a driver that now wants another type must not be fed those bytes. */
    private void requireUnchangedType() throws AsioException {
        for (int ch : channels) {
            int t = driver.outputChannelType(ch);
            if (t != outType.asioCode()) {
                throw new IllegalStateException("%s now reports sample type %d on output %d, not %d"
                        .formatted(sink.name(), t, ch, outType.asioCode()));
            }
        }
    }

    /** The device is gone: release what can be released, and tell the sink's listeners. */
    private void lose(String why) {
        String text = sink.name() + " was lost: " + why;
        lostReason = text;
        releaseBuffers(); // best effort
        sink.markReloadNeeded();
        sink.notifyDeviceLost(text);
    }

    /** Spins (on the control thread) until no callback is inside {@link Callbacks#bufferSwitch}. */
    private void awaitCallbacks() {
        long start = System.nanoTime();
        while (inCallback && System.nanoTime() - start < CALLBACK_GRACE_NANOS) {
            Thread.onSpinWait();
        }
    }

    // ---- driver thread -------------------------------------------------------------------------------------

    /** What the driver calls on its own thread. */
    private final class Callbacks implements AsioCallbacks {

        @Override
        public void bufferSwitch(int half) {
            // Raised before reading `buffers`/`pumping`, which the control thread clears before reading this.
            inCallback = true;
            try {
                Buffers b = buffers;
                if (b == null) {
                    return; // no buffers (being rebuilt or disposed): touch nothing
                }
                MemorySegment[] out = b.halves()[half];
                if (pumping) {
                    core.fill(out, b.fillFrames());
                } else {
                    out[0].fill(silence);
                    out[1].fill(silence);
                }
                driver.outputReady();
            } catch (Throwable t) {
                callbackErrors.incrementAndGet();
            } finally {
                inCallback = false;
            }
        }

        @Override
        public void event(AsioEvent e, int value) {
            try {
                // `==` rather than a switch: an enum switch would load a synthetic class on this thread.
                if (e == AsioEvent.RESET_REQUEST || e == AsioEvent.BUFFER_SIZE_CHANGE
                        || e == AsioEvent.SAMPLE_RATE_CHANGED) {
                    requestRebuild();
                }
            } catch (Throwable t) {
                callbackErrors.incrementAndGet();
            }
        }

        /** Allocation-free and never throws: a no-op while a rebuild is pending or the control thread is closing. */
        private void requestRebuild() {
            if (!disposed) {
                rebuildSignal.raise();
            }
        }
    }
}
