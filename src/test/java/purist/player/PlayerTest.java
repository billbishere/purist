package purist.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.AudioDeviceException;
import purist.audio.AudioSink;
import purist.audio.CaptureSink;
import purist.audio.NotBitPerfectException;
import purist.audio.SampleType;
import purist.audio.SignalPath;
import purist.audio.SinkStream;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.decode.wav.WavHandler;
import purist.playlist.TrackRef;
import purist.testutil.FlacWriter;
import purist.testutil.WavWriter;

/**
 * Drives the real Player through a {@link CaptureSink} whose "driver" is a pump thread calling {@code pump(1)}
 * every millisecond (512 frames, so about 11× real time at 44.1 kHz). Every check is made on the captured bytes.
 *
 * <p>Test signal: 16-bit stereo where each frame names itself. Left = {@code track * 1000 + (frame >> 14) + 1},
 * right = {@code -((frame & 0x3FFF) + 1)}: never zero, so silence (both channels 0) is unmistakable, and the
 * track and frame of every captured frame can be read back. Timing is only ever awaited, never assumed.
 */
@Timeout(60)
class PlayerTest {

    private static final long SILENCE = -1;
    private static final long WAIT_NANOS = 20_000_000_000L;

    @TempDir
    Path dir;

    private CaptureSink sink;
    private Player player;
    private Thread pump;
    private volatile boolean pumping = true;
    /** While true the fake driver takes nothing from the stream, so whatever was written stays queued. */
    private volatile boolean driverStalled;
    private final Recorder rec = new Recorder();

