package purist.asio;

/** An ASIO (or COM) call that failed. */
public final class AsioException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String op;
    private final String driverMessage;

    /**
     * @param op the failing operation, for example {@code "createBuffers"}
     * @param code the ASIOError, or for {@code CoCreateInstance} the failing HRESULT
     * @param driverMessage what the driver's {@code getErrorMessage} said, or null / blank for nothing
     */
    public AsioException(String op, int code, String driverMessage) {
        super(format(op, code, driverMessage));
        this.code = code;
        this.op = op;
        this.driverMessage = driverMessage == null ? "" : driverMessage.strip();
    }

    /** The error code the call returned, see {@link AsioConstants#errorName}. */
    public int code() {
        return code;
    }

    /** The failing operation, as given to the constructor. */
    String op() {
        return op;
    }

    /** What the driver's {@code getErrorMessage} said; empty if nothing. */
    String driverMessage() {
        return driverMessage;
    }

    private static String format(String op, int code, String driverMessage) {
        String base = "%s failed: %s (%d)".formatted(op, AsioConstants.errorName(code), code);
        return driverMessage == null || driverMessage.isBlank() ? base : base + " — " + driverMessage;
    }
}
