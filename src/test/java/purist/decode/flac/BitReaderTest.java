package purist.decode.flac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.BufferUnderflowException;
import java.util.Random;
import org.junit.jupiter.api.Test;
import purist.testutil.BitWriter;

class BitReaderTest {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    @Test
    void readsMostSignificantBitFirstAcrossBytes() {
        BitReader r = new BitReader(bytes(0b1011_0011, 0b1100_0001), 0, 2);
        assertEquals(0b1, r.readUInt(1));
        assertEquals(0b011, r.readUInt(3));
        assertEquals(0b0011_1100, r.readUInt(8));
        assertEquals(0b0001, r.readUInt(4));
    }

    @Test
    void zeroWidthReadsConsumeNothing() {
        BitReader r = new BitReader(bytes(0xFF), 0, 1);
        assertEquals(0, r.readUInt(0));
        assertEquals(0, r.readSInt(0));
        assertEquals(0, r.readULong(0));
        assertEquals(0, r.readSLong(0));
        assertEquals(0xFF, r.readUInt(8));
    }

    @Test
    void fullWidthReads() {
        BitReader r = new BitReader(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x80, 0, 0, 0, 0, 0, 0, 1), 0, 12);
        assertEquals(-1, r.readUInt(32));
        assertEquals(0x8000_0000_0000_0001L, r.readULong(64));
    }

    @Test
    void signedReadsExtendTheSign() {
        BitReader r = new BitReader(bytes(0b1110_0111, 0xFF, 0xFF, 0xFF, 0xFF, 0x80), 0, 6);
        assertEquals(-1, r.readSInt(3));     // 111
        assertEquals(1, r.readSInt(3));      // 001
        // 2 bits "11" + 32 bits of ones + "1" = 35 bits of ones: -1 as a 35-bit number
        assertEquals(-1L, r.readSLong(35));
        assertEquals(0, r.readUInt(1));
    }

    @Test
    void thirtyThreeBitValues() {
        long[] values = {(1L << 32) - 1, -(1L << 32), -1L, 0L, 1L << 31, -(1L << 31) - 1};
        BitWriter w = new BitWriter();
        for (long v : values) {
            w.bit(1).signed(v, 33);
        }
        byte[] b = w.toByteArray();
        BitReader r = new BitReader(b, 0, b.length);
        for (long v : values) {
            assertEquals(1, r.readUInt(1));
            assertEquals(v, r.readSLong(33));
        }
    }

    @Test
    void unaryRunsLongerThanTheCache() {
        BitWriter w = new BitWriter().bits(0b101, 3).unary(0).unary(7).unary(200).unary(63).unary(64).unary(1);
        byte[] b = w.toByteArray();
        BitReader r = new BitReader(b, 0, b.length);
        assertEquals(0b101, r.readUInt(3));
        assertEquals(0, r.readUnary());
        assertEquals(7, r.readUnary());
        assertEquals(200, r.readUnary());
        assertEquals(63, r.readUnary());
        assertEquals(64, r.readUnary());
        assertEquals(1, r.readUnary());
    }

    @Test
    void riceDecodesZigzag() {
        long[] values = {0, -1, 1, -2, 2, 1000, -1000, (1L << 31) - 1, -(1L << 31), 123456789012L};
        int[] ks = {0, 1, 2, 3, 0, 8, 9, 30, 30, 30};
        BitWriter w = new BitWriter();
        for (int i = 0; i < values.length; i++) {
            w.rice(values[i], ks[i]);
        }
        byte[] b = w.toByteArray();
        BitReader r = new BitReader(b, 0, b.length);
        for (int i = 0; i < values.length; i++) {
            assertEquals(values[i], r.readRice(ks[i]), "value " + i);
        }
    }

    @Test
    void alignAndBytePosition() {
        byte[] b = bytes(0x00, 0xA5, 0x5A, 0xC3, 0x3C, 0x00);
        BitReader r = new BitReader(b, 1, 4);
        assertEquals(1, r.bytePosition());
        r.alignToByte();
        assertEquals(1, r.bytePosition());
        assertEquals(0xA, r.readUInt(4));
        assertEquals(1, r.bytePosition());
        r.alignToByte();
        assertEquals(2, r.bytePosition());
        assertEquals(0x5A, r.readUInt(8));
        assertEquals(3, r.bytePosition());
        assertEquals(1, r.readUInt(1));
        r.alignToByte();
        assertEquals(4, r.bytePosition());
        assertEquals(0x3C, r.readUInt(8));
        assertEquals(5, r.bytePosition());
    }

    @Test
    void readingPastTheEndUnderflows() {
        byte[] b = bytes(0xFF, 0x00, 0x00, 0x77);
        BitReader r = new BitReader(b, 0, 3); // the 0x77 is outside the range
        assertEquals(0xFF, r.readUInt(8));
        assertThrows(BufferUnderflowException.class, r::readUnary);
        BitReader s = new BitReader(b, 0, 2);
        assertEquals(0xFF0, s.readUInt(12));
        assertThrows(BufferUnderflowException.class, () -> s.readUInt(5));
        BitReader t = new BitReader(b, 3, 1);
        assertThrows(BufferUnderflowException.class, () -> t.readULong(9));
        assertThrows(IndexOutOfBoundsException.class, () -> new BitReader(b, 2, 3));
    }

    @Test
    void randomRoundTrip() {
        Random rnd = new Random(42);
        for (int round = 0; round < 200; round++) {
            int ops = 1 + rnd.nextInt(300);
            int[] kind = new int[ops];
            int[] width = new int[ops];
            long[] value = new long[ops];
            BitWriter w = new BitWriter();
            int lead = rnd.nextInt(5);
            w.bytes(new byte[lead]);
            for (int i = 0; i < ops; i++) {
                kind[i] = rnd.nextInt(5);
                switch (kind[i]) {
                    case 0 -> { // unsigned int
                        width[i] = rnd.nextInt(33);
                        value[i] = width[i] == 0 ? 0 : rnd.nextLong() >>> (64 - width[i]);
                        w.bits(value[i], width[i]);
                    }
                    case 1 -> { // signed long
                        width[i] = 1 + rnd.nextInt(64);
                        value[i] = rnd.nextLong() >> (64 - width[i]);
                        w.signed(value[i], width[i]);
                    }
                    case 2 -> { // unary
                        value[i] = rnd.nextInt(4) == 0 ? rnd.nextInt(300) : rnd.nextInt(10);
                        w.unary(value[i]);
                    }
                    case 3 -> { // rice
                        width[i] = rnd.nextInt(31);
                        value[i] = (rnd.nextLong() >> 40) >> Math.max(0, 20 - width[i]);
                        w.rice(value[i], width[i]);
                    }
                    default -> { // align
                        w.align();
                    }
                }
            }
            byte[] b = w.toByteArray();
            BitReader r = new BitReader(b, lead, b.length - lead);
            for (int i = 0; i < ops; i++) {
                String at = "round " + round + " op " + i;
                switch (kind[i]) {
                    case 0 -> assertEquals(value[i], r.readUInt(width[i]) & 0xFFFFFFFFL, at);
                    case 1 -> assertEquals(value[i], r.readSLong(width[i]), at);
                    case 2 -> assertEquals(value[i], r.readUnary(), at);
                    case 3 -> assertEquals(value[i], r.readRice(width[i]), at);
                    default -> r.alignToByte();
                }
            }
            r.alignToByte();
            assertEquals(b.length, r.bytePosition(), "round " + round);
        }
    }
}
