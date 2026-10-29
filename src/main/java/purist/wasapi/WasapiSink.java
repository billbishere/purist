package purist.wasapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import purist.asio.AsioControlThread;
import purist.audio.AudioDeviceException;
import purist.audio.AudioSink;
import purist.audio.Kind;
import purist.audio.NotBitPerfectException;
import purist.audio.Packer;
import purist.audio.SampleType;
import purist.audio.SinkStream;
import purist.audio.StreamFormat;
import purist.win.ComException;

/**
 * The second bit-perfect output (spec §3 "Any DAC"): one WASAPI render endpoint in event-driven exclusive mode,
 * opened at each stream's own rate and depth or not at all. The Windows mixer and its effects are never involved:
 * there is no shared-mode fallback and nothing is ever resampled.
 *
 * <p><b>Threads.</b> Every {@code IAudioClient} call runs on the sink's control thread (an {@link AsioControlThread}
 * in the multithreaded apartment, as WASAPI expects), including creating and releasing the COM objects. Each open
 * stream has a render thread that only waits for the device's event and fills its buffer; see
 * {@link WasapiSinkStream}.
 *
 * <p><b>Format.</b> PCM is offered as {@code WAVEFORMATEXTENSIBLE} integer PCM at the source rate, stereo (a mono
 * source is duplicated to both channels, as on ASIO), in the first container the device takes, lossless only:
 * the exact depth (16 or 24 or 32), then 24 valid bits in 32, then 32 with zero-padded LSBs. A source deeper than
 * the container is never tried. DSD is offered as DoP v1.1 at a sixteenth of its bit rate, 24 valid bits in a 24
 * or 32-bit container (32/32 last: the same bits on the wire). A device that takes none of these is a refusal
 * ({@link NotBitPerfectException}); a device that is missing, busy or set to refuse exclusive mode is a device
 * error ({@link AudioDeviceException}).
 *
 * <p><b>Note on DoP.</b> WASAPI cannot tell whether a DAC decodes DoP: one that does not will accept the carrier
 * PCM format and play the stream as full-scale noise. So DoP is opt-in per device: DSD is refused unless the
 * {@code dopAllowed} switch the sink was made with says the user marked this DAC as DoP-capable (Settings → Output).
 */
public final class WasapiSink implements AudioSink {

    private static final long HNS_PER_SECOND = 10_000_000L;
    /** DoP carries 16 DSD bits per channel in each PCM frame. */
    static final int DOP_BITS_PER_FRAME = 16;

    private final WasapiDevice device;
    private final AsioControlThread ctl;
    private final WasapiBackend backend;
    private final BooleanSupplier dopAllowed;
    private final boolean ownsControl;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    // Confined to the control thread.
    private WasapiSinkStream current;

    /**
     * @param ctl     the thread every COM call runs on (in the MTA); the caller keeps ownership and closes it after
     *                this sink
     * @param backend makes the audio clients, on {@code ctl}
     */
    WasapiSink(WasapiDevice device, AsioControlThread ctl, WasapiBackend backend) {
        this(device, ctl, backend, () -> false);
    }

    /** As above; {@code dopAllowed} is asked at every open of a DSD stream (see the class comment). */
    WasapiSink(WasapiDevice device, AsioControlThread ctl, WasapiBackend backend, BooleanSupplier dopAllowed) {
        this(device, ctl, backend, dopAllowed, false);
    }

    private WasapiSink(WasapiDevice device, AsioControlThread ctl, WasapiBackend backend, BooleanSupplier dopAllowed,
            boolean ownsControl) {
        this.device = Objects.requireNonNull(device, "device");
        this.ctl = Objects.requireNonNull(ctl, "ctl");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.dopAllowed = Objects.requireNonNull(dopAllowed, "dopAllowed");
        this.ownsControl = ownsControl;
    }

    /** A sink for a real endpoint, with its own control thread (closed with the sink). DSD is refused (no DoP). */
    public static WasapiSink forDevice(WasapiDevice device) {
        return forDevice(device, () -> false);
    }

    /**
     * A sink for a real endpoint, with its own control thread (closed with the sink).
     *
     * @param dopAllowed true while the user has marked this DAC as DoP-capable; read at each open, from any thread
     */
    public static WasapiSink forDevice(WasapiDevice device, BooleanSupplier dopAllowed) {
        Objects.requireNonNull(device, "device");
        AsioControlThread ctl = new AsioControlThread("wasapi-control", true);
        return new WasapiSink(device, ctl, new WasapiClient(), dopAllowed, true);
    }

    @Override
    public String name() {
        return device.name();
    }

    /**
     * Disposes the previous stream (even if this open is then refused), then opens one for {@code f}. The returned
     * stream already has {@code f} as its source and renders nothing until {@link SinkStream#start()}.
     *
     * @throws NotBitPerfectException if the device is there but takes no lossless exclusive format for {@code f}
     * @throws AudioDeviceException   if the device is missing, in use by another app, refuses exclusive mode, or
     *                                failed
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

    @Override
    public void addDeviceListener(Consumer<String> onDeviceLost) {
        listeners.add(Objects.requireNonNull(onDeviceLost, "onDeviceLost"));
    }

    /** Disposes the open stream and releases every COM object; closes the control thread if this sink made it. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            ctl.call(() -> {
                try {
                    disposeCurrent();
                } finally {
                    backend.close();
                }
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

    // ---- negotiation (pure) ----------------------------------------------------------------------------------

    /** The exclusive formats offered for {@code f}, in the order tried. All are lossless for {@code f}. */
    static List<WaveFormat> candidates(StreamFormat f) {
        List<WaveFormat> c = new ArrayList<>(3);
        if (f.kind() == Kind.DSD) {
            int rate = f.sampleRate() / DOP_BITS_PER_FRAME;
            c.add(new WaveFormat(rate, 2, 24, 24));
            c.add(new WaveFormat(rate, 2, 32, 24));
            c.add(new WaveFormat(rate, 2, 32, 32));
            return c;
        }
        int bits = f.bits();
        if (bits == 16 || bits == 24 || bits == 32) {
            c.add(new WaveFormat(f.sampleRate(), 2, bits, bits));
        }
        if (bits <= 24) {
            c.add(new WaveFormat(f.sampleRate(), 2, 32, 24));
        }
        if (bits != 32) {
            c.add(new WaveFormat(f.sampleRate(), 2, 32, 32));
        }
        return c;
    }

