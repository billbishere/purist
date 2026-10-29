package purist.asio;

/**
 * ASIO numeric constants, transcribed from spec section 6 (the layout is reproduced from the GPLv3 ASIO
 * documentation; no Steinberg SDK files are vendored).
 */
public final class AsioConstants {

    private AsioConstants() {
    }

    // ---- ASIOError values -------------------------------------------------------------------------

    /** Success for every call except {@code future}. */
    public static final int ASE_OK = 0;
    /** What {@code future} returns on success; {@link #ASE_OK} is accepted as well. */
    public static final int ASE_SUCCESS = 0x3f4847a0;
    public static final int ASE_NOT_PRESENT = -1000;
    public static final int ASE_HW_MALFUNCTION = -999;
    public static final int ASE_INVALID_PARAMETER = -998;
    public static final int ASE_INVALID_MODE = -997;
    public static final int ASE_SP_NOT_ADVANCING = -996;
    public static final int ASE_NO_CLOCK = -995;
    public static final int ASE_NO_MEMORY = -994;

    // ---- asioMessage selectors ---------------------------------------------------------------------

    public static final int MSG_SELECTOR_SUPPORTED = 1;
    public static final int MSG_ENGINE_VERSION = 2;
    public static final int MSG_RESET_REQUEST = 3;
    public static final int MSG_BUFFER_SIZE_CHANGE = 4;
    public static final int MSG_RESYNC_REQUEST = 5;
    public static final int MSG_LATENCIES_CHANGED = 6;
    public static final int MSG_SUPPORTS_TIME_INFO = 7;
    public static final int MSG_SUPPORTS_TIME_CODE = 8;
    public static final int MSG_OVERLOAD = 15;

    // ---- ASIOSampleType ----------------------------------------------------------------------------

    public static final int ST_INT16_LSB = 16;
    public static final int ST_INT24_LSB = 17;
    public static final int ST_INT32_LSB = 18;
    /** Refused by Purist: bit-perfect output never converts through floating point. */
    public static final int ST_FLOAT32_LSB = 19;
    /** Refused by Purist, as {@link #ST_FLOAT32_LSB}. */
    public static final int ST_FLOAT64_LSB = 20;
    public static final int ST_INT32_LSB16 = 24;
    public static final int ST_INT32_LSB18 = 25;
    public static final int ST_INT32_LSB20 = 26;
    public static final int ST_INT32_LSB24 = 27;
    public static final int ST_DSD_INT8_LSB1 = 32;
    public static final int ST_DSD_INT8_MSB1 = 33;
    /** Refused by Purist. */
    public static final int ST_DSD_INT8_NER8 = 40;

    // ---- future() selectors and I/O formats ----------------------------------------------------------

    public static final int K_ASIO_CAN_DO_IO_FORMAT = 0x23112004;
    public static final int K_ASIO_SET_IO_FORMAT = 0x23111961;
    public static final int K_ASIO_GET_IO_FORMAT = 0x23111983;
    /** {@code kASIOPCMFormat}, the value of {@code ASIOIoFormat.FormatType}. */
    public static final int FORMAT_PCM = 0;
    /** {@code kASIODSDFormat}. */
    public static final int FORMAT_DSD = 1;

    // ---- IASIO vtable slots (0-2 are IUnknown) ----------------------------------------------------

    public static final int INIT = 3;
    public static final int GET_DRIVER_NAME = 4;
    public static final int GET_DRIVER_VERSION = 5;
    public static final int GET_ERROR_MESSAGE = 6;
    public static final int START = 7;
    public static final int STOP = 8;
    public static final int GET_CHANNELS = 9;
    public static final int GET_LATENCIES = 10;
    public static final int GET_BUFFER_SIZE = 11;
    public static final int CAN_SAMPLE_RATE = 12;
    public static final int GET_SAMPLE_RATE = 13;
    public static final int SET_SAMPLE_RATE = 14;
    public static final int GET_CLOCK_SOURCES = 15;
    public static final int SET_CLOCK_SOURCE = 16;
    public static final int GET_SAMPLE_POSITION = 17;
    public static final int GET_CHANNEL_INFO = 18;
    public static final int CREATE_BUFFERS = 19;
    public static final int DISPOSE_BUFFERS = 20;
    public static final int CONTROL_PANEL = 21;
    public static final int FUTURE = 22;
    public static final int OUTPUT_READY = 23;

    /** The SDK name of {@code code} ({@code "ASE_NoClock"}), or {@code "Unknown"} for anything else. */
    public static String errorName(int code) {
        return switch (code) {
            case ASE_OK -> "ASE_OK";
            case ASE_SUCCESS -> "ASE_SUCCESS";
            case ASE_NOT_PRESENT -> "ASE_NotPresent";
            case ASE_HW_MALFUNCTION -> "ASE_HWMalfunction";
            case ASE_INVALID_PARAMETER -> "ASE_InvalidParameter";
            case ASE_INVALID_MODE -> "ASE_InvalidMode";
            case ASE_SP_NOT_ADVANCING -> "ASE_SPNotAdvancing";
            case ASE_NO_CLOCK -> "ASE_NoClock";
            case ASE_NO_MEMORY -> "ASE_NoMemory";
            default -> "Unknown";
        };
    }
}
