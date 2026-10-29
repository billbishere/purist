package purist.asio;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A programmable, in-memory {@link AsioDriver} for the {@link AsioSink} tests.
 *
 * <p><b>Threading.</b> Like {@link ComAsioDriver} it belongs to the thread that loaded it: a control call made on
 * any other thread is refused with {@link IllegalStateException} and recorded in {@link #violations()}. Only
 * {@link #outputReady()} and {@link #buffer} are free-threaded.
 *
 * <p><b>Nothing happens on its own.</b> {@link #tick()} plays one buffer: while started it calls
 * {@code bufferSwitch} on the <em>calling</em> thread, alternating the halves, then appends what the callback
 * wrote to a per-channel capture. {@link #fire} delivers an event on the calling thread. Tests are therefore
 * deterministic.
 *
 * <p><b>Buffers</b> follow the driver convention the sink has to match, worked out here independently of the
 * sink: {@code bufferFrames} × container bytes per channel for PCM types, {@code bufferFrames / 8} bytes for the
 * DSD types (spec §6). {@link #buffer} refuses any other {@code byteSize}, and the memory is freed by
 * {@link #disposeBuffers()}, so a stale segment fails loudly instead of being silently written.
 *
 * <p>Every control call is logged as a string, for example {@code "setSampleRate:96000.0"},
 * {@code "setIoFormat:PCM"} or {@code "createBuffers:[0, 1]:512"}.
 */
final class FakeAsioDriver implements AsioDriver {

    /** What the buffers hold before a callback writes them: neither PCM nor DSD silence. */
    private static final byte POISON = (byte) 0xA5;

    // ---- settings; change them before (or between) the sink's calls ------------------------------------

    /** Rates {@code canSampleRate} accepts: PCM sample rates and DSD bit rates alike. */
    volatile Set<Integer> rates = Set.of(44100, 48000, 88200, 96000, 176400, 192000);
    /** The channel type reported in PCM mode. */
    volatile int pcmType = AsioConstants.ST_INT32_LSB;
    /**
     * Whether {@code canDoDsd} says yes. A driver that cannot also fails every {@code setIoFormat}, as one without
     * {@code future} support does.
     */
    volatile boolean dsdCapable;
    /** The channel type reported after {@code setIoFormat(DSD)}: 33, or 18 to emulate the UA Volt's false "yes". */
    volatile int dsdTypeAfterSwitch = AsioConstants.ST_DSD_INT8_MSB1;
    /** {@code getBufferSize}'s preferred size, in samples. */
    volatile int preferred = 512;
    /** {@code getBufferSize}'s largest size, in samples. */
    volatile int maxBufferSize = 4096;
    volatile int outputs = 2;
    volatile boolean failOnLoad;
    /** When set, {@link #failOnLoad} fails with this instead of "device in use" (ASE_HWMalfunction). */
    volatile AsioException loadError;
    volatile boolean failOnStart;
    /**
     * The device was pulled out while the driver stayed loaded: {@code canSampleRate} and {@code canDoDsd} answer
     * no, and every call that needs the hardware fails with {@code ASE_NotPresent}. Teardown ({@code stop},
     * {@code disposeBuffers}, {@code close}) still works.
     */
    volatile boolean unplugged;
    /** When not 0, {@code setSampleRate} fails with this ASIOError even for a rate {@code canSampleRate} accepts. */
    volatile int setSampleRateFailure;

    // ---- state -----------------------------------------------------------------------------------------

    private final String name;
    private final List<String> log = new ArrayList<>();          // guarded by this
    private final List<String> violations = new ArrayList<>();   // guarded by this
    private final ByteArrayOutputStream[] capture = {new ByteArrayOutputStream(), new ByteArrayOutputStream()};
    private final AtomicInteger outputReadyCalls = new AtomicInteger();

    private Thread owner;                  // guarded by this
    private boolean closed = true;         // guarded by this; true until the loader hands the driver out
    private boolean dsdMode;               // guarded by this
    private double rate = 44100;           // guarded by this
    private Arena arena;                   // guarded by this; non-null while buffers exist
    private MemorySegment[][] memory;      // guarded by this; [half][channel index]
    private long bufferBytes;              // guarded by this
    private volatile AsioCallbacks callbacks;
    private boolean started;               // guarded by this
    private int nextHalf;                  // guarded by this

    FakeAsioDriver(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** The registry entry a sink is created for. */
    AsioDriverInfo info() {
        return new AsioDriverInfo(name, "{00000000-0000-0000-0000-000000000000}");
    }

    /**
     * The loader to give the sink. Each call makes the calling thread the owner (as {@code ComAsioDriver.load}
     * does) and hands out this same instance, or fails like a busy device when {@link #failOnLoad} is set.
     */
    Function<AsioDriverInfo, AsioDriver> loader() {
        return this::load;
    }

    private synchronized AsioDriver load(AsioDriverInfo info) {
        owner = Thread.currentThread();
        log.add("load");
        if (failOnLoad) {
            AsioException e = loadError != null ? loadError
                    : new AsioException("init", AsioConstants.ASE_HW_MALFUNCTION, "device in use");
            throw new IllegalStateException(e.getMessage(), e);
        }
        closed = false;
        return this;
    }

    // ---- test controls -------------------------------------------------------------------------------------

    /**
     * One driver callback, on the calling thread: {@code bufferSwitch} for the next half, then the half's
     * contents are appended to the capture.
     *
     * @return false (and nothing happens) unless the driver is started
     */
    synchronized boolean tick() {
        if (!started) {
            return false;
        }
        int half = nextHalf;
        nextHalf ^= 1;
        callbacks.bufferSwitch(half);
        for (int c = 0; c < capture.length; c++) {
            capture[c].writeBytes(memory[half][c].toArray(ValueLayout.JAVA_BYTE));
        }
        return true;
    }

    /** Delivers {@code e} to the installed callbacks on the calling thread, as the driver's own thread would. */
    void fire(AsioEvent e) {
        AsioCallbacks cb = callbacks;
        if (cb == null) {
            throw new IllegalStateException("no callbacks installed: createBuffers has not been called");
        }
        int value;
        synchronized (this) {
            value = switch (e) {
                case BUFFER_SIZE_CHANGE -> preferred;
                case SAMPLE_RATE_CHANGED -> (int) Math.round(rate);
                default -> 0;
            };
        }
        cb.event(e, value);
    }

    /** Everything played on channel index {@code index} (0 or 1) since this fake was made, in order. */
    synchronized byte[] captured(int index) {
        return capture[index].toByteArray();
    }

    /** The control calls so far, in order. */
    synchronized List<String> log() {
        return List.copyOf(log);
    }

    /** Control calls that arrived on the wrong thread; should stay empty. */
    synchronized List<String> violations() {
        return List.copyOf(violations);
    }

    int outputReadyCalls() {
        return outputReadyCalls.get();
    }

    synchronized boolean started() {
        return started;
    }

    // ---- AsioDriver --------------------------------------------------------------------------------------

    @Override
    public String name() {
        return name;
    }

    @Override
    public synchronized int outputChannels() throws AsioException {
        control("outputChannels");
        present("getChannels");
        return outputs;
    }

    @Override
    public synchronized BufferSize bufferSize() throws AsioException {
        control("bufferSize");
        present("getBufferSize");
        return new BufferSize(Math.min(64, preferred), Math.max(maxBufferSize, preferred), preferred, -1);
    }

    @Override
    public synchronized boolean canSampleRate(double r) {
        control("canSampleRate:" + r);
        return !unplugged && r == Math.rint(r) && rates.contains((int) r);
    }

    @Override
    public synchronized void setSampleRate(double r) throws AsioException {
        control("setSampleRate:" + r);
        present("setSampleRate");
        if (setSampleRateFailure != 0) {
            throw new AsioException("setSampleRate", setSampleRateFailure, "");
        }
        if (r != Math.rint(r) || !rates.contains((int) r)) {
            throw new AsioException("setSampleRate", AsioConstants.ASE_NO_CLOCK, "");
        }
        rate = r;
    }

    @Override
    public synchronized double sampleRate() throws AsioException {
        control("sampleRate");
        present("getSampleRate");
        return rate;
    }

    @Override
    public synchronized boolean canDoDsd() {
        control("canDoDsd");
        return dsdCapable && !unplugged;
    }

    @Override
    public synchronized void setIoFormat(boolean dsd) throws AsioException {
        control("setIoFormat:" + (dsd ? "DSD" : "PCM"));
        if (!dsdCapable || unplugged) {
            throw new AsioException("setIoFormat", AsioConstants.ASE_NOT_PRESENT, "");
        }
        dsdMode = dsd;
    }

    @Override
    public synchronized int outputChannelType(int channel) throws AsioException {
        control("outputChannelType:" + channel);
        present("getChannelInfo");
        if (channel < 0 || channel >= outputs) {
            throw new AsioException("getChannelInfo", AsioConstants.ASE_INVALID_PARAMETER, "");
        }
        return currentType();
    }

    @Override
    public synchronized void createBuffers(int[] outputChannels, int bufferFrames, AsioCallbacks cb)
            throws AsioException {
        control("createBuffers:" + Arrays.toString(outputChannels) + ":" + bufferFrames);
        present("createBuffers");
        Objects.requireNonNull(cb, "cb");
        if (arena != null) {
            throw new IllegalStateException("buffers already created; call disposeBuffers first");
        }
        for (int ch : outputChannels) {
            if (ch < 0 || ch >= outputs) {
                throw new AsioException("createBuffers", AsioConstants.ASE_INVALID_PARAMETER, "");
            }
        }
        bufferBytes = bytesPerChannel(currentType(), bufferFrames);
        arena = Arena.ofShared();
        memory = new MemorySegment[2][outputChannels.length];
        for (MemorySegment[] half : memory) {
            for (int c = 0; c < half.length; c++) {
                half[c] = arena.allocate(bufferBytes);
                half[c].fill(POISON);
            }
        }
        callbacks = cb;
        nextHalf = 0;
    }

    @Override
    public synchronized MemorySegment buffer(int index, int half, long byteSize) {
        if (memory == null) {
            throw new IllegalStateException("no buffers: createBuffers has not been called");
        }
        if (byteSize != bufferBytes) {
            throw new IllegalArgumentException("asked for " + byteSize + " bytes, but the driver's buffers hold "
                    + bufferBytes);
        }
        return memory[half][index];
    }

    @Override
    public synchronized void start() throws AsioException {
        control("start");
        present("start");
        if (arena == null) {
            throw new IllegalStateException("createBuffers must be called before start");
        }
        if (failOnStart) {
            throw new AsioException("start", AsioConstants.ASE_NOT_PRESENT, "device unplugged");
        }
        started = true;
        nextHalf = 0;
    }

    @Override
    public synchronized void stop() {
        control("stop");
        started = false;
    }

    @Override
    public synchronized void disposeBuffers() {
        control("disposeBuffers");
        freeBuffers();
    }

    @Override
    public void outputReady() {
        outputReadyCalls.incrementAndGet();
    }

    @Override
    public synchronized void controlPanel() {
        control("controlPanel");
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        control("close");
        started = false;
        freeBuffers();
        closed = true;
    }

    // ---- plumbing ------------------------------------------------------------------------------------------

    private void control(String call) {
        Thread t = Thread.currentThread();
        if (t != owner) {
            String v = call + " on " + t.getName();
            violations.add(v);
            throw new IllegalStateException(name + " must be used on the thread that loaded it: " + v);
        }
        if (closed) {
            throw new IllegalStateException(name + " has been closed (" + call + ")");
        }
        log.add(call);
    }

    /** What a driver whose device has gone answers to {@code op}. */
    private void present(String op) throws AsioException {
        if (unplugged) {
            throw new AsioException(op, AsioConstants.ASE_NOT_PRESENT, "");
        }
    }

    private int currentType() {
        return dsdMode ? dsdTypeAfterSwitch : pcmType;
    }

    private void freeBuffers() {
        started = false;
        callbacks = null;
        memory = null;
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    /** The driver's own notion of a buffer's size per channel; deliberately not shared with the sink's code. */
    private static long bytesPerChannel(int type, int frames) {
        return switch (type) {
            case AsioConstants.ST_DSD_INT8_LSB1, AsioConstants.ST_DSD_INT8_MSB1 -> frames / 8;
            case AsioConstants.ST_INT16_LSB -> frames * 2L;
            case AsioConstants.ST_INT24_LSB -> frames * 3L;
            case AsioConstants.ST_FLOAT64_LSB -> frames * 8L;
            default -> frames * 4L;
        };
    }
}
