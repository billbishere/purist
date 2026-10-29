package purist.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScanErrorLogTest {

    @TempDir
    Path tmp;

    @Test
    void appendWritesIsoTimePathAndMessage() throws Exception {
        Path file = tmp.resolve("logs").resolve("scan-errors.log");
        ScanErrorLog log = new ScanErrorLog(file);
        log.append(Path.of("E:\\Music\\a.flac"), "bad header");

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertTrue(Pattern.matches("\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z E:\\\\Music\\\\a\\.flac: bad header", lines.get(0)),
                lines.get(0));
    }

    @Test
    void tailReturnsTheLastLinesInOrder() {
        ScanErrorLog log = new ScanErrorLog(tmp.resolve("e.log"));
        for (int i = 1; i <= 5; i++) {
            log.append(Path.of("E:\\f" + i + ".flac"), "m" + i);
        }
        List<String> tail = log.tail(2);
        assertEquals(2, tail.size());
        assertTrue(tail.get(0).endsWith("E:\\f4.flac: m4"));
        assertTrue(tail.get(1).endsWith("E:\\f5.flac: m5"));
        assertEquals(5, log.tail(100).size());
    }

    @Test
    void tailOfAMissingFileIsEmpty() {
        assertTrue(new ScanErrorLog(tmp.resolve("none.log")).tail(10).isEmpty());
    }

    @Test
    void aMessageWithNewlinesStaysOnOneLine() throws Exception {
        Path file = tmp.resolve("e.log");
        ScanErrorLog log = new ScanErrorLog(file);
        log.append(Path.of("E:\\a.flac"), "first\nsecond\r\nthird");
        assertEquals(1, Files.readAllLines(file).size());
    }
}
