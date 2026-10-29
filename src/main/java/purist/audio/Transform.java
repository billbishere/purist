package purist.audio;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete list of things done to a stream between decoder and DAC. Every one of them preserves
 * all sample bits (spec section 5).
 *
 * @param shift         left shift applied to each PCM sample: it is zero-padding of the LSBs
 * @param bitReverse    each DSD byte is bit-reversed because the source and driver bit orders differ
 * @param monoDuplicate a one-channel source is copied unchanged to both output channels
 */
public record Transform(int shift, boolean bitReverse, boolean monoDuplicate) {

    public Transform {
        if (shift < 0) {
            throw new IllegalArgumentException("a right shift would discard sample bits: " + shift);
        }
    }

    /**
     * True when no sample bit is moved or changed. Duplicating a mono channel does not count: it leaves
     * every sample bit as it was.
     */
    public boolean isPassThrough() {
        return shift == 0 && !bitReverse;
    }

    /** A short human-readable description for the signal-path display. */
    public String describe() {
        List<String> parts = new ArrayList<>(3);
        if (shift != 0) {
            parts.add("zero-pad " + shift + " LSBs");
        }
        if (bitReverse) {
            parts.add("DSD bit-order reversal");
        }
        if (parts.isEmpty()) {
            parts.add("pass-through");
        }
        if (monoDuplicate) {
            parts.add("mono→both channels");
        }
        return String.join(", ", parts);
    }
}
