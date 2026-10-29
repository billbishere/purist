package purist.wasapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import purist.audio.NotBitPerfectException;
import purist.audio.SampleType;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;

/**
 * {@link WasapiSink} on the UA Volt 176's WASAPI endpoint, in exclusive mode. Needs the Volt connected and idle,
 * clocked at 44.1 or 48 kHz: its endpoint only takes the rate its hardware clock is set to. It plays one second of
 * digital silence (zeros), so nothing is heard.
 */
@Tag("hardware")
@Timeout(60)
class WasapiSinkVoltIT {

    private static final String VOLT = "MONITOR L/R (Volt 176)";

    private static WasapiDevice volt() {
        return WasapiDeviceList.active().stream()
                .filter(d -> d.name().equals(VOLT))
                .findFirst()
                .orElseThrow(() -> new AssertionError(VOLT + " is not an active render endpoint"));
    }

    @Test
    void playsOneSecondInExclusiveModeAndRefusesWhatTheVoltCannotTake() throws Exception {
        try (WasapiSink sink = WasapiSink.forDevice(volt())) {
            // The endpoint takes only its hardware clock's rate, and UA's driver may restore 48 kHz after the ASIO
            // test closes: play at whichever of the two it is set to.
            SinkStream s = null;
            int rate = 0;
            for (int r : new int[] {44100, 48000}) {
                try {
                    s = sink.open(StreamFormat.pcm(r, 24, 2, r));
                    rate = r;
                    break;
                } catch (NotBitPerfectException e) {
                    System.out.println("[WasapiSinkVoltIT] not clocked at " + r + ": " + e.getMessage());
                }
            }
            assertTrue(s != null, "the Volt took neither 44.1 nor 48 kHz in exclusive mode");
            WasapiSinkStream ws = (WasapiSinkStream) s;
            System.out.println("[WasapiSinkVoltIT] " + s.signalPath().describe() + ", buffer "
                    + ws.bufferFrames() + " frames");
            assertEquals(SampleType.INT32_LSB, s.signalPath().outType());
            assertEquals("24-bit " + (rate == 44100 ? "44.1" : "48") + " kHz → WASAPI exclusive 24-in-32 @ " + rate
                    + " Hz", s.signalPath().describe());
            assertEquals(Optional.empty(), s.signalPath().notBitPerfectReason(0));

            StreamCore core = s.core();
            core.write(MemorySegment.ofArray(new byte[rate * 2 * 4]), rate); // 1 s of zeros
            core.endOfInput();
            s.start();
            Thread.sleep(500);
            int padding = ws.currentPadding();
            System.out.println("[WasapiSinkVoltIT] GetCurrentPadding mid-play: " + padding);
            // Event-driven exclusive mode double-buffers: GetBufferSize is one of two halves, and the Volt reports
            // both as queued (padding 2 × buffer) while playing.
            assertTrue(padding >= 0 && padding <= 2 * ws.bufferFrames(), "padding " + padding);
            Thread.sleep(800);
            s.stop();

            System.out.println("[WasapiSinkVoltIT] framesPlayed " + core.framesPlayed() + ", dropouts "
                    + core.dropouts() + ", render errors " + ws.renderErrors());
            assertEquals(rate, core.framesPlayed());
            assertEquals(0, core.dropouts());
            assertEquals(0, ws.renderErrors());

            NotBitPerfectException e = assertThrows(NotBitPerfectException.class,
                    () -> sink.open(StreamFormat.pcm(352800, 24, 2, 352800)));
            System.out.println("[WasapiSinkVoltIT] refused: " + e.getMessage());
            assertEquals("rate 352800 Hz / 24-bit not supported by " + VOLT + " in exclusive mode", e.getMessage());
        }
    }
}
