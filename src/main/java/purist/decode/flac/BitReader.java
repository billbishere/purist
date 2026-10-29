package purist.decode.flac;

import java.nio.BufferUnderflowException;
import java.util.Objects;

/**
 * Reads a byte range most-significant bit first, as every FLAC field is stored. Bits are taken through a 64-bit
 * cache that is refilled a byte at a time, so a read never looks at a byte outside the range; running out of
 * bits throws {@link BufferUnderflowException}, which callers turn into "the frame continues past the data I
 * have".
 *
 * <p>Widths: {@code readUInt}/{@code readSInt} take 0 to 32 bits, {@code readULong}/{@code readSLong} 0 to 64,
 * {@code readRice} a parameter of 0 to 32. They are not checked; the frame decoder only passes widths that come
 * from validated fields.
 */
final class BitReader {

    private byte[] buf;
    private int pos;   // the next byte to load into the cache
    private int end;   // one past the last byte of the range
    private long cache; // unread bits, left-aligned; the bits below them are zero
    private int bits;  // how many bits of the cache are unread

    BitReader(byte[] buf, int off, int len) {
        reset(buf, off, len);
    }

    /** Starts reading another range, so one reader can serve every frame without allocating. */
    void reset(byte[] buf, int off, int len) {
        Objects.checkFromIndexSize(off, len, buf.length);
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
        this.cache = 0;
        this.bits = 0;
    }

    /** The next {@code n} bits as an unsigned number; for {@code n == 32} the result is the raw 32 bits. */
    int readUInt(int n) {
        if (n == 0) {
            return 0;
        }
        if (bits < n) {
            refill();
            if (bits < n) {
                throw new BufferUnderflowException();
            }
        }
        int v = (int) (cache >>> (64 - n));
        cache <<= n;
        bits -= n;
        return v;
    }

    /** The next {@code n} bits as a two's complement number. */
    int readSInt(int n) {
        if (n == 0) {
            return 0;
        }
        int shift = 32 - n;
        return readUInt(n) << shift >> shift;
    }

    /** The next {@code n} bits as an unsigned number; for {@code n == 64} the result is the raw 64 bits. */
    long readULong(int n) {
        if (n <= 32) {
            return readUInt(n) & 0xFFFF_FFFFL;
        }
        long high = readUInt(n - 32) & 0xFFFF_FFFFL;
        return high << 32 | (readUInt(32) & 0xFFFF_FFFFL);
    }

    /** The next {@code n} bits as a two's complement number (33 bits for a 32-bit stream's side channel). */
    long readSLong(int n) {
        if (n == 0) {
            return 0;
        }
        int shift = 64 - n;
        return readULong(n) << shift >> shift;
    }

    /** Counts zero bits up to the next one bit and consumes them and the one. */
    int readUnary() {
        int count = 0;
        while (true) {
            if (bits == 0) {
                refill();
                if (bits == 0) {
                    throw new BufferUnderflowException();
                }
            }
            if (cache == 0) { // every unread bit in the cache is zero
                count += bits;
                bits = 0;
                continue;
            }
            int zeros = Long.numberOfLeadingZeros(cache);
            cache = cache << zeros << 1; // two shifts: Java ignores a shift of 64
            bits -= zeros + 1;
            return count + zeros;
        }
    }

    /** A Rice code with parameter {@code k}: unary quotient, {@code k}-bit remainder, then zigzag-decoded. */
    long readRice(int k) {
        long folded = (long) readUnary() << k | (readUInt(k) & 0xFFFF_FFFFL);
        return folded >>> 1 ^ -(folded & 1);
    }

    /** Skips to the next byte boundary (the zero padding at the end of a frame's subframes). */
    void alignToByte() {
        int drop = bits & 7;
        cache <<= drop;
        bits -= drop;
    }

    /** The index in the array of the byte that holds the next unread bit. */
    int bytePosition() {
        return pos - ((bits + 7) >> 3);
    }

    private void refill() {
        while (bits <= 56 && pos < end) {
            cache |= (buf[pos++] & 0xFFL) << (56 - bits);
            bits += 8;
        }
    }
}
