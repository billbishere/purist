package purist.wasapi;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import purist.app.AppLog;
import purist.asio.AsioControlThread;
import purist.audio.AudioDeviceException;
import purist.audio.Kind;
import purist.audio.NotBitPerfectException;
import purist.audio.Packer;
import purist.audio.SignalPath;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;
import purist.win.ComException;
import purist.win.Ole32;

/**
 * One open exclusive-mode stream of a {@link WasapiSink}: an initialised {@code IAudioClient} at one fixed format,
 * its render client and event, a {@link StreamCore}, and a render thread that drains the core into the device.
 *
 * <p><b>Threads.</b> Everything but the render loop runs on the sink's control thread, which owns the COM objects
 * and releases them. The render thread ({@code "wasapi-render"}, a platform thread registered with MMCSS as "Pro
 * Audio", in the MTA) waits for the device's event, then {@code GetBuffer} → {@link StreamCore#fill} into
 * preallocated planar buffers → {@link Interleaver} into the device buffer → {@code ReleaseBuffer}. That path
 * allocates nothing, takes no lock and catches every {@link Throwable}: device memory is written through one
 * all-of-memory segment made up front, and the buffer address arrives as a {@code long}.
 *
 * <p><b>Start and stop.</b> {@link #start()} prefills one whole buffer on the control thread (the render thread is
 * idle then) before {@code IAudioClient::Start}, as event-driven exclusive mode requires. {@link #stop()} clears
 * {@code running}, calls {@code Stop}, waits out a render pass in flight (the same volatile hand-shake as the ASIO
 * callbacks) and then {@code Reset}s the device's queue; when it returns nothing touches the core. A render pass
 * that wakes while not running touches nothing.
 *
 * <p><b>Device loss.</b> A {@code GetBuffer}/{@code ReleaseBuffer} that reports the device gone, or two seconds
 * without an event while running, raises a preallocated {@link AsioControlThread.Signal}; the control thread then
 * stops the stream, marks it lost and tells the sink's device listeners, as the ASIO sink does. Silence while
 * paused and dropout counting are the core's own (spec §5, §6).
 *
 * <p><b>Failed passes.</b> Once {@code GetBuffer} has succeeded, {@code ReleaseBuffer} is always called: with
 * {@code AUDCLNT_BUFFERFLAGS_SILENT} if filling the buffer failed. Any failed pass is counted as a dropout (so the
 * BIT-PERFECT light goes out) and a run of failing passes lasting over 200 ms is treated as a lost device, so a
 * stuck buffer cannot stall the output silently.
 *
 * <p><b>Buffer units.</b> {@link #signalPath()} reports the buffer in <em>core</em> frames (one DSD byte per
 * channel for DoP, so a DoP device frame is two), the unit the Player prefills in. {@link #bufferFrames()} is the
 * device's own count.
 */
final class WasapiSinkStream implements SinkStream {

    private static final Logger LOG = AppLog.logger(WasapiSinkStream.class);
    private static final long GRACE_NANOS = 1_000_000_000L;
    /** {@code AUDCLNT_BUFFERFLAGS_SILENT}: the engine treats the released buffer as silence. */
    private static final int BUFFERFLAGS_SILENT = 0x2;
    /** Consecutive failing render passes for this long mean the device is not working: 200 ms. */
    private static final long FAIL_LOSS_NANOS = 200_000_000L;
    private static final int WAIT_MILLIS = 200;
    /** Waits without an event, while running, before the device counts as stalled: 2 s. */
    private static final int STALL_WAITS = 10;
    /** All of memory, so a device buffer address can be written without making a segment for it. */
    private static final MemorySegment EVERYTHING = MemorySegment.NULL.reinterpret(Long.MAX_VALUE);

    private final WasapiSink sink;
    private final AudioClient client;
    private final WaveFormat wave;
    private final int bufferFrames;
    private final int coreFrames;
    private final StreamCore core;
    private final Interleaver interleaver;
    private final Arena arena;
    private final MemorySegment[] planar;
    private final long event;
    private final RenderClient render;
    private final AsioControlThread.Signal lostSignal;
    private final Thread renderThread;
    private final String link;
    private final double linkRate;
    private final AtomicLong renderErrors = new AtomicLong();

