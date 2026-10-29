package purist.asio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import purist.audio.AudioDeviceException;
import purist.audio.BitOrder;
import purist.audio.NotBitPerfectException;
import purist.audio.SampleType;
import purist.audio.SignalPath;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;

/**
 * {@link AsioSink} against {@link FakeAsioDriver}: the §6 open sequence, the refusals, format switching, the
 * reset rebuild and device loss, and bit-identity through the real callback path. Every test also checks that
 * no driver call was made off the control thread. Expected bytes are computed here with plain arithmetic.
 */
@Timeout(30)
class AsioSinkTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final String HINT = ". Close other apps using ASIO — foobar2000 may be holding the device.";

    private FakeAsioDriver fake;
    private AsioControlThread ctl;
    private AsioSink sink;

    @BeforeEach
    void setUp() {
        fake = new FakeAsioDriver("Fake");
        ctl = new AsioControlThread();
        sink = new AsioSink(fake.info(), ctl, fake.loader());
    }

    @AfterEach
    void tearDown() {
        try {
            sink.close();
        } finally {
            ctl.close();
        }
        assertEquals(List.of(), fake.violations(), "driver calls made off the control thread");
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** Returns once everything already posted to the control thread (a rebuild, say) has run. */
    private void flushControl() throws Exception {
        ctl.call(() -> null);
    }

    /** Ticks the fake until {@code core} is drained; returns the number of callbacks. */
    private int tickUntilDrained(StreamCore core, int max) {
        int n = 0;
        while (!core.drained()) {
            if (n == max) {
                fail("not drained after " + max + " callbacks");
            }
            assertTrue(fake.tick(), "the driver is not running");
            n++;
        }
        return n;
    }

    /** {@code expected} appears in {@code log} in this order, other entries allowed in between. */
    private static void assertSubsequence(List<String> log, String... expected) {
        int at = 0;
        for (String want : expected) {
            while (at < log.size() && !log.get(at).equals(want)) {
                at++;
            }
            if (at == log.size()) {
                fail("expected " + Arrays.toString(expected) + " in this order in " + log + "; missing \"" + want
                        + "\"");
            }
            at++;
        }
    }

    private static List<String> since(List<String> log, int from) {
        return log.subList(from, log.size());
    }

    private static List<String> matching(List<String> log, String prefix) {
        return log.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    /** Seeded signed {@code bits}-bit samples, right-aligned as decoders emit them, edges first. */
    private static int[] randomSamples(int bits, int frames, long seed) {
        int min = bits == 32 ? Integer.MIN_VALUE : -(1 << (bits - 1));
        int max = ~min;
        int[] edges = {min, max, 0, -1, 1, min + 1, max - 1};
        SplittableRandom rnd = new SplittableRandom(seed);
        int[] s = new int[frames];
        for (int i = 0; i < frames; i++) {
            s[i] = i < edges.length ? edges[i] : rnd.nextInt() >> (32 - bits);
        }
        return s;
    }

    /** Interleaved stereo little-endian int32, the decoders' PCM output. */
    private static MemorySegment interleaved(int[] left, int[] right) {
        MemorySegment seg = MemorySegment.ofArray(new byte[left.length * 8]);
        for (int i = 0; i < left.length; i++) {
            seg.set(LE_INT, i * 8L, left[i]);
            seg.set(LE_INT, i * 8L + 4, right[i]);
        }
        return seg;
    }

    /** What the driver buffer must hold: each sample shifted into the container, little-endian. */
    private static byte[] reference(int[] samples, int bits, SampleType out) {
        int cb = out.containerBytes();
        int shift = out.validBits() - bits;
        byte[] r = new byte[samples.length * cb];
        for (int i = 0; i < samples.length; i++) {
            long container = ((long) samples[i]) << shift;
            for (int b = 0; b < cb; b++) {
                r[i * cb + b] = (byte) (container >> (8 * b));
            }
        }
        return r;
    }

    private static void assertDataThenSilence(String what, byte[] captured, byte[] expected, byte silence) {
        assertTrue(captured.length >= expected.length,
                what + ": captured " + captured.length + " bytes, expected at least " + expected.length);
        assertArrayEquals(expected, Arrays.copyOf(captured, expected.length), what + ": data");
        for (int i = expected.length; i < captured.length; i++) {
            if (captured[i] != silence) {
                fail(what + ": byte " + i + " after the data is " + (captured[i] & 0xFF) + ", not silence "
                        + (silence & 0xFF));
            }
        }
    }

    private static long callbackErrors(SinkStream s) {
        return ((AsioSinkStream) s).callbackErrors();
    }

    // ---- opening (§6 steps 1-7) ---------------------------------------------------------------------------

    @Test
    void opensPcmAtSourceRate() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(96000, 24, 2, 96000));

        List<String> log = fake.log();
        // The fake has no DSD, so its setIoFormat fails like a driver without future(): that is ignored.
        assertSubsequence(log, "load", "setIoFormat:PCM", "canSampleRate:96000.0", "setSampleRate:96000.0",
                "outputChannels", "outputChannelType:0", "outputChannelType:1", "bufferSize",
                "createBuffers:[0, 1]:512");
        assertFalse(log.contains("start"), "a stream pumps nothing until start()");

        SignalPath p = s.signalPath();
        assertEquals(SampleType.INT32_LSB, p.outType());
        assertEquals(96000.0, p.deviceRate());
        assertEquals(512, p.bufferFrames());
        assertEquals("Fake", p.deviceName());
        assertEquals("24-bit 96 kHz → INT32_LSB @ 96000 Hz", p.describe());
        assertEquals("Fake", sink.name());
    }

    @Test
    void outputReadyIsWarmedOnceOnTheControlThreadBeforeStart() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        assertEquals(1, fake.outputReadyCalls(), "one warm-up call after createBuffers");
        assertFalse(fake.started());
        s.start();
        assertTrue(fake.started());
        assertEquals(1, fake.outputReadyCalls(), "start itself does not call it");
    }

    @Test
    void refusesUnsupportedRate() {
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(352800, 24, 2, 1000)));
        assertEquals("rate 352800 Hz not supported by Fake", e.getMessage());
        assertFalse(fake.log().contains("setSampleRate:352800.0"), "never set a rate the driver refused");
        assertEquals(List.of(), matching(fake.log(), "createBuffers"));
    }

    @Test
    void refusesDsdWhenTheDriverOffersNone() {
        fake.rates = Set.of(44100, 2822400);
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.dsd(2822400, 2, 1000, BitOrder.MSB_FIRST)));
        assertEquals("Fake does not offer native DSD", e.getMessage());
        assertEquals(List.of(), matching(fake.log(), "setIoFormat"), "DSD is never forced on such a driver");
        assertEquals(List.of(), matching(fake.log(), "createBuffers"));
    }

    @Test
    void refusesDsdWhenDriverReportsPcmTypeAfterSwitch() {
        // The UA Volt: says yes to canDoIoFormat(DSD) and to the DSD rate, then still reports Int32LSB.
        fake.dsdCapable = true;
        fake.dsdTypeAfterSwitch = AsioConstants.ST_INT32_LSB;
        fake.rates = Set.of(44100, 48000, 2822400);

        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.dsd(2822400, 2, 1000, BitOrder.MSB_FIRST)));
        assertEquals("Fake did not switch to a DSD sample type (reported 18)", e.getMessage());
        assertTrue(e.getMessage().contains("did not switch to a DSD sample type (reported 18)"));
        List<String> formats = matching(fake.log(), "setIoFormat");
        assertEquals("setIoFormat:PCM", formats.get(formats.size() - 1), "the driver is put back into PCM");
        assertEquals(List.of("setIoFormat:DSD", "setIoFormat:PCM"), formats);
        assertEquals(List.of(), matching(fake.log(), "createBuffers"));
    }

    @Test
    void opensDsdWhenTypeIs33() throws Exception {
        fake.dsdCapable = true;
        fake.rates = Set.of(44100, 2822400);

        SinkStream s = sink.open(StreamFormat.dsd(2822400, 2, 705600, BitOrder.MSB_FIRST));
        assertEquals(SampleType.DSD_INT8_MSB1, s.signalPath().outType());
        assertEquals(2822400.0, s.signalPath().deviceRate());
        assertSubsequence(fake.log(), "canDoDsd", "setIoFormat:DSD", "canSampleRate:2822400.0",
                "setSampleRate:2822400.0", "createBuffers:[0, 1]:512");
        assertEquals(List.of("setIoFormat:DSD"), matching(fake.log(), "setIoFormat"));
    }

    @Test
    void refusesAFloatOutputType() {
        fake.pcmType = AsioConstants.ST_FLOAT32_LSB;
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals("unsupported ASIO sample type 19", e.getMessage());
        assertEquals(List.of(), matching(fake.log(), "createBuffers"));
    }

    @Test
    void refusesASourceDeeperThanTheContainer() {
        fake.pcmType = AsioConstants.ST_INT16_LSB;
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(44100, 24, 2, -1)));
        assertEquals("24-bit source does not fit INT16_LSB", e.getMessage());
        assertEquals(List.of(), matching(fake.log(), "createBuffers"), "refused before any buffer exists");
    }

    @Test
    void usesTheConfiguredOutputPair() throws Exception {
        fake.outputs = 4;
        sink.setFirstOutputChannel(2);
        sink.open(StreamFormat.pcm(48000, 24, 2, -1));
        assertSubsequence(fake.log(), "outputChannelType:2", "outputChannelType:3", "createBuffers:[2, 3]:512");
        assertThrows(IllegalArgumentException.class, () -> sink.setFirstOutputChannel(-1));
    }

    @Test
    void aMissingOutputPairIsADeviceError() {
        sink.setFirstOutputChannel(2); // the fake has outputs 0 and 1 only
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(48000, 24, 2, -1)));
        assertEquals("Fake has no output channels 2 and 3 (it has 2 outputs)", e.getMessage());
    }

    // ---- switching and teardown ---------------------------------------------------------------------------

    @Test
    void switchingFormatDisposesFirst() throws Exception {
        SinkStream first = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        int mark = fake.log().size();
        SinkStream second = sink.open(StreamFormat.pcm(96000, 24, 2, -1));

        assertSubsequence(since(fake.log(), mark), "stop", "disposeBuffers", "setSampleRate:96000.0",
                "createBuffers:[0, 1]:512");
        assertEquals(1, matching(since(fake.log(), mark), "createBuffers").size());
        assertThrows(IllegalStateException.class, first::start, "the old stream is gone");
        assertFalse(first.accepts(StreamFormat.pcm(44100, 16, 2, -1)));
        second.start();
        assertTrue(fake.started());
    }

    @Test
    void aRefusedOpenStillDisposesThePreviousStream() throws Exception {
        SinkStream first = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        first.start();
        int mark = fake.log().size();

        assertThrows(NotBitPerfectException.class, () -> sink.open(StreamFormat.pcm(352800, 16, 2, -1)));
        assertSubsequence(since(fake.log(), mark), "stop", "disposeBuffers");
        assertEquals(List.of(), matching(since(fake.log(), mark), "createBuffers"));
        assertFalse(fake.started());
        assertThrows(IllegalStateException.class, first::start);
    }

    @Test
    void acceptsOnlyTheSameStreamShape() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        assertTrue(s.accepts(StreamFormat.pcm(44100, 24, 2, -1)), "24 bits fit INT32_LSB: gapless");
        assertFalse(s.accepts(StreamFormat.pcm(48000, 16, 2, -1)), "another rate needs a reopen");
        assertFalse(s.accepts(StreamFormat.pcm(44100, 16, 1, -1)), "another channel count needs a reopen");
    }

    @Test
    void aSourceDeeperThanTheDriversRealDepthIsNeverAccepted() throws Exception {
        // ASIO names the real depth in the sample type (INT32_LSB24 = 24 valid bits), so the Packer's check is exact:
        // unlike a WASAPI 24-in-32 stream, no 32-bit source can slip into a stream whose device keeps only 24 bits.
        fake.pcmType = AsioConstants.ST_INT32_LSB24;
        SinkStream s = sink.open(StreamFormat.pcm(44100, 24, 2, -1));
        assertFalse(s.accepts(StreamFormat.pcm(44100, 32, 2, -1)));
        assertTrue(s.accepts(StreamFormat.pcm(44100, 24, 2, -1)));
        assertThrows(NotBitPerfectException.class, () -> s.core().setSource(StreamFormat.pcm(44100, 32, 2, -1)));
        assertEquals(24, s.signalPath().source().bits());
    }

    @Test
    void stopIsSynchronousAndTheTickerGoesQuiet() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.start();
        assertTrue(fake.tick());
        s.stop();
        assertFalse(fake.started());
        assertFalse(fake.tick(), "a stopped driver does not call back");
        s.stop(); // idempotent
        s.core().reset(); // legal: no callback can be running
    }

    @Test
    void closeReleasesTheDriverOnTheControlThread() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.start();
        int mark = fake.log().size();
        sink.close();
        assertSubsequence(since(fake.log(), mark), "stop", "disposeBuffers", "close");
        assertThrows(IllegalStateException.class, s::start);
        sink.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals(1, ctl.call(() -> 1), "a control thread passed in is not the sink's to close");
    }

    @Test
    void openControlPanelLoadsTheDriverAndRunsOnTheControlThread() throws Exception {
        sink.openControlPanel();
        assertSubsequence(fake.log(), "load", "controlPanel");
    }

    // ---- device errors ------------------------------------------------------------------------------------

    @Test
    void busyDriverGivesHint() throws Exception {
        fake.failOnLoad = true;
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertTrue(e.getMessage().contains("foobar2000 may be holding the device"), e.getMessage());
        assertEquals("Could not open Fake: init failed: ASE_HWMalfunction (-999) — device in use" + HINT,
                e.getMessage());

        AudioDeviceException panel = assertThrows(AudioDeviceException.class, sink::openControlPanel);
        assertTrue(panel.getMessage().endsWith(HINT));

        fake.failOnLoad = false;
        sink.open(StreamFormat.pcm(44100, 16, 2, -1)); // the next open simply tries again
        assertEquals(3, matching(fake.log(), "load").size());
    }

    @Test
    void aSilentInitFailureReadsAsDeviceNotAvailableWithoutTheBusyHint() {
        fake.failOnLoad = true;
        fake.loadError = new AsioException("init", AsioConstants.ASE_OK, "");
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals("Fake is not available — check it is on and connected.", e.getMessage());
        assertFalse(e.getMessage().contains("foobar2000"));
        AudioDeviceException panel = assertThrows(AudioDeviceException.class, sink::openControlPanel);
        assertEquals(e.getMessage(), panel.getMessage());
    }

    @Test
    void aDriverSayingNotPresentOrNotConnectedIsDeviceNotAvailable() {
        fake.failOnLoad = true;
        for (AsioException cause : List.of(
                new AsioException("init", AsioConstants.ASE_NOT_PRESENT, ""),
                new AsioException("init", AsioConstants.ASE_HW_MALFUNCTION, "Device is not connected"))) {
            fake.loadError = cause;
            AudioDeviceException e = assertThrows(AudioDeviceException.class,
                    () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
            assertEquals("Fake is not available — check it is on and connected.", e.getMessage());
        }
    }

    @Test
    void aComFailureDoesNotBlameAnotherAsioApp() {
        fake.failOnLoad = true;
        fake.loadError = new AsioException("CoCreateInstance", 0x80040154, "");
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals("Could not open Fake: CoCreateInstance failed: Unknown (-2147221164)", e.getMessage());
    }

    @Test
    void aFailedStartIsReportedToTheCaller() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        fake.failOnStart = true;
        AudioDeviceException e = assertThrows(AudioDeviceException.class, s::start);
        assertEquals("Could not start Fake: start failed: ASE_NotPresent (-1000) — device unplugged", e.getMessage());
        assertFalse(fake.started());

        fake.failOnStart = false;
        int mark = fake.log().size();
        sink.open(StreamFormat.pcm(44100, 16, 2, -1)).start();
        assertSubsequence(since(fake.log(), mark), "disposeBuffers", "close", "load", "createBuffers:[0, 1]:512",
                "start");
    }

    @Test
    void deviceLostNotifiesListener() throws Exception {
        List<String> lost = new CopyOnWriteArrayList<>();
        sink.addDeviceListener(lost::add);
        StreamFormat f = StreamFormat.pcm(48000, 24, 2, -1);
        SinkStream s = sink.open(f);
        s.start();

        fake.failOnStart = true;
        fake.fire(AsioEvent.RESET_REQUEST);
        flushControl();

        assertEquals(List.of("Fake was lost: start failed: ASE_NotPresent (-1000) — device unplugged"), lost);
        assertFalse(fake.started());
        AudioDeviceException e = assertThrows(AudioDeviceException.class, s::start);
        assertEquals(lost.get(0), e.getMessage());
        assertFalse(s.accepts(f), "a lost stream makes the player reopen");

        // Re-opening (the banner's RETRY) reloads the driver.
        fake.failOnStart = false;
        int mark = fake.log().size();
        sink.open(f).start();
        assertSubsequence(since(fake.log(), mark), "close", "load", "createBuffers:[0, 1]:512", "start");
        assertEquals(1, lost.size());
    }

    @Test
    void unpluggedDriverReportsDeviceLossNotRateRefusal() throws Exception {
        fake.dsdCapable = true;
        fake.rates = Set.of(44100, 48000, 2822400);
        SinkStream s = sink.open(StreamFormat.pcm(48000, 24, 2, -1));
        s.start();
        s.stop();
        // Pulled out while idle: the loaded driver now says no to every rate and to DSD, which must not read as
        // "this track cannot be played" (the player would skip the whole queue without showing the banner).
        fake.unplugged = true;

        AudioDeviceException pcm = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals("Could not open Fake: getSampleRate failed: ASE_NotPresent (-1000)", pcm.getMessage());

        int mark = fake.log().size();
        AudioDeviceException dsd = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST)));
        assertEquals("Could not open Fake: getSampleRate failed: ASE_NotPresent (-1000)", dsd.getMessage());
        assertSubsequence(since(fake.log(), mark), "close", "load", "canDoDsd", "sampleRate");

        // Plugged back in: the device error made the next open reload the driver, and it plays again.
        fake.unplugged = false;
        mark = fake.log().size();
        sink.open(StreamFormat.pcm(44100, 16, 2, -1)).start();
        assertSubsequence(since(fake.log(), mark), "close", "load", "setSampleRate:44100.0",
                "createBuffers:[0, 1]:512", "start");

        // A healthy driver's "no" is still a refusal.
        assertEquals("rate 352800 Hz not supported by Fake", assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(352800, 16, 2, -1))).getMessage());
    }

    static Stream<Arguments> setSampleRateFailures() {
        return Stream.of(
                Arguments.of(AsioConstants.ASE_NO_CLOCK, false),
                Arguments.of(AsioConstants.ASE_INVALID_MODE, false),
                Arguments.of(AsioConstants.ASE_NOT_PRESENT, true),
                Arguments.of(AsioConstants.ASE_HW_MALFUNCTION, true));
    }

    @ParameterizedTest(name = "setSampleRate error {0}: device loss {1}")
    @MethodSource("setSampleRateFailures")
    void aFailedSetSampleRateIsADeviceErrorOnlyWhenTheDeviceIsGone(int code, boolean deviceLoss) throws Exception {
        fake.setSampleRateFailure = code;
        StreamFormat f = StreamFormat.pcm(96000, 24, 2, -1);
        String error = "setSampleRate failed: " + AsioConstants.errorName(code) + " (" + code + ")";
        if (deviceLoss) {
            AudioDeviceException e = assertThrows(AudioDeviceException.class, () -> sink.open(f));
            assertEquals("Could not open Fake: " + error, e.getMessage());
        } else {
            NotBitPerfectException e = assertThrows(NotBitPerfectException.class, () -> sink.open(f));
            assertEquals("rate 96000 Hz not supported by Fake (" + error + ")", e.getMessage());
        }
        fake.setSampleRateFailure = 0;
        int mark = fake.log().size();
        sink.open(f);
        assertEquals(deviceLoss, since(fake.log(), mark).contains("load"), "a device error reloads the driver");
    }

    // ---- rebuilds -----------------------------------------------------------------------------------------

    @Test
    void resetRequestRebuildsAndKeepsRing() throws Exception {
        final int frames = 3000;
        int[] left = randomSamples(24, frames, 31);
        int[] right = randomSamples(24, frames, 32);
        StreamFormat f = StreamFormat.pcm(48000, 24, 2, frames);

        SinkStream s = sink.open(f);
        StreamCore core = s.core();
        core.setSource(f);
        core.write(interleaved(left, right), frames);
        core.endOfInput();
        s.start();
        assertTrue(fake.tick());
        assertTrue(fake.tick());
        assertEquals(1024, core.framesPlayed());

        fake.preferred = 1024;
        int mark = fake.log().size();
        fake.fire(AsioEvent.RESET_REQUEST);
        flushControl();
        assertSubsequence(since(fake.log(), mark), "stop", "disposeBuffers", "bufferSize",
                "createBuffers:[0, 1]:1024", "start");
        assertEquals(1024, s.signalPath().bufferFrames());
        assertTrue(fake.started(), "it was running, so it runs again");

        tickUntilDrained(core, 10);
        assertDataThenSilence("left", fake.captured(0), reference(left, 24, SampleType.INT32_LSB), (byte) 0);
        assertDataThenSilence("right", fake.captured(1), reference(right, 24, SampleType.INT32_LSB), (byte) 0);
        assertEquals(frames, core.framesPlayed(), "the same core, with its ring, carried on");
        assertEquals(1, core.dropouts(), "the rebuild dropped driver halves: counted once, nothing else ran dry");
        assertEquals(0, callbackErrors(s));
    }

    static Stream<AsioEvent> rebuildEvents() {
        return Stream.of(AsioEvent.RESET_REQUEST, AsioEvent.BUFFER_SIZE_CHANGE, AsioEvent.SAMPLE_RATE_CHANGED);
    }

    @ParameterizedTest
    @MethodSource("rebuildEvents")
    void theseEventsRebuildAStoppedStreamWithoutStartingIt(AsioEvent e) throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(48000, 24, 2, -1));
        fake.preferred = 256;
        int mark = fake.log().size();
        fake.fire(e);
        flushControl();
        assertSubsequence(since(fake.log(), mark), "disposeBuffers", "createBuffers:[0, 1]:256");
        assertFalse(since(fake.log(), mark).contains("start"));
        assertEquals(256, s.signalPath().bufferFrames());
        assertEquals(1, s.core().dropouts(), "every rebuild counts as a dropout");
    }

    @Test
    void eachRebuildCountsAsADropoutAndShowsNotBitPerfect() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(48000, 24, 2, -1));
        s.start();
        long before = s.core().dropouts();
        assertEquals(0, before);
        assertEquals(java.util.Optional.empty(), s.signalPath().notBitPerfectReason(0));

        fake.preferred = 1024;
        fake.fire(AsioEvent.RESET_REQUEST);
        flushControl();
        assertEquals(1, s.core().dropouts());
        assertTrue(s.signalPath().notBitPerfectReason(s.core().dropouts() - before).isPresent(),
                "the indicator must say NOT BIT-PERFECT, with a reason");

        fake.preferred = 256;
        fake.fire(AsioEvent.BUFFER_SIZE_CHANGE);
        flushControl();
        assertEquals(2, s.core().dropouts(), "a second rebuild is a second dropout");
    }

    @Test
    void otherEventsDoNotRebuild() throws Exception {
        sink.open(StreamFormat.pcm(48000, 24, 2, -1)).start();
        int mark = fake.log().size();
        fake.fire(AsioEvent.RESYNC_REQUEST);
        fake.fire(AsioEvent.LATENCIES_CHANGED);
        fake.fire(AsioEvent.OVERLOAD);
        flushControl();
        assertEquals(List.of(), since(fake.log(), mark));
    }

    @Test
    void aRebuildRefusesASampleTypeChangeInsteadOfMisplayingIt() throws Exception {
        List<String> lost = new CopyOnWriteArrayList<>();
        sink.addDeviceListener(lost::add);
        sink.open(StreamFormat.pcm(48000, 16, 2, -1)).start();
        fake.pcmType = AsioConstants.ST_INT16_LSB; // say the user changed the driver's format in its panel
        int mark = fake.log().size();
        fake.fire(AsioEvent.RESET_REQUEST);
        flushControl();
        assertEquals(List.of("Fake was lost: Fake now reports sample type 16 on output 0, not 18"), lost);
        assertEquals(List.of(), matching(since(fake.log(), mark), "createBuffers"),
                "no buffers are created for a type the stream was not packed for");
        assertFalse(fake.started());
    }

    // ---- bit identity through the callback ----------------------------------------------------------------

    static Stream<Arguments> pcmTypesAndDepths() {
        return Stream.of(
                Arguments.of(AsioConstants.ST_INT32_LSB, 16),
                Arguments.of(AsioConstants.ST_INT32_LSB, 24),
                Arguments.of(AsioConstants.ST_INT32_LSB, 32),
                Arguments.of(AsioConstants.ST_INT24_LSB, 24),
                Arguments.of(AsioConstants.ST_INT16_LSB, 16),
                Arguments.of(AsioConstants.ST_INT32_LSB24, 24));
    }

    @ParameterizedTest(name = "{1}-bit into ASIO type {0}")
    @MethodSource("pcmTypesAndDepths")
    void callbackDeliversBitIdenticalData(int asioType, int bits) throws Exception {
        final int frames = 5000;
        fake.pcmType = asioType;
        SampleType out = SampleType.fromAsio(asioType).orElseThrow();
        int[] left = randomSamples(bits, frames, 100L + asioType + bits);
        int[] right = randomSamples(bits, frames, 200L + asioType + bits);
        StreamFormat f = StreamFormat.pcm(44100, bits, 2, frames);

        SinkStream s = sink.open(f);
        assertEquals(out, s.signalPath().outType());
        StreamCore core = s.core();
        core.setSource(f);
        core.write(interleaved(left, right), frames);
        core.endOfInput();
        s.start();
        int callbacks = tickUntilDrained(core, 100);

        assertDataThenSilence("left", fake.captured(0), reference(left, bits, out), (byte) 0);
        assertDataThenSilence("right", fake.captured(1), reference(right, bits, out), (byte) 0);
        assertEquals((long) callbacks * 512 * out.containerBytes(), fake.captured(0).length,
                "each callback fills exactly one driver buffer");
        assertEquals(frames, core.framesPlayed());
        assertEquals(0, core.dropouts());
        assertEquals(1 + callbacks, fake.outputReadyCalls(), "the warm-up, then one per callback");
        assertEquals(0, callbackErrors(s));
    }

    @Test
    void dsdCallbackDeliversBitIdenticalBytes() throws Exception {
        final int frames = 2000; // bytes per channel
        fake.dsdCapable = true;
        fake.dsdTypeAfterSwitch = AsioConstants.ST_DSD_INT8_MSB1;
        fake.rates = Set.of(2822400);
        SplittableRandom rnd = new SplittableRandom(7);
        byte[] src = new byte[frames * 2];
        byte[] expLeft = new byte[frames];
        byte[] expRight = new byte[frames];
        for (int i = 0; i < frames; i++) {
            int l = i < 256 ? i : rnd.nextInt(256);
            int r = i < 256 ? 255 - i : rnd.nextInt(256);
            src[2 * i] = (byte) l;
            src[2 * i + 1] = (byte) r;
            expLeft[i] = (byte) (Integer.reverse(l) >>> 24); // LSB-first source, MSB-first driver
            expRight[i] = (byte) (Integer.reverse(r) >>> 24);
        }
        StreamFormat f = StreamFormat.dsd(2822400, 2, frames, BitOrder.LSB_FIRST);

        SinkStream s = sink.open(f);
        StreamCore core = s.core();
        core.setSource(f);
        core.write(MemorySegment.ofArray(src), frames);
        core.endOfInput();
        s.start();
        int callbacks = tickUntilDrained(core, 100);

        assertDataThenSilence("left", fake.captured(0), expLeft, (byte) 0x69);
        assertDataThenSilence("right", fake.captured(1), expRight, (byte) 0x69);
        assertEquals(callbacks * (512 / 8), fake.captured(0).length, "512 DSD samples are 64 bytes per channel");
        assertEquals(frames, core.framesPlayed());
        assertEquals(0, core.dropouts());
        assertEquals(0, callbackErrors(s));
    }

    @Test
    void aCallbackWhileNotStartedWritesSilenceAndLeavesTheRingAlone() throws Exception {
        StreamFormat f = StreamFormat.pcm(44100, 16, 2, 100);
        SinkStream s = sink.open(f);
        s.core().write(interleaved(randomSamples(16, 100, 1), randomSamples(16, 100, 2)), 100);
        // As if a sloppy driver called back while stopped: no data is consumed and nothing counts as a dropout.
        ((AsioSinkStream) s).callbacksForTest().bufferSwitch(0);
        assertEquals(0, s.core().framesPlayed());
        assertEquals(0, s.core().dropouts());
        assertEquals(0, callbackErrors(s));
        assertEquals(2, fake.outputReadyCalls(), "the warm-up, then the late callback's");
    }

    /** A step of {@link #fewestBytesAllocated}; lets the producer's {@code write} throw. */
    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    /**
     * Runs {@code produce} then {@code measured} many times and returns the fewest bytes the calling thread
     * allocated inside {@code measured} over any window of 50000 rounds. As in
     * {@code StreamCoreTest.fillAllocatesNothing}, early windows may see JIT or linkage allocations, so it stops
     * at the first clean window; code that really allocates dirties every one.
     */
    private static long fewestBytesAllocated(com.sun.management.ThreadMXBean threads, Step produce, Step measured)
            throws Exception {
        for (int i = 0; i < 200_000; i++) { // warm-up: JIT and linkage
            produce.run();
            measured.run();
        }
        long smallest = Long.MAX_VALUE;
        for (int window = 0; window < 20 && smallest != 0; window++) {
            long allocated = 0;
            for (int i = 0; i < 50_000; i++) {
                produce.run();
                long before = threads.getCurrentThreadAllocatedBytes();
                measured.run();
                allocated += threads.getCurrentThreadAllocatedBytes() - before;
            }
            smallest = Math.min(smallest, allocated);
        }
        return smallest;
    }

    /**
     * The driver-thread callback allocates nothing on any of its paths: real data, padding when the ring runs
     * dry, and silence while stopped (global constraint "callback code allocates nothing").
     */
    @Test
    void bufferSwitchAllocatesNothing() throws Exception {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        SinkStream s = sink.open(StreamFormat.pcm(44100, 24, 2, -1));
        AsioCallbacks cb = ((AsioSinkStream) s).callbacksForTest();
        StreamCore core = s.core();
        MemorySegment src = interleaved(randomSamples(24, 512, 5), randomSamples(24, 512, 6));

        s.start();
        // write() belongs to the producer and may allocate, so it runs outside the measured part.
        assertEquals(0, fewestBytesAllocated(threads, () -> core.write(src, 512), () -> {
            cb.bufferSwitch(0); // the 512 frames just written: the data path
            cb.bufferSwitch(1); // the ring is now empty: the padding path
        }), "bytes allocated by 50000 running callback pairs in the cleanest window");

        s.stop();
        assertEquals(0, fewestBytesAllocated(threads, () -> { }, () -> {
            cb.bufferSwitch(0); // stopped: silence, the ring is left alone
            cb.bufferSwitch(1);
        }), "bytes allocated by 50000 stopped callback pairs in the cleanest window");
        assertEquals(0, callbackErrors(s));
    }

    /**
     * The driver's event callback allocates nothing either, including when it asks the control thread for a
     * rebuild. The stream is lost first, so each requested rebuild is a cheap no-op and requests keep being
     * accepted (rather than coalesced into one that is still pending) throughout the measurement.
     */
    @Test
    void eventCallbacksAllocateNothing() throws Exception {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());

        SinkStream s = sink.open(StreamFormat.pcm(48000, 24, 2, -1));
        s.start();
        fake.failOnStart = true;
        fake.fire(AsioEvent.RESET_REQUEST);
        flushControl();
        assertThrows(AudioDeviceException.class, s::start, "the stream is lost: rebuilds now return at once");

        AsioCallbacks cb = ((AsioSinkStream) s).callbacksForTest();
        AsioEvent[] events = AsioEvent.values();
        assertEquals(0, fewestBytesAllocated(threads, () -> { }, () -> {
            for (AsioEvent e : events) {
                cb.event(e, 0);
            }
        }), "bytes allocated by 50000 rounds of every event in the cleanest window");
        assertEquals(0, callbackErrors(s));
    }

    @Test
    void rebuildRequestsCoalesceWhileOneIsPending() throws Exception {
        sink.open(StreamFormat.pcm(48000, 24, 2, -1));
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ctl.post(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        busy.await();
        int mark = fake.log().size();
        fake.fire(AsioEvent.RESET_REQUEST);
        fake.fire(AsioEvent.BUFFER_SIZE_CHANGE);
        fake.fire(AsioEvent.SAMPLE_RATE_CHANGED);
        fake.fire(AsioEvent.RESET_REQUEST);
        release.countDown();
        flushControl();
        assertEquals(List.of("createBuffers:[0, 1]:512"), matching(since(fake.log(), mark), "createBuffers"),
                "four requests while the control thread was busy: one rebuild");

        // Once that rebuild has run, a new request is honoured again.
        fake.preferred = 256;
        mark = fake.log().size();
        fake.fire(AsioEvent.BUFFER_SIZE_CHANGE);
        flushControl();
        assertEquals(List.of("createBuffers:[0, 1]:256"), matching(since(fake.log(), mark), "createBuffers"));
    }

    // ---- buffer units and ring size ------------------------------------------------------------------------

    @Test
    void bufferBytesPerChannel() {
        assertEquals(2048, AsioSink.bytesPerBuffer(SampleType.INT32_LSB, 512));
        assertEquals(1536, AsioSink.bytesPerBuffer(SampleType.INT24_LSB, 512));
        assertEquals(1024, AsioSink.bytesPerBuffer(SampleType.INT16_LSB, 512));
        assertEquals(64, AsioSink.bytesPerBuffer(SampleType.DSD_INT8_MSB1, 512), "a DSD sample is one bit");
        assertEquals(64, AsioSink.bytesPerBuffer(SampleType.DSD_INT8_LSB1, 512));
        assertEquals(512, AsioSink.fillFrames(SampleType.INT24_LSB, 512));
        assertEquals(64, AsioSink.fillFrames(SampleType.DSD_INT8_MSB1, 512), "a DSD frame is one byte per channel");
    }

    @Test
    void ringHoldsTwoSecondsOrFourBuffers() {
        assertEquals(96000, AsioSink.ringFrames(StreamFormat.pcm(48000, 24, 2, -1), 512));
        assertEquals(705600, AsioSink.ringFrames(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST), 64));
        assertEquals(4 * 8192, AsioSink.ringFrames(StreamFormat.pcm(8000, 16, 2, -1), 8192));
    }
}
