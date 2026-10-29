package purist.win;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileAttributesTest {

    private static final int FILE_ATTRIBUTE_DIRECTORY = 0x10;

    @TempDir
    Path dir;

    @Test
    void tempFileNotCloudOnly() throws IOException {
        Path file = Files.writeString(dir.resolve("local.flac"), "data");
        assertNotEquals(-1, FileAttributes.get(file));
        assertFalse(FileAttributes.isCloudOnly(file));
    }

    @Test
    void missingFileIsMinusOne() {
        assertEquals(-1, FileAttributes.get(dir.resolve("nope.flac")));
    }

    @Test
    void missingFileIsNotCloudOnly() {
        assertFalse(FileAttributes.isCloudOnly(dir.resolve("nope.flac")));
    }

    @Test
    void directoryBitIsReported() {
        assertTrue((FileAttributes.get(dir) & FILE_ATTRIBUTE_DIRECTORY) != 0);
    }

    @Test
    void nonAsciiNamesAreSupported() throws IOException {
        Path file = Files.writeString(dir.resolve("Béla Bartók – 音楽.flac"), "data");
        assertNotEquals(-1, FileAttributes.get(file));
    }

    @Test
    void pathsLongerThanMaxPathAreFound() throws IOException {
        // The JVM is not long-path aware: without the \\?\ prefix GetFileAttributesW reports these as missing.
        Path deep = dir;
        for (int i = 0; deep.toString().length() < 300; i++) {
            deep = deep.resolve("nested-directory-name-" + i);
        }
        Files.createDirectories(deep);
        Path file = Files.writeString(deep.resolve("track.flac"), "data");
        assertTrue(file.toString().length() > 260);
        assertNotEquals(-1, FileAttributes.get(file));
        assertFalse(FileAttributes.isCloudOnly(file));
    }

    @Test
    void win32PathPrefixesOnlyLongPaths() {
        String longTail = "a\\".repeat(150) + "track.flac";

        Path shortPath = Path.of("C:\\Music\\track.flac");
        assertEquals("C:\\Music\\track.flac", FileAttributes.win32Path(shortPath));

        assertEquals("\\\\?\\C:\\Music\\" + longTail,
                FileAttributes.win32Path(Path.of("C:\\Music\\" + longTail)));
        assertEquals("\\\\?\\C:\\Music\\" + longTail,
                FileAttributes.win32Path(Path.of("C:\\Music\\other\\..\\" + longTail)),
                "dot-dot segments are resolved because the prefix disables Win32's own handling");
        assertEquals("\\\\?\\UNC\\server\\share\\" + longTail,
                FileAttributes.win32Path(Path.of("\\\\server\\share\\" + longTail)));
        assertEquals("\\\\?\\C:\\Music\\" + longTail,
                FileAttributes.win32Path(Path.of("\\\\?\\C:\\Music\\" + longTail)),
                "an already extended path is left alone");
    }

    @Test
    void cloudPlaceholderBitsAreRecognised() {
        assertTrue(FileAttributes.isCloudOnly(0x400000), "RECALL_ON_DATA_ACCESS");
        assertTrue(FileAttributes.isCloudOnly(0x1000), "OFFLINE");
        assertTrue(FileAttributes.isCloudOnly(0x20 | 0x400000));
        assertFalse(FileAttributes.isCloudOnly(0x20), "ARCHIVE only");
        assertFalse(FileAttributes.isCloudOnly(0), "no attributes");
        assertFalse(FileAttributes.isCloudOnly(-1), "INVALID_FILE_ATTRIBUTES has every bit set");
    }
}
