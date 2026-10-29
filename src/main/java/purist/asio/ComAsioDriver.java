package purist.asio;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import purist.win.ComException;
import purist.win.ComObject;
import purist.win.Native;
import purist.win.Ole32;

/**
 * An ASIO driver driven through its COM vtable with the foreign function API.
 *
 * <p><b>Threading.</b> The driver object lives in the single-threaded apartment of the thread that
 * called {@link #load}; every method except {@link #name()}, {@link #outputReady()}, {@link #buffer} and
 * {@link #callbackErrors()} must be called on that same thread (the ASIO control thread) and fails fast
 * with {@link IllegalStateException} otherwise. The driver calls the {@link AsioCallbacks} on its own
 * thread through upcall stubs that never let an exception escape.
 *
 * <p><b>Lifetime.</b> The upcall stubs, the callback table and the buffer-info array live in a shared
 * arena created by {@link #createBuffers} and closed by {@link #disposeBuffers} only after the driver's
 * own {@code disposeBuffers} has returned. If that driver call fails, the arena is deliberately left
 * open (a small leak) rather than freeing memory the driver may still call into.
 */
public final class ComAsioDriver implements AsioDriver {

    // Each vtable slot is always bound with the same descriptor instance (ComObject binds lazily and
    // refuses to rebind a slot with a different one). Slots shared by shape are noted per constant.

