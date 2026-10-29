package purist.decode.sacd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reads the user's real SACD ISO (read-only). Run with
 * {@code mvn test "-Dtest.excluded=" -Dgroups=library -Dtest=SacdRealDiscTest}; the path can be changed with
 * {@code -Dpurist.sacdIso=...}.
 */
@Tag("library")
class SacdRealDiscTest {

    static final Path DISC = Path.of(System.getProperty("purist.sacdIso",
            "E:\\Music\\Ted Nugent - Ted Nugent (1975) SACD-ISO+Flac 24-88.2\\"
                    + "Ted Nugent - Ted Nugent (1975) [APO Remaster 2015] (SACD-ISO)\\"
                    + "Analogue Productions - Ted Nugent.iso"));

    @Test
    void tedNugentToc() throws IOException {
        assertTrue(Files.isRegularFile(DISC), "no such file: " + DISC);
        try (SacdImage img = SacdImage.open(DISC)) {
            assertEquals(2048, img.sectorSize());
            assertEquals("Ted Nugent", img.albumTitle());
            assertEquals("Ted Nugent", img.albumArtist());
            System.out.println("date=" + img.date());
            SacdArea a = img.stereoArea().orElseThrow();
            assertEquals(9, a.tracks().size());
            assertEquals(2, a.frameFormat());
            assertEquals(2, a.channels());
            assertEquals(4, a.fsCode());
            assertFalse(a.isDst());
            SacdTrack t1 = a.tracks().get(0);
            assertEquals(1, t1.number());
            assertEquals(1324, t1.startLsn());
            assertEquals(176_881, t1.lengthSectors());
            assertEquals(37_903, t1.lengthFrames());
            assertEquals(150, t1.startTimeFrames());
            assertEquals("Stranglehold", t1.tags().first("TITLE").orElseThrow());
            assertEquals("Ted Nugent", t1.tags().first("ARTIST").orElseThrow());
            for (SacdTrack t : a.tracks()) {
                System.out.println(t.number() + "  lsn " + t.startLsn() + "+" + t.lengthSectors() + "  frames "
                        + t.startTimeFrames() + "+" + t.lengthFrames() + "  " + t.tags());
            }
        }
    }
}
