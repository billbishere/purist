package purist.audio;

/**
 * The audio device could not be opened, started or kept: it is busy, gone, or its driver misbehaved. This is
 * distinct from {@link NotBitPerfectException}, which says the device works but cannot play this source
 * bit-perfectly. The message is shown to the user.
 */
public final class AudioDeviceException extends Exception {

    private static final long serialVersionUID = 1L;

    /** @param cause the underlying failure, or null when the device simply reported an error code */
    public AudioDeviceException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
