package purist.testutil;

import java.util.Arrays;

/**
 * A most-significant-bit-first bit writer for building FLAC test fixtures. It shares no code with the decoder's
 * {@code BitReader}: it writes one bit at a time, which is slow but obviously right, and fixtures are small.
 */
public final class BitWriter {

    private byte[] buf = new byte[256];
    private long bits;

    /** Writes the low {@code n} bits of {@code value}, most significant first ({@code 0 <= n <= 64}). */
    public BitWriter bits(long value, int n) {
        if (n < 0 || n > 64) {
            throw new IllegalArgumentException("bit count " + n);
        }
        for (int i = n - 1; i >= 0; i--) {
            bit((int) (value >>> i) & 1);
        }
        return this;
    }

    /** Writes {@code value} as an {@code n}-bit two's complement number; it must fit. */
    public BitWriter signed(long value, int n) {
        if (!fitsSigned(value, n)) {
            throw new IllegalArgumentException(value + " does not fit in " + n + " signed bits");
        }
        return bits(value, n);
    }

    /** Writes {@code value} as an {@code n}-bit unsigned number; it must fit. */
    public BitWriter unsigned(long value, int n) {
        if (value < 0 || (n < 64 && value >>> n != 0)) {
            throw new IllegalArgumentException(value + " does not fit in " + n + " unsigned bits");
        }
        return bits(value, n);
    }

    /** {@code q} zero bits followed by a one bit. */
    public BitWriter unary(long q) {
        for (long i = 0; i < q; i++) {
            bit(0);
        }
        return bit(1);
    }

    /** A Rice code with parameter {@code k} of the zigzag-folded {@code value} (0, -1, 1, -2, ... map to 0, 1, 2, 3, ...). */
    public BitWriter rice(long value, int k) {
        long folded = value >= 0 ? value * 2 : -value * 2 - 1;
        unary(folded >>> k);
        return bits(folded, k); // the low k bits
    }

    /** Pads with zero bits to the next byte boundary. */
    public BitWriter align() {
        while ((bits & 7) != 0) {
            bit(0);
        }
        return this;
    }

    public BitWriter bit(int b) {
        int index = (int) (bits >>> 3);
        if (index == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        if (b != 0) {
            buf[index] |= (byte) (0x80 >>> (bits & 7));
        }
        bits++;
        return this;
    }

    /** Appends whole bytes; the writer must be byte-aligned. */
    public BitWriter bytes(byte[] b) {
        if ((bits & 7) != 0) {
            throw new IllegalStateException("not byte-aligned");
        }
        for (byte x : b) {
            bits(x & 0xFF, 8);
        }
        return this;
    }

    /** The number of bits written so far. */
    public long bitLength() {
        return bits;
    }

    /** The bytes written so far; a final partial byte is padded with zero bits. */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, (int) ((bits + 7) >>> 3));
    }

    /** True when {@code value} is representable as an {@code n}-bit two's complement number. */
    public static boolean fitsSigned(long value, int n) {
        if (n >= 64) {
            return true;
        }
        if (n == 0) {
            return value == 0;
        }
        long min = -(1L << (n - 1));
        long max = (1L << (n - 1)) - 1;
        return value >= min && value <= max;
    }
}
