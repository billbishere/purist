package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import purist.audio.NotBitPerfectException;
import purist.audio.SampleType;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;

/**
 * {@link AsioSink} on the real UA Volt 176. Needs the Volt connected and idle (close foobar2000 first); it plays
 * one second of digital silence and leaves the device at 44.1 kHz.
 */
@Tag("hardware")
@Timeout(60)
class AsioSinkVoltIT {

    private static final String VOLT = "Universal Audio Volt";

    private static AsioDriverInfo volt() {
        return AsioDriverList.installed().stream()
                .filter(d -> d.name().equals(VOLT))
                .findFirst()
                .orElseThrow(() -> new AssertionError(VOLT + " is not installed"));
    }

    @Test
    void playsOneSecondExactlyThenSwitchesRateAndRefusesWhatTheVoltCannotDo() throws Exception {
        try (AsioSink sink = AsioSink.forDriver(volt())) {
            StreamFormat f = StreamFormat.pcm(48000, 24, 2, 48000);
            SinkStream s = sink.open(f);
            assertEquals(SampleType.INT32_LSB, s.signalPath().outType());
            assertEquals(48000.0, s.signalPath().deviceRate());

            StreamCore core = s.core();
            core.setSource(f);
            core.write(MemorySegment.ofArray(new byte[48000 * 2 * 4]), 48000); // 1 s of zeros
            core.endOfInput();
            s.start();
            Thread.sleep(1300);
            s.stop();

            assertEquals(48000, core.framesPlayed());
            assertEquals(0, core.dropouts());
            assertEquals(0, ((AsioSinkStream) s).callbackErrors());

            SinkStream s44 = sink.open(StreamFormat.pcm(44100, 16, 2, 44100));
            assertEquals(44100.0, s44.signalPath().deviceRate());

            assertThrows(NotBitPerfectException.class, () -> sink.open(StreamFormat.pcm(352800, 24, 2, 352800)));
        }
    }
}
