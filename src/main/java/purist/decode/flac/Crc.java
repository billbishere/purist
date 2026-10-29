package purist.decode.flac;

/**
 * The two CRCs of a FLAC frame (RFC 9639 §9.1.8 and §9.3): CRC-8 with polynomial {@code x^8 + x^2 + x + 1}
 * ({@code 0x07}) over the frame header, and CRC-16 with polynomial {@code x^16 + x^15 + x^2 + 1}
 * ({@code 0x8005}) over the whole frame. Both start from 0, are not reflected and have no final XOR.
 */
final class Crc {

    private static final int[] CRC8 = new int[256];
    private static final int[] CRC16 = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c8 = i;
            int c16 = i << 8;
            for (int bit = 0; bit < 8; bit++) {
                c8 = (c8 & 0x80) != 0 ? (c8 << 1) ^ 0x07 : c8 << 1;
                c16 = (c16 & 0x8000) != 0 ? (c16 << 1) ^ 0x8005 : c16 << 1;
            }
            CRC8[i] = c8 & 0xFF;
            CRC16[i] = c16 & 0xFFFF;
        }
    }

    private Crc() {}

    static int crc8(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off, end = off + len; i < end; i++) {
            crc = CRC8[crc ^ (b[i] & 0xFF)];
        }
        return crc;
    }

    static int crc16(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off, end = off + len; i < end; i++) {
            crc = ((crc << 8) & 0xFFFF) ^ CRC16[(crc >>> 8) ^ (b[i] & 0xFF)];
        }
        return crc;
    }
}
