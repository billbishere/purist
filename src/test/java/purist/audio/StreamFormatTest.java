package purist.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import purist.asio.AsioConstants;

class StreamFormatTest {

    // ---- StreamFormat ----------------------------------------------------------------------------

    @Test
    void pcmLabels() {
        assertEquals("24/96", StreamFormat.pcm(96000, 24, 2, 0).label());
        assertEquals("16/44.1", StreamFormat.pcm(44100, 16, 2, 0).label());
        assertEquals("24/88.2", StreamFormat.pcm(88200, 24, 2, 0).label());
        assertEquals("24/192", StreamFormat.pcm(192000, 24, 2, 0).label());
        assertEquals("24/48", StreamFormat.pcm(48000, 24, 2, 0).label());
        assertEquals("24/176.4", StreamFormat.pcm(176400, 24, 2, 0).label());
        assertEquals("32/352.8", StreamFormat.pcm(352800, 32, 2, 0).label());
        assertEquals("16/22.05", StreamFormat.pcm(22050, 16, 1, 0).label());
        assertEquals("8/11.025", StreamFormat.pcm(11025, 8, 1, 0).label());
    }

    @Test
    void dsdLabels() {
        assertEquals("DSD64", StreamFormat.dsd(2822400, 2, 0, BitOrder.MSB_FIRST).label());
        assertEquals("DSD128", StreamFormat.dsd(5644800, 2, 0, BitOrder.MSB_FIRST).label());
        assertEquals("DSD256", StreamFormat.dsd(11289600, 2, 0, BitOrder.LSB_FIRST).label());
        assertEquals("DSD512", StreamFormat.dsd(22579200, 2, 0, BitOrder.MSB_FIRST).label());
    }

    @Test
    void dsdRateOutsideTheFortyFourOneFamilyStillGetsAnHonestLabel() {
        assertEquals("DSD 3072000 Hz", StreamFormat.dsd(3072000, 2, 0, BitOrder.MSB_FIRST).label());
    }

    @Test
    void pcmFactorySetsKindAndLeavesDsdOrderNull() {
        StreamFormat f = StreamFormat.pcm(44100, 24, 2, 1234);
        assertEquals(Kind.PCM, f.kind());
        assertEquals(44100, f.sampleRate());
        assertEquals(24, f.bits());
        assertEquals(2, f.channels());
        assertEquals(1234L, f.lengthFrames());
        assertNull(f.dsdOrder());
    }

    @Test
    void dsdFactoryUsesOneBitAndKeepsTheOrder() {
        StreamFormat f = StreamFormat.dsd(2822400, 2, 99, BitOrder.LSB_FIRST);
        assertEquals(Kind.DSD, f.kind());
        assertEquals(2822400, f.sampleRate());
        assertEquals(1, f.bits());
        assertEquals(2, f.channels());
        assertEquals(99L, f.lengthFrames());
        assertEquals(BitOrder.LSB_FIRST, f.dsdOrder());
    }

    @Test
    void dsdRequiresABitOrder() {
        assertThrows(NullPointerException.class, () -> StreamFormat.dsd(2822400, 2, 0, null));
    }

    @Test
    void secondsConvertsFramesToTime() {
        assertEquals(1.0, StreamFormat.dsd(2822400, 2, 352800, BitOrder.MSB_FIRST).seconds(352800));
        assertEquals(1.0, StreamFormat.pcm(44100, 16, 2, 0).seconds(44100));
        assertEquals(0.5, StreamFormat.pcm(44100, 16, 2, 0).seconds(22050));
        assertEquals(0.0, StreamFormat.pcm(44100, 16, 2, 0).seconds(0));
    }

    @Test
    void bytesPerDecodedFrame() {
        assertEquals(8, StreamFormat.pcm(44100, 16, 2, 0).bytesPerDecodedFrame());
        assertEquals(8, StreamFormat.pcm(96000, 24, 2, 0).bytesPerDecodedFrame());
        assertEquals(4, StreamFormat.pcm(44100, 16, 1, 0).bytesPerDecodedFrame());
        assertEquals(2, StreamFormat.dsd(2822400, 2, 0, BitOrder.MSB_FIRST).bytesPerDecodedFrame());
        assertEquals(1, StreamFormat.dsd(2822400, 1, 0, BitOrder.MSB_FIRST).bytesPerDecodedFrame());
    }

