package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import purist.audio.BitOrder;
import purist.audio.SinkStream;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;

/**
 * {@link AsioSink} on the real Topping D50, the release target. Needs the D50 connected, switched on and idle
 * (close foobar2000 first). It plays half a second of digital silence at each PCM rate and at native DSD64 and
 * DSD128, so nothing is heard; the D50 may click its relay when it changes between PCM and DSD.
 */
@Tag("topping")
@Timeout(120)
class AsioSinkToppingIT {

    private static final String TOPPING = "Topping USB Audio Device";
    /** One DSD byte of digital silence: an equal number of ones and zeros, in either bit order. */
    private static final byte DSD_SILENCE = 0x69;

    private static AsioDriverInfo topping() {
        return AsioDriverList.installed().stream()
                .filter(d -> d.name().equals(TOPPING))
                .findFirst()
                .orElseThrow(() -> new AssertionError(TOPPING + " is not installed"));
    }

    @Test
    void playsEveryPcmRateAndNativeDsdBitPerfect() throws Exception {
        try (AsioSink sink = AsioSink.forDriver(topping())) {
            play(sink, StreamFormat.pcm(44100, 16, 2, 22050), new byte[22050 * 2 * 4]);
            play(sink, StreamFormat.pcm(96000, 24, 2, 48000), new byte[48000 * 2 * 4]);
            play(sink, StreamFormat.pcm(192000, 24, 2, 96000), new byte[96000 * 2 * 4]);
            for (int bitRate : new int[] {2822400, 5644800}) {
                int frames = bitRate / 8 / 2; // half a second, one byte per channel per frame
                byte[] silence = new byte[frames * 2];
                Arrays.fill(silence, DSD_SILENCE);
                SinkStream s = play(sink, StreamFormat.dsd(bitRate, 2, frames, BitOrder.LSB_FIRST), silence);
                assertTrue(s.signalPath().outType().isDsd(), "native DSD, got " + s.signalPath().outType());
            }
        }
    }

    private static SinkStream play(AsioSink sink, StreamFormat f, byte[] data) throws Exception {
        SinkStream s = sink.open(f);
        System.out.println("[AsioSinkToppingIT] " + f.label() + ": " + s.signalPath().describe());
        assertEquals((double) f.sampleRate(), s.signalPath().deviceRate());
        assertEquals(Optional.empty(), s.signalPath().notBitPerfectReason(0));
        StreamCore core = s.core();
        core.setSource(f);
        core.write(MemorySegment.ofArray(data), (int) f.lengthFrames());
        core.endOfInput();
        s.start();
        Thread.sleep(900);
        s.stop();
        System.out.println("[AsioSinkToppingIT] " + f.label() + ": played " + core.framesPlayed() + ", dropouts "
                + core.dropouts() + ", callback errors " + ((AsioSinkStream) s).callbackErrors());
        assertEquals(f.lengthFrames(), core.framesPlayed());
        assertEquals(0, core.dropouts());
        assertEquals(0, ((AsioSinkStream) s).callbackErrors());
        return s;
    }
}
