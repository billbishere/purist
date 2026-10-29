package purist.decode;

import java.io.IOException;

/**
 * A well-formed file that Purist will not play, with the reason it is skipped (for example float WAV, which is
 * not bit-perfect to an integer DAC). The reason is also the exception message.
 */
public class UnsupportedFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    public UnsupportedFormatException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** The text shown next to the greyed-out track. */
    public String reason() {
        return reason;
    }
}
