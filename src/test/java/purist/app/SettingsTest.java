package purist.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    @Test
    void roundTrip(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("settings.properties");
        Settings s = Settings.load(f);
        assertEquals("none", s.get("output.driver", "none"));
        s.put("output.driver", "Topping USB Audio Device");
        s.put("path", "E:\\Music\\Motörhead ö");
        s.put("window.w", "1280");
        s.put("bad.int", "12x");
        s.save();

        Settings t = Settings.load(f);
        assertEquals("Topping USB Audio Device", t.get("output.driver", "none"));
        assertEquals("E:\\Music\\Motörhead ö", t.get("path", null));
        assertEquals(1280, t.getInt("window.w", 0));
        assertEquals(7, t.getInt("bad.int", 7));
        assertEquals(7, t.getInt("missing", 7));

        t.put("path", null);
        t.save();
        assertEquals("gone", Settings.load(f).get("path", "gone"));
    }

    @Test
    void atomicSaveLeavesNoTemp(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("settings.properties");
        Settings s = Settings.load(f);
        s.put("a", "1");
        s.save();
        s.put("a", "2");
        s.save();
        try (Stream<Path> files = Files.list(tmp)) {
            assertEquals(List.of(f), files.toList());
        }
        assertEquals("2", Settings.load(f).get("a", null));
    }
}