    @BeforeEach
    void setUp() {
        sink = new CaptureSink("Capture", SampleType.INT32_LSB, SampleType.DSD_INT8_MSB1, 512,
                Set.of(44100, 48000, 96000));
        pump = new Thread(() -> {
            while (pumping) {
                if (!driverStalled) {
                    sink.pump(1);
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "test-pump");
        pump.setDaemon(true);
        pump.start();
        player = new Player(sink, DecoderRegistry.standard(), Runnable::run);
        player.addListener(rec);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        player.close();
        pumping = false;
        pump.join(5000);
        sink.close();
    }

    // ---- signal ---------------------------------------------------------------------------------------------

    private static int left(int track, long frame) {
        return track * 1000 + (int) (frame >> 14) + 1;
    }

    private static int right(long frame) {
        return -(int) ((frame & 0x3FFF) + 1);
    }

    private static long code(int track, long frame) {
        return track * 100_000_000L + frame;
    }

    private static int[] samples(int track, int frames) {
        int[] s = new int[frames * 2];
        for (int i = 0; i < frames; i++) {
            s[2 * i] = left(track, i);
            s[2 * i + 1] = right(i);
        }
        return s;
    }

    private Path wav(String name, int track, int rate, int frames) throws IOException {
        return WavWriter.write(dir, name, rate, 16, 2, samples(track, frames));
    }

    private static long[] codes(int track, long from, long to) {
        return LongStream.range(from, to).map(f -> code(track, f)).toArray();
    }

    private static long[] concat(long[]... parts) {
        return Arrays.stream(parts).flatMapToLong(Arrays::stream).toArray();
    }

    /** Every captured frame, as {@link #code} or {@link #SILENCE}; fails on anything that is not the signal. */
    private long[] captured() {
        int[] l = ints(sink.captured(0));
        int[] r = ints(sink.captured(1));
        long[] out = new long[Math.min(l.length, r.length)];
        for (int i = 0; i < out.length; i++) {
            if (l[i] == 0 && r[i] == 0) {
                out[i] = SILENCE;
                continue;
            }
            if ((l[i] & 0xFFFF) != 0 || (r[i] & 0xFFFF) != 0) {
                fail("frame " + i + " is not a 16-bit sample left-justified in INT32: " + l[i] + ", " + r[i]);
            }
            int a = l[i] >> 16;
            int b = r[i] >> 16;
            int track = a / 1000;
            long frame = (long) (a - track * 1000 - 1) << 14 | (-b - 1);
            out[i] = code(track, frame);
        }
        return out;
    }

    /** The captured frames with the silence taken out: what was heard, in order. */
    private long[] heard() {
        return Arrays.stream(captured()).filter(c -> c != SILENCE).toArray();
    }

    private static int[] ints(byte[] b) {
        int[] out = new int[b.length / 4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
        return out;
    }

    private static void assertAllSilence(long[] frames, int from, String what) {
        for (int i = from; i < frames.length; i++) {
            if (frames[i] != SILENCE) {
                fail(what + ": frame " + i + " is data " + frames[i]);
            }
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for: " + what);
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Waits for playback to have started and then come to its natural end. */
    private void awaitPlayedToTheEnd() {
        await(() -> rec.sawPlaying && player.status().state() == PlayerState.STOPPED, "playback to finish");
    }

    private static List<TrackRef> refs(Path... paths) {
        return Arrays.stream(paths).map(p -> new TrackRef(p, 0)).toList();
    }

    // ---- output correctness -----------------------------------------------------------------------------------

    @Test
    void playsTrackBitIdentical() throws Exception {
        int n = 22050;
        Path a = wav("a.wav", 1, 44100, n);
        player.setQueue(refs(a), 0);
        awaitPlayedToTheEnd();

        byte[] left = sink.captured(0);
        byte[] right = sink.captured(1);
        ByteBuffer wantLeft = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer wantRight = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            wantLeft.putInt(left(1, i) << 16); // 16-bit left-justified into INT32_LSB, nothing else
            wantRight.putInt(right(i) << 16);
        }
        assertArrayEquals(wantLeft.array(), Arrays.copyOf(left, n * 4), "left channel");
        assertArrayEquals(wantRight.array(), Arrays.copyOf(right, n * 4), "right channel");
        assertAllSilence(captured(), n, "after the track");
        assertEquals(1, sink.opens());
        assertEquals(List.of(0), rec.startedIndices());
        PlayerStatus end = player.status();
        assertEquals(0, end.dropoutsThisTrack());
        assertEquals(0, end.index());
        assertEquals(a, end.track().path());
    }

    @Test
    void gaplessSameFormat() throws Exception {
        int na = 17640;
        int nb = 17640;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 44100, nb);
        player.setQueue(refs(a, b), 0);
        awaitPlayedToTheEnd();

        long[] cap = captured();
        assertArrayEquals(concat(codes(1, 0, na), codes(2, 0, nb)), Arrays.copyOf(cap, na + nb),
                "b follows a with no frame of silence between them");
        assertAllSilence(cap, na + nb, "after the queue");
        assertEquals(1, sink.opens(), "one stream for both tracks");
        assertEquals(List.of(0, 1), rec.startedIndices());
        assertEquals(0, player.status().dropoutsThisTrack());
    }

    @Test
    void rateChangeReopens() throws Exception {
        int na = 17640;
        int nb = 38400;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 96000, nb);
        player.setQueue(refs(a, b), 0);
        awaitPlayedToTheEnd();

        assertArrayEquals(codes(1, 0, na), Arrays.copyOf(captured(), na), "a, unbroken");
        assertArrayEquals(concat(codes(1, 0, na), codes(2, 0, nb)), heard(), "a played out, then all of b");
        assertEquals(2, sink.opens(), "the stream was reopened at 96 kHz");
        assertEquals(List.of(0, 1), rec.startedIndices());
        assertEquals(96000.0, rec.started.get(1).path().deviceRate());
    }

    // ---- failures ---------------------------------------------------------------------------------------------

    @Test
    void unsupportedRateSkipsWithReason() throws Exception {
        int na = 8820;
        int nc = 8820;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 352800, 35280);
        Path c = wav("c.wav", 3, 44100, nc);
        player.setQueue(refs(a, b, c), 0);
        awaitPlayedToTheEnd();

        assertEquals(List.of(new Skip(1, new TrackRef(b, 0), "rate 352800 Hz not supported by Capture")), rec.skipped);
        assertArrayEquals(concat(codes(1, 0, na), codes(3, 0, nc)), heard(), "a, then c; nothing of b");
        assertEquals(List.of(0, 2), rec.startedIndices());
        assertEquals(List.of(), rec.errors);
        assertEquals(List.of(), rec.deviceLost);
    }

    @Test
    void corruptFileSkipsToNext() throws Exception {
        int block = 1024;
        int flacFrames = 10 * block;
        Path whole = FlacWriter.writeFile(dir, "whole.flac", 44100, 16, 2, samples(1, flacFrames), block,
                FlacWriter.SubframeKind.VERBATIM, FlacWriter.ChannelMode.INDEPENDENT, false, null, null);
        byte[] bytes = Files.readAllBytes(whole);
        // A verbatim 1024-frame stereo 16-bit frame is over 4 KiB, so this cut lands inside the last frame.
        Path cut = Files.write(dir.resolve("cut.flac"), Arrays.copyOf(bytes, bytes.length - 2000));
        int nw = 8820;
        Path w = wav("w.wav", 2, 44100, nw);

        player.setQueue(refs(cut, w), 0);
        awaitPlayedToTheEnd();

        assertEquals(1, rec.errors.size(), "errors: " + rec.errors);
        assertTrue(rec.errors.get(0).startsWith("Stopped reading cut.flac: truncated"), rec.errors.get(0));
        long[] want = concat(codes(1, 0, flacFrames - block), codes(2, 0, nw));
        assertArrayEquals(want, Arrays.copyOf(captured(), want.length),
                "the nine good FLAC frames, then the WAV, gaplessly");
        assertArrayEquals(want, heard());
        assertEquals(List.of(0, 1), rec.startedIndices());
        assertEquals(List.of(), rec.skipped);
    }

    // ---- controls ---------------------------------------------------------------------------------------------

    @Test
    void thePrefillIsTwoBuffersPlusOneInTheUnitTheSignalPathReports() {
        // A DoP device buffer of 480 device frames is 960 core frames, which is what the WASAPI stream reports.
        SignalPath dop = new SignalPath("D", StreamFormat.dsd(2822400, 2, -1, purist.audio.BitOrder.MSB_FIRST),
                SampleType.DSD_INT8_MSB1, 2822400, 960, new purist.audio.Transform(0, false, false));
        assertEquals(2L * 960 + 1, Player.prefillFrames(dop));
        SignalPath pcm = new SignalPath("D", StreamFormat.pcm(44100, 16, 2, -1), SampleType.INT32_LSB, 44100, 512,
                new purist.audio.Transform(16, false, false));
        assertEquals(2L * 512 + 1, Player.prefillFrames(pcm));
    }

    @Test
    void pauseOutputsSilenceWithoutDropouts() throws Exception {
        int n = 3 * 44100;
        Path a = wav("a.wav", 1, 44100, n);
        player.setQueue(refs(a), 0);
        await(() -> player.status().positionSeconds() >= 0.3, "0.3 s played");

        player.pause();
        await(() -> player.status().state() == PlayerState.PAUSED, "paused");
        double pausedAt = player.status().positionSeconds();
        int before = sink.captured(0).length / 4;
        await(() -> sink.captured(0).length / 4 >= before + 40 * 512, "40 callbacks while paused");
        PlayerStatus paused = player.status();
        assertEquals(PlayerState.PAUSED, paused.state());
        assertEquals(pausedAt, paused.positionSeconds(), "the position does not move while paused");
        assertEquals(0, paused.dropoutsThisTrack(), "paused silence is not a dropout");

        player.resume();
        awaitPlayedToTheEnd();

        assertArrayEquals(codes(1, 0, n), heard(), "every frame, once, in order");
        long[] cap = captured();
        int last = cap.length - 1;
        while (cap[last] == SILENCE) {
            last--;
        }
        List<Integer> runs = silenceRuns(cap, last);
        assertEquals(1, runs.size(), "silence only where it was paused: " + runs);
        assertTrue(runs.get(0) >= 40 * 512, "the paused stretch: " + runs);
        assertEquals(0, player.status().dropoutsThisTrack());
        assertEquals(List.of(), rec.errors);
    }

    /** The lengths of the runs of silence inside frames {@code [0, end)}. */
    private static List<Integer> silenceRuns(long[] frames, int end) {
        List<Integer> runs = new ArrayList<>();
        int run = 0;
        for (int i = 0; i < end; i++) {
            if (frames[i] == SILENCE) {
                run++;
            } else if (run > 0) {
                runs.add(run);
                run = 0;
            }
        }
        return runs;
    }

    @Test
    void seekPositionsExactly() throws Exception {
        int n = 3 * 44100;
        Path a = wav("a.wav", 1, 44100, n);
        player.setQueue(refs(a), 0);
        await(() -> player.status().positionSeconds() >= 0.1, "0.1 s played");

        player.seek(1.0);
        awaitPlayedToTheEnd();

        long[] heard = heard();
        assertEquals(code(1, 0), heard[0]);
        int jump = -1;
        for (int i = 1; i < heard.length; i++) {
            if (heard[i] != heard[i - 1] + 1) {
                assertEquals(-1, jump, "a second discontinuity at " + i);
                jump = i;
            }
        }
        assertTrue(jump > 0, "the seek left no discontinuity");
        assertEquals(code(1, 44100), heard[jump], "after the seek the next frame is source frame 44100");
        assertEquals(code(1, n - 1), heard[heard.length - 1], "then on to the end");
        assertEquals(0, player.status().dropoutsThisTrack());
    }

    @Test
    void trackStartedFiresAtBoundary() throws Exception {
        int na = 22050;
        int nb = 3 * 44100;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 44100, nb);
        player.setQueue(refs(a, b), 0);
        awaitPlayedToTheEnd();

        assertEquals(List.of(0, 1), rec.startedIndices());
        Started second = rec.started.get(1);
        assertEquals(b, second.track().path());
        assertEquals(44100, second.track().format().sampleRate());
        assertEquals(44100.0, second.path().deviceRate());
        assertEquals("Capture", second.path().deviceName());
        // The ticker runs every 50 ms: trackStarted comes once the output has reached b, while b is playing.
        assertTrue(second.capturedFrames() + 512 >= na, "fired before the boundary: " + second.capturedFrames());
        assertTrue(second.capturedFrames() < na + nb, "fired only after b ended: " + second.capturedFrames());
        assertEquals(1, second.status().index(), "status follows the boundary");
        assertEquals(b, second.status().track().path());
        assertTrue(second.status().positionSeconds() < 3.0, "position is within b: " + second.status());
    }

    @Test
    void nextPreviousAndRestart() throws Exception {
        int n = 5 * 44100;
        Path a = wav("a.wav", 1, 44100, n);
        Path b = wav("b.wav", 2, 44100, n);
        player.setQueue(refs(a, b), 0);
        await(() -> player.status().positionSeconds() >= 0.1, "a under way");

        player.next();
        await(() -> player.status().index() == 1 && player.status().positionSeconds() >= 0.05, "b under way");
        player.previous(); // b is well under 3 s in: back to a
        await(() -> player.status().index() == 0, "previous went back to a");
        await(() -> player.status().positionSeconds() > 3.2, "a past 3 s");
        player.previous(); // past 3 s: restart a
        await(() -> player.status().positionSeconds() >= 0.05 && player.status().positionSeconds() < 1.0,
                "a restarted and under way again");
        assertEquals(0, player.status().index());
        player.stop();
        await(() -> player.status().state() == PlayerState.STOPPED, "stopped");

        assertEquals(List.of(0, 1, 0), rec.startedIndices(), "a restart is not a new track");
        assertEquals(List.of(code(1, 0), code(2, 0), code(1, 0), code(1, 0)), segmentStarts(heard()));
    }

    /** The first frame of every run of consecutive frames. */
    private static List<Long> segmentStarts(long[] heard) {
        List<Long> starts = new ArrayList<>();
        for (int i = 0; i < heard.length; i++) {
            if (i == 0 || heard[i] != heard[i - 1] + 1) {
                starts.add(heard[i]);
            }
        }
        return starts;
    }

    // ---- editing the queue while it plays -------------------------------------------------------------------

    @Test
    void reorderingWhatFollowsDoesNotTouchTheTrackPlaying() throws Exception {
        int na = 6 * 44100;
        int nb = 4410;
        int nc = 4410;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 44100, nb);
        Path c = wav("c.wav", 3, 44100, nc);
        player.setQueue(refs(a, b, c), 0);
        await(() -> player.status().positionSeconds() >= 0.3, "a under way");

        List<TrackRef> edited = refs(a, c, b);
        player.editQueue(edited, new int[] {0, 2, 1});
        assertEquals(edited, player.queue());
        awaitPlayedToTheEnd();

        assertArrayEquals(concat(codes(1, 0, na), codes(3, 0, nc), codes(2, 0, nb)), heard(),
                "a unbroken, then c, then b: the new order");
        assertEquals(List.of(0, 1, 2), rec.startedIndices(), "indices are the new queue's");
        assertEquals(1, sink.opens(), "one stream the whole time");
        assertEquals(List.of(), rec.errors);
    }

