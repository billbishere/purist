package purist.wasapi;

/** The HRESULTs Purist tells apart, and the words for the device errors among them. */
final class WasapiErrors {

    static final int S_OK = 0;
    static final int AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004;
    static final int AUDCLNT_E_BUFFER_TOO_LARGE = 0x88890006;
    static final int AUDCLNT_E_UNSUPPORTED_FORMAT = 0x88890008;
    static final int AUDCLNT_E_DEVICE_IN_USE = 0x8889000A;
    static final int AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED = 0x8889000E;
    static final int AUDCLNT_E_ENDPOINT_CREATE_FAILED = 0x8889000F;
    static final int AUDCLNT_E_BUFFER_SIZE_ERROR = 0x88890018;
    static final int AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED = 0x88890019;
    static final int AUDCLNT_E_INVALID_DEVICE_PERIOD = 0x88890020;
    static final int AUDCLNT_E_RESOURCES_INVALIDATED = 0x88890026;
    /** {@code HRESULT_FROM_WIN32(ERROR_NOT_FOUND)}: {@code GetDevice} for an endpoint that is not there. */
    static final int E_NOTFOUND = 0x80070490;
    static final int E_INVALIDARG = 0x80070057;

    private WasapiErrors() {
    }

    /** True for the codes that mean the endpoint has gone (unplugged, switched off, or its driver reset). */
    static boolean isDeviceGone(int hr) {
        return hr == AUDCLNT_E_DEVICE_INVALIDATED || hr == AUDCLNT_E_RESOURCES_INVALIDATED || hr == E_NOTFOUND
                || hr == AUDCLNT_E_ENDPOINT_CREATE_FAILED;
    }

    /**
     * The message for an HRESULT that says the device cannot be used at all, as opposed to a refusal of one
     * format; null for any other code.
     */
    static String deviceProblem(int hr, String device) {
        if (isDeviceGone(hr)) {
            return device + " is not available — check it is on and connected.";
        }
        if (hr == AUDCLNT_E_DEVICE_IN_USE) {
            return device + " is in use by another app in exclusive mode";
        }
        if (hr == AUDCLNT_E_EXCLUSIVE_MODE_NOT_ALLOWED) {
            return device + " does not allow exclusive mode — turn on \"Allow applications to take exclusive"
                    + " control of this device\" in the Windows sound settings";
        }
        return null;
    }

    /** The name of a code Purist meets in {@code Initialize}, with the number: {@code "UNSUPPORTED_FORMAT (0x88890008)"}. */
    static String describe(int hr) {
        String name = switch (hr) {
            case AUDCLNT_E_UNSUPPORTED_FORMAT -> "AUDCLNT_E_UNSUPPORTED_FORMAT";
            case AUDCLNT_E_BUFFER_SIZE_ERROR -> "AUDCLNT_E_BUFFER_SIZE_ERROR";
            case AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED -> "AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED";
            case AUDCLNT_E_INVALID_DEVICE_PERIOD -> "AUDCLNT_E_INVALID_DEVICE_PERIOD";
            case E_INVALIDARG -> "E_INVALIDARG";
            default -> null;
        };
        String code = "HRESULT 0x%08X".formatted(hr);
        return name == null ? code : name + ", " + code;
    }
}
