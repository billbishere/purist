package purist.decode;

import java.io.IOException;

/**
 * A file that claims to be a supported format but is damaged or inconsistent: a bad magic number, a missing
 * mandatory chunk, header fields that contradict each other. Contrast {@link UnsupportedFormatException}, which
 * says the file is well formed but cannot be played bit-perfectly. The message is shown to the user.
 */
public class DecodeException extends IOException {

    private static final long serialVersionUID = 1L;

    public DecodeException(String message) {
        super(message);
    }

    public DecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
