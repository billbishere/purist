package purist.decode.aiff;

/**
 * The 80-bit IEEE 754 extended-precision number AIFF uses for its sample rate: a sign bit, a 15-bit exponent
 * biased by 16383, and a 64-bit mantissa whose integer bit is stored explicitly. The ten bytes are big-endian.
 *
 * <p>A mantissa has up to 64 significant bits and a {@code double} only 53, so a general value is rounded to
 * nearest-even. Every sample rate that matters (the whole numbers up to 2^53) converts exactly.
 */
public final class Extended80 {

    private static final int EXPONENT_BIAS = 16383;
    /** The mantissa is an integer scaled by 2^-63, since its binary point sits after the top bit. */
    private static final int MANTISSA_POINT = 63;
    private static final int INFINITE_EXPONENT = 0x7FFF;
    private static final long FRACTION_MASK = 0x7FFF_FFFF_FFFF_FFFFL;

    private Extended80() {}

    /**
     * Converts the ten bytes of an 80-bit extended number to a {@code double}.
     *
     * @param tenBytes the number, most significant byte first
     * @return the value; infinities and NaNs map to their {@code double} counterparts
     * @throws IllegalArgumentException if {@code tenBytes} is null or not exactly ten bytes
     */
    public static double toDouble(byte[] tenBytes) {
        if (tenBytes == null || tenBytes.length != 10) {
            throw new IllegalArgumentException("an 80-bit extended number is 10 bytes, got "
                    + (tenBytes == null ? "null" : tenBytes.length + " bytes"));
        }
        int signAndExponent = (tenBytes[0] & 0xFF) << 8 | (tenBytes[1] & 0xFF);
        boolean negative = (signAndExponent & 0x8000) != 0;
        int exponent = signAndExponent & 0x7FFF;
        long mantissa = 0;
        for (int i = 2; i < 10; i++) {
            mantissa = mantissa << 8 | (tenBytes[i] & 0xFFL);
        }

        double magnitude;
        if (exponent == INFINITE_EXPONENT) {
            magnitude = (mantissa & FRACTION_MASK) == 0 ? Double.POSITIVE_INFINITY : Double.NaN;
        } else if (mantissa == 0) {
            magnitude = 0.0;
        } else {
            // Denormals (exponent 0) share the scale of exponent 1.
            int scale = Math.max(exponent, 1) - EXPONENT_BIAS - MANTISSA_POINT;
            magnitude = Math.scalb(unsignedToDouble(mantissa), scale);
        }
        return negative ? -magnitude : magnitude;
    }

    /**
     * The unsigned 64-bit value {@code u} as the nearest {@code double}. Shifting right by one folds the lost
     * bit back in as a sticky bit, so the single rounding that follows is still correct.
     */
    private static double unsignedToDouble(long u) {
        if (u >= 0) {
            return u;
        }
        return ((u >>> 1) | (u & 1)) * 2.0;
    }
}
