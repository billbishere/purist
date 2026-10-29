package purist.audio;

/**
 * Thrown when a source cannot reach the DAC with every sample bit intact. The message is the stated
 * reason shown when the track is skipped; a track is never converted instead.
 */
public final class NotBitPerfectException extends Exception {

    private static final long serialVersionUID = 1L;

    public NotBitPerfectException(String reason) {
        super(reason);
    }
}
