package purist.wasapi;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * An integer-PCM {@code WAVEFORMATEXTENSIBLE}, the only kind of format Purist ever offers a device. Samples are
 * left-justified in their container: with {@code validBits < containerBits} the low bits are zero padding.
 *
 * @param rate          frames per second
 * @param channels      channel count (front left and right for 2)
 * @param containerBits bits each sample occupies: 16, 24 or 32
 * @param validBits     bits that carry the sample, at most {@code containerBits}
 */
record WaveFormat(int rate, int channels, int containerBits, int validBits) {

    /** {@code sizeof(WAVEFORMATEXTENSIBLE)}. */
    static final long BYTES = 40;

    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final short WAVE_FORMAT_EXTENSIBLE = (short) 0xFFFE;
    private static final short EXTENSIBLE_EXTRA = 22;
    /** KSDATAFORMAT_SUBTYPE_PCM, {00000001-0000-0010-8000-00AA00389B71}, as stored. */
    private static final byte[] SUBTYPE_PCM = {
        1, 0, 0, 0, 0, 0, 0x10, 0, (byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71,
    };

    WaveFormat {
        if (rate < 1 || channels < 1 || channels > 2) {
            throw new IllegalArgumentException("unsupported shape: " + rate + " Hz, " + channels + " channels");
        }
        if (containerBits != 16 && containerBits != 24 && containerBits != 32) {
            throw new IllegalArgumentException("unsupported container: " + containerBits + " bits");
        }
        if (validBits < 1 || validBits > containerBits) {
            throw new IllegalArgumentException(validBits + " valid bits do not fit a " + containerBits
                    + "-bit container");
        }
    }

    /** Bytes per frame ({@code nBlockAlign}). */
    int blockAlign() {
        return channels * containerBits / 8;
    }

    /** {@code "16-bit"}, {@code "24-bit"}, {@code "32-bit"}, or {@code "24-in-32"} when the container is padded. */
    String describe() {
        return validBits == containerBits ? containerBits + "-bit" : validBits + "-in-" + containerBits;
    }

    /** Writes the 40-byte structure at the start of {@code s}. */
    void writeTo(MemorySegment s) {
        s.set(LE_SHORT, 0, WAVE_FORMAT_EXTENSIBLE);
        s.set(LE_SHORT, 2, (short) channels);
        s.set(LE_INT, 4, rate);
        s.set(LE_INT, 8, rate * blockAlign());
        s.set(LE_SHORT, 12, (short) blockAlign());
        s.set(LE_SHORT, 14, (short) containerBits);
        s.set(LE_SHORT, 16, EXTENSIBLE_EXTRA);
        s.set(LE_SHORT, 18, (short) validBits);
        s.set(LE_INT, 20, channels == 2 ? 0x3 : 0x4); // FRONT_LEFT | FRONT_RIGHT, or FRONT_CENTER
        MemorySegment.copy(MemorySegment.ofArray(SUBTYPE_PCM), 0, s, 24, SUBTYPE_PCM.length);
    }
}
