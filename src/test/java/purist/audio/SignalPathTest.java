package purist.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SignalPathTest {

    private static SignalPath path(StreamFormat source, SampleType out, double deviceRate) throws Exception {
        return new SignalPath("Test device", source, out, deviceRate, 512, Packer.create(source, out).transform());
    }

    // ---- describe() ----------------------------------------------------------------------------------------

    @Test
    void describesPcm() throws Exception {
        assertEquals("24-bit 96 kHz → INT32_LSB @ 96000 Hz",
                path(StreamFormat.pcm(96000, 24, 2, -1), SampleType.INT32_LSB, 96000).describe());
    }

    @Test
    void describesFractionalKiloHertzSourcesWithoutLocaleEffects() throws Exception {
        assertEquals("16-bit 44.1 kHz → INT16_LSB @ 44100 Hz",
                path(StreamFormat.pcm(44100, 16, 2, -1), SampleType.INT16_LSB, 44100).describe());
        assertEquals("24-bit 88.2 kHz → INT24_LSB @ 88200 Hz",
                path(StreamFormat.pcm(88200, 24, 2, -1), SampleType.INT24_LSB, 88200).describe());
        assertEquals("32-bit 352.8 kHz → INT32_LSB @ 352800 Hz",
                path(StreamFormat.pcm(352800, 32, 2, -1), SampleType.INT32_LSB, 352800).describe());
        assertEquals("24-bit 192 kHz → INT32_LSB24 @ 192000 Hz",
                path(StreamFormat.pcm(192000, 24, 2, -1), SampleType.INT32_LSB24, 192000).describe());
    }

    @Test
    void describesDsd() throws Exception {
        assertEquals("DSD128 → native DSD (DSD_INT8_MSB1) @ 5.6448 MHz",
                path(StreamFormat.dsd(5644800, 2, -1, BitOrder.MSB_FIRST), SampleType.DSD_INT8_MSB1, 5644800)
                        .describe());
        assertEquals("DSD64 → native DSD (DSD_INT8_LSB1) @ 2.8224 MHz",
                path(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST), SampleType.DSD_INT8_LSB1, 2822400)
                        .describe());
        assertEquals("DSD256 → native DSD (DSD_INT8_MSB1) @ 11.2896 MHz",
                path(StreamFormat.dsd(11289600, 2, -1, BitOrder.LSB_FIRST), SampleType.DSD_INT8_MSB1, 11289600)
                        .describe());
    }

    @Test
    void describeShowsTheDeviceRateNotTheSourceRate() throws Exception {
        assertEquals("16-bit 44.1 kHz → INT32_LSB @ 48000 Hz",
                path(StreamFormat.pcm(44100, 16, 2, -1), SampleType.INT32_LSB, 48000).describe());
    }

    @Test
    void aLinkLabelNamesWhatTheDeviceReceives() throws Exception {
        StreamFormat pcm = StreamFormat.pcm(96000, 24, 2, -1);
        SignalPath p = new SignalPath("Volt", pcm, SampleType.INT32_LSB, 96000, 480,
                Packer.create(pcm, SampleType.INT32_LSB).transform(), "WASAPI exclusive 24-in-32 @ 96000 Hz");
        assertEquals("24-bit 96 kHz → WASAPI exclusive 24-in-32 @ 96000 Hz", p.describe());
        StreamFormat dsd = StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST);
        SignalPath d = new SignalPath("D50", dsd, SampleType.DSD_INT8_MSB1, 2822400, 480,
                Packer.create(dsd, SampleType.DSD_INT8_MSB1).transform(), "WASAPI exclusive DoP 24-in-32 @ 176400 Hz");
        assertEquals("DSD64 → WASAPI exclusive DoP 24-in-32 @ 176400 Hz", d.describe());
        assertTrue(d.rateMatches());
        assertEquals(null, path(pcm, SampleType.INT32_LSB, 96000).link(), "ASIO paths have no link label");
    }

    // ---- rate and not-bit-perfect reason ------------------------------------------------------------------------

    @Test
    void rateMatchesComparesDeviceAndSourceRate() throws Exception {
        assertTrue(path(StreamFormat.pcm(96000, 24, 2, -1), SampleType.INT32_LSB, 96000).rateMatches());
        assertFalse(path(StreamFormat.pcm(44100, 24, 2, -1), SampleType.INT32_LSB, 48000).rateMatches());
        assertTrue(path(StreamFormat.dsd(2822400, 2, -1, BitOrder.MSB_FIRST), SampleType.DSD_INT8_MSB1, 2822400)
                .rateMatches());
    }

    @Test
    void bitPerfectWhenRateMatchesAndNoDropouts() throws Exception {
        assertEquals(Optional.empty(),
                path(StreamFormat.pcm(96000, 24, 2, -1), SampleType.INT32_LSB, 96000).notBitPerfectReason(0));
    }

    @Test
    void rateMismatchIsNotBitPerfect() throws Exception {
        assertEquals(Optional.of("device runs at 48000 Hz, source is 44100 Hz"),
                path(StreamFormat.pcm(44100, 24, 2, -1), SampleType.INT32_LSB, 48000).notBitPerfectReason(0));
    }

    @Test
    void threeDropoutsAreNotBitPerfect() throws Exception {
        assertEquals(Optional.of("3 dropouts"),
                path(StreamFormat.pcm(96000, 24, 2, -1), SampleType.INT32_LSB, 96000).notBitPerfectReason(3));
    }

    @Test
    void rateMismatchIsReportedBeforeDropouts() throws Exception {
        assertEquals(Optional.of("device runs at 48000 Hz, source is 44100 Hz"),
                path(StreamFormat.pcm(44100, 24, 2, -1), SampleType.INT32_LSB, 48000).notBitPerfectReason(7));
    }

    @Test
    void aNonIntegralDeviceRateIsShownExactly() throws Exception {
        assertEquals(Optional.of("device runs at 44099.5 Hz, source is 44100 Hz"),
                path(StreamFormat.pcm(44100, 24, 2, -1), SampleType.INT32_LSB, 44099.5).notBitPerfectReason(0));
    }

    // ---- the record ------------------------------------------------------------------------------------------------

    @Test
    void carriesItsComponents() throws Exception {
        StreamFormat src = StreamFormat.pcm(96000, 24, 2, -1);
        Transform t = Packer.create(src, SampleType.INT32_LSB).transform();
        SignalPath p = new SignalPath("Volt 176", src, SampleType.INT32_LSB, 96000, 1024, t);
        assertEquals("Volt 176", p.deviceName());
        assertEquals(src, p.source());
        assertEquals(SampleType.INT32_LSB, p.outType());
        assertEquals(96000.0, p.deviceRate());
        assertEquals(1024, p.bufferFrames());
        assertEquals(t, p.transform());
    }

    @Test
    void rejectsMissingComponents() throws Exception {
        StreamFormat src = StreamFormat.pcm(96000, 24, 2, -1);
        Transform t = Packer.create(src, SampleType.INT32_LSB).transform();
        assertThrows(NullPointerException.class, () -> new SignalPath(null, src, SampleType.INT32_LSB, 96000, 512, t));
        assertThrows(NullPointerException.class, () -> new SignalPath("d", null, SampleType.INT32_LSB, 96000, 512, t));
        assertThrows(NullPointerException.class, () -> new SignalPath("d", src, null, 96000, 512, t));
        assertThrows(NullPointerException.class,
                () -> new SignalPath("d", src, SampleType.INT32_LSB, 96000, 512, null));
    }
}
