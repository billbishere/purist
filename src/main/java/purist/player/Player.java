package purist.player;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import purist.audio.AudioDeviceException;
import purist.audio.AudioSink;
import purist.audio.Kind;
import purist.audio.NotBitPerfectException;
import purist.audio.SignalPath;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.playlist.TrackRef;

/**
 * Plays a queue of tracks bit-perfectly through an {@link AudioSink}: gaplessly while consecutive tracks fit one
 * stream, reopening the device when the format changes, and skipping (with the reason) any track that cannot be
 * played bit-perfectly (§5). Nothing here converts, scales or fades a sample.
 *
 * <p><b>Threads (§4).</b>
 * <ul>
 *   <li>The <b>decode thread</b> {@code "purist-decode"} owns everything that touches the decoder, the sink and
 *       the stream. Every public control method only posts a command to it, so all state changes happen on that
 *       one thread. While it feeds the ring it polls for commands between chunks; when the ring is full it is
 *       parked inside {@link StreamCore#write}, and a command reaches it through {@link StreamCore#cancelWrite()}
 *       (never {@link Thread#interrupt()}, which would close the decoder's interruptible file channel). A
 *       cancelled write reports how much it queued and the rest of the chunk stays buffered, so no frame is ever
 *       lost or repeated.
 *   <li>The <b>ticker</b> {@code "purist-ticker"} runs every 50 ms. It pops the gapless {@link Boundary
 *       boundaries} the output has reached ({@code startFrame <= framesPlayed}), makes that track the one being
 *       heard (resetting the per-track dropout baseline) and fires {@code trackStarted}.
 *   <li>The driver callback only ever runs {@link StreamCore#fill}; nothing here runs on it.
 * </ul>
 * Listeners always run through the {@code events} executor (the EDT in the app, {@code Runnable::run} in
 * tests). {@link #status()} may be called from any thread.
 *
 * <p><b>Failures.</b>
 * <ul>
 *   <li>A track the decoder cannot open (unsupported or damaged at its start) or the device refuses
 *       ({@link NotBitPerfectException}) fires {@code trackSkipped} with the reason, and the queue moves on.
 *   <li>A read or decode error part-way through fires {@code error("Stopped reading <file>: <message>")} after
 *       the good frames before the damage have been played, then playback continues as at the end of the track.
 *   <li>Device trouble is never a skip: an {@link AudioDeviceException} from opening or starting, or a message
 *       from the sink's device listener, stops playback and fires {@code deviceLost}. So does a refusal of a
 *       format the sink already accepted with this player (an unplugged ASIO device can answer "rate not
 *       supported"; it played that very format before, so the device, not the track, has changed).
 *   <li>A run of unplayable tracks never loops: once as many tracks in a row as the queue holds have failed
 *       (each reported), playback stops with an {@code error}. With repeat off the end of the queue usually comes
 *       first; with repeat all or repeat track this is what ends the loop.
 * </ul>
 */
public final class Player implements AutoCloseable {

    /** Frames read and written per step, the same as {@link StreamCore}'s chunk, so one write is one chunk. */
    private static final int CHUNK_FRAMES = 4096;
    /** The decode buffer holds a chunk of up to 8 channels of int32 (more channels are refused by the sink). */
    private static final long BUFFER_BYTES = CHUNK_FRAMES * 8L * Integer.BYTES;
    private static final long TICK_MILLIS = 50;
    private static final long DRAIN_POLL_MILLIS = 2;
    private static final double RESTART_SECONDS = 3.0;
    private static final long CLOSE_WAIT_MILLIS = 5000;

    private static final PlayerStatus NOTHING =
            new PlayerStatus(PlayerState.STOPPED, -1, null, 0, 0, null, 0, Optional.empty());

    // ---- commands (posted by any thread, run on the decode thread) ----------------------------------------------

    private sealed interface Command permits Simple, Play, SetQueue, EditQueue, SetSink, Seek, DeviceLost {}

    private enum Simple implements Command { PAUSE, RESUME, TOGGLE_PAUSE, STOP, NEXT, PREVIOUS, CLOSE }

    private record Play(int index) implements Command {}

    private record SetQueue(List<TrackRef> tracks, int startIndex) implements Command {}

    private record EditQueue(List<TrackRef> tracks, int[] oldToNew) implements Command {}

    private record SetSink(AudioSink sink, CompletableFuture<AudioSink> done) implements Command {}

    private record Seek(double seconds) implements Command {}

    private record DeviceLost(AudioSink sink, String message) implements Command {}

    /** Where a gapless join happens: the next track's first frame is {@code startFrame} into the core session. */
    private record Boundary(long startFrame, int index, TrackRef ref, TrackInfo info, StreamFormat format,
            SignalPath path) {}

    /**
     * The track being heard. Its position is {@code baseFrame + framesPlayed - startFrame}: {@code startFrame} is
     * where it begins in the core session, {@code baseFrame} the source frame it began at (non-zero after a seek).
     */
    private record Playing(int index, TrackRef ref, TrackInfo info, StreamFormat format, SignalPath path,
            StreamCore core, long startFrame, long baseFrame, long dropoutBaseline) {}