    // Shared with the render thread.
    private volatile boolean alive = true;
    private volatile boolean running;
    private volatile boolean inRender;
    /** The render thread's last device-gone HRESULT, or 0. */
    private volatile int renderFailure;
    private volatile boolean stalled;
    /** Failing passes have gone on for {@link #FAIL_LOSS_NANOS}; reset by {@link #startOnControl}. */
    private volatile boolean persistentFailure;
    // Render thread only: the current run of failing passes.
    private boolean failing;
    private long failingSince;

    // Written on the control thread, read anywhere.
    private volatile boolean disposed;
    private volatile String lostReason;

    /**
     * Takes over {@code client} (initialised for {@code wave}) only if this returns: on a throw the caller still
     * owns it, and everything made here has been released.
     */
    WasapiSinkStream(WasapiSink sink, AudioClient client, StreamFormat source, WaveFormat wave, boolean dop,
            int bufferFrames) throws ComException {
        this.sink = sink;
        this.client = client;
        this.wave = wave;
        this.bufferFrames = bufferFrames;
        this.interleaver = dop ? Interleaver.dop(wave.containerBits() / 8) : Interleaver.pcm(wave.containerBits() / 8);
        this.coreFrames = bufferFrames * interleaver.coreFramesPerFrame();
        this.link = "WASAPI exclusive " + (dop ? "DoP " : "") + wave.describe() + " @ " + wave.rate() + " Hz";
        this.linkRate = dop ? (double) wave.rate() * WasapiSink.DOP_BITS_PER_FRAME : wave.rate();
        if (bufferFrames < 1) {
            throw new IllegalStateException(sink.name() + " reports an unusable buffer of " + bufferFrames + " frames");
        }

        // The device keeps wave.validBits() of each sample: a deeper PCM source would silently lose its low bits.
        StreamCore c = new StreamCore(WasapiSink.coreType(wave, dop), coreFrames, ringFrames(source, coreFrames),
                wave.validBits());
        Arena a = Arena.ofShared();
        long ev = 0;
        RenderClient r = null;
        try {
            long bytes = (long) coreFrames * c.outType().containerBytes();
            MemorySegment[] pl = {a.allocate(bytes, 8), a.allocate(bytes, 8)};
            ev = Win32.createEvent();
            client.setEventHandle(ev);
            r = client.renderClient();
            this.core = c;
            this.arena = a;
            this.planar = pl;
            this.event = ev;
            this.render = r;
            this.lostSignal = sink.control().newSignal(this::checkLostOnControl);
            this.renderThread = new Thread(this::renderLoop, "wasapi-render");
            renderThread.setDaemon(true);
            renderThread.setPriority(Thread.MAX_PRIORITY);
            renderThread.start();
        } catch (ComException | RuntimeException | Error e) {
            if (r != null) {
                r.release();
            }
            if (ev != 0) {
                Win32.closeHandle(ev);
            }
            a.close();
            c.close();
            throw e;
        }
    }

    /** {@code max(2 s of core frames, 4 fills)}; a DSD core frame is one byte (8 bits) per channel. */
    static int ringFrames(StreamFormat f, int fillFrames) {
        long perSecond = f.kind() == Kind.DSD ? f.sampleRate() / 8L : f.sampleRate();
        return Math.toIntExact(Math.max(2 * perSecond, 4L * fillFrames));
    }

    // ---- SinkStream ----------------------------------------------------------------------------------------

    @Override
    public StreamCore core() {
        return core;
    }

    @Override
    public SignalPath signalPath() {
        return core.signalPath(sink.name(), linkRate, coreFrames, link);
    }

    @Override
    public boolean accepts(StreamFormat next) {
        if (disposed || lostReason != null || !next.sameStream(core.source())) {
            return false;
        }
        if (next.kind() == Kind.PCM && next.bits() > wave.validBits()) {
            return false; // the device was initialised with fewer valid bits: it would drop the low ones
        }
        try {
            Packer.create(next, core.outType());
            return true;
        } catch (NotBitPerfectException e) {
            return false;
        }
    }

    /**
     * Prefills one buffer and starts the device.
     *
     * @throws AudioDeviceException  if the device will not start, or the stream was lost
     * @throws IllegalStateException if the sink has opened another stream since
     */
    @Override
    public void start() throws AudioDeviceException {
        sink.onControlDevice(() -> {
            startOnControl();
            return null;
        });
    }

    /** Stops the device; when this returns no render pass is running or will touch the core. Idempotent. */
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