    @Test
    void appendingToTheQueueWhileItPlaysPlaysTheNewTrackNext() throws Exception {
        int na = 6 * 44100;
        int nb = 4410;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 44100, nb);
        player.setQueue(refs(a), 0);
        await(() -> player.status().positionSeconds() >= 0.3, "a under way");

        player.editQueue(refs(a, b), new int[] {0});
        awaitPlayedToTheEnd();

        assertArrayEquals(concat(codes(1, 0, na), codes(2, 0, nb)), heard(), "b follows a gaplessly");
        assertEquals(List.of(0, 1), rec.startedIndices());
        assertEquals(1, sink.opens());
    }

    @Test
    void tracksAddedWhileTheLastOneIsPlayingOutItsBufferStillPlay() throws Exception {
        int na = 20_000; // fits in the stream's buffer: all of it is written, and the input ended, long before it is heard
        int nb = 4410;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 44100, nb);
        driverStalled = true;
        player.setQueue(refs(a), 0);
        await(() -> rec.sawPlaying, "a started");
        Thread.sleep(300); // the decode thread has written all of a and is waiting for it to be heard

        player.editQueue(refs(a, b), new int[] {0});
        driverStalled = false;
        awaitPlayedToTheEnd();

        assertArrayEquals(concat(codes(1, 0, na), codes(2, 0, nb)), heard(), "b follows a though a's end was written");
        assertEquals(List.of(0, 1), rec.startedIndices());
        assertEquals(List.of(), rec.errors);
    }

    @Test
    void theQueueRanOutOnlyWhenTheLastTrackFinishedByItself() throws Exception {
        int na = 4410;
        Path a = wav("a.wav", 1, 44100, na);
        assertFalse(player.queueRanOut());
        player.setQueue(refs(a), 0);
        awaitPlayedToTheEnd();
        await(player::queueRanOut, "the end of the queue to be noted");

        player.play(0);
        await(() -> !player.queueRanOut(), "playing again clears it");
        awaitPlayedToTheEnd();
        await(player::queueRanOut, "ran out again");
        player.setQueue(refs(a), -1);
        await(() -> !player.queueRanOut(), "a new queue clears it");

        driverStalled = true;
        player.play(0);
        await(() -> rec.sawPlaying, "playing");
        player.stop();
        await(() -> player.status().state() == PlayerState.STOPPED, "stopped");
        assertFalse(player.queueRanOut(), "a stop by the user is not the end of the queue");
    }

    @Test
    void theQueueIsAlreadyMarkedRanOutWhenTheStoppedStateIsAnnounced() throws Exception {
        Path a = wav("a.wav", 1, 44100, 4410);
        List<Boolean> seenAtStop = new java.util.concurrent.CopyOnWriteArrayList<>();
        player.addListener(new PlayerListener() {
            @Override
            public void stateChanged(PlayerStatus s) {
                if (s.state() == PlayerState.STOPPED) {
                    seenAtStop.add(player.queueRanOut()); // a PLAY issued from here must already see "ran out"
                }
            }
        });

        player.setQueue(refs(a), 0);
        awaitPlayedToTheEnd();
        await(() -> !seenAtStop.isEmpty(), "the stop to be announced");

        assertEquals(List.of(true), seenAtStop);
    }

    @Test
    void aBadOldToNewMapIsRefusedAtOnceNotOnTheDecodeThread() {
        Path a = Path.of("nowhere", "a.wav");
        Path b = Path.of("nowhere", "b.wav");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> player.editQueue(refs(a, b), new int[] {0, 2}));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> player.editQueue(refs(a, b), new int[] {-2}));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> player.editQueue(refs(a, b), new int[] {1, 1}));
        assertEquals(List.of(), player.queue(), "a refused edit changes nothing");
        player.editQueue(refs(a, b), new int[] {-1, 1}); // taking one out is fine
        assertEquals(refs(a, b), player.queue());
    }

    @Test
    void removingAFutureTrackWhilePlayingSkipsIt() throws Exception {
        int na = 6 * 44100;
        int nb = 4410;
        int nc = 4410;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 44100, nb);
        Path c = wav("c.wav", 3, 44100, nc);
        player.setQueue(refs(a, b, c), 0);
        await(() -> player.status().positionSeconds() >= 0.3, "a under way");

        player.editQueue(refs(a, c), new int[] {0, -1, 1});
        awaitPlayedToTheEnd();

        assertArrayEquals(concat(codes(1, 0, na), codes(3, 0, nc)), heard(), "b was taken out");
        assertEquals(List.of(0, 1), rec.startedIndices());
    }

    @Test
    void movingTheTrackPlayingKeepsItPlayingUnderItsNewIndex() throws Exception {
        int na = 6 * 44100;
        int nb = 4410;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = wav("b.wav", 2, 44100, nb);
        player.setQueue(refs(a, b), 0);
        await(() -> player.status().positionSeconds() >= 0.3, "a under way");

        player.editQueue(refs(b, a), new int[] {1, 0}); // a is now the second of two
        await(() -> player.status().index() == 1, "the status follows a to its new index");
        assertEquals(a, player.status().track().path());
        awaitPlayedToTheEnd();

        assertArrayEquals(concat(codes(1, 0, na)), heard(), "a plays on to its end; nothing follows it now");
        assertEquals(1, sink.opens());
    }

    @Test
    void editingAStoppedQueueJustReplacesIt() {
        Path a = Path.of("nowhere", "a.wav");
        Path b = Path.of("nowhere", "b.wav");
        player.editQueue(refs(a, b), new int[0]);
        assertEquals(refs(a, b), player.queue());
        assertEquals(PlayerState.STOPPED, player.status().state());
    }

    // ---- device trouble (never a skip) ---------------------------------------------------------------------

    @Test
    void deviceLostStopsWithoutSkipping() throws Exception {
        FlakySink flaky = new FlakySink(sink);
        player.setSink(flaky);
        Path a = wav("a.wav", 1, 44100, 5 * 44100);
        player.setQueue(refs(a), 0);
        await(() -> player.status().positionSeconds() >= 0.1, "playing");

        flaky.lose("Volt unplugged");
        await(() -> player.status().state() == PlayerState.STOPPED, "stopped");
        assertEquals(List.of("Volt unplugged"), rec.deviceLost);
        assertEquals(List.of(), rec.skipped);
        PlayerStatus at = player.status();
        assertEquals(0, at.index(), "the status remembers what was playing, for a retry");
        assertTrue(at.positionSeconds() > 0, "and where: " + at);

        player.play(0); // the retry reopens the device
        await(() -> rec.started.size() == 2, "playing again");
        assertEquals(2, sink.opens());
    }

    @Test
    void refusingAFormatItPlayedBeforeIsDeviceTroubleNotASkip() throws Exception {
        FlakySink flaky = new FlakySink(sink);
        player.setSink(flaky);
        Path a = wav("a.wav", 1, 44100, 8820);
        Path b = wav("b.wav", 2, 48000, 9600);
        Path c = wav("c.wav", 3, 44100, 8820);
        flaky.refuseAfterOpens(1, "device says no");
        player.setQueue(refs(a, b, c), 0);
        await(() -> !rec.deviceLost.isEmpty(), "device trouble reported");

        assertEquals(List.of(new Skip(1, new TrackRef(b, 0), "device says no")), rec.skipped,
                "48 kHz was never played, so its refusal is an ordinary skip");
        assertEquals(List.of("Capture refused 16/44.1 after playing it earlier: device says no"), rec.deviceLost);
        assertEquals(PlayerState.STOPPED, player.status().state());
        assertArrayEquals(codes(1, 0, 8820), heard());
    }

    @Test
    void aQueueWithNothingPlayableStopsInsteadOfLooping() throws Exception {
        player.setRepeat(RepeatMode.ALL);
        Path b = wav("b.wav", 2, 352800, 3528);
        Path d = wav("d.wav", 4, 352800, 3528);
        player.setQueue(refs(b, d), 0);
        await(() -> !rec.errors.isEmpty(), "the player gives up");

        assertEquals(List.of("None of the 2 tracks in the queue could be played (last: rate 352800 Hz not supported"
                + " by Capture)"), rec.errors);
        assertEquals(List.of(0, 1), rec.skipped.stream().map(Skip::index).toList(), "each skipped once");
        assertEquals(PlayerState.STOPPED, player.status().state());
    }

    @Test
    void repeatAllOverTracksThatGiveNothingStopsInsteadOfSpinning() throws Exception {
        Path empty = WavWriter.write(dir, "empty.wav", 44100, 16, 2, new int[0]);
        Path whole = FlacWriter.writeFile(dir, "one.flac", 44100, 16, 2, samples(1, 1024), 1024,
                FlacWriter.SubframeKind.VERBATIM, FlacWriter.ChannelMode.INDEPENDENT, false, null, null);
        byte[] bytes = Files.readAllBytes(whole);
        // Its only frame is over 4 KiB: this cut leaves it damaged from the first frame on.
        Path broken = Files.write(dir.resolve("broken.flac"), Arrays.copyOf(bytes, bytes.length - 2000));
        player.setRepeat(RepeatMode.ALL);
        player.setQueue(refs(empty, broken), 0);

        await(() -> rec.errors.stream().anyMatch(e -> e.startsWith("None of the 2 tracks")), "the player gives up");
        await(() -> player.status().state() == PlayerState.STOPPED, "stopped");
        assertTrue(rec.errors.getLast().startsWith("None of the 2 tracks in the queue could be played (last: "
                + "Stopped reading broken.flac: "), rec.errors.toString());
        assertTrue(rec.errors.size() <= 3, "it did not loop: " + rec.errors);
        assertArrayEquals(new long[0], heard());
    }

    @Test
    void seekingToTheEndUnderRepeatAllPlaysOnNotGivesUp() throws Exception {
        Path a = wav("a.wav", 1, 44100, 3 * 44100);
        player.setRepeat(RepeatMode.ALL);
        player.setQueue(refs(a), 0);
        await(() -> player.status().positionSeconds() >= 0.1, "playing");
        player.seek(10.0); // past the end: nothing more of this pass is written
        await(() -> rec.started.size() >= 2, "the next pass of the one-track queue");
        assertEquals(List.of(), rec.errors);
    }

    @Test
    void errorTextNamesTheFileEvenWhenEventsRunLater() throws Exception {
        int block = 1024;
        Path whole = FlacWriter.writeFile(dir, "whole.flac", 44100, 16, 2, samples(1, 4 * block), block,
                FlacWriter.SubframeKind.VERBATIM, FlacWriter.ChannelMode.INDEPENDENT, false, null, null);
        byte[] bytes = Files.readAllBytes(whole);
        Path cut = Files.write(dir.resolve("cut.flac"), Arrays.copyOf(bytes, bytes.length - 2000));
        Path w = wav("w.wav", 2, 44100, 4410);
        ConcurrentLinkedQueue<Runnable> later = new ConcurrentLinkedQueue<>(); // like the EDT: runs afterwards
        Recorder late = new Recorder();
        try (Player p = new Player(sink, DecoderRegistry.standard(), later::add)) {
            p.addListener(late);
            p.setQueue(refs(cut, w), 0);
            await(() -> p.status().index() == 1 && p.status().state() == PlayerState.STOPPED, "both played");
        }
        for (Runnable r = later.poll(); r != null; r = later.poll()) {
            r.run();
        }
        assertEquals(1, late.errors.size(), late.errors.toString());
        assertTrue(late.errors.get(0).startsWith("Stopped reading cut.flac: "), late.errors.get(0));
    }

    @Test
    void aSlowOrFailingNextTrackCostsNoDropout() throws Exception {
        AtomicReference<Player> slowPlayer = new AtomicReference<>();
        AtomicReference<PlayerStatus> duringProbe = new AtomicReference<>();
        WavHandler wav = new WavHandler();
        DecoderRegistry registry = DecoderRegistry.standard();
        registry.register(new FormatHandler() { // a WAV on a slow disk: the probe takes a second
            @Override
            public List<String> extensions() {
                return List.of("slow");
            }

            @Override
            public List<TrackInfo> probe(Path p) throws IOException {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                duringProbe.compareAndSet(null, slowPlayer.get().status());
                return wav.probe(p);
            }

            @Override
            public Decoder open(Path p, int subsong) throws IOException {
                return wav.open(p, subsong);
            }
        });
        registry.register(new FormatHandler() { // a file that cannot be opened at all
            @Override
            public List<String> extensions() {
                return List.of("bad");
            }

            @Override
            public List<TrackInfo> probe(Path p) throws IOException {
                throw new DecodeException("damaged");
            }

            @Override
            public Decoder open(Path p, int subsong) throws IOException {
                throw new DecodeException("damaged header");
            }
        });
        int na = 11025; // a quarter second: played out long before the slow probe returns
        int nc = 9600;
        Path a = wav("a.wav", 1, 44100, na);
        Path b = Files.copy(wav("b.wav", 2, 44100, 4410), dir.resolve("b.bad"));
        Path c = Files.copy(wav("c.wav", 3, 96000, nc), dir.resolve("c.slow"));
        Recorder own = new Recorder();
        try (Player p = new Player(sink, registry, Runnable::run)) {
            slowPlayer.set(p);
            p.addListener(own);
            p.setQueue(refs(a, b, c), 0);
            await(() -> own.started.size() == 2 && p.status().state() == PlayerState.STOPPED, "a, then c, played");
        }

        assertEquals(0, duringProbe.get().index(), "a was still the track being heard: " + duringProbe.get());
        assertEquals(0, duringProbe.get().dropoutsThisTrack(),
                "a ran dry while c was being probed: that must be its end, not a dropout");
        assertEquals(List.of(new Skip(1, new TrackRef(b, 0), "damaged header")), own.skipped);
        assertArrayEquals(concat(codes(1, 0, na), codes(3, 0, nc)), heard());
    }

    // ---- switching sinks -------------------------------------------------------------------------------------

    private CaptureSink secondSink() {
        return new CaptureSink("Second", SampleType.INT32_LSB, SampleType.DSD_INT8_MSB1, 512, Set.of(44100, 48000));
    }

    @Test
    void setSinkCompletesOnlyOnceTheSwitchIsAppliedAndTheOldSinkIsNoLongerInUse() throws Exception {
        GateSink gate = new GateSink(sink);
        assertSame(sink, player.setSink(gate).get(10, TimeUnit.SECONDS));
        gate.holdOpen = new CountDownLatch(1);
        Path a = wav("a.wav", 1, 44100, 5 * 44100);
        player.setQueue(refs(a), 0);
        assertTrue(gate.openEntered.await(10, TimeUnit.SECONDS), "the decode thread is inside the old sink's open");

        CaptureSink second = secondSink();
        CompletableFuture<AudioSink> switched = player.setSink(second);
        Thread.sleep(300); // a fixed timer would have fired by now and closed a sink that is in use
        assertFalse(switched.isDone(), "the decode thread is still busy with the old sink");

        gate.holdOpen.countDown();
        assertSame(gate, switched.get(10, TimeUnit.SECONDS), "completes with the previous sink");
        assertEquals(PlayerState.STOPPED, player.status().state(), "the switch stopped playback");

        gate.close(); // what MainWindow does next, off the EDT
        Thread.sleep(300);
        assertEquals(0, gate.usesAfterClose.get(), "nothing touched the old sink after the switch");
        assertEquals(List.of(), rec.deviceLost);
        assertEquals(List.of(), rec.errors);

        player.play(0);
        await(() -> second.opens() == 1, "the next play opens the new sink");
        assertEquals(List.of(), rec.deviceLost);
    }

    @Test
    void aFailureOfTheSinkBeingReplacedIsStaleAndNotAnnounced() throws Exception {
        GateSink gate = new GateSink(sink);
        player.setSink(gate).get(10, TimeUnit.SECONDS);
        gate.holdStart = new CountDownLatch(1);
        gate.failStart = true;
        Path a = wav("a.wav", 1, 44100, 5 * 44100);
        player.setQueue(refs(a), 0);
        assertTrue(gate.startEntered.await(10, TimeUnit.SECONDS), "the decode thread is inside the old stream's start");

        CompletableFuture<AudioSink> switched = player.setSink(secondSink());
        gate.holdStart.countDown(); // now the old start() fails, with the switch already waiting
        assertSame(gate, switched.get(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(List.of(), rec.deviceLost, "the output the user replaced is not reported lost");
        assertEquals(PlayerState.STOPPED, player.status().state());
    }

    @Test
    void aStartFailureWithNoSwitchWaitingIsStillReportedLost() throws Exception {
        GateSink gate = new GateSink(sink);
        player.setSink(gate).get(10, TimeUnit.SECONDS);
        gate.failStart = true;
        player.setQueue(refs(wav("a.wav", 1, 44100, 44100)), 0);
        await(() -> !rec.deviceLost.isEmpty(), "device loss reported");
        assertEquals(List.of("start failed"), rec.deviceLost);
    }

    @Test
    void setSinkAfterCloseCompletesWithNull() throws Exception {
        player.close();
        assertNull(player.setSink(secondSink()).get(5, TimeUnit.SECONDS));
    }

    /** A {@link CaptureSink} whose open and start can be held or made to fail, and that notices use after close. */
    private static final class GateSink implements AudioSink {
        private final CaptureSink delegate;
        final CountDownLatch openEntered = new CountDownLatch(1);
        final CountDownLatch startEntered = new CountDownLatch(1);
        final AtomicInteger usesAfterClose = new AtomicInteger();
        volatile CountDownLatch holdOpen;
        volatile CountDownLatch holdStart;
        volatile boolean failStart;
        private volatile boolean closed;

        GateSink(CaptureSink delegate) {
            this.delegate = delegate;
        }

        private static void await(CountDownLatch l) {
            if (l != null) {
                try {
                    l.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public SinkStream open(StreamFormat f) throws NotBitPerfectException {
            openEntered.countDown();
            await(holdOpen);
            if (closed) {
                usesAfterClose.incrementAndGet();
            }
            return new GateStream(delegate.open(f));
        }

        @Override
        public void addDeviceListener(Consumer<String> onDeviceLost) {
            delegate.addDeviceListener(onDeviceLost);
        }

        @Override
        public void close() {
            closed = true;
        }

        private final class GateStream implements SinkStream {
            private final SinkStream inner;

            GateStream(SinkStream inner) {
                this.inner = inner;
            }

            @Override
            public purist.audio.StreamCore core() {
                return inner.core();
            }

            @Override
            public SignalPath signalPath() {
                return inner.signalPath();
            }

            @Override
            public boolean accepts(StreamFormat next) {
                return inner.accepts(next);
            }

            @Override
            public void start() throws AudioDeviceException {
                startEntered.countDown();
                await(holdStart);
                if (closed) {
                    usesAfterClose.incrementAndGet();
                }
                if (failStart) {
                    throw new AudioDeviceException("start failed", null);
                }
                inner.start();
            }

            @Override
            public void stop() {
                if (closed) {
                    usesAfterClose.incrementAndGet();
                }
                inner.stop();
            }
        }
    }

    /** A {@link CaptureSink} that can refuse every open after the first few, or report the device lost. */
    private static final class FlakySink implements AudioSink {
        private final CaptureSink delegate;
        private final List<Consumer<String>> lostListeners = new CopyOnWriteArrayList<>();
        private volatile int opensAllowed = Integer.MAX_VALUE;
        private volatile String refusal;
        private int opens;

        FlakySink(CaptureSink delegate) {
            this.delegate = delegate;
        }

        void refuseAfterOpens(int allowed, String reason) {
            opensAllowed = allowed;
            refusal = reason;
        }

        void lose(String message) {
            lostListeners.forEach(l -> l.accept(message));
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public synchronized SinkStream open(StreamFormat f) throws NotBitPerfectException {
            if (opens >= opensAllowed) {
                delegate.close(); // like a real sink: after a refusal there is no open stream
                throw new NotBitPerfectException(refusal);
            }
            SinkStream s = delegate.open(f);
            opens++;
            return s;
        }

        @Override
        public void addDeviceListener(Consumer<String> onDeviceLost) {
            lostListeners.add(onDeviceLost);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    // ---- recorder ---------------------------------------------------------------------------------------------

    record Skip(int index, TrackRef ref, String reason) {}

    record Started(int index, TrackInfo track, SignalPath path, int capturedFrames, PlayerStatus status) {}

    private final class Recorder implements PlayerListener {
        volatile boolean sawPlaying;
        final List<Started> started = new CopyOnWriteArrayList<>();
        final List<Skip> skipped = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<String> deviceLost = new CopyOnWriteArrayList<>();

        @Override
        public void stateChanged(PlayerStatus s) {
            if (s.state() == PlayerState.PLAYING) {
                sawPlaying = true;
            }
        }

        @Override
        public void trackStarted(int index, TrackInfo t, SignalPath p) {
            started.add(new Started(index, t, p, sink.captured(0).length / 4, player.status()));
        }

        @Override
        public void trackSkipped(int index, TrackRef ref, String reason) {
            skipped.add(new Skip(index, ref, reason));
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }

        @Override
        public void deviceLost(String message) {
            deviceLost.add(message);
        }

        List<Integer> startedIndices() {
            return started.stream().map(Started::index).toList();
        }
    }
}