    /** A track whose decoder is open and not yet feeding the stream. */
    private record Opened(int index, TrackRef ref, Decoder decoder, TrackInfo info) {}

    private enum Mode { IDLE, FEEDING, DRAINING }

    private enum Start { STARTED, SKIPPED, HALTED }

    // ---- any thread ------------------------------------------------------------------------------------------

    private final DecoderRegistry registry;
    private final Executor events;
    private final List<PlayerListener> listeners = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Command> commands = new LinkedBlockingQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread decodeThread;
    private final ScheduledExecutorService ticker;
    private volatile List<TrackRef> queueView = List.of();
    private volatile RepeatMode repeat = RepeatMode.OFF;
    private volatile boolean shuffle;
    private volatile long shuffleSeed = System.nanoTime();
    /** Written only by the decode thread; read by {@link #post} to cancel a blocked write. */
    private volatile SinkStream stream;
    /** Written only by the decode thread. */
    private volatile PlayerState state = PlayerState.STOPPED;
    /** True from the moment the queue played out to its end until anything plays again or the queue is replaced. */
    private volatile boolean ranOut;
    /** What {@link #status()} reports while stopped: the moment playback stopped. */
    private volatile PlayerStatus stoppedStatus = NOTHING;

    // ---- decode thread and ticker, guarded by session ---------------------------------------------------------

    private final Object session = new Object();
    private final ArrayDeque<Boundary> boundaries = new ArrayDeque<>();
    /** Written under {@link #session}; read without it by {@link #status()}. Null while stopped. */
    private volatile Playing playing;

    // ---- decode thread only -------------------------------------------------------------------------------------

    private AudioSink sink;
    private List<TrackRef> queue = List.of();
    private Mode mode = Mode.IDLE;
    /** True between a successful {@link SinkStream#start()} and the next stop. */
    private boolean running;
    private Decoder decoder;
    private int decIndex = -1;
    private TrackRef decRef;
    /** A track opened while the stream drains, to start once it has (a format change); null at the end. */
    private Opened pendingNext;
    private MemorySegment buf;
    private int maxFrames;
    private long frameBytes;
    /** The decoded frames in {@link #buf} not yet written are {@code [bufOff, bufFrames)}. */
    private int bufFrames;
    private int bufOff;
    /** The prefill met the end of the track (or an error) before the stream started; {@link #feed} acts on it. */
    private boolean trackEnded;
    /** Frames written into the current core session (since its last reset). */
    private long written;
    private int unplayableRun;
    private String lastFailure;
    /** Frames of the current decoder's track written so far; 0 at its end means it was unplayable. */
    private long decFrames;
    /** The current decoder's track ended in a read error (already reported). */
    private boolean decFailed;
    /** Why the last {@link #openDecoder} failed. */
    private String openFailure;
    private int lastIndex = -1;
    /** Formats the current sink opened for this player, for telling device trouble from a real refusal. */
    private final Set<StreamFormat> playedFormats = new HashSet<>();