    /** Render passes that threw or got a failing HRESULT (and were survived). */
    long renderErrors() {
        return renderErrors.get();
    }

    /** {@code GetCurrentPadding}, on the control thread: frames queued in the device and not yet played. */
    int currentPadding() throws AudioDeviceException {
        return sink.onControlDevice(() -> {
            try {
                return client.currentPadding();
            } catch (ComException e) {
                throw new AudioDeviceException(e.getMessage(), e);
            }
        });
    }

    /** The device's buffer in device frames (for DoP half of what {@link #signalPath()} reports). */
    int bufferFrames() {
        return bufferFrames;
    }

    // ---- control thread ------------------------------------------------------------------------------------

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
            Win32.waitFor(event, 0); // drop a stale signal from before the last stop
        } catch (Throwable ignored) {
            // a stale signal only costs a render pass that is told the buffer is full
        }
        try {
            check("IAudioRenderClient::GetBuffer", render.getBuffer(bufferFrames));
            boolean filled = false;
            try {
                core.fill(planar, coreFrames);
                interleaver.write(planar, EVERYTHING, render.buffer(), bufferFrames);
                filled = true;
            } finally {
                int hr = render.releaseBuffer(bufferFrames, filled ? 0 : BUFFERFLAGS_SILENT);
                if (filled) {
                    check("IAudioRenderClient::ReleaseBuffer", hr);
                }
            }
            stalled = false;
            persistentFailure = false;
            running = true;
            client.start();
        } catch (ComException e) {
            running = false;
            try {
                client.reset(); // drop the prefilled buffer, so the next start prefills a whole one again
            } catch (ComException | RuntimeException ignored) {
                // the device is probably gone; the error below says what failed first
            }
            String problem = WasapiErrors.deviceProblem(e.hresult(), sink.name());
            throw new AudioDeviceException(problem != null ? problem
                    : "Could not start " + sink.name() + ": " + e.getMessage(), e);
        }
    }

    private void stopOnControl() {
        boolean was = running;
        running = false;
        if (disposed || !was) {
            return;
        }
        ComException failure = null;
        try {
            client.stop();
        } catch (ComException e) {
            failure = e;
        }
        awaitRender();
        try {
            client.reset(); // drop what the device still holds, so the next start prefills a whole buffer
        } catch (ComException e) {
            if (failure == null) {
                failure = e;
            }
        }
        if (failure != null && WasapiErrors.isDeviceGone(failure.hresult())) {
            lose("the device was removed or reset");
        }
    }

    /** The lost signal's action: the render thread saw the device go, or stop asking for audio. */
    private void checkLostOnControl() {
        if (disposed || lostReason != null) {
            return;
        }
        if (renderFailure != 0) {
            lose("the device was removed or reset (HRESULT 0x%08X)".formatted(renderFailure));
            return;
        }
        if (persistentFailure && running) {
            persistentFailure = false;
            lose("rendering kept failing for over 200 ms");
            return;
        }
        if (stalled && running) {
            stalled = false;
            try {
                client.currentPadding();
            } catch (ComException e) {
                lose("the device was removed or reset (HRESULT 0x%08X)".formatted(e.hresult()));
                return;
            }
            lose("it stopped asking for audio");
        }
    }

    /** The device is gone: stop rendering and tell the sink's listeners. {@link #start()} refuses from now on. */
    private void lose(String why) {
        String text = sink.name() + " was lost: " + why;
        lostReason = text;
        running = false;
        try {
            client.stop();
        } catch (ComException | RuntimeException ignored) {
            // it is gone either way
        }
        awaitRender();
        sink.notifyDeviceLost(text);
    }

    /** Ends this stream for good: the sink is opening another one, or closing. Releases every COM object here. */
    void disposeOnControl() {
        if (disposed) {
            return;
        }
        disposed = true;
        running = false;
        alive = false;
        try {
            client.stop();
        } catch (ComException | RuntimeException ignored) {
            // being released either way
        }
        Win32.setEvent(event); // wake the render thread so it sees alive == false
        if (!joinRenderThread()) {
            // A render thread stuck inside a driver call may still use the COM objects, the event and the buffers.
            // Releasing any of them under it could crash the process; leaking them cannot. So leave them all.
            LOG.warning(sink.name() + ": the render thread did not end within " + 2 * GRACE_NANOS / 1_000_000_000L
                    + " s; leaking its audio client, render client, event and buffers instead of releasing them");
            return;
        }
        try {
            render.release();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            client.release();
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            Win32.closeHandle(event);
        } catch (RuntimeException ignored) {
            // a leaked handle is harmless; the memory below must still go
        }
        arena.close();
        core.close();
    }

    private boolean joinRenderThread() {
        long deadline = System.nanoTime() + 2 * GRACE_NANOS;
        boolean interrupted = false;
        while (renderThread.isAlive() && System.nanoTime() < deadline) {
            try {
                renderThread.join(100);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return !renderThread.isAlive();
    }

    /** Spins (on the control thread) until no render pass is in flight. */
    private void awaitRender() {
        long start = System.nanoTime();
        while (inRender && System.nanoTime() - start < GRACE_NANOS) {
            Thread.onSpinWait();
        }
    }

    private static void check(String op, int hr) throws ComException {
        if (hr < 0) {
            throw new ComException(op, hr);
        }
    }

    // ---- render thread -------------------------------------------------------------------------------------

    private void renderLoop() {
        boolean com = false;
        long mmcss = 0;
        try {
            Ole32.coInitializeMta();
            com = true;
            mmcss = Win32.joinMmcss("Pro Audio");
        } catch (Throwable t) {
            renderErrors.incrementAndGet();
        }
        try {
            int idle = 0;
            while (alive) {
                int woke;
                try {
                    woke = Win32.waitFor(event, WAIT_MILLIS);
                } catch (Throwable t) {
                    woke = -1;
                }
                // Raised before reading `running`, which the control thread clears before reading this.
                inRender = true;
                try {
                    if (!alive || !running) {
                        idle = 0;
                        failing = false;
                        continue;
                    }
                    if (woke != Win32.WAIT_OBJECT_0) {
                        if (++idle >= STALL_WAITS) {
                            idle = 0;
                            stalled = true;
                            lostSignal.raise();
                        }
                        continue;
                    }
                    idle = 0;
                    if (renderPass()) {
                        failing = false;
                    } else {
                        passFailed();
                    }
                } catch (Throwable t) {
                    renderErrors.incrementAndGet();
                } finally {
                    inRender = false;
                }
            }
        } finally {
            Win32.leaveMmcss(mmcss);
            if (com) {
                try {
                    Ole32.coUninitialize();
                } catch (Throwable ignored) {
                    // the thread is ending
                }
            }
        }
    }

    /** One device buffer: true if it was filled and released; false (counted) if the pass failed. Never throws. */
    private boolean renderPass() {
        boolean ok;
        try {
            ok = renderOnce();
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            renderErrors.incrementAndGet();
        }
        return ok;
    }

    /**
     * Allocation-free and lock-free. Once {@code GetBuffer} has succeeded the buffer is always released, flagged
     * silent if filling it failed, so a failure never leaves the engine holding a buffer.
     */
    private boolean renderOnce() {
        int hr = render.getBuffer(bufferFrames);
        if (hr < 0) {
            // A stale event while the prefilled buffer is still queued: nothing was taken from the core.
            return hr == WasapiErrors.AUDCLNT_E_BUFFER_TOO_LARGE || noteFailure(hr);
        }
        boolean filled = false;
        try {
            long at = render.buffer();
            core.fill(planar, coreFrames);
            interleaver.write(planar, EVERYTHING, at, bufferFrames);
            filled = true;
        } catch (Throwable t) {
            // fall through: the buffer is released silent below, and the pass is counted as failed
        }
        hr = render.releaseBuffer(bufferFrames, filled ? 0 : BUFFERFLAGS_SILENT);
        if (hr < 0) {
            return noteFailure(hr);
        }
        return filled;
    }

    /** Remembers a device-gone HRESULT for the control thread; always false, the pass having failed. */
    private boolean noteFailure(int hr) {
        if (WasapiErrors.isDeviceGone(hr)) {
            renderFailure = hr;
            lostSignal.raise();
        }
        return false;
    }

    /** A pass failed: that is an audible gap, and a long enough run of them is a dead device. */
    private void passFailed() {
        core.recordDropout(); // this thread is the consumer, the counters' only writer while the stream runs
        long now = System.nanoTime();
        if (!failing) {
            failing = true;
            failingSince = now;
        } else if (now - failingSince >= FAIL_LOSS_NANOS && !persistentFailure) {
            persistentFailure = true;
            lostSignal.raise();
        }
    }
}
