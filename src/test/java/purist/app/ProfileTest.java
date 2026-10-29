package purist.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileTest {

    @Test
    void honoursSystemProperty(@TempDir Path tmp) {
        String before = System.getProperty(Profile.PROPERTY);
        Path dir = tmp.resolve("p");
        System.setProperty(Profile.PROPERTY, dir.toString());
        try {
            Profile p = Profile.current();
            assertEquals(dir, p.dir());
            assertTrue(Files.isDirectory(dir));
            assertEquals(dir.resolve("library.db"), p.libraryDb());
            assertEquals(dir.resolve("settings.properties"), p.settings());
            assertEquals(dir.resolve("purist.log"), p.log());
            assertEquals(dir.resolve("scan-errors.log"), p.scanErrors());
            assertTrue(Files.isDirectory(p.playlists()));
            assertTrue(Files.isDirectory(p.thumbs()));
        } finally {
            if (before == null) {
                System.clearProperty(Profile.PROPERTY);
            } else {
                System.setProperty(Profile.PROPERTY, before);
            }
        }
    }
}
