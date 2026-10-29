package purist.wasapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import purist.asio.AsioControlThread;
import purist.audio.AudioDeviceException;
import purist.audio.BitOrder;
import purist.audio.NotBitPerfectException;
import purist.audio.Packer;
import purist.audio.SampleType;
import purist.audio.SignalPath;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;

/**
 * {@link WasapiSink} against {@link FakeWasapi}: exclusive-format negotiation (the container order and the
 * refusals), the aligned-period retry, the device errors, and bit-exact output through the real render thread,
 * PCM and DoP. Expected bytes are computed here with plain arithmetic.
 */
@Timeout(30)
class WasapiSinkTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final WasapiDevice DEVICE = new WasapiDevice("{0.0.0.00000000}.{fake}", "Fake DAC");

    private FakeWasapi fake;
    private AsioControlThread ctl;
    private WasapiSink sink;
    /** What the user has said about this DAC and DoP; on for most tests, which are about the DoP path itself. */
    private volatile boolean dopOn = true;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeWasapi();
        ctl = new AsioControlThread("wasapi-control", true);
        fake.setControlThread(ctl.call(Thread::currentThread));
        sink = new WasapiSink(DEVICE, ctl, fake, () -> dopOn);
    }

    @AfterEach
    void tearDown() {
        try {
            sink.close();
        } finally {
            ctl.close();
        }
        assertEquals(List.of(), fake.violations, "COM calls made off the control thread");
    }

    private List<String> formatQueries() {
        return fake.log.stream().filter(s -> s.startsWith("supported? ")).map(s -> s.substring(11)).toList();
    }

    // ---- format negotiation ------------------------------------------------------------------------------

    @Test
    void pcmTriesTheExactDepthFirst() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        assertEquals(List.of("16/16@44100"), formatQueries());
        assertEquals(new WaveFormat(44100, 2, 16, 16), fake.lastClient().initialized);
        assertEquals(SampleType.INT16_LSB, s.core().outType());
        assertEquals("16-bit 44.1 kHz → WASAPI exclusive 16-bit @ 44100 Hz", s.signalPath().describe());
    }

    @Test
    void pcm24FallsBackTo24In32ThenTo32() throws Exception {
        fake.supported = w -> w.containerBits() == 32 && w.validBits() == 24;
        SinkStream s = sink.open(StreamFormat.pcm(96000, 24, 2, -1));
        assertEquals(List.of("24/24@96000", "24/32@96000"), formatQueries());
        assertEquals(new WaveFormat(96000, 2, 32, 24), fake.lastClient().initialized);
        SignalPath p = s.signalPath();
        assertEquals("24-bit 96 kHz → WASAPI exclusive 24-in-32 @ 96000 Hz", p.describe());
        assertEquals(8, p.transform().shift(), "24-in-32 is the sample left-justified: 8 zero LSBs");
        assertEquals(Optional.empty(), p.notBitPerfectReason(0));
        assertEquals("Fake DAC", p.deviceName());
    }

    @Test
    void pcm16GoesTo32WithZeroPaddedLsbsWhenNothingNarrowerIsTaken() throws Exception {
        fake.supported = w -> w.containerBits() == 32 && w.validBits() == 32;
        SinkStream s = sink.open(StreamFormat.pcm(48000, 16, 2, -1));
        assertEquals(List.of("16/16@48000", "24/32@48000", "32/32@48000"), formatQueries());
        assertEquals(16, s.signalPath().transform().shift());
        assertEquals("16-bit 48 kHz → WASAPI exclusive 32-bit @ 48000 Hz", s.signalPath().describe());
    }

    @Test
    void pcm32OnlyEverTries32() throws Exception {
        fake.supported = w -> false;
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(44100, 32, 2, -1)));
        assertEquals(List.of("32/32@44100"), formatQueries());
        assertEquals("rate 44100 Hz / 32-bit not supported by Fake DAC in exclusive mode", e.getMessage());
    }

    @Test
    void refusesWhenNoLosslessFormatIsTakenAndNeverInitializes() throws Exception {
        fake.supported = w -> false;
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(352800, 24, 2, -1)));
        assertEquals("rate 352800 Hz / 24-bit not supported by Fake DAC in exclusive mode", e.getMessage());
        assertTrue(fake.log.stream().noneMatch(s -> s.startsWith("initialize")), fake.log.toString());
        assertTrue(fake.lastClient().released, "the probing client is released");
    }

    @Test
    void refusesMultichannelBeforeTouchingTheDevice() {
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(48000, 24, 6, -1)));
        assertTrue(e.getMessage().contains("multichannel"), e.getMessage());
        assertEquals(List.of(), fake.log);
    }

    @Test
    void monoIsSentAsStereo() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 1, -1));
        assertEquals(2, fake.lastClient().initialized.channels());
        assertTrue(s.signalPath().transform().monoDuplicate());
    }

    // ---- Initialize -------------------------------------------------------------------------------------------

    @Test
    void initializesWithTheDefaultPeriodInEventMode() throws Exception {
        fake.defaultPeriod = 30_000;
        sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        assertTrue(fake.log.contains("initialize#1 16/16@44100 period 30000"), fake.log.toString());
        assertTrue(fake.lastClient().event != 0, "an event handle was set");
    }

    @Test
    void anUnalignedBufferIsRetriedOnAFreshClientWithTheAlignedPeriod() throws Exception {
        fake.initResults.add(WasapiErrors.AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED);
        fake.alignedFrames = 1056;
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        long aligned = Math.round(1e7 * 1056 / 44100); // 239455.78 -> 239456, as the IAudioClient docs compute it
        assertEquals(239456, aligned);
        assertEquals(List.of(
                "activate " + DEVICE.id(),
                "supported? 16/16@44100",
                "initialize#1 16/16@44100 period 100000",
                "release client#1",
                "activate " + DEVICE.id(),
                "initialize#2 16/16@44100 period 239456"), fake.log);
        assertEquals(2, fake.clients.size());
        assertTrue(fake.clients.get(0).released);
        assertFalse(fake.clients.get(1).released);
        assertEquals(480, s.signalPath().bufferFrames());
    }

    @Test
    void aSecondUnalignedAnswerIsARefusalOfThatFormatNotADeviceError() {
        fake.initResults.add(WasapiErrors.AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED);
        fake.initResults.add(WasapiErrors.AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED);
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertTrue(e.getMessage().startsWith("Fake DAC refused 16-bit @ 44100 Hz in exclusive mode"), e.getMessage());
        assertTrue(e.getMessage().contains("AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED"), e.getMessage());
        assertTrue(fake.clients.stream().allMatch(c -> c.released));
    }

    @Test
    void aDeviceInUseSaysSo() {
        fake.initResults.add(WasapiErrors.AUDCLNT_E_DEVICE_IN_USE);
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals("Fake DAC is in use by another app in exclusive mode", e.getMessage());
        assertTrue(fake.lastClient().released);
    }

    @Test
    void aDeviceInUseWhileProbingIsNotARefusal() {
        fake.formatHr = WasapiErrors.AUDCLNT_E_DEVICE_IN_USE;
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals("Fake DAC is in use by another app in exclusive mode", e.getMessage());
    }

    @Test
    void aMissingDeviceIsADeviceErrorNotARefusal() {
        fake.activateHr = WasapiErrors.AUDCLNT_E_DEVICE_INVALIDATED;
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertEquals("Fake DAC is not available — check it is on and connected.", e.getMessage());
    }

    @Test
    void aDeviceThatVanishesWhileProbingIsADeviceError() {
        fake.formatHr = WasapiErrors.AUDCLNT_E_DEVICE_INVALIDATED;
        assertThrows(AudioDeviceException.class, () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
    }

    @Test
    void exclusiveModeTurnedOffInWindowsIsExplained() {
        fake.initResults.add(WasapiErrors.AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED);
        AudioDeviceException e = assertThrows(AudioDeviceException.class,
                () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        assertTrue(e.getMessage().contains("exclusive"), e.getMessage());
    }

    @Test
    void openReplacesThePreviousStream() throws Exception {
        SinkStream first = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        sink.open(StreamFormat.pcm(96000, 24, 2, -1));
        int released = fake.log.indexOf("release client#1");
        int second = fake.log.lastIndexOf("activate " + DEVICE.id());
        assertTrue(released >= 0 && released < second, fake.log.toString());
        assertTrue(fake.log.indexOf("release render") < released, "the render client goes first");
        assertFalse(first.accepts(StreamFormat.pcm(44100, 16, 2, -1)), "a replaced stream accepts nothing");
        assertThrows(IllegalStateException.class, first::start);
    }

    @Test
    void acceptsTracksOfTheSameStreamThatFitTheContainer() throws Exception {
        fake.supported = w -> w.containerBits() == 16;
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        assertTrue(s.accepts(StreamFormat.pcm(44100, 16, 2, 1000)));
        assertFalse(s.accepts(StreamFormat.pcm(44100, 24, 2, 1000)), "24 bits do not fit a 16-bit container");
        assertFalse(s.accepts(StreamFormat.pcm(48000, 16, 2, 1000)));
    }

    @Test
    void closeReleasesEverythingOnTheControlThread() throws Exception {
        sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        sink.close();
        assertTrue(fake.lastClient().released);
        assertTrue(fake.lastClient().render.closed);
        assertTrue(fake.log.contains("backend close"));
        assertTrue(renderThreads().isEmpty(), "the render thread has ended");
        assertThrows(IllegalStateException.class, () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
    }

    private static List<Thread> renderThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("wasapi-render") && t.isAlive()).toList();
    }

    // ---- DoP negotiation -----------------------------------------------------------------------------------

    @Test
    void dsdIsOfferedAsDopAt24BitsAndOneSixteenthOfTheRate() throws Exception {
        fake.supported = w -> w.containerBits() == 32 && w.validBits() == 24;
        SinkStream s = sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.LSB_FIRST));
        assertEquals(List.of("24/24@176400", "24/32@176400"), formatQueries());
        SignalPath p = s.signalPath();
        assertEquals("DSD64 → WASAPI exclusive DoP 24-in-32 @ 176400 Hz", p.describe());
        assertEquals(Optional.empty(), p.notBitPerfectReason(0), "DoP carries the DSD rate unchanged");
        assertTrue(p.transform().bitReverse(), "DSF is LSB-first; DoP wants the first bit in the MSB");
        assertEquals(SampleType.DSD_INT8_MSB1, s.core().outType());
    }

    @Test
    void dsd128UsesTwice176400() throws Exception {
        SinkStream s = sink.open(StreamFormat.dsd(5644800, 2, -1, BitOrder.MSB_FIRST));
        assertEquals(new WaveFormat(352800, 2, 24, 24), fake.lastClient().initialized);
        assertFalse(s.signalPath().transform().bitReverse());
    }

    @Test
    void dsdIsRefusedWhenTheDeviceTakesNoDopFormat() {
        fake.supported = w -> w.validBits() == 16;
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST)));
        assertEquals("DSD over DoP not supported by Fake DAC", e.getMessage());
        assertFalse(formatQueries().contains("16/16@176400"), "DoP is never tried below 24 bits");
    }

    // ---- the render thread --------------------------------------------------------------------------------

    @Test
    void pcmReachesTheDeviceBitExactInterleavedAndLeftJustified() throws Exception {
        fake.supported = w -> w.containerBits() == 32 && w.validBits() == 24;
        fake.bufferFrames = 64;
        StreamFormat f = StreamFormat.pcm(96000, 24, 2, -1);
        SinkStream s = sink.open(f);
        int frames = 200; // prefill + 3 ticks = 256 device frames: the last 56 are silence
        int[] left = new int[frames];
        int[] right = new int[frames];
        java.util.SplittableRandom r = new java.util.SplittableRandom(7);
        MemorySegment src = MemorySegment.ofArray(new byte[frames * 8]);
        for (int i = 0; i < frames; i++) {
            left[i] = r.nextInt(-(1 << 23), 1 << 23);
            right[i] = r.nextInt(-(1 << 23), 1 << 23);
            src.set(LE_INT, i * 8L, left[i]);
            src.set(LE_INT, i * 8L + 4, right[i]);
        }
        StreamCore core = s.core();
        core.write(src, frames);
        core.endOfInput();
        s.start();
        FakeWasapi.FakeClient c = fake.lastClient();
        for (int i = 0; i < 3; i++) {
            assertTrue(c.tick(), "buffer " + i);
        }
        s.stop();

        byte[] out = c.render.captured();
        assertEquals(4 * 64 * 8, out.length);
        MemorySegment o = MemorySegment.ofArray(out);
        for (int i = 0; i < 256; i++) {
            int l = i < frames ? left[i] << 8 : 0;
            int rr = i < frames ? right[i] << 8 : 0;
            assertEquals(l, o.get(LE_INT, i * 8L), "left " + i);
            assertEquals(rr, o.get(LE_INT, i * 8L + 4), "right " + i);
        }
        assertEquals(frames, core.framesPlayed());
        assertEquals(0, core.dropouts());
        assertTrue(fake.log.indexOf("start#1") > 0);
        assertTrue(fake.log.contains("stop#1") && fake.log.contains("reset#1"));
    }

    @Test
    void dopMarkersAlternateAcrossBuffersAndPauseSendsMarkedIdle() throws Exception {
        fake.supported = w -> w.containerBits() == 24;
        fake.bufferFrames = 5; // odd, so a marker sequence that restarted per buffer would show
        StreamFormat f = StreamFormat.dsd(2822400, 2, -1, BitOrder.LSB_FIRST);
        SinkStream s = sink.open(f);
        int bytes = 2 * 5 * 3 + 3; // 33 DSD bytes per channel: the 17th pair is half data, half idle
        byte[] src = new byte[bytes * 2];
        new java.util.SplittableRandom(11).nextBytes(src);
        StreamCore core = s.core();
        core.write(MemorySegment.ofArray(src), bytes);
        core.endOfInput();
        s.start();
        FakeWasapi.FakeClient c = fake.lastClient();
        for (int i = 0; i < 3; i++) {
            assertTrue(c.tick());
        }
        core.setPaused(true);
        assertTrue(c.tick());
        s.stop();

        byte[] out = c.render.captured();
        int deviceFrames = 5 * 5; // prefill + 4 ticks
        assertEquals(deviceFrames * 2 * 3, out.length);
        for (int i = 0; i < deviceFrames; i++) {
            int marker = i % 2 == 0 ? 0x05 : 0xFA;
            for (int ch = 0; ch < 2; ch++) {
                int at = (i * 2 + ch) * 3;
                int first = dsdByte(src, bytes, 2 * i, ch, i >= 20);
                int second = dsdByte(src, bytes, 2 * i + 1, ch, i >= 20);
                String where = "frame " + i + " channel " + ch;
                assertEquals(second, out[at] & 0xFF, where + ": the later DSD byte is the low byte");
                assertEquals(first, out[at + 1] & 0xFF, where + ": the earlier DSD byte is the middle byte");
                assertEquals(marker, out[at + 2] & 0xFF, where + ": marker");
            }
        }
        assertEquals(bytes, core.framesPlayed(), "frames are counted in DSD bytes, as the decoder writes them");
    }

    /** The {@code k}-th DSD byte of channel {@code ch} as DoP must carry it: MSB-first, or idle 0x69. */
    private static int dsdByte(byte[] src, int bytes, int k, int ch, boolean paused) {
        if (paused || k >= bytes) {
            return 0x69;
        }
        return Packer.reverseBits(src[k * 2 + ch]);
    }

    @Test
    void theRenderThreadReportsALostDevice() throws Exception {
        AtomicReference<String> lost = new AtomicReference<>();
        java.util.concurrent.CountDownLatch told = new java.util.concurrent.CountDownLatch(1);
        sink.addDeviceListener(m -> {
            lost.set(m);
            told.countDown();
        });
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.core().endOfInput();
        s.start();
        fake.getBufferHr = WasapiErrors.AUDCLNT_E_DEVICE_INVALIDATED;
        fake.lastClient().tick();
        assertTrue(told.await(5, TimeUnit.SECONDS), "device listeners are told");
        assertTrue(lost.get().startsWith("Fake DAC was lost"), lost.get());
        assertFalse(s.accepts(StreamFormat.pcm(44100, 16, 2, -1)));
        AudioDeviceException e = assertThrows(AudioDeviceException.class, s::start);
        assertTrue(e.getMessage().startsWith("Fake DAC was lost"));
    }

    // ---- the review's fix wave -----------------------------------------------------------------------------

    private static final int E_FAIL = 0x80004005;
    private static final int E_INVALIDARG = 0x80070057;
    private static final int AUDCLNT_E_BUFFER_SIZE_ERROR = 0x88890018;
    private static final int AUDCLNT_E_INVALID_DEVICE_PERIOD = 0x88890020;

    private static void awaitTrue(java.util.function.BooleanSupplier c, String what) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!c.getAsBoolean()) {
            assertTrue(System.nanoTime() < end, "timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    // 1. a source deeper than the valid bits must not share a stream

    @Test
    void a32BitSourceIsNotAcceptedOnA24In32Stream() throws Exception {
        fake.supported = w -> w.containerBits() == 32 && w.validBits() == 24;
        SinkStream s = sink.open(StreamFormat.pcm(96000, 24, 2, -1));
        StreamFormat deep = StreamFormat.pcm(96000, 32, 2, 1000);
        assertFalse(s.accepts(deep), "the device was initialised with 24 valid bits: the low byte would be dropped");
        assertTrue(s.accepts(StreamFormat.pcm(96000, 24, 2, 1000)));
        assertTrue(s.accepts(StreamFormat.pcm(96000, 16, 2, 1000)));
        assertThrows(NotBitPerfectException.class, () -> s.core().setSource(deep),
                "the core itself refuses a source deeper than the valid bits");
        assertEquals(24, s.signalPath().source().bits(), "a refused source never shows in the signal path");
        assertEquals(Optional.empty(), s.signalPath().notBitPerfectReason(0));
    }

    @Test
    void aStreamWith32ValidBitsStillAcceptsEveryShallowerSource() throws Exception {
        fake.supported = w -> w.containerBits() == 32 && w.validBits() == 32;
        SinkStream s = sink.open(StreamFormat.pcm(44100, 32, 2, -1));
        assertTrue(s.accepts(StreamFormat.pcm(44100, 32, 2, 10)));
        assertTrue(s.accepts(StreamFormat.pcm(44100, 24, 2, 10)));
        assertTrue(s.accepts(StreamFormat.pcm(44100, 16, 2, 10)));
    }

    @Test
    void a32BitSourceIsRefusedWhenOnlyA24In32FormatIsTaken() {
        fake.supported = w -> w.containerBits() == 32 && w.validBits() == 24;
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.pcm(44100, 32, 2, -1)));
        assertEquals(List.of("32/32@44100"), formatQueries(), "a 24-in-32 container is never offered for 32 bits");
        assertTrue(e.getMessage().contains("32-bit"), e.getMessage());
    }

    // 2. the stream's buffer is in core frames, the unit the Player prefills in

    @Test
    void aDopStreamReportsItsBufferInCoreFramesAndThePrefillCoversTwoDeviceBuffers() throws Exception {
        fake.bufferFrames = 64;
        SinkStream s = sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST));
        int coreFramesPerDeviceBuffer = 64 * 2; // one DoP device frame carries two DSD bytes per channel
        assertEquals(coreFramesPerDeviceBuffer, s.signalPath().bufferFrames());
        long prefill = 2L * s.signalPath().bufferFrames(); // what the Player writes before start
        assertTrue(prefill >= 2L * coreFramesPerDeviceBuffer, "at least two device buffers of core frames: " + prefill);
        // start() takes one device buffer out of the core; a whole one must still be queued for the first event
        assertTrue(prefill - coreFramesPerDeviceBuffer >= coreFramesPerDeviceBuffer);
    }

    @Test
    void aPcmStreamReportsItsDeviceBuffer() throws Exception {
        fake.bufferFrames = 64;
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        assertEquals(64, s.signalPath().bufferFrames());
    }

    // 3. Initialize failing after IsFormatSupported said yes

    @Test
    void anInitializeFormatFailureIsARefusalNotADeviceLoss() throws Exception {
        java.util.List<String> lost = new java.util.concurrent.CopyOnWriteArrayList<>();
        sink.addDeviceListener(lost::add);
        for (int hr : new int[] {WasapiErrors.AUDCLNT_E_UNSUPPORTED_FORMAT, AUDCLNT_E_BUFFER_SIZE_ERROR,
                AUDCLNT_E_INVALID_DEVICE_PERIOD, E_INVALIDARG, E_FAIL}) {
            fake.initResults.add(hr);
            NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                    () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)), "HRESULT 0x%08X".formatted(hr));
            assertTrue(e.getMessage().contains("Fake DAC") && e.getMessage().contains("%08X".formatted(hr)),
                    e.getMessage());
            assertTrue(fake.lastClient().released, "the half-made client is released");
        }
        assertEquals(List.of(), lost, "a refusal is not a lost device");
        assertNotNull(sink.open(StreamFormat.pcm(44100, 16, 2, -1)), "the device still opens afterwards");
    }

    @Test
    void anInitializeThatFindsTheDeviceGoneOrBusyIsStillADeviceError() {
        fake.initResults.add(WasapiErrors.AUDCLNT_E_DEVICE_INVALIDATED);
        assertThrows(AudioDeviceException.class, () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
        fake.initResults.add(WasapiErrors.AUDCLNT_E_DEVICE_IN_USE);
        assertThrows(AudioDeviceException.class, () -> sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
    }

    // 4. a failed render pass is a dropout, releases its buffer, and a persistent one is a lost device

    @Test
    void aFailedGetBufferCountsADropoutAndThenTheNextPassWorks() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.core().endOfInput(); // the core's own running dry is then never counted
        s.start();
        FakeWasapi.FakeClient c = fake.lastClient();
        fake.getBufferHr = E_FAIL;
        c.tick();
        awaitTrue(() -> s.core().dropouts() == 1, "the failed pass to be counted as a dropout");
        assertEquals(1, ((WasapiSinkStream) s).renderErrors());

        fake.getBufferHr = 0;
        assertTrue(c.tick(), "the next pass works");
        assertEquals(2, c.render.buffers.size(), "the prefill and the good pass");
        assertEquals(1, s.core().dropouts());
        s.stop();
    }

    @Test
    void aFillFailureReleasesTheBufferSilentAndCountsADropout() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.core().endOfInput();
        s.start();
        FakeWasapi.FakeClient c = fake.lastClient();
        c.render.flags.clear();
        fake.bufferThrows = true;
        assertTrue(c.tick(), "GetBuffer succeeded, so ReleaseBuffer must still be called");
        awaitTrue(() -> s.core().dropouts() == 1, "the failed pass to be counted as a dropout");
        assertEquals(List.of(2), c.render.flags,
                "AUDCLNT_BUFFERFLAGS_SILENT, so the engine ignores the half-filled buffer");

        fake.bufferThrows = false;
        assertTrue(c.tick(), "the next pass works");
        assertEquals(List.of(2, 0), c.render.flags);
        assertEquals(1, s.core().dropouts());
        s.stop();
    }

    @Test
    void renderPassesThatKeepFailingBecomeALostDevice() throws Exception {
        java.util.concurrent.CountDownLatch told = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> lost = new AtomicReference<>();
        sink.addDeviceListener(m -> {
            lost.set(m);
            told.countDown();
        });
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.core().endOfInput();
        s.start();
        FakeWasapi.FakeClient c = fake.lastClient();
        fake.getBufferHr = E_FAIL;
        for (int i = 0; i < 16 && told.getCount() > 0; i++) { // about 400 ms of failing passes
            c.tick();
            Thread.sleep(25);
        }
        assertTrue(told.await(5, TimeUnit.SECONDS), "a stuck buffer must not stall the output silently");
        assertTrue(lost.get().startsWith("Fake DAC was lost"), lost.get());
        assertTrue(s.core().dropouts() >= 2, "each failed pass was counted");
        assertFalse(s.accepts(StreamFormat.pcm(44100, 16, 2, 1)));
    }

    @Test
    void aBriefFailureIsNotADeviceLoss() throws Exception {
        java.util.List<String> lost = new java.util.concurrent.CopyOnWriteArrayList<>();
        sink.addDeviceListener(lost::add);
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.core().endOfInput();
        s.start();
        FakeWasapi.FakeClient c = fake.lastClient();
        fake.getBufferHr = E_FAIL;
        c.tick();
        c.tick();
        fake.getBufferHr = 0;
        assertTrue(c.tick());
        Thread.sleep(300);
        fake.getBufferHr = E_FAIL; // the streak starts again: it was reset by the good pass
        c.tick();
        Thread.sleep(50);
        assertEquals(List.of(), lost);
        s.stop();
    }

    // 5. DoP is opt-in per device

    @Test
    void dsdIsRefusedWhileTheUserHasNotSaidTheDacTakesDop() {
        dopOn = false;
        NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST)));
        assertEquals("DSD needs DoP — enable \"supports DoP\" for Fake DAC in Settings if your DAC supports it",
                e.getMessage());
        assertEquals(List.of(), fake.log, "DoP noise must never be sent to a DAC that was not marked as DoP-capable");
    }

    @Test
    void dsdOpensOnceTheUserHasSaidTheDacTakesDop() throws Exception {
        dopOn = false;
        assertThrows(NotBitPerfectException.class,
                () -> sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST)));
        dopOn = true; // the checkbox is read at every open: no need to rebuild the sink
        SinkStream s = sink.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST));
        assertEquals("DSD64 → WASAPI exclusive DoP 24-bit @ 176400 Hz", s.signalPath().describe());
    }

    @Test
    void theDopSwitchNeverAffectsPcm() throws Exception {
        dopOn = false;
        assertNotNull(sink.open(StreamFormat.pcm(44100, 16, 2, -1)));
    }

    @Test
    void aSinkMadeWithoutASwitchRefusesDsd() {
        WasapiSink plain = new WasapiSink(DEVICE, ctl, fake);
        assertThrows(NotBitPerfectException.class,
                () -> plain.open(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST)));
    }

    // 7. Start failing after the prefill

    @Test
    void aFailedStartResetsTheClientBeforeReporting() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        fake.startHr = E_FAIL;
        AudioDeviceException e = assertThrows(AudioDeviceException.class, s::start);
        assertTrue(e.getMessage().startsWith("Could not start Fake DAC"), e.getMessage());
        int start = fake.log.indexOf("start#1");
        int reset = fake.log.indexOf("reset#1");
        assertTrue(start >= 0 && reset > start, "Reset follows the failed Start: " + fake.log);
        fake.startHr = 0;
        s.start(); // and the stream can be started again, prefilling anew
        assertEquals(2, fake.lastClient().render.buffers.size(), "one prefill per start attempt");
    }

    // 8. COM objects are released only once the render thread has really ended

    @Test
    void aRenderThreadThatWillNotEndKeepsItsComObjectsAlive() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.core().endOfInput();
        s.start();
        FakeWasapi.FakeClient c = fake.lastClient();
        java.util.concurrent.CountDownLatch hang = new java.util.concurrent.CountDownLatch(1);
        fake.hangGetBuffer = hang;
        Win32.setEvent(c.event); // the render thread wakes and sticks inside GetBuffer
        awaitTrue(() -> renderThreads().stream().anyMatch(t -> t.getState() == Thread.State.WAITING), "the hang");
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(WasapiSinkStream.class.getName());
        List<String> logged = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) {
                logged.add(r.getLevel() + " " + r.getMessage());
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        boolean parents = log.getUseParentHandlers();
        log.setUseParentHandlers(false); // keep the expected warning out of the test run's console
        log.addHandler(capture);
        try {
            sink.close();
            assertEquals(1, logged.size(), "the leak is logged: " + logged);
            assertTrue(logged.getFirst().startsWith("WARNING Fake DAC: the render thread did not end"), logged.toString());
            assertFalse(c.render.closed, "the render client is leaked, not released under a thread that may use it");
            assertFalse(c.released, "likewise the audio client");
            assertFalse(fake.log.contains("release render"), fake.log.toString());
        } finally {
            log.removeHandler(capture);
            log.setUseParentHandlers(parents);
            hang.countDown();
        }
        awaitTrue(() -> renderThreads().isEmpty(), "the render thread to end once the call returns");
    }

    @Test
    void aRenderThreadThatEndsHasItsComObjectsReleasedInOrder() throws Exception {
        sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        sink.close();
        assertTrue(fake.lastClient().render.closed);
        assertTrue(fake.lastClient().released);
        assertTrue(fake.log.indexOf("release render") < fake.log.indexOf("release client#1"));
    }

    @Test
    void stopThenStartPrefillsAgain() throws Exception {
        SinkStream s = sink.open(StreamFormat.pcm(44100, 16, 2, -1));
        s.core().endOfInput();
        s.start();
        s.stop();
        s.start();
        s.stop();
        assertEquals(2, fake.lastClient().render.buffers.size(), "one prefill per start");
        assertEquals(List.of("start#1", "stop#1", "reset#1", "start#1", "stop#1", "reset#1"),
                fake.log.stream().filter(x -> x.matches("(start|stop|reset)#1")).toList());
    }
}