    /** start, stop, disposeBuffers, controlPanel, outputReady. */
    private static final FunctionDescriptor FD_NO_ARGS = FunctionDescriptor.of(JAVA_INT);
    /** init(sysHandle). */
    private static final FunctionDescriptor FD_INIT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    /** getErrorMessage(char[124]). */
    private static final FunctionDescriptor FD_GET_ERROR_MESSAGE = FunctionDescriptor.ofVoid(ADDRESS);
    /** getChannels(long* in, long* out). */
    private static final FunctionDescriptor FD_GET_CHANNELS = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    /** getBufferSize(long* min, long* max, long* preferred, long* granularity). */
    private static final FunctionDescriptor FD_GET_BUFFER_SIZE =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    /** canSampleRate(rate) and setSampleRate(rate). */
    private static final FunctionDescriptor FD_RATE_ARG = FunctionDescriptor.of(JAVA_INT, JAVA_DOUBLE);
    /** getSampleRate(double*). */
    private static final FunctionDescriptor FD_GET_SAMPLE_RATE = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    /** getChannelInfo(ASIOChannelInfo*). */
    private static final FunctionDescriptor FD_GET_CHANNEL_INFO = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    /** createBuffers(ASIOBufferInfo*, long numChannels, long bufferSize, ASIOCallbacks*). */
    private static final FunctionDescriptor FD_CREATE_BUFFERS =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
    /** future(long selector, void* option). */
    private static final FunctionDescriptor FD_FUTURE = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);

    // The callback signatures. The pointer arguments are declared as JAVA_LONG: on x64 Windows a pointer
    // and a 64-bit integer travel in the same register, and this way the upcall boxes no MemorySegment
    // on the driver's real-time thread. ASIO's `long` is 32 bits here.
    private static final FunctionDescriptor FD_BUFFER_SWITCH = FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor FD_SAMPLE_RATE_DID_CHANGE = FunctionDescriptor.ofVoid(JAVA_DOUBLE);
    private static final FunctionDescriptor FD_ASIO_MESSAGE =
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG);
    private static final FunctionDescriptor FD_BUFFER_SWITCH_TIME_INFO =
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);

    private static final MethodHandle UP_BUFFER_SWITCH =
            upcallTarget("bufferSwitch", void.class, int.class, int.class);
    private static final MethodHandle UP_SAMPLE_RATE_DID_CHANGE =
            upcallTarget("sampleRateDidChange", void.class, double.class);
    private static final MethodHandle UP_ASIO_MESSAGE =
            upcallTarget("asioMessage", int.class, int.class, int.class, long.class, long.class);
    private static final MethodHandle UP_BUFFER_SWITCH_TIME_INFO =
            upcallTarget("bufferSwitchTimeInfo", long.class, long.class, int.class, int.class);

    private static final MethodHandle GET_DESKTOP_WINDOW =
            Native.fn("user32", "GetDesktopWindow", FunctionDescriptor.of(ADDRESS));

    private static final long BUFFER_INFO_SIZE = AsioLayouts.BUFFER_INFO.byteSize();
    private static final long BUFFER_INFO_IS_INPUT = AsioLayouts.BUFFER_INFO.byteOffset(groupElement("isInput"));
    private static final long BUFFER_INFO_CHANNEL_NUM = AsioLayouts.BUFFER_INFO.byteOffset(groupElement("channelNum"));
    private static final long BUFFER_INFO_BUFFERS = AsioLayouts.BUFFER_INFO.byteOffset(groupElement("buffers"));
    private static final long CHANNEL_INFO_CHANNEL = AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("channel"));
    private static final long CHANNEL_INFO_IS_INPUT = AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("isInput"));
    private static final long CHANNEL_INFO_TYPE = AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("type"));
    private static final long CALLBACKS_BUFFER_SWITCH = AsioLayouts.CALLBACKS.byteOffset(groupElement("bufferSwitch"));
    private static final long CALLBACKS_SAMPLE_RATE_DID_CHANGE =
            AsioLayouts.CALLBACKS.byteOffset(groupElement("sampleRateDidChange"));
    private static final long CALLBACKS_ASIO_MESSAGE = AsioLayouts.CALLBACKS.byteOffset(groupElement("asioMessage"));
    private static final long CALLBACKS_BUFFER_SWITCH_TIME_INFO =
            AsioLayouts.CALLBACKS.byteOffset(groupElement("bufferSwitchTimeInfo"));
    private static final long IO_FORMAT_FORMAT_TYPE = AsioLayouts.IO_FORMAT.byteOffset(groupElement("formatType"));

    /** The SDK reserves 124 characters for an error message; a little more keeps the terminator in. */
    private static final long ERROR_MESSAGE_BYTES = 128;
    private static final Charset ANSI = Charset.forName("windows-1252");

    /** Everything {@link #createBuffers} allocates that the driver and the callback thread rely on. */
    private record Session(Arena arena, MemorySegment infos, int channels, MethodHandle outputReady) {
    }

    private final AsioDriverInfo info;
    private final ComObject com;
    private final Thread controlThread = Thread.currentThread();
    private final AtomicLong callbackErrors = new AtomicLong();

    /** Written by the control thread, read by the callback thread; null while no buffers exist. */
    private volatile Session session;
    private boolean started;
    private boolean closed;

    private ComAsioDriver(AsioDriverInfo info, ComObject com) {
        this.info = info;
        this.com = com;
    }

    /**
     * Creates the driver's COM object and initialises it. The calling thread becomes the control thread
     * and must already be in a single-threaded apartment ({@link Ole32#coInitializeSta()}).
     *
     * @throws AsioException if COM cannot create the object ({@code op} "CoCreateInstance", code the
     *         HRESULT) or the driver's {@code init} returns false (code 0, with the driver's message)
     */
    public static ComAsioDriver load(AsioDriverInfo info) throws AsioException {
        Objects.requireNonNull(info, "info");
        ComObject com;
        try {
            com = Ole32.coCreateInstance(info.clsid(), info.clsid());
        } catch (ComException e) {
            throw new AsioException("CoCreateInstance", e.hresult(), "");
        }
        ComAsioDriver driver = new ComAsioDriver(info, com);
        boolean initialised = false;
        try {
            driver.init();
            initialised = true;
            return driver;
        } finally {
            if (!initialised) {
                com.release();
            }
        }
    }

    private void init() throws AsioException {
        MemorySegment hwnd;
        try {
            hwnd = (MemorySegment) GET_DESKTOP_WINDOW.invokeExact();
        } catch (Throwable t) {
            throw unchecked("GetDesktopWindow", t);
        }
        // init returns an ASIOBool: non-zero is success.
        if (callInt("init", AsioConstants.INIT, FD_INIT, hwnd) == 0) {
            throw new AsioException("init", 0, driverMessage());
        }
    }

    @Override
    public String name() {
        return info.name();
    }

    @Override
    public int outputChannels() throws AsioException {
        live();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment in = a.allocate(JAVA_INT);
            MemorySegment out = a.allocate(JAVA_INT);
            check("getChannels", callInt("getChannels", AsioConstants.GET_CHANNELS, FD_GET_CHANNELS, in, out));
            return out.get(JAVA_INT, 0);
        }
    }

    @Override
    public BufferSize bufferSize() throws AsioException {
        live();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment v = a.allocate(4L * JAVA_INT.byteSize(), JAVA_INT.byteAlignment());
            check("getBufferSize", callInt("getBufferSize", AsioConstants.GET_BUFFER_SIZE, FD_GET_BUFFER_SIZE,
                    v, v.asSlice(4), v.asSlice(8), v.asSlice(12)));
            return new BufferSize(v.getAtIndex(JAVA_INT, 0), v.getAtIndex(JAVA_INT, 1),
                    v.getAtIndex(JAVA_INT, 2), v.getAtIndex(JAVA_INT, 3));
        }
    }

    @Override
    public boolean canSampleRate(double rate) {
        live();
        return callInt("canSampleRate", AsioConstants.CAN_SAMPLE_RATE, FD_RATE_ARG, rate) == AsioConstants.ASE_OK;
    }

    @Override
    public void setSampleRate(double rate) throws AsioException {
        live();
        check("setSampleRate", callInt("setSampleRate", AsioConstants.SET_SAMPLE_RATE, FD_RATE_ARG, rate));
    }

    @Override
    public double sampleRate() throws AsioException {
        live();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment rate = a.allocate(JAVA_DOUBLE);
            check("getSampleRate", callInt("getSampleRate", AsioConstants.GET_SAMPLE_RATE, FD_GET_SAMPLE_RATE, rate));
            return rate.get(JAVA_DOUBLE, 0);
        }
    }

    @Override
    public boolean canDoDsd() {
        live();
        return isFutureSuccess(future("canDoIoFormat", AsioConstants.K_ASIO_CAN_DO_IO_FORMAT, AsioConstants.FORMAT_DSD));
    }

    @Override
    public void setIoFormat(boolean dsd) throws AsioException {
        live();
        int rc = future("setIoFormat", AsioConstants.K_ASIO_SET_IO_FORMAT,
                dsd ? AsioConstants.FORMAT_DSD : AsioConstants.FORMAT_PCM);
        if (!isFutureSuccess(rc)) {
            throw failure("setIoFormat", rc);
        }
    }

    @Override
    public int outputChannelType(int channel) throws AsioException {
        live();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment channelInfo = a.allocate(AsioLayouts.CHANNEL_INFO);
            channelInfo.set(JAVA_INT, CHANNEL_INFO_CHANNEL, channel);
            channelInfo.set(JAVA_INT, CHANNEL_INFO_IS_INPUT, 0);
            check("getChannelInfo",
                    callInt("getChannelInfo", AsioConstants.GET_CHANNEL_INFO, FD_GET_CHANNEL_INFO, channelInfo));
            return channelInfo.get(JAVA_INT, CHANNEL_INFO_TYPE);
        }
    }

    @Override
    public void createBuffers(int[] outputChannels, int bufferFrames, AsioCallbacks cb) throws AsioException {
        live();
        Objects.requireNonNull(outputChannels, "outputChannels");
        Objects.requireNonNull(cb, "cb");
        if (outputChannels.length == 0) {
            throw new IllegalArgumentException("no output channels");
        }
        if (bufferFrames <= 0) {
            throw new IllegalArgumentException("bufferFrames must be positive: " + bufferFrames);
        }
        if (session != null) {
            throw new IllegalStateException("buffers already created; call disposeBuffers first");
        }
        int count = outputChannels.length;
        Arena arena = Arena.ofShared();
        boolean created = false;
        try {
            MemorySegment infos = arena.allocate(BUFFER_INFO_SIZE * count, AsioLayouts.BUFFER_INFO.byteAlignment());
            for (int i = 0; i < count; i++) {
                infos.set(JAVA_INT, i * BUFFER_INFO_SIZE + BUFFER_INFO_IS_INPUT, 0);
                infos.set(JAVA_INT, i * BUFFER_INFO_SIZE + BUFFER_INFO_CHANNEL_NUM, outputChannels[i]);
            }

            Upcalls upcalls = new Upcalls(cb, callbackErrors);
            MemorySegment callbacks = arena.allocate(AsioLayouts.CALLBACKS);
            callbacks.set(ADDRESS, CALLBACKS_BUFFER_SWITCH,
                    stub(UP_BUFFER_SWITCH.bindTo(upcalls), FD_BUFFER_SWITCH, arena));
            callbacks.set(ADDRESS, CALLBACKS_SAMPLE_RATE_DID_CHANGE,
                    stub(UP_SAMPLE_RATE_DID_CHANGE.bindTo(upcalls), FD_SAMPLE_RATE_DID_CHANGE, arena));
            callbacks.set(ADDRESS, CALLBACKS_ASIO_MESSAGE,
                    stub(UP_ASIO_MESSAGE.bindTo(upcalls), FD_ASIO_MESSAGE, arena));
            callbacks.set(ADDRESS, CALLBACKS_BUFFER_SWITCH_TIME_INFO,
                    stub(UP_BUFFER_SWITCH_TIME_INFO.bindTo(upcalls), FD_BUFFER_SWITCH_TIME_INFO, arena));

            // Bind the one slot the callback thread uses now: ComObject binds lazily and a first bind
            // allocates and locks, which the real-time thread must never do.
            MethodHandle outputReady = com.method(AsioConstants.OUTPUT_READY, FD_NO_ARGS);

            check("createBuffers", callInt("createBuffers", AsioConstants.CREATE_BUFFERS, FD_CREATE_BUFFERS,
                    infos, count, bufferFrames, callbacks));
            session = new Session(arena, infos, count, outputReady);
            created = true;
        } finally {
            if (!created) {
                arena.close();
            }
        }
    }

    @Override
    public MemorySegment buffer(int index, int half, long byteSize) {
        Session s = session;
        if (s == null) {
            throw new IllegalStateException("no buffers: createBuffers has not been called");
        }
        Objects.checkIndex(index, s.channels());
        Objects.checkIndex(half, 2);
        long offset = index * BUFFER_INFO_SIZE + BUFFER_INFO_BUFFERS + half * ADDRESS.byteSize();
        return s.infos().get(ADDRESS, offset).reinterpret(byteSize);
    }

    @Override
    public void start() throws AsioException {
        live();
        if (session == null) {
            throw new IllegalStateException("createBuffers must be called before start");
        }
        check("start", callInt("start", AsioConstants.START, FD_NO_ARGS));
        started = true;
    }

    @Override
    public void stop() throws AsioException {
        live();
        check("stop", callInt("stop", AsioConstants.STOP, FD_NO_ARGS));
        started = false;
    }

    @Override
    public void disposeBuffers() throws AsioException {
        live();
        Session s = session;
        if (s == null) {
            return;
        }
        // If the driver refuses, the arena stays open: it may still call the stubs.
        check("disposeBuffers", callInt("disposeBuffers", AsioConstants.DISPOSE_BUFFERS, FD_NO_ARGS));
        session = null;
        s.arena().close();
    }

    @Override
    public void outputReady() {
        Session s = session;
        if (s == null) {
            return;
        }
        try {
            int ignored = (int) s.outputReady().invokeExact();
        } catch (Throwable t) {
            callbackErrors.incrementAndGet();
        }
    }

    @Override
    public void controlPanel() throws AsioException {
        live();
        check("controlPanel", callInt("controlPanel", AsioConstants.CONTROL_PANEL, FD_NO_ARGS));
    }

    /** How many times a callback threw (and was swallowed) since this driver was loaded. */
    public long callbackErrors() {
        return callbackErrors.get();
    }

    /**
     * Stops and disposes if still needed (ignoring driver errors), then releases the COM object. Like
     * every other control call it must run on the thread that loaded the driver. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        requireControlThread();
        try {
            if (started) {
                try {
                    stop();
                } catch (AsioException | RuntimeException ignored) {
                    // best effort: the object is about to be released anyway
                }
            }
            if (session != null) {
                try {
                    disposeBuffers();
                } catch (AsioException | RuntimeException ignored) {
                    // best effort; the arena then stays open, see the class comment
                }
            }
        } finally {
            closed = true;
            try {
                com.release();
            } catch (RuntimeException ignored) {
                // nothing useful left to do
            }
        }
    }

    // ---- plumbing -----------------------------------------------------------------------------------

    private void live() {
        if (closed) {
            throw new IllegalStateException(info.name() + " has been closed");
        }
        requireControlThread();
    }

    private void requireControlThread() {
        if (Thread.currentThread() != controlThread) {
            throw new IllegalStateException(info.name() + " must be used on the thread that loaded it ("
                    + controlThread.getName() + "), not " + Thread.currentThread().getName());
        }
    }

    private int callInt(String op, int slot, FunctionDescriptor fd, Object... args) {
        try {
            return (int) com.method(slot, fd).invokeWithArguments(args);
        } catch (Throwable t) {
            throw unchecked(op, t);
        }
    }

    private int future(String op, int selector, int formatType) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment format = a.allocate(AsioLayouts.IO_FORMAT);
            format.set(JAVA_INT, IO_FORMAT_FORMAT_TYPE, formatType);
            return callInt(op, AsioConstants.FUTURE, FD_FUTURE, selector, format);
        }
    }

    private static boolean isFutureSuccess(int rc) {
        return rc == AsioConstants.ASE_SUCCESS || rc == AsioConstants.ASE_OK;
    }

    private void check(String op, int rc) throws AsioException {
        if (rc != AsioConstants.ASE_OK) {
            throw failure(op, rc);
        }
    }

    private AsioException failure(String op, int rc) {
        return new AsioException(op, rc, driverMessage());
    }

    /** The driver's own explanation of its last failure, or "" if it has none or cannot be asked. */
    private String driverMessage() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buffer = a.allocate(ERROR_MESSAGE_BYTES);
            callVoid("getErrorMessage", AsioConstants.GET_ERROR_MESSAGE, FD_GET_ERROR_MESSAGE, buffer);
            return buffer.getString(0, ANSI).strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void callVoid(String op, int slot, FunctionDescriptor fd, Object... args) {
        try {
            com.method(slot, fd).invokeWithArguments(args);
        } catch (Throwable t) {
            throw unchecked(op, t);
        }
    }

    private static RuntimeException unchecked(String op, Throwable t) {
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof RuntimeException r) {
            return r;
        }
        return new IllegalStateException(op + " failed", t);
    }

    private static MemorySegment stub(MethodHandle target, FunctionDescriptor fd, Arena arena) {
        return Native.LINKER.upcallStub(target, fd, arena);
    }

    private static MethodHandle upcallTarget(String name, Class<?> returnType, Class<?>... parameterTypes) {
        try {
            return MethodHandles.lookup().findVirtual(Upcalls.class, name,
                    MethodType.methodType(returnType, parameterTypes));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The four ASIO callbacks, run on the driver's thread. Nothing here allocates, locks or lets a
     * {@link Throwable} out (an exception escaping an upcall would terminate the JVM); a failure is
     * counted instead.
     */
    static final class Upcalls {

        private final AsioCallbacks callbacks;
        private final AtomicLong errors;

        Upcalls(AsioCallbacks callbacks, AtomicLong errors) {
            this.callbacks = callbacks;
            this.errors = errors;
        }

        void bufferSwitch(int half, int directProcess) {
            try {
                callbacks.bufferSwitch(half);
            } catch (Throwable t) {
                errors.incrementAndGet();
            }
        }

        void sampleRateDidChange(double rate) {
            try {
                callbacks.event(AsioEvent.SAMPLE_RATE_CHANGED, (int) Math.round(rate));
            } catch (Throwable t) {
                errors.incrementAndGet();
            }
        }

        int asioMessage(int selector, int value, long message, long opt) {
            try {
                switch (selector) {
                    case AsioConstants.MSG_SELECTOR_SUPPORTED:
                        return value >= 1 && value <= 7 ? 1 : 0;
                    case AsioConstants.MSG_ENGINE_VERSION:
                        return 2;
                    case AsioConstants.MSG_RESET_REQUEST:
                        callbacks.event(AsioEvent.RESET_REQUEST, value);
                        return 1;
                    case AsioConstants.MSG_BUFFER_SIZE_CHANGE:
                        callbacks.event(AsioEvent.BUFFER_SIZE_CHANGE, value);
                        return 1;
                    case AsioConstants.MSG_RESYNC_REQUEST:
                        callbacks.event(AsioEvent.RESYNC_REQUEST, value);
                        return 1;
                    case AsioConstants.MSG_LATENCIES_CHANGED:
                        callbacks.event(AsioEvent.LATENCIES_CHANGED, value);
                        return 1;
                    case AsioConstants.MSG_OVERLOAD:
                        callbacks.event(AsioEvent.OVERLOAD, value);
                        return 0;
                    default:
                        // Includes kAsioSupportsTimeInfo / kAsioSupportsTimeCode: no, so drivers keep
                        // using the plain bufferSwitch.
                        return 0;
                }
            } catch (Throwable t) {
                errors.incrementAndGet();
                return 0;
            }
        }

        /** {@code params} is an {@code ASIOTime*} that is handed straight back, as the SDK requires. */
        long bufferSwitchTimeInfo(long params, int half, int directProcess) {
            try {
                callbacks.bufferSwitch(half);
            } catch (Throwable t) {
                errors.incrementAndGet();
            }
            return params;
        }
    }
}
