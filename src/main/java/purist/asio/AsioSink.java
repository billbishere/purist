package purist.asio;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import purist.audio.AudioDeviceException;
import purist.audio.AudioSink;
import purist.audio.Kind;
import purist.audio.NotBitPerfectException;
import purist.audio.SampleType;
import purist.audio.SinkStream;
import purist.audio.StreamFormat;

/**
 * The real output: one ASIO driver, opened at each stream's exact format (PCM, or native DSD) or not at all.
 *
 * <p>Every driver call runs on the {@link AsioControlThread}, including loading the driver (lazily, on the first
 * {@link #open} or {@link #openControlPanel}) and releasing it. The driver's callbacks are handled by
 * {@link AsioSinkStream}.
 *
 * <p>{@link #open} follows spec §6 steps 1–7: pick the I/O format, set the rate, check the output pair and its
 * sample type, read the preferred buffer size, create the buffers. It refuses with
 * {@link NotBitPerfectException} whenever the device cannot carry the source unchanged, and never converts.
 *
 * <p>After a device error (a failed load, start or rebuild, or any driver error while opening) the next
 * {@code open} releases the driver and loads it afresh, which is what lets "retry" recover an unplugged device.
 */
public final class AsioSink implements AudioSink {

    /**
     * The most frames per callback a stream's {@code StreamCore} is sized for, whatever the driver claims its
     * largest buffer is. A rebuild to a larger buffer than the stream was opened for is a device loss.
     */
    static final int MAX_FILL_FRAMES_CAP = 1 << 16;

    private static final String BUSY_HINT = ". Close other apps using ASIO — foobar2000 may be holding the device.";

    private final AsioDriverInfo info;
    private final AsioControlThread ctl;
    private final Function<AsioDriverInfo, AsioDriver> loader;
    private final boolean ownsControl;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int firstChannel;

    // Confined to the control thread.
    private AsioDriver driver;
    private boolean reloadNeeded;
    private AsioSinkStream current;

    /**
     * @param info   the driver to open
     * @param ctl    the thread every driver call runs on; the caller keeps ownership and closes it after this sink
     * @param loader creates the driver on {@code ctl}; a {@link RuntimeException} from it means the device could
     *               not be opened, and its message is shown
     */
    public AsioSink(AsioDriverInfo info, AsioControlThread ctl, Function<AsioDriverInfo, AsioDriver> loader) {
        this(info, ctl, loader, false);
    }

    private AsioSink(AsioDriverInfo info, AsioControlThread ctl, Function<AsioDriverInfo, AsioDriver> loader,
            boolean ownsControl) {
        this.info = Objects.requireNonNull(info, "info");
        this.ctl = Objects.requireNonNull(ctl, "ctl");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ownsControl = ownsControl;
    }

    /** A sink for an installed driver, with its own control thread (closed with the sink). */
    public static AsioSink forDriver(AsioDriverInfo info) {
        Objects.requireNonNull(info, "info");
        return new AsioSink(info, new AsioControlThread(), AsioSink::loadCom, true);
    }

