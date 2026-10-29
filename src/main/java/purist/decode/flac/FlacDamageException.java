package purist.decode.flac;

import java.util.Objects;
import purist.decode.DecodeException;

/**
 * A {@link DecodeException} that proves the file is damaged, not merely that the decoder could not play it, and says
 * how. Tools that must tell damage from a decoder fault ({@link FlacVerify}, the real-library check) read
 * {@link #kind()} and never the message, which is written for the user and may change. A {@code DecodeException} that
 * is not a {@code FlacDamageException} (a reserved field value, say) makes no such claim.
 */
public final class FlacDamageException extends DecodeException {

    private static final long serialVersionUID = 1L;

    /** The kinds of damage the decoder can prove from the bytes alone. */
    public enum Kind {
        /** A frame's CRC-8 (header) or CRC-16 does not match its bytes. */
        CRC_MISMATCH,
        /** There is no {@code fLaC} marker (or too few bytes to hold one): not a FLAC stream. */
        NOT_FLAC,
        /**
         * A frame that does not end within twice STREAMINFO's maximum frame size (the decoder's cap, never less than
         * 64 KiB), or bytes where the next frame must start that are not a frame sync: sync is lost.
         */
        OVERSIZED_FRAME,
        /** The file ends inside the metadata, inside a frame, or before the samples STREAMINFO promises. */
        TRUNCATED
    }

    private final Kind kind;

    public FlacDamageException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind);
    }

    public FlacDamageException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}