    /**
     * The sample type the {@link purist.audio.StreamCore} packs into for {@code w}: the container, with the sample
     * left-justified, which is exactly what WASAPI's {@code wValidBitsPerSample} expects. DoP packs DSD bytes,
     * MSB-first, two per device frame.
     */
    static SampleType coreType(WaveFormat w, boolean dop) {
        if (dop) {
            return SampleType.DSD_INT8_MSB1;
        }
        return switch (w.containerBits()) {
            case 16 -> SampleType.INT16_LSB;
            case 24 -> SampleType.INT24_LSB;
            default -> SampleType.INT32_LSB;
        };
    }

    /** The period that makes a buffer of {@code frames} at {@code rate}, as the {@code IAudioClient::Initialize} docs compute it. */
    static long alignedPeriod(int frames, int rate) {
        return (long) ((double) HNS_PER_SECOND * frames / rate + 0.5);
    }

    // ---- control thread -----------------------------------------------------------------------------------

    private SinkStream openOnControl(StreamFormat f) throws NotBitPerfectException, AudioDeviceException {
        disposeCurrent();
        boolean dop = f.kind() == Kind.DSD;
        // Channel count and depth first, so a multichannel or odd source is refused without touching the device.
        Packer.create(f, dop ? SampleType.DSD_INT8_MSB1 : SampleType.INT32_LSB);
        if (dop && !dopAllowed.getAsBoolean()) {
            throw new NotBitPerfectException("DSD needs DoP — enable \"supports DoP\" for " + name()
                    + " in Settings if your DAC supports it");
        }

        AudioClient client = null;
        WasapiSinkStream stream = null;
        try {
            client = backend.activate(device.id());
            WaveFormat w = negotiate(client, f, dop);
            Packer.create(f, coreType(w, dop)); // cannot fail for a candidate; checked anyway
            if (!dop && f.bits() > w.validBits()) { // likewise: the device would drop the low bits
                throw new NotBitPerfectException("%d-bit source does not fit %s's %d valid bits"
                        .formatted(f.bits(), name(), w.validBits()));
            }

            long period = client.defaultDevicePeriod();
            int hr = client.initialize(w, period);
            if (hr == WasapiErrors.AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
                // Docs: GetBufferSize now gives the next aligned size; start again on a fresh client with it.
                period = alignedPeriod(client.bufferSize(), w.rate());
                AudioClient old = client;
                client = null;
                old.release();
                client = backend.activate(device.id());
                hr = client.initialize(w, period);
            }
            if (hr < 0) {
                if (WasapiErrors.deviceProblem(hr, name()) != null) {
                    throw new ComException("IAudioClient::Initialize", hr); // gone, busy or barred: a device error
                }
                // IsFormatSupported said yes and Initialize still will not take it (a bad buffer size or period, an
                // argument it dislikes): the device is fine, this format on it is not. Skip the track, keep the device.
                throw new NotBitPerfectException("%s refused %s @ %d Hz in exclusive mode (%s)"
                        .formatted(name(), w.describe(), w.rate(), WasapiErrors.describe(hr)));
            }
            int bufferFrames = client.bufferSize();
            stream = new WasapiSinkStream(this, client, f, w, dop, bufferFrames);
            client = null; // the stream owns it now, even if what follows fails
            stream.core().setSource(f);
            current = stream;
            return stream;
        } catch (ComException e) {
            String problem = WasapiErrors.deviceProblem(e.hresult(), name());
            throw new AudioDeviceException(problem != null ? problem : "Could not open " + name() + ": " + e.getMessage(),
                    e);
        } finally {
            if (client != null) {
                client.release();
            }
            if (stream != null && current != stream) {
                stream.disposeOnControl();
            }
        }
    }

    /**
     * The first candidate the device takes in exclusive mode. An answer that says the device itself is gone, busy
     * or barred from exclusive mode is thrown as that, never read as a refusal.
     */
    private WaveFormat negotiate(AudioClient client, StreamFormat f, boolean dop)
            throws NotBitPerfectException, ComException {
        for (WaveFormat w : candidates(f)) {
            int hr = client.isFormatSupported(w);
            if (hr == WasapiErrors.S_OK) {
                return w;
            }
            if (WasapiErrors.deviceProblem(hr, name()) != null) {
                throw new ComException("IAudioClient::IsFormatSupported", hr);
            }
        }
        if (dop) {
            throw new NotBitPerfectException("DSD over DoP not supported by " + name());
        }
        throw new NotBitPerfectException("rate %d Hz / %d-bit not supported by %s in exclusive mode"
                .formatted(f.sampleRate(), f.bits(), name()));
    }

    private void disposeCurrent() {
        WasapiSinkStream s = current;
        current = null;
        if (s != null) {
            s.disposeOnControl();
        }
    }

    // ---- for WasapiSinkStream ------------------------------------------------------------------------------

    AsioControlThread control() {
        return ctl;
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