    /** {@link ComAsioDriver#load} as a loader: its {@link AsioException} becomes an unchecked one, same message. */
    static AsioDriver loadCom(AsioDriverInfo info) {
        try {
            return ComAsioDriver.load(info);
        } catch (AsioException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return info.name();
    }

    /**
     * Selects the output pair {@code ch} and {@code ch + 1} (default 0, so outputs 0 and 1) for the next
     * {@link #open}.
     */
    public void setFirstOutputChannel(int ch) {
        if (ch < 0) {
            throw new IllegalArgumentException("first output channel must not be negative: " + ch);
        }
        firstChannel = ch;
    }

    /**
     * Stops and disposes the previous stream (even if this open is then refused), then opens one for {@code f}.
     * The returned stream already has {@code f} as its source and pumps nothing until
     * {@link SinkStream#start()}.
     *
     * @throws NotBitPerfectException if the device is there but cannot play {@code f} unchanged
     * @throws AudioDeviceException   if the driver could not be loaded or failed, or the device has gone
     *                                ({@code ASE_NotPresent} / {@code ASE_HWMalfunction}): a driver whose device was
     *                                unplugged says no to every rate, and that is not taken as a refusal. The next
     *                                open then reloads the driver.
     */
    @Override
    public SinkStream open(StreamFormat f) throws NotBitPerfectException, AudioDeviceException {
        Objects.requireNonNull(f, "f");
        requireOpen();
        return onControl(() -> {
            requireOpen(); // a close() that got to the control thread first wins
            return openOnControl(f);
        });
    }

    /** Opens the driver's own settings panel (loading the driver if needed); it may block until it is closed. */
    public void openControlPanel() throws AudioDeviceException {
        requireOpen();
        onControlDevice(() -> {
            try {
                loadedDriver().controlPanel();
            } catch (AsioException e) {
                reloadNeeded = true;
                throw new AudioDeviceException("Could not open the control panel of " + name() + ": "
                        + e.getMessage(), e);
            }
            return null;
        });
    }

    /**
     * Registers a listener for device loss while a stream is open (a rebuild after a reset request failed, or
     * the driver failed to stop). It is called on the ASIO control thread: hand the message to another thread
     * and return.
     */
    @Override
    public void addDeviceListener(Consumer<String> onDeviceLost) {
        listeners.add(Objects.requireNonNull(onDeviceLost, "onDeviceLost"));
    }

    /** Disposes the open stream and releases the driver; closes the control thread if this sink made it. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            ctl.call(() -> {
                disposeCurrent();
                releaseDriver();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e); // the task above throws nothing checked
        } finally {
            if (ownsControl) {
                ctl.close();
            }
        }
    }

    // ---- buffer units (the one place they are decided) -------------------------------------------------------

    /**
     * Bytes in one channel's half of the driver's double buffer when the driver's buffer size is
     * {@code bufferSize} samples: {@code bufferSize × containerBytes} for PCM, and {@code bufferSize / 8} for the
     * DSD types, whose samples are single bits (spec §6). This is the only place the DSD unit is decided; Task 21
     * verifies it on real DSD hardware.
     */
    static long bytesPerBuffer(SampleType type, int bufferSize) {
        return type.isDsd() ? bufferSize / 8 : (long) bufferSize * type.containerBytes();
    }

    /** Frames {@code StreamCore.fill} copies per callback: one frame is one container per channel, a byte for DSD. */
    static int fillFrames(SampleType type, int bufferSize) {
        return Math.toIntExact(bytesPerBuffer(type, bufferSize) / type.containerBytes());
    }

    /** {@code max(2 s of output frames, 4 callbacks)}; a DSD output frame is 8 bits per channel. */
    static int ringFrames(StreamFormat f, int fillFrames) {
        long perSecond = f.kind() == Kind.DSD ? f.sampleRate() / 8L : f.sampleRate();
        return Math.toIntExact(Math.max(2 * perSecond, 4L * fillFrames));
    }

    // ---- control thread -----------------------------------------------------------------------------------

    private SinkStream openOnControl(StreamFormat f) throws NotBitPerfectException, AudioDeviceException {
        disposeCurrent();
        AsioDriver d = loadedDriver();
        boolean dsd = f.kind() == Kind.DSD;
        boolean backToPcm = false;
        AsioSinkStream stream = null;
        try {
            // 1. The I/O format. DSD is only ever native; PCM-only drivers may lack future() altogether.
            if (dsd) {
                if (!d.canDoDsd()) {
                    requirePresent(d);
                    throw new NotBitPerfectException(name() + " does not offer native DSD");
                }
                backToPcm = true; // from here, a refusal puts the driver back into PCM
                try {
                    d.setIoFormat(true);
                } catch (AsioException e) {
                    // future() reports an unsupported selector as ASE_NotPresent too, so ask the device itself.
                    requirePresent(d);
                    throw new NotBitPerfectException(name() + " does not offer native DSD");
                }
            } else {
                try {
                    d.setIoFormat(false);
                } catch (AsioException ignored) {
                    // no future() support: the driver is PCM-only, which is what we want
                }
            }

            // 2. The rate: the source's own, or nothing. A "no" only counts from a device that is still there.
            int rate = f.sampleRate();
            if (!d.canSampleRate(rate)) {
                requirePresent(d);
                throw new NotBitPerfectException("rate %d Hz not supported by %s".formatted(rate, name()));
            }
            try {
                d.setSampleRate(rate);
            } catch (AsioException e) {
                if (isDeviceGone(e)) {
                    throw e; // a device error below, not a refusal
                }
                throw new NotBitPerfectException(
                        "rate %d Hz not supported by %s (%s)".formatted(rate, name(), e.getMessage()));
            }

            // 3 + 4. The output pair and its sample type.
            int first = firstChannel;
            int[] channels = {first, first + 1};
            int outputs = d.outputChannels();
            if (first + 1 >= outputs) {
                throw new AudioDeviceException("%s has no output channels %d and %d (it has %d outputs)"
                        .formatted(name(), first, first + 1, outputs), null);
            }
            SampleType type = outputType(d, channels, dsd);

            // 5. The preferred buffer size, and room in the core for any size the driver may switch to.
            BufferSize sizes = d.bufferSize();
            int fill = fillFrames(type, sizes.preferred());
            if (fill < 1) {
                throw new AudioDeviceException(name() + " reports an unusable buffer size of "
                        + sizes.preferred() + " samples", null);
            }
            int maxFill = Math.max(fill, fillFrames(type, Math.min(sizes.max(), MAX_FILL_FRAMES_CAP)));

            // 6 + 7. The core (refusing a source the type cannot carry), the buffers, the rate read back.
            stream = new AsioSinkStream(this, d, f, type, channels, maxFill, ringFrames(f, fill));
            stream.core().setSource(f);
            stream.createBuffers(sizes.preferred());
            current = stream;
            backToPcm = false;
            return stream;
        } catch (AsioException e) {
            reloadNeeded = true;
            throw new AudioDeviceException("Could not open " + name() + ": " + e.getMessage(), e);
        } finally {
            if (stream != null && current != stream) {
                stream.disposeOnControl();
            }
            if (backToPcm) {
                try {
                    d.setIoFormat(false);
                } catch (AsioException ignored) {
                    // the next open sets the format again anyway
                }
            }
        }
    }

    /** {@code ASE_NotPresent} or {@code ASE_HWMalfunction}: the device has gone or broken, whatever was asked. */
    static boolean isDeviceGone(AsioException e) {
        return e.code() == AsioConstants.ASE_NOT_PRESENT || e.code() == AsioConstants.ASE_HW_MALFUNCTION;
    }

    /**
     * Called before a boolean "no" ({@code canSampleRate}, {@code canDoDsd}) is turned into a refusal: a driver
     * whose device was unplugged answers no to everything, and the player would then skip every track instead
     * of reporting the device lost. Asks the driver something every healthy driver answers ({@code
     * getSampleRate}); if it says the device is gone, that error is thrown and {@link #open} reports a device
     * error. Any other answer means the driver is talking, so its "no" stands.
     */
    private static void requirePresent(AsioDriver d) throws AsioException {
        try {
            d.sampleRate();
        } catch (AsioException e) {
            if (isDeviceGone(e)) {
                throw e;
            }
        }
    }

    /** Both channels' sample types, which must be one type Purist writes (and a DSD type for a DSD source). */
    private SampleType outputType(AsioDriver d, int[] channels, boolean dsd)
            throws AsioException, NotBitPerfectException {
        int[] types = {d.outputChannelType(channels[0]), d.outputChannelType(channels[1])};
        for (int t : types) {
            // The UA Volt answers yes to DSD and then keeps reporting Int32LSB: only the read-back type counts.
            if (dsd && t != AsioConstants.ST_DSD_INT8_LSB1 && t != AsioConstants.ST_DSD_INT8_MSB1) {
                throw new NotBitPerfectException(
                        "%s did not switch to a DSD sample type (reported %d)".formatted(name(), t));
            }
            if (SampleType.fromAsio(t).isEmpty()) {
                throw new NotBitPerfectException("unsupported ASIO sample type " + t);
            }
        }
        if (types[0] != types[1]) {
            throw new NotBitPerfectException("%s reports different sample types on outputs %d and %d (%d, %d)"
                    .formatted(name(), channels[0], channels[1], types[0], types[1]));
        }
        return SampleType.fromAsio(types[0]).orElseThrow();
    }

    /** The driver, loading it (again, after a device error) if needed. */
    private AsioDriver loadedDriver() throws AudioDeviceException {
        if (driver != null && reloadNeeded) {
            disposeCurrent();
            releaseDriver();
        }
        if (driver == null) {
            AsioDriver d;
            try {
                d = Objects.requireNonNull(loader.apply(info), "loader returned null");
            } catch (RuntimeException e) {
                throw new AudioDeviceException(loadFailureMessage(e), e);
            }
            driver = d;
            reloadNeeded = false;
        }
        return driver;
    }

    /**
     * The text for a driver that would not load. A device that is off or unplugged makes the driver's {@code init}
     * return false, usually with no message at all, and that reads as "not available"; blaming another ASIO app
     * ({@link #BUSY_HINT}) is kept for an {@code init} that failed with a reason of its own, where the device
     * exists. A COM failure (driver not registered or broken) has nothing to do with another app.
     */
    private String loadFailureMessage(RuntimeException e) {
        if (e.getCause() instanceof AsioException ae) {
            if (deviceAbsent(ae)) {
                return name() + " is not available — check it is on and connected.";
            }
            if (!"init".equals(ae.op())) {
                return "Could not open " + name() + ": " + e.getMessage();
            }
        }
        return "Could not open " + name() + ": " + e.getMessage() + BUSY_HINT;
    }

    /** The driver reports the device as not there: {@code ASE_NotPresent}, a silent {@code init} false, or its words. */
    static boolean deviceAbsent(AsioException e) {
        if (e.code() == AsioConstants.ASE_NOT_PRESENT) {
            return true;
        }
        if (!"init".equals(e.op())) {
            return false;
        }
        String m = e.driverMessage().toLowerCase(java.util.Locale.ROOT);
        return e.code() == AsioConstants.ASE_OK && m.isEmpty()
                || m.contains("not connected") || m.contains("not present") || m.contains("not found")
                || m.contains("not available") || m.contains("no device") || m.contains("disconnected")
                || m.contains("unplugged");
    }

    private void disposeCurrent() {
        AsioSinkStream s = current;
        current = null;
        if (s != null) {
            s.disposeOnControl();
        }
    }

    private void releaseDriver() {
        AsioDriver d = driver;
        driver = null;
        if (d != null) {
            try {
                d.close();
            } catch (RuntimeException ignored) {
                // best effort: the object is being dropped either way
            }
        }
    }

    // ---- for AsioSinkStream (control thread) ----------------------------------------------------------------

    AsioControlThread control() {
        return ctl;
    }

    /** The next open releases the driver and loads it afresh. */
    void markReloadNeeded() {
        reloadNeeded = true;
    }

    void notifyDeviceLost(String message) {
        for (Consumer<String> l : listeners) {
            try {
                l.accept(message);
            } catch (RuntimeException e) {
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);
            }
        }
    }

    // ---- plumbing ------------------------------------------------------------------------------------------

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException(name() + ": this sink has been closed");
        }
    }

    /** What {@link #onControl} runs: a task that may refuse or fail like {@link #open}. */
    @FunctionalInterface
    interface ControlTask<T> {
        T run() throws NotBitPerfectException, AudioDeviceException;
    }

    /** Runs {@code task} on the control thread, rethrowing what it threw unchanged. */
    <T> T onControl(ControlTask<T> task) throws NotBitPerfectException, AudioDeviceException {
        try {
            return ctl.call(task::run);
        } catch (NotBitPerfectException | AudioDeviceException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e); // a ControlTask throws nothing else
        }
    }

    /** {@link #onControl} for a task that never refuses a format. */
    <T> T onControlDevice(ControlTask<T> task) throws AudioDeviceException {
        try {
            return onControl(task);
        } catch (NotBitPerfectException e) {
            throw new IllegalStateException("unexpected refusal", e);
        }
    }
}
