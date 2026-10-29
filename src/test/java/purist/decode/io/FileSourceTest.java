package purist.decode.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSourceTest {

    @TempDir
    Path dir;

    /** Byte {@code i} of the pattern file: deterministic, and never constant across a 4-byte window. */
    private static byte patternByte(long i) {
        return (byte) (i * 31 + 7);
    }

    private Path patternFile(String name, int size) throws IOException {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = patternByte(i);
        }
        Path p = dir.resolve(name);
        Files.write(p, b);
        return p;
    }

    private Path bytesFile(int... bytes) throws IOException {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) bytes[i];
        }
        Path p = dir.resolve("b.bin");
        Files.write(p, b);
        return p;
    }

    @Test
    void littleEndianReaders() throws IOException {
        Path p = bytesFile(0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0);
        try (FileSource s = FileSource.open(p)) {
            assertEquals(0x3412, s.u16le());
            s.seek(0);
            assertEquals(0x78563412L, s.u32le());
            s.seek(0);
            assertEquals(0xF0DEBC9A78563412L, s.u64le());
        }
    }

    @Test
    void bigEndianReaders() throws IOException {
        Path p = bytesFile(0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0);
        try (FileSource s = FileSource.open(p)) {
            assertEquals(0x1234, s.u16be());
            s.seek(0);
            assertEquals(0x12345678L, s.u32be());
            s.seek(0);
            assertEquals(0x123456789ABCDEF0L, s.u64be());
        }
    }

    @Test
    void unsignedValuesStayPositive() throws IOException {
        Path p = bytesFile(0xFF, 0xFF, 0xFF, 0xFF, 0x80, 0x00);
        try (FileSource s = FileSource.open(p)) {
            assertEquals(255, s.u8());
            s.seek(0);
            assertEquals(0xFFFF, s.u16le());
            s.seek(0);
            assertEquals(0xFFFF, s.u16be());
            s.seek(0);
            assertEquals(4294967295L, s.u32le());
            s.seek(0);
            assertEquals(4294967295L, s.u32be());
            s.seek(4);
            assertEquals(0x80, s.u8());
        }
    }

    @Test
    void fourccBytesSkipAndPosition() throws IOException {
        Path p = bytesFile('R', 'I', 'F', 'F', 1, 2, 3, 4, 5, 6, 7, 8, 9);
        try (FileSource s = FileSource.open(p)) {
            assertEquals(13, s.size());
            assertEquals(0, s.position());
            assertEquals("RIFF", s.fourcc());
            assertEquals(4, s.position());
            assertArrayEquals(new byte[] {1, 2, 3}, s.bytes(3));
            assertEquals(7, s.position());
            s.skip(2);
            assertEquals(9, s.position());
            assertEquals(6, s.u8());
            s.skip(-3);
            assertEquals(7, s.position());
            assertEquals(4, s.u8());
        }
    }

    @Test
    void fourccIsLatin1() throws IOException {
        Path p = bytesFile('i', 'd', '3', ' ', 0xE9, 'A', 'B', 'C');
        try (FileSource s = FileSource.open(p)) {
            assertEquals("id3 ", s.fourcc());
            assertEquals("éABC", s.fourcc());
        }
    }

    /** Loads the first 64 KiB block, so that a following seek stays inside it. */
    private static void loadFirstBlock(FileSource s) throws IOException {
        s.seek(0);
        s.u8();
    }

    @Test
    void sequentialMultiByteReadsAcrossTheBufferBoundary() throws IOException {
        int size = 3 * 65536 + 1000;
        Path p = patternFile("big.bin", size);
        try (FileSource s = FileSource.open(p)) {
            assertEquals(size, s.size());
            // A 5-byte step (u8, then u32le) does not divide 65536, so the reads land on every offset
            // relative to the three buffer boundaries.
            for (long pos = 0; pos + 5 <= size; pos += 5) {
                assertEquals(patternByte(pos) & 0xFF, s.u8(), "u8 at " + pos);
                long expected = 0;
                for (int b = 4; b >= 1; b--) {
                    expected = (expected << 8) | (patternByte(pos + b) & 0xFF);
                }
                assertEquals(expected, s.u32le(), "u32 at " + (pos + 1));
                assertEquals(pos + 5, s.position());
            }
        }
    }

    @Test
    void everyWidthStraddlesTheBoundary() throws IOException {
        Path p = patternFile("big.bin", 2 * 65536 + 100);
        try (FileSource s = FileSource.open(p)) {
            for (int back = 1; back < 8; back++) {
                long start = 65536 - back;
                loadFirstBlock(s);
                s.seek(start);
                assertEquals(((patternByte(start) & 0xFF) << 8) | (patternByte(start + 1) & 0xFF), s.u16be());
                loadFirstBlock(s);
                s.seek(start);
                assertEquals(((patternByte(start + 1) & 0xFF) << 8) | (patternByte(start) & 0xFF), s.u16le());
                long be = 0;
                long le = 0;
                for (int b = 0; b < 8; b++) {
                    be = (be << 8) | (patternByte(start + b) & 0xFF);
                    le |= (patternByte(start + b) & 0xFFL) << (8 * b);
                }
                loadFirstBlock(s);
                s.seek(start);
                assertEquals(be, s.u64be());
                loadFirstBlock(s);
                s.seek(start);
                assertEquals(le, s.u64le());
                loadFirstBlock(s);
                s.seek(start);
                assertEquals(be >>> 32, s.u32be());
                loadFirstBlock(s);
                s.seek(start);
                assertEquals(le & 0xFFFFFFFFL, s.u32le());
                loadFirstBlock(s);
                s.seek(start);
                assertEquals("" + (char) (patternByte(start) & 0xFF) + (char) (patternByte(start + 1) & 0xFF)
                        + (char) (patternByte(start + 2) & 0xFF) + (char) (patternByte(start + 3) & 0xFF),
                        s.fourcc());
                assertEquals(start + 4, s.position());
            }
        }
    }

    @Test
    void bulkReadSpansSeveralBuffers() throws IOException {
        int size = 300_000;
        Path p = patternFile("big.bin", size);
        try (FileSource s = FileSource.open(p)) {
            s.seek(100);
            byte[] b = new byte[10 + 250_000 + 10];
            assertEquals(250_000, s.read(b, 10, 250_000));
            assertEquals(250_100, s.position());
            for (int i = 0; i < 250_000; i++) {
                assertEquals(patternByte(100 + i), b[10 + i], "at " + i);
            }
            assertEquals(0, b[0]);
            assertEquals(0, b[b.length - 1]);
        }
    }

    @Test
    void bulkReadStopsAtTheEndAndThenReportsIt() throws IOException {
        Path p = patternFile("small.bin", 1000);
        try (FileSource s = FileSource.open(p)) {
            s.seek(900);
            byte[] b = new byte[500];
            assertEquals(100, s.read(b, 0, 500));
            assertEquals(1000, s.position());
            assertEquals(-1, s.read(b, 0, 500));
            assertEquals(0, s.read(b, 0, 0));
        }
    }

    @Test
    void seekBackwardsAndFarAwayAreBothExact() throws IOException {
        int size = 400_000;
        Path p = patternFile("big.bin", size);
        try (FileSource s = FileSource.open(p)) {
            s.seek(250_000);
            assertEquals(patternByte(250_000) & 0xFF, s.u8());
            s.seek(10);                               // far outside the buffered window
            assertEquals(patternByte(10) & 0xFF, s.u8());
            s.seek(250_001);                          // outside again
            assertEquals(patternByte(250_001) & 0xFF, s.u8());
            s.seek(250_000);                          // one byte back, inside the window
            assertEquals(250_000, s.position());
            assertEquals(patternByte(250_000) & 0xFF, s.u8());
            s.seek(size - 1);
            assertEquals(patternByte(size - 1) & 0xFF, s.u8());
            assertEquals(size, s.position());
        }
    }

    @Test
    void readingPastTheEndThrowsEof() throws IOException {
        Path p = bytesFile(1, 2, 3);
        try (FileSource s = FileSource.open(p)) {
            s.skip(3);
            assertThrows(EOFException.class, s::u8);
        }
        try (FileSource s = FileSource.open(p)) {
            s.skip(2);
            assertThrows(EOFException.class, s::u16le);
        }
        try (FileSource s = FileSource.open(p)) {
            assertThrows(EOFException.class, s::u32be);
        }
        try (FileSource s = FileSource.open(p)) {
            assertThrows(EOFException.class, s::u64le);
        }
        try (FileSource s = FileSource.open(p)) {
            assertThrows(EOFException.class, () -> s.bytes(4));
        }
    }

    @Test
    void seekPastTheEndIsAllowedAndReadsAsEof() throws IOException {
        Path p = bytesFile(1, 2, 3);
        try (FileSource s = FileSource.open(p)) {
            s.seek(1000);
            assertEquals(1000, s.position());
            assertThrows(EOFException.class, s::u8);
            assertEquals(-1, s.read(new byte[4], 0, 4));
            s.seek(1);
            assertEquals(2, s.u8());
        }
    }

    @Test
    void negativePositionsAreRefused() throws IOException {
        Path p = bytesFile(1, 2, 3);
        try (FileSource s = FileSource.open(p)) {
            assertThrows(IllegalArgumentException.class, () -> s.seek(-1));
            assertThrows(IllegalArgumentException.class, () -> s.skip(-1));
            assertThrows(IllegalArgumentException.class, () -> s.bytes(-1));
        }
    }

    @Test
    void emptyFile() throws IOException {
        Path p = dir.resolve("empty.bin");
        Files.write(p, new byte[0]);
        try (FileSource s = FileSource.open(p)) {
            assertEquals(0, s.size());
            assertThrows(EOFException.class, s::u8);
            assertEquals(-1, s.read(new byte[1], 0, 1));
            assertEquals(0, s.bytes(0).length);
        }
    }

    @Test
    void missingFileThrowsNoSuchFile() {
        assertThrows(NoSuchFileException.class, () -> FileSource.open(dir.resolve("nope.bin")));
    }

    @Test
    void closeIsIdempotentAndStopsReads() throws IOException {
        Path p = patternFile("small.bin", 100_000);
        FileSource s = FileSource.open(p);
        s.u8();
        s.close();
        s.close();
        assertThrows(IOException.class, s::u8);
        assertThrows(IOException.class, () -> s.read(new byte[10], 0, 10));
    }

    @Test
    void nonAsciiFileNameOpens() throws IOException {
        Path p = dir.resolve("Motörhead ö 日本語 - Ж.bin");
        Files.write(p, new byte[] {9, 8, 7});
        try (FileSource s = FileSource.open(p)) {
            assertEquals(3, s.size());
            assertEquals(9, s.u8());
        }
    }

    @Test
    void pathLongerThan260CharactersOpens() throws IOException {
        Path d = dir;
        StringBuilder seg = new StringBuilder();
        while (seg.length() < 60) {
            seg.append("segmenté");
        }
        while (d.toString().length() < 330) {
            d = d.resolve(seg.toString());
        }
        Files.createDirectories(d);
        Path p = d.resolve("long.bin");
        Files.write(p, new byte[] {5, 6, 7, 8});
        assertTrue(p.toString().length() > 260);
        try (FileSource s = FileSource.open(p)) {
            assertEquals(4, s.size());
            assertEquals(0x08070605L, s.u32le());
        }
    }
}
