package purist.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainSmokeTest {

    @Test
    void versionComesFromPom() {
        assertEquals("0.1.0-SNAPSHOT", Main.version());
    }
}
