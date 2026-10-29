package purist.decode.flac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;
import purist.testutil.FlacWriter;

class CrcTest {

    private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

    @Test
    void crc8CheckValue() {
        assertEquals(0xF4, Crc.crc8(CHECK, 0, CHECK.length));
    }

    @Test
    void crc16CheckValue() {
        assertEquals(0xFEE8, Crc.crc16(CHECK, 0, CHECK.length));
    }

    @Test
    void emptyRangeIsZero() {
        assertEquals(0, Crc.crc8(CHECK, 3, 0));
        assertEquals(0, Crc.crc16(CHECK, 3, 0));
    }

    @Test
    void honoursOffsetAndLength() {
        byte[] padded = new byte[CHECK.length + 7];
        padded[0] = (byte) 0xAA;
        padded[1] = 0x55;
        padded[2] = (byte) 0xFF;
        System.arraycopy(CHECK, 0, padded, 3, CHECK.length);
        padded[padded.length - 1] = 0x12;
        assertEquals(0xF4, Crc.crc8(padded, 3, CHECK.length));
        assertEquals(0xFEE8, Crc.crc16(padded, 3, CHECK.length));
    }

    @Test
    void matchesBitwiseReferenceOnRandomData() {
        Random rnd = new Random(9639);
        for (int round = 0; round < 500; round++) {
            byte[] b = new byte[rnd.nextInt(300)];
            rnd.nextBytes(b);
            int off = b.length == 0 ? 0 : rnd.nextInt(b.length);
            int len = rnd.nextInt(b.length - off + 1);
            assertEquals(FlacWriter.crc8(b, off, len), Crc.crc8(b, off, len), "crc8 round " + round);
            assertEquals(FlacWriter.crc16(b, off, len), Crc.crc16(b, off, len), "crc16 round " + round);
        }
    }

    @Test
    void frameWithItsOwnCrcAppendedChecksToZero() {
        // A CRC with init 0 and no final XOR leaves a zero remainder over data followed by its own CRC.
        byte[] b = new byte[CHECK.length + 2];
        System.arraycopy(CHECK, 0, b, 0, CHECK.length);
        b[CHECK.length] = (byte) 0xFE;
        b[CHECK.length + 1] = (byte) 0xE8;
        assertEquals(0, Crc.crc16(b, 0, b.length));
        byte[] c = new byte[CHECK.length + 1];
        System.arraycopy(CHECK, 0, c, 0, CHECK.length);
        c[CHECK.length] = (byte) 0xF4;
        assertEquals(0, Crc.crc8(c, 0, c.length));
    }
}