    @Test
    void sameStreamComparesKindRateAndChannelsOnly() {
        assertTrue(StreamFormat.pcm(44100, 16, 2, 0).sameStream(StreamFormat.pcm(44100, 24, 2, 9)));
        assertFalse(StreamFormat.pcm(44100, 16, 2, 0).sameStream(StreamFormat.pcm(48000, 16, 2, 0)));
        assertFalse(StreamFormat.pcm(44100, 16, 2, 0).sameStream(StreamFormat.pcm(44100, 16, 1, 0)));
        assertFalse(StreamFormat.pcm(2822400, 1, 2, 0)
                .sameStream(StreamFormat.dsd(2822400, 2, 0, BitOrder.MSB_FIRST)));
        assertTrue(StreamFormat.dsd(2822400, 2, 5, BitOrder.MSB_FIRST)
                .sameStream(StreamFormat.dsd(2822400, 2, 7, BitOrder.LSB_FIRST)));
    }

    // ---- SampleType ------------------------------------------------------------------------------

    @Test
    void sampleTypeTable() {
        assertSampleType(SampleType.INT16_LSB, 16, 2, 16, false);
        assertSampleType(SampleType.INT24_LSB, 17, 3, 24, false);
        assertSampleType(SampleType.INT32_LSB, 18, 4, 32, false);
        assertSampleType(SampleType.INT32_LSB16, 24, 4, 16, false);
        assertSampleType(SampleType.INT32_LSB18, 25, 4, 18, false);
        assertSampleType(SampleType.INT32_LSB20, 26, 4, 20, false);
        assertSampleType(SampleType.INT32_LSB24, 27, 4, 24, false);
        assertSampleType(SampleType.DSD_INT8_LSB1, 32, 1, 1, true);
        assertSampleType(SampleType.DSD_INT8_MSB1, 33, 1, 1, true);
        assertEquals(9, SampleType.values().length);
    }

    private static void assertSampleType(SampleType t, int asio, int container, int valid, boolean dsd) {
        assertEquals(asio, t.asioCode(), t + " asioCode");
        assertEquals(container, t.containerBytes(), t + " containerBytes");
        assertEquals(valid, t.validBits(), t + " validBits");
        assertEquals(dsd, t.isDsd(), t + " isDsd");
    }

    @Test
    void asioCodesAgreeWithAsioConstants() {
        assertEquals(AsioConstants.ST_INT16_LSB, SampleType.INT16_LSB.asioCode());
        assertEquals(AsioConstants.ST_INT24_LSB, SampleType.INT24_LSB.asioCode());
        assertEquals(AsioConstants.ST_INT32_LSB, SampleType.INT32_LSB.asioCode());
        assertEquals(AsioConstants.ST_INT32_LSB16, SampleType.INT32_LSB16.asioCode());
        assertEquals(AsioConstants.ST_INT32_LSB18, SampleType.INT32_LSB18.asioCode());
        assertEquals(AsioConstants.ST_INT32_LSB20, SampleType.INT32_LSB20.asioCode());
        assertEquals(AsioConstants.ST_INT32_LSB24, SampleType.INT32_LSB24.asioCode());
        assertEquals(AsioConstants.ST_DSD_INT8_LSB1, SampleType.DSD_INT8_LSB1.asioCode());
        assertEquals(AsioConstants.ST_DSD_INT8_MSB1, SampleType.DSD_INT8_MSB1.asioCode());
    }

    @Test
    void fromAsioFindsEveryTypeByItsCode() {
        for (SampleType t : SampleType.values()) {
            assertEquals(Optional.of(t), SampleType.fromAsio(t.asioCode()));
        }
    }

    @Test
    void fromAsioRejectsEverythingPurisWillNotPlay() {
        assertEquals(Optional.empty(), SampleType.fromAsio(AsioConstants.ST_FLOAT32_LSB));
        assertEquals(Optional.empty(), SampleType.fromAsio(AsioConstants.ST_FLOAT64_LSB));
        assertEquals(Optional.empty(), SampleType.fromAsio(AsioConstants.ST_DSD_INT8_NER8));
        assertEquals(Optional.empty(), SampleType.fromAsio(0)); // Int16MSB, a big-endian type
        assertEquals(Optional.empty(), SampleType.fromAsio(-1));
    }

    @Test
    void dsdTypesCarryTheirBitOrder() {
        assertEquals(BitOrder.LSB_FIRST, SampleType.DSD_INT8_LSB1.bitOrder());
        assertEquals(BitOrder.MSB_FIRST, SampleType.DSD_INT8_MSB1.bitOrder());
    }

    @Test
    void pcmTypesHaveNoBitOrder() {
        assertThrows(IllegalStateException.class, SampleType.INT32_LSB::bitOrder);
    }
}