    /**
     * Starts the decode thread and the ticker. Playback starts with {@link #setQueue} or {@link #play}.
     *
     * @param events runs every listener call (the EDT in the app)
     */
    public Player(AudioSink sink, DecoderRegistry registry, Executor events) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.events = Objects.requireNonNull(events, "events");
        watch(sink);
        decodeThread = new Thread(this::decodeLoop, "purist-decode");
        decodeThread.setDaemon(true);
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "purist-ticker");
            t.setDaemon(true);
            return t;
        });
        decodeThread.start();
        ticker.scheduleAtFixedRate(this::tickSafely, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    // ---- public controls (any thread) ---------------------------------------------------------------------------

    /**
     * Stops playback and plays through {@code sink} from now on. The caller owns (and closes) both sinks.
     *
     * <p>The switch happens on the decode thread, between commands, so it can wait behind a slow file open or
     * device open. The returned future completes, on the decode thread, once the switch has been applied and the
     * old sink's stream stopped: from then on nothing here touches the previous sink, and only then is it safe to
     * close it. It completes with that previous sink; with {@code null} if the player had already been closed
     * (nothing is using any sink then). Do not block on it from the event thread, and close the old sink from a
     * background thread (closing an ASIO sink waits for its control thread).
     */
    public CompletableFuture<AudioSink> setSink(AudioSink sink) {
        Objects.requireNonNull(sink, "sink");
        CompletableFuture<AudioSink> done = new CompletableFuture<>();
        if (closed.get()) {
            done.complete(null);
            return done;
        }
        watch(sink);
        post(new SetSink(sink, done));
        if (closed.get()) {
            done.complete(null); // lost a race with close(); a no-op if the decode thread got to it
        }
        return done;
    }

    /**
     * Replaces the queue and plays it from {@code startIndex}; a negative {@code startIndex} replaces it and stops.
     *
     * @throws IndexOutOfBoundsException if {@code startIndex} is past the end of {@code tracks}
     */
    public void setQueue(List<TrackRef> tracks, int startIndex) {
        List<TrackRef> copy = List.copyOf(tracks);
        if (startIndex >= copy.size()) {
            throw new IndexOutOfBoundsException("start index " + startIndex + " of a queue of " + copy.size());
        }
        queueView = copy;
        post(new SetQueue(copy, startIndex));
    }

    public List<TrackRef> queue() {
        return queueView;
    }

    /**
     * True if playback stopped because the last track of the queue finished (not because the user stopped it, or
     * a device failed), and nothing has played since. PLAY then continues with tracks that were added after the
     * end instead of replaying the old last track.
     */
    public boolean queueRanOut() {
        return ranOut;
    }

    /**
     * Changes the queue without interrupting anything: the track playing carries on, and so does the stream. Use it
     * to append, remove or reorder tracks while the queue plays; {@link #setQueue} restarts playback.
     *
     * @param tracks   the whole new queue
     * @param oldToNew for each index of the queue as it was, its index in {@code tracks}, or -1 if the track was
     *                 taken out. Indices past the end of the array count as taken out, so an append passes the
     *                 identity map (or just {@code new int[0]}, when nothing is playing). The track being heard
     *                 should not be taken out: it keeps playing, but Next then has no place to go from.
     * @throws IllegalArgumentException if a value of {@code oldToNew} is neither -1 nor an index of {@code tracks},
     *                 or two old tracks map to one new place
     */
    public void editQueue(List<TrackRef> tracks, int[] oldToNew) {
        List<TrackRef> copy = List.copyOf(tracks);
        boolean[] taken = new boolean[copy.size()];
        for (int i = 0; i < oldToNew.length; i++) {
            int to = oldToNew[i];
            if (to < -1 || to >= copy.size()) {
                throw new IllegalArgumentException("oldToNew[" + i + "] = " + to + " is not -1 or an index of the "
                        + copy.size() + "-track queue");
            }
            if (to >= 0) {
                if (taken[to]) {
                    throw new IllegalArgumentException("oldToNew maps two tracks to place " + to);
                }
                taken[to] = true;
            }
        }
        queueView = copy;
        post(new EditQueue(copy, oldToNew.clone()));
    }

    /**
     * Plays the queue from {@code index}, stopping whatever was playing.
     *
     * @throws IndexOutOfBoundsException if {@code index} is not in the queue
     */
    public void play(int index) {
        Objects.checkIndex(index, queueView.size());
        post(new Play(index));
    }

    /** Pauses the output (digital silence, the buffered audio kept). Ignored unless playing. */
    public void pause() {
        post(Simple.PAUSE);
    }

    /** Resumes after {@link #pause()}. */
    public void resume() {
        post(Simple.RESUME);
    }

    public void togglePause() {
        post(Simple.TOGGLE_PAUSE);
    }

    public void stop() {
        post(Simple.STOP);
    }

    /** The next track in play order (repeat track counts as repeat off here); stops at the end of the queue. */
    public void next() {
        post(Simple.NEXT);
    }

    /** Restarts the track when more than 3 s in; otherwise the previous track in play order, if there is one. */
    public void previous() {
        post(Simple.PREVIOUS);
    }

    /** Moves the current track to {@code seconds} (clamped to the track); keeps a pause. */
    public void seek(double seconds) {
        if (Double.isNaN(seconds)) {
            throw new IllegalArgumentException("seek to NaN");
        }
        post(new Seek(Math.max(0, seconds)));
    }

    public void setRepeat(RepeatMode m) {
        repeat = Objects.requireNonNull(m, "m");
    }

    /** Turning shuffle on picks a new play order. */
    public void setShuffle(boolean on) {
        if (on && !shuffle) {
            shuffleSeed = System.nanoTime();
        }
        shuffle = on;
    }

    /** What is playing now. Cheap; safe from any thread. */
    public PlayerStatus status() {
        PlayerState s = state;
        Playing p = playing;
        if (s == PlayerState.STOPPED || p == null) {
            return stoppedStatus;
        }
        return statusOf(p, s);
    }

    public void addListener(PlayerListener l) {
        listeners.add(Objects.requireNonNull(l, "l"));
    }

    /**
     * Stops playback and ends the decode thread and the ticker. The sink is not closed: it belongs to the caller.
     * Later control calls are ignored.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        commands.add(Simple.CLOSE);
        cancelBlockedWrite();
        if (Thread.currentThread() != decodeThread) {
            try {
                decodeThread.join(CLOSE_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ticker.shutdownNow();
    }

    private void post(Command c) {
        if (closed.get()) {
            return;
        }
        commands.add(c); // before the cancel, so a write that the cancel ends finds the command waiting
        cancelBlockedWrite();
    }

    private void cancelBlockedWrite() {
        SinkStream s = stream;
        if (s != null) {
            s.core().cancelWrite();
        }
    }

    private void watch(AudioSink s) {
        s.addDeviceListener(message -> post(new DeviceLost(s, message)));
    }

    // ---- the decode thread --------------------------------------------------------------------------------------

    private void decodeLoop() {
        try (Arena arena = Arena.ofConfined()) {
            buf = arena.allocate(BUFFER_BYTES, Integer.BYTES);
            for (;;) {
                Command c = switch (mode) {
                    case IDLE -> commands.take();
                    case FEEDING -> commands.poll();
                    case DRAINING -> commands.poll(DRAIN_POLL_MILLIS, TimeUnit.MILLISECONDS);
                };
                if (c == Simple.CLOSE) {
                    break;
                }
                try {
                    if (c != null) {
                        handle(c);
                    } else if (mode == Mode.FEEDING) {
                        feed();
                    } else if (mode == Mode.DRAINING && stream.core().drained()) {
                        drained();
                    }
                } catch (RuntimeException e) {
                    // A bug must not kill the thread: log it, stop, say so, and keep taking commands.
                    Thread t = Thread.currentThread();
                    t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    halt(l -> l.error("Playback stopped: " + message(e)));
                }
            }
        } catch (InterruptedException e) {
            // Nothing interrupts this thread; if something does, treat it as close.
        } finally {
            try {
                stopOutput();
            } catch (RuntimeException ignored) {
                // closing anyway
            }
            for (Command c; (c = commands.poll()) != null; ) {
                if (c instanceof SetSink s) {
                    s.done().complete(sink); // never applied; the player no longer uses it either way
                }
            }
        }
    }

    private void handle(Command c) throws InterruptedException {
        switch (c) {
            case Play p -> {
                if (p.index() < queue.size()) {
                    restartAt(OptionalInt.of(p.index()));
                }
            }
            case EditQueue e -> applyEdit(e);
            case SetQueue q -> {
                ranOut = false;
                stopOutput();
                queue = q.tracks();
                if (q.startIndex() >= 0) {
                    restartAt(OptionalInt.of(q.startIndex()));
                } else {
                    finish();
                }
            }
            case SetSink s -> {
                AudioSink previous = sink;
                try {
                    finish(); // stops the old stream
                } finally {
                    stream = null; // the old sink's stream is the old sink's business
                    sink = s.sink();
                    playedFormats.clear();
                    s.done().complete(previous); // nothing here touches `previous` again
                }
            }
            case DeviceLost d -> {
                if (d.sink() == sink) {
                    deviceLost(d.message());
                }
            }
            case Seek s -> {
                Playing p = playing;
                if (p != null && state != PlayerState.STOPPED) {
                    seekTo(Math.round(s.seconds() * framesPerSecond(p.format())));
                }
            }
            case Simple.PAUSE -> setPaused(true);
            case Simple.RESUME -> setPaused(false);
            case Simple.TOGGLE_PAUSE -> setPaused(state == PlayerState.PLAYING);
            case Simple.STOP -> {
                ranOut = false;
                finish();
            }
            case Simple.NEXT -> next(currentIndex());
            case Simple.PREVIOUS -> previous(currentIndex());
            case Simple.CLOSE -> throw new IllegalStateException("handled by the loop");
        }
    }

    private int currentIndex() {
        Playing p = playing;
        return p != null ? p.index() : lastIndex;
    }

    private void next(int from) throws InterruptedException {
        if (from < 0 || from >= queue.size()) {
            return;
        }
        restartAt(manualNav().next(from));
    }

    private void previous(int from) throws InterruptedException {
        Playing p = playing;
        boolean live = p != null && state != PlayerState.STOPPED;
        if (live && positionFrames(p) > RESTART_SECONDS * framesPerSecond(p.format())) {
            seekTo(0);
            return;
        }
        if (from < 0 || from >= queue.size()) {
            return;
        }
        OptionalInt to = manualNav().previous(from);
        if (to.isEmpty()) {
            if (live) {
                seekTo(0);
            }
            return;
        }
        restartAt(to);
    }

    /** A user-chosen start: drop whatever is playing, then play from {@code candidate}. */
    private void restartAt(OptionalInt candidate) throws InterruptedException {
        stopOutput();
        unplayableRun = 0;
        advance(candidate, false);
    }

    private void setPaused(boolean pause) {
        if (playing == null || stream == null || state == PlayerState.STOPPED) {
            return;
        }
        PlayerState want = pause ? PlayerState.PAUSED : PlayerState.PLAYING;
        if (state == want) {
            return;
        }
        stream.core().setPaused(pause);
        state = want;
        fireState(status());
    }

    // ---- feeding ------------------------------------------------------------------------------------------------

    private void feed() throws InterruptedException {
        if (trackEnded || !step()) {
            trackEnded = false;
            endOfTrack();
        }
    }

    /**
     * Reads a chunk if the buffer is used up, then writes what is buffered (a cancelled write leaves the rest for
     * the next step). Returns false at the end of the track: a clean end, or an error that was just reported.
     * Short reads are not the end: a damaged stream returns its good frames and throws on the next read.
     */
    private boolean step() throws InterruptedException {
        if (bufOff == bufFrames) {
            int n;
            try {
                n = decoder.read(buf, maxFrames);
            } catch (IOException e) {
                trackFailed(stoppedReading(e));
                return false;
            }
            if (n == 0) {
                return false;
            }
            bufOff = 0;
            bufFrames = n;
        }
        int want = bufFrames - bufOff;
        int done;
        try {
            done = stream.core().write(buf.asSlice(bufOff * frameBytes, want * frameBytes), want);
        } catch (IllegalArgumentException e) {
            // A decoded sample beyond its declared depth (a decoder bug). One write is one chunk, so nothing of
            // this call was queued and `written` stays exact.
            bufOff = bufFrames;
            trackFailed(stoppedReading(e));
            return false;
        }
        bufOff += done;
        written += done;
        if (done > 0) {
            decFrames += done;
            unplayableRun = 0; // a track that reached the stream ends any run of unplayable ones
        }
        return true;
    }

    /** Reports a read error of the current track. The text is built here, on the decode thread, not lazily. */
    private void trackFailed(String message) {
        lastFailure = message;
        decFailed = true;
        fire(l -> l.error(message));
    }

    /**
     * Core frames to queue before starting: two driver buffers, plus one frame so a whole buffer is still queued
     * after the stream's start takes the first. The path's buffer is in core frames (a DoP device frame is two).
     */
    static long prefillFrames(SignalPath path) {
        return 2L * path.bufferFrames() + 1;
    }

    /** Fills at least {@code frames} into the stopped stream, so the start is not a dropout. */
    private void prefill(long frames) throws InterruptedException {
        while (written < frames) {
            if (!step()) {
                trackEnded = true; // the main loop moves on once the stream has started
                return;
            }
        }
    }

    private void endOfTrack() throws InterruptedException {
        int finished = decIndex;
        if (decFrames == 0) { // it started but gave nothing (empty, or damaged from its first frame)
            unplayableRun++;
            if (!decFailed) {
                lastFailure = fileName(decRef) + " holds no audio";
            }
        }
        closeDecoder();
        advance(autoNext(finished), true);
    }

    /**
     * Plays the first playable track from {@code candidate} on, in automatic play order. {@code natural} means the
     * previous track has just been fully written and is still playing out: then a track the stream accepts is
     * joined gaplessly, and anything else ends the input at once (before any probe, skip or further open, so a slow
     * or bad file cannot make the stream run dry before the end is declared) and waits for the stream to drain.
     */
    private void advance(OptionalInt candidate, boolean natural) throws InterruptedException {
        boolean tail = natural && running; // the previous track is still playing out of this stream
        boolean ended = false;             // endOfInput() has been called on it
        OptionalInt c = candidate;
        while (c.isPresent()) {
            int i = c.getAsInt();
            if (unplayableRun >= queue.size()) {
                String why = "None of the " + queue.size() + " tracks in the queue could be played (last: "
                        + lastFailure + ")";
                fire(l -> l.error(why));
                break;
            }
            TrackRef ref = queue.get(i);
            Decoder d = openDecoder(ref);
            if (d == null) {
                if (tail && !ended) {
                    stream.core().endOfInput(); // no gapless join now; the skip comes after
                    ended = true;
                }
                skip(i, ref, openFailure);
            } else if (tail) {
                if (!ended && stream.accepts(d.format()) && joinGapless(new Opened(i, ref, d, null))) {
                    return;
                }
                if (!ended) {
                    stream.core().endOfInput(); // decided on the format alone, before the probe
                }
                pendingNext = new Opened(i, ref, d, describe(ref, d.format()));
                mode = Mode.DRAINING;
                return;
            } else if (startFresh(new Opened(i, ref, d, describe(ref, d.format()))) != Start.SKIPPED) {
                return;
            }
            unplayableRun++;
            c = autoNav().next(i);
        }
        if (tail) {
            if (!ended) {
                stream.core().endOfInput(); // the last write was the queue's last frame
            }
            pendingNext = null;
            mode = Mode.DRAINING;
        } else {
            finish();
        }
    }

    /** The stream has played everything out: start the track that needed a new stream, or stop at the end. */
    private void drained() throws InterruptedException {
        tick(); // so a boundary near the very end is announced before anything else happens
        Opened next = pendingNext;
        pendingNext = null;
        if (next == null) {
            ranOut = true; // before finish() announces STOPPED: a PLAY issued from that callback must see it
            finish();
            return;
        }
        if (startFresh(next) == Start.SKIPPED) {
            unplayableRun++;
            advance(autoNext(next.index()), false);
        }
    }

    private boolean joinGapless(Opened opened) {
        StreamFormat f = opened.decoder().format();
        try {
            stream.core().setSource(f);
        } catch (NotBitPerfectException e) {
            return false; // accepts() said yes; let the reopen path decide
        }
        SignalPath path = stream.signalPath(); // right after setSource: this track's path
        Opened o = new Opened(opened.index(), opened.ref(), opened.decoder(), describe(opened.ref(), f));
        adopt(o);
        synchronized (session) {
            boundaries.addLast(new Boundary(written, o.index(), o.ref(), o.info(), f, path));
        }
        return true;
    }

    /** Starts {@code o} in a new core session: reuse or (re)open the stream, prefill, start. */
    private Start startFresh(Opened o) throws InterruptedException {
        StreamFormat f = o.decoder().format();
        haltStream();
        if (stream == null || !stream.accepts(f)) {
            stream = null; // open() disposes the previous stream, even when it then refuses
            try {
                stream = sink.open(f);
            } catch (NotBitPerfectException e) {
                closeQuietly(o.decoder());
                if (playedFormats.contains(withoutLength(f))) {
                    deviceLost(sink.name() + " refused " + f.label() + " after playing it earlier: "
                            + e.getMessage());
                    return Start.HALTED;
                }
                skip(o.index(), o.ref(), e.getMessage());
                return Start.SKIPPED;
            } catch (AudioDeviceException e) {
                closeQuietly(o.decoder());
                deviceLost(e.getMessage());
                return Start.HALTED;
            }
        }
        StreamCore core = stream.core();
        core.reset();
        try {
            core.setSource(f);
        } catch (NotBitPerfectException e) {
            closeQuietly(o.decoder());
            skip(o.index(), o.ref(), e.getMessage());
            return Start.SKIPPED;
        }
        playedFormats.add(withoutLength(f));
        SignalPath path = stream.signalPath();
        adopt(o);
        written = 0;
        synchronized (session) {
            boundaries.clear();
            playing = new Playing(o.index(), o.ref(), o.info(), f, path, core, 0, 0, core.dropouts());
        }
        core.setPaused(false);
        prefill(prefillFrames(path));
        if (!startStream()) {
            return Start.HALTED;
        }
        mode = Mode.FEEDING;
        state = PlayerState.PLAYING;
        fire(l -> l.trackStarted(o.index(), o.info(), path));
        fireState(status());
        return Start.STARTED;
    }

    /** Seeks the track being heard: stop the stream, discard the ring, reposition the decoder, refill, start. */
    private void seekTo(long frame) throws InterruptedException {
        Playing p = playing;
        long length = p.format().lengthFrames();
        long target = length >= 0 ? Math.min(frame, length) : frame;
        haltStream();
        discardPending();
        bufOff = bufFrames = 0;
        trackEnded = false;
        if (stream == null) {
            finish();
            return;
        }
        if (decoder == null || decIndex != p.index()) { // it already moved on to the next track
            closeDecoder();
            Decoder d;
            try {
                d = registry.open(p.ref());
            } catch (IOException e) {
                String why = "Could not reopen " + fileName(p.ref()) + ": " + message(e);
                fire(l -> l.error(why));
                finish();
                return;
            }
            adopt(new Opened(p.index(), p.ref(), d, p.info()));
        }
        decFrames = Math.max(decFrames, 1); // it was heard before the seek: a seek to its end is not "unplayable"
        try {
            decoder.seek(target);
        } catch (IOException e) {
            String why = stoppedReading(e);
            fire(l -> l.error(why));
            finish();
            return;
        }
        StreamCore core = stream.core();
        core.reset();
        try {
            core.setSource(p.format());
        } catch (NotBitPerfectException e) {
            throw new IllegalStateException("the stream refused the format it is playing", e);
        }
        written = 0;
        synchronized (session) {
            boundaries.clear();
            playing = new Playing(p.index(), p.ref(), p.info(), p.format(), p.path(), core, 0, target,
                    p.dropoutBaseline());
        }
        prefill(prefillFrames(p.path()));
        if (startStream()) {
            mode = Mode.FEEDING;
            fireState(status());
        }
    }

    private boolean startStream() {
        try {
            stream.start();
            running = true;
            return true;
        } catch (AudioDeviceException e) {
            deviceLost(e.getMessage());
            return false;
        }
    }

    // ---- opening, adopting, closing -----------------------------------------------------------------------------

    /** Opens the decoder, or returns null with the reason in {@link #openFailure} (the caller reports the skip). */
    private Decoder openDecoder(TrackRef ref) {
        try {
            return registry.open(ref);
        } catch (UnsupportedFormatException e) {
            openFailure = e.reason();
        } catch (IOException | RuntimeException e) {
            openFailure = message(e);
        }
        return null;
    }

    /** The library's description of the track; a bare one built from the format if the file cannot be probed. */
    private TrackInfo describe(TrackRef ref, StreamFormat format) {
        try {
            for (TrackInfo t : registry.probe(ref.path())) {
                if (t.subsong() == ref.subsong()) {
                    return t;
                }
            }
        } catch (IOException | RuntimeException e) {
            // the decoder opened it, so play it; the tags are just missing
        }
        String name = fileName(ref);
        int dot = name.lastIndexOf('.');
        String codec = dot > 0 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "?";
        return new TrackInfo(ref.path(), ref.subsong(), codec, format, null, true, null);
    }

    private void adopt(Opened o) {
        ranOut = false;
        decoder = o.decoder();
        decIndex = o.index();
        decRef = o.ref();
        frameBytes = o.decoder().format().bytesPerDecodedFrame();
        maxFrames = (int) Math.max(1, Math.min(CHUNK_FRAMES, buf.byteSize() / frameBytes));
        bufOff = bufFrames = 0;
        trackEnded = false;
        decFrames = 0;
        decFailed = false;
    }

    private void skip(int index, TrackRef ref, String reason) {
        lastFailure = reason;
        fire(l -> l.trackSkipped(index, ref, reason));
    }

    private void closeDecoder() {
        if (decoder != null) {
            closeQuietly(decoder);
            decoder = null;
        }
    }

    private void discardPending() {
        if (pendingNext != null) {
            closeQuietly(pendingNext.decoder());
            pendingNext = null;
        }
    }

    private static void closeQuietly(Decoder d) {
        try {
            d.close();
        } catch (IOException | RuntimeException ignored) {
            // nothing more to read from it either way
        }
    }

    /** Stops the driver calling back. If that fails the stream is dropped; the next track opens a new one. */
    private void haltStream() {
        if (!running) {
            return;
        }
        running = false;
        try {
            stream.stop();
        } catch (RuntimeException e) {
            stream = null; // its state is unknown; a real device reports the loss through its listener
        }
    }

    /** Stops output and drops all decoding; the stream stays open for reuse. Leaves the state alone. */
    private void stopOutput() {
        haltStream();
        closeDecoder();
        discardPending();
        bufOff = bufFrames = 0;
        trackEnded = false;
        mode = Mode.IDLE;
        synchronized (session) {
            boundaries.clear();
        }
    }

    /** STOPPED, remembering where playback was. */
    private void finish() {
        boolean wasActive = state != PlayerState.STOPPED || playing != null;
        PlayerStatus at = snapshot();
        stopOutput();
        toStopped(at);
        if (wasActive) {
            fireState(at);
        }
    }

    /**
     * Device trouble: stops and tells the listeners, unless a {@link SetSink} is already waiting. Then the sink
     * that failed is the one being replaced (the user picked another output, or a retry reopened it), so its
     * trouble is stale: stop quietly and let the switch go through.
     */
    private void deviceLost(String message) {
        if (sinkSwapPending()) {
            halt(l -> { });
        } else {
            halt(l -> l.deviceLost(message));
        }
    }

    private boolean sinkSwapPending() {
        for (Command c : commands) {
            if (c instanceof SetSink) {
                return true;
            }
        }
        return false;
    }

    /** Stops after trouble, drops the stream (the next play reopens it) and fires {@code event}. */
    private void halt(Consumer<PlayerListener> event) {
        PlayerStatus at = snapshot();
        try {
            stopOutput();
        } catch (RuntimeException ignored) {
            // already in trouble; the state below is what matters
        }
        running = false;
        stream = null;
        toStopped(at);
        fire(event);
        fireState(at);
    }

    private void toStopped(PlayerStatus at) {
        Playing p = playing;
        if (p != null) {
            lastIndex = p.index();
        }
        stoppedStatus = at;
        synchronized (session) {
            playing = null;
        }
        state = PlayerState.STOPPED;
    }

    /** The current status as a STOPPED one. */
    private PlayerStatus snapshot() {
        Playing p = playing;
        return p == null ? stoppedStatus : statusOf(p, PlayerState.STOPPED);
    }

    // ---- the ticker ---------------------------------------------------------------------------------------------

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            Thread th = Thread.currentThread();
            th.getUncaughtExceptionHandler().uncaughtException(th, t); // and keep ticking
        }
    }

    /** Makes every track whose boundary the output has reached the one being heard, and announces it. */
    private void tick() {
        List<Boundary> reached = new ArrayList<>(0);
        synchronized (session) {
            Playing p = playing;
            if (p == null) {
                return;
            }
            long played = p.core().framesPlayed();
            for (Boundary b = boundaries.peekFirst(); b != null && b.startFrame() <= played;
                    b = boundaries.peekFirst()) {
                boundaries.pollFirst();
                p = new Playing(b.index(), b.ref(), b.info(), b.format(), b.path(), p.core(), b.startFrame(), 0,
                        p.core().dropouts());
                reached.add(b);
            }
            if (reached.isEmpty()) {
                return;
            }
            playing = p;
        }
        for (Boundary b : reached) {
            fire(l -> l.trackStarted(b.index(), b.info(), b.path()));
        }
        fireState(status());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * The track after {@code from} in play order. Before the first one ({@code -1}, which {@link #applyEdit} leaves
     * behind when the track at an index was taken out and nothing before it is left) that is the first track.
     */
    private OptionalInt autoNext(int from) {
        if (from < 0) {
            return queue.isEmpty() ? OptionalInt.empty() : OptionalInt.of(0);
        }
        return autoNav().next(from);
    }

    /** Swaps in an edited queue and moves every index the decode thread holds to where its track now sits. */
    private void applyEdit(EditQueue e) {
        int[] map = e.oldToNew();
        queue = e.tracks();
        decIndex = remap(decIndex, map);
        lastIndex = remap(lastIndex, map);
        if (pendingNext != null) {
            Opened o = pendingNext;
            pendingNext = new Opened(remap(o.index(), map), o.ref(), o.decoder(), o.info());
        }
        if (mode == Mode.DRAINING && pendingNext == null && playing != null) {
            continueAfterAppend();
        }
        synchronized (session) {
            Playing p = playing;
            if (p != null) {
                playing = new Playing(remap(p.index(), map), p.ref(), p.info(), p.format(), p.path(), p.core(),
                        p.startFrame(), p.baseFrame(), p.dropoutBaseline());
            }
            if (!boundaries.isEmpty()) {
                List<Boundary> old = new ArrayList<>(boundaries);
                boundaries.clear();
                for (Boundary b : old) {
                    boundaries.addLast(new Boundary(b.startFrame(), remap(b.index(), map), b.ref(), b.info(),
                            b.format(), b.path()));
                }
            }
        }
    }

    /**
     * The last frame of the old last track is already written and the stream is playing it out (a window of about
     * two seconds): tracks added now would never be reached. Open the first one as {@link #pendingNext}, which
     * {@link #drained} starts on a fresh stream the moment the old one has played out.
     */
    private void continueAfterAppend() {
        OptionalInt c = autoNext(decIndex);
        while (c.isPresent() && unplayableRun < queue.size()) {
            int i = c.getAsInt();
            TrackRef ref = queue.get(i);
            Decoder d = openDecoder(ref);
            if (d != null) {
                pendingNext = new Opened(i, ref, d, describe(ref, d.format()));
                return;
            }
            skip(i, ref, openFailure);
            unplayableRun++;
            c = autoNav().next(i);
        }
    }

    /**
     * Where the track that was at {@code index} is now. A track taken out leaves the index of the last kept track
     * before it ({@code -1} if none), so that "the one after it" is still the right next track.
     */
    private static int remap(int index, int[] map) {
        if (index < 0) {
            return index;
        }
        if (index < map.length && map[index] >= 0) {
            return map[index];
        }
        int kept = -1;
        for (int j = 0; j < Math.min(index, map.length); j++) {
            if (map[j] >= 0) {
                kept = Math.max(kept, map[j]);
            }
        }
        return kept;
    }

    private PlaybackQueue autoNav() {
        return new PlaybackQueue(queue.size(), repeat, shuffle, shuffleSeed);
    }

    /** Next and previous buttons move on even under repeat track. */
    private PlaybackQueue manualNav() {
        RepeatMode m = repeat == RepeatMode.TRACK ? RepeatMode.OFF : repeat;
        return new PlaybackQueue(queue.size(), m, shuffle, shuffleSeed);
    }

    private static PlayerStatus statusOf(Playing p, PlayerState s) {
        long frames = positionFrames(p);
        long length = p.format().lengthFrames();
        long drops = p.core().dropouts() - p.dropoutBaseline();
        return new PlayerStatus(s, p.index(), p.info(), p.format().seconds(frames),
                length >= 0 ? p.format().seconds(length) : 0, p.path(), drops, p.path().notBitPerfectReason(drops));
    }

    private static long positionFrames(Playing p) {
        long frames = p.baseFrame() + Math.max(0, p.core().framesPlayed() - p.startFrame());
        long length = p.format().lengthFrames();
        return length >= 0 ? Math.min(frames, length) : frames;
    }

    /** Decoded frames per second: the sample rate for PCM, bytes per channel (8 DSD bits) for DSD. */
    private static double framesPerSecond(StreamFormat f) {
        return f.kind() == Kind.PCM ? f.sampleRate() : f.sampleRate() / 8.0;
    }

    /** The format as a stream sees it, without the track length. */
    private static StreamFormat withoutLength(StreamFormat f) {
        return new StreamFormat(f.kind(), f.sampleRate(), f.bits(), f.channels(), -1, f.dsdOrder());
    }

    private String stoppedReading(Exception e) {
        return "Stopped reading " + fileName(decRef) + ": " + message(e);
    }

    private static String fileName(TrackRef ref) {
        Path name = ref.path().getFileName();
        return name == null ? ref.path().toString() : name.toString();
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private void fireState(PlayerStatus s) {
        fire(l -> l.stateChanged(s));
    }

    private void fire(Consumer<PlayerListener> call) {
        if (listeners.isEmpty()) {
            return;
        }
        try {
            events.execute(() -> {
                for (PlayerListener l : listeners) {
                    try {
                        call.accept(l);
                    } catch (RuntimeException e) {
                        Thread t = Thread.currentThread();
                        t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // the event thread is gone (the app is exiting)
        }
    }
}
