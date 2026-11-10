package purist.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainSmokeTest {

    @Test
    void versionComesFromPom() {
        assertEquals("0.1.0-SNAPSHOT", Main.version());
    }

    @Test
    void theInstalledLauncherNamesTheReleaseVersion() {
        System.setProperty(Main.VERSION_PROPERTY, " 0.1.5 ");
        try {
            assertEquals("0.1.5", Main.version());
            System.setProperty(Main.VERSION_PROPERTY, " ");
            assertEquals("0.1.0-SNAPSHOT", Main.version());
        } finally {
            System.clearProperty(Main.VERSION_PROPERTY);
        }
    }
}
