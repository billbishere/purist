package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AsioConstantsTest {

    @Test
    void errorNames() {
        assertEquals("ASE_NoClock", AsioConstants.errorName(-995));
        assertEquals("ASE_SUCCESS", AsioConstants.errorName(0x3f4847a0));
        assertEquals("ASE_NotPresent", AsioConstants.errorName(-1000));
    }

    @Test
    void everyErrorCodeHasItsSdkName() {
        assertEquals("ASE_OK", AsioConstants.errorName(0));
        assertEquals("ASE_HWMalfunction", AsioConstants.errorName(-999));
        assertEquals("ASE_InvalidParameter", AsioConstants.errorName(-998));
        assertEquals("ASE_InvalidMode", AsioConstants.errorName(-997));
        assertEquals("ASE_SPNotAdvancing", AsioConstants.errorName(-996));
        assertEquals("ASE_NoMemory", AsioConstants.errorName(-994));
    }

    @Test
    void unknownCodesGetAPlaceholderRatherThanAnException() {
        assertEquals("Unknown", AsioConstants.errorName(0x80040154)); // an HRESULT, not an ASIO code
        assertEquals("Unknown", AsioConstants.errorName(12345));
    }

    @Test
    void specValuesAreTranscribedExactly() {
        assertEquals(0x23112004, AsioConstants.K_ASIO_CAN_DO_IO_FORMAT);
        assertEquals(0x23111961, AsioConstants.K_ASIO_SET_IO_FORMAT);
        assertEquals(0x23111983, AsioConstants.K_ASIO_GET_IO_FORMAT);
        assertEquals(0, AsioConstants.FORMAT_PCM);
        assertEquals(1, AsioConstants.FORMAT_DSD);

        assertEquals(16, AsioConstants.ST_INT16_LSB);
        assertEquals(17, AsioConstants.ST_INT24_LSB);
        assertEquals(18, AsioConstants.ST_INT32_LSB);
        assertEquals(19, AsioConstants.ST_FLOAT32_LSB);
        assertEquals(20, AsioConstants.ST_FLOAT64_LSB);
        assertEquals(24, AsioConstants.ST_INT32_LSB16);
        assertEquals(25, AsioConstants.ST_INT32_LSB18);
        assertEquals(26, AsioConstants.ST_INT32_LSB20);
        assertEquals(27, AsioConstants.ST_INT32_LSB24);
        assertEquals(32, AsioConstants.ST_DSD_INT8_LSB1);
        assertEquals(33, AsioConstants.ST_DSD_INT8_MSB1);
        assertEquals(40, AsioConstants.ST_DSD_INT8_NER8);

        assertEquals(1, AsioConstants.MSG_SELECTOR_SUPPORTED);
        assertEquals(2, AsioConstants.MSG_ENGINE_VERSION);
        assertEquals(3, AsioConstants.MSG_RESET_REQUEST);
        assertEquals(4, AsioConstants.MSG_BUFFER_SIZE_CHANGE);
        assertEquals(5, AsioConstants.MSG_RESYNC_REQUEST);
        assertEquals(6, AsioConstants.MSG_LATENCIES_CHANGED);
        assertEquals(7, AsioConstants.MSG_SUPPORTS_TIME_INFO);
        assertEquals(8, AsioConstants.MSG_SUPPORTS_TIME_CODE);
        assertEquals(15, AsioConstants.MSG_OVERLOAD);
    }

    @Test
    void vtableSlotsFollowTheIasioDeclarationOrder() {
        assertEquals(3, AsioConstants.INIT);
        assertEquals(4, AsioConstants.GET_DRIVER_NAME);
        assertEquals(5, AsioConstants.GET_DRIVER_VERSION);
        assertEquals(6, AsioConstants.GET_ERROR_MESSAGE);
        assertEquals(7, AsioConstants.START);
        assertEquals(8, AsioConstants.STOP);
        assertEquals(9, AsioConstants.GET_CHANNELS);
        assertEquals(10, AsioConstants.GET_LATENCIES);
        assertEquals(11, AsioConstants.GET_BUFFER_SIZE);
        assertEquals(12, AsioConstants.CAN_SAMPLE_RATE);
        assertEquals(13, AsioConstants.GET_SAMPLE_RATE);
        assertEquals(14, AsioConstants.SET_SAMPLE_RATE);
        assertEquals(15, AsioConstants.GET_CLOCK_SOURCES);
        assertEquals(16, AsioConstants.SET_CLOCK_SOURCE);
        assertEquals(17, AsioConstants.GET_SAMPLE_POSITION);
        assertEquals(18, AsioConstants.GET_CHANNEL_INFO);
        assertEquals(19, AsioConstants.CREATE_BUFFERS);
        assertEquals(20, AsioConstants.DISPOSE_BUFFERS);
        assertEquals(21, AsioConstants.CONTROL_PANEL);
        assertEquals(22, AsioConstants.FUTURE);
        assertEquals(23, AsioConstants.OUTPUT_READY);
    }
}
