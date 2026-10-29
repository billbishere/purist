package purist.wasapi;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import purist.win.ComException;

/**
 * A scripted WASAPI endpoint for {@link WasapiSink}: it answers {@code IsFormatSupported} from a predicate, can
 * refuse {@code Initialize} with any HRESULT (an unaligned buffer, the device in use), and captures every buffer
 * the render path releases. {@link #tick} signals the stream's real Win32 event, as the audio engine would, and
 * waits for the buffer it asked for.
 *
 * <p>Every call is logged in order, so tests can check the format order, the retry and the release order. Calls
 * other than the render client's {@code GetBuffer}/{@code ReleaseBuffer} must come from the control thread; any
 * that does not is recorded in {@link #violations}.
 */
final class FakeWasapi implements WasapiBackend {

    final List<String> log = new CopyOnWriteArrayList<>();
    final List<String> violations = new CopyOnWriteArrayList<>();
    final List<FakeClient> clients = new CopyOnWriteArrayList<>();

    /** Which exclusive-mode formats the device takes. */
    volatile Predicate<WaveFormat> supported = w -> true;
    /** HRESULT for {@code Activate}; a failure throws, as the real backend does. */
    volatile int activateHr;
    /** HRESULTs for successive {@code Initialize} calls; S_OK once these run out. */
    final List<Integer> initResults = new CopyOnWriteArrayList<>();
    /** What {@code GetBufferSize} says after an unaligned {@code Initialize}. */
    volatile int alignedFrames = 1056;
    volatile long defaultPeriod = 100_000;
    /** The buffer size after a successful {@code Initialize}. */
    volatile int bufferFrames = 480;
    /** HRESULT for the render thread's {@code GetBuffer}; S_OK by default. */
    volatile int getBufferHr;
    /** HRESULT for {@code IsFormatSupported} on top of {@link #supported}: a device error, say. */
    volatile int formatHr;
    /** HRESULT for {@code IAudioClient::Start}; a failure throws, as the real client does. */
    volatile int startHr;
    /** When set, the render client's {@code GetBuffer} blocks until this reaches zero: a hung driver call. */
    volatile java.util.concurrent.CountDownLatch hangGetBuffer;
    /** When true the render client's {@code buffer()} throws, standing in for any failure while filling. */
    volatile boolean bufferThrows;

    private volatile Thread controlThread;

    void setControlThread(Thread t) {
        controlThread = t;
    }

    private void onControl(String call) {
        Thread c = controlThread;
        if (c != null && Thread.currentThread() != c) {
            violations.add(call + " on " + Thread.currentThread().getName());
        }
    }

    @Override
    public AudioClient activate(String endpointId) throws ComException {
        onControl("activate");
        log.add("activate " + endpointId);
        if (activateHr < 0) {
            throw new ComException("IMMDevice::Activate", activateHr);
        }
        FakeClient c = new FakeClient(clients.size() + 1);
        clients.add(c);
        return c;
    }

    @Override
    public void close() {
        log.add("backend close");
    }

    FakeClient lastClient() {
        return clients.getLast();
    }

    static String fmt(WaveFormat w) {
        return w.validBits() + "/" + w.containerBits() + "@" + w.rate();
    }

    /** One IAudioClient. */
    final class FakeClient implements AudioClient {

        final int n;
        volatile WaveFormat initialized;
        volatile long event;
        volatile FakeRender render;
        volatile boolean released;
        volatile boolean started;

        FakeClient(int n) {
            this.n = n;
        }

        @Override
        public int isFormatSupported(WaveFormat f) {
            onControl("isFormatSupported");
            log.add("supported? " + fmt(f));
            if (formatHr < 0) {
                return formatHr;
            }
            return supported.test(f) ? 0 : WasapiErrors.AUDCLNT_E_UNSUPPORTED_FORMAT;
        }

        @Override
        public long defaultDevicePeriod() {
            onControl("defaultDevicePeriod");
            return defaultPeriod;
        }

        @Override
        public int initialize(WaveFormat f, long periodHns) {
            onControl("initialize");
            log.add("initialize#" + n + " " + fmt(f) + " period " + periodHns);
            int hr = initResults.isEmpty() ? 0 : initResults.removeFirst();
            if (hr >= 0) {
                initialized = f;
            }
            return hr;
        }

        @Override
        public int bufferSize() {
            onControl("bufferSize");
            return initialized == null ? alignedFrames : bufferFrames;
        }

        @Override
        public void setEventHandle(long h) {
            onControl("setEventHandle");
            event = h;
        }

        @Override
        public RenderClient renderClient() {
            onControl("renderClient");
            render = new FakeRender(bufferFrames, initialized.blockAlign());
            return render;
        }

        @Override
        public void start() throws ComException {
            onControl("start");
            log.add("start#" + n);
            if (startHr < 0) {
                throw new ComException("IAudioClient::Start", startHr);
            }
            started = true;
        }

        @Override
        public void stop() {
            onControl("stop");
            log.add("stop#" + n);
            started = false;
        }

        @Override
        public void reset() {
            onControl("reset");
            log.add("reset#" + n);
        }

        @Override
        public int currentPadding() {
            onControl("currentPadding");
            return 0;
        }

        @Override
        public void release() {
            onControl("release");
            log.add("release client#" + n);
            released = true;
        }

        /** Signals the stream's event and waits until it has released one more buffer. */
        boolean tick() throws InterruptedException {
            FakeRender r = render;
            r.released.drainPermits(); // the prefill's, or an earlier pass's: wait for this tick's buffer only
            Win32.setEvent(event);
            return r.released.tryAcquire(2, TimeUnit.SECONDS);
        }
    }

    /** One IAudioRenderClient: a native buffer per GetBuffer, captured on ReleaseBuffer. */
    final class FakeRender implements RenderClient {

        private final Arena arena = Arena.ofShared();
        private final MemorySegment buffer;
        private final int blockAlign;
        final Semaphore released = new Semaphore(0);
        final List<byte[]> buffers = new CopyOnWriteArrayList<>();
        /** The flags of every ReleaseBuffer, in order. */
        final List<Integer> flags = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        FakeRender(int frames, int blockAlign) {
            this.blockAlign = blockAlign;
            buffer = arena.allocate((long) frames * blockAlign);
        }

        @Override
        public int getBuffer(int frames) {
            java.util.concurrent.CountDownLatch hang = hangGetBuffer;
            if (hang != null) {
                try {
                    hang.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int hr = getBufferHr;
            if (hr < 0) {
                released.release(); // let tick() return
                return hr;
            }
            buffer.fill((byte) 0xA5); // poison: every byte must be written
            return 0;
        }

        @Override
        public long buffer() {
            if (bufferThrows) {
                throw new IllegalStateException("the fake buffer() was told to fail");
            }
            return buffer.address();
        }

        @Override
        public int releaseBuffer(int frames, int flags) {
            this.flags.add(flags);
            buffers.add(buffer.asSlice(0, (long) frames * blockAlign).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
            released.release();
            return 0;
        }

        @Override
        public void release() {
            onControl("release render");
            log.add("release render");
            closed = true;
        }

        /** Everything released so far, in order. */
        byte[] captured() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] b : new ArrayList<>(buffers)) {
                out.writeBytes(b);
            }
            return out.toByteArray();
        }
    }
}
