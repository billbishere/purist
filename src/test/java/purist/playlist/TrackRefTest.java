package purist.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TrackRefTest {

    @Test
    void roundTripAwkwardPath() {
        Path p = Path.of("E:\\Music\\Laserdance - Trance Space Express (cd album '2018)-(flac)\\Motörhead ö.flac");
        TrackRef ref = new TrackRef(p, 0);
        assertEquals("file://" + p, ref.toUri());
        TrackRef back = TrackRef.parse(ref.toUri());
        assertEquals(ref, back);
        assertEquals(p.toString(), back.path().toString());
    }

    @Test
    void hashInFlacFilenameNotSubsong() {
        TrackRef ref = TrackRef.parse("file://C:\\m\\Track #1.flac");
        assertEquals(0, ref.subsong());
        assertEquals("C:\\m\\Track #1.flac", ref.path().toString());
        assertEquals("file://C:\\m\\Track #1.flac", ref.toUri());
    }

    @Test
    void isoSubsong() {
        TrackRef ref = TrackRef.parse("file://E:\\x\\disc.ISO#3");
        assertEquals(3, ref.subsong());
        assertEquals("E:\\x\\disc.ISO", ref.path().toString());
        assertEquals("file://E:\\x\\disc.ISO#3", ref.toUri());
    }

    @Test
    void isoWithoutSubsongSuffixParsesAsSubsongZeroAndWritesIt() {
        TrackRef ref = TrackRef.parse("file://E:\\x\\disc.iso");
        assertEquals(0, ref.subsong());
        assertEquals("file://E:\\x\\disc.iso#0", ref.toUri());
    }

    @Test
    void hashInsideIsoFolderIsKept() {
        TrackRef ref = TrackRef.parse("file://E:\\x #2\\disc.iso#12");
        assertEquals(12, ref.subsong());
        assertEquals("E:\\x #2\\disc.iso", ref.path().toString());
    }

    @Test
    void nonIsoNeverWritesSubsong() {
        assertEquals("file://C:\\m\\a.flac", new TrackRef(Path.of("C:\\m\\a.flac"), 4).toUri());
    }

    @Test
    void longPathOver260() {
        String dir = "C:\\" + "d".repeat(100) + "\\" + "e".repeat(100) + "\\";
        String text = dir + "f".repeat(80) + ".flac";
        assertTrue(text.length() > 260);
        TrackRef ref = TrackRef.parse("file://" + text);
        assertEquals(text, ref.path().toString());
        assertEquals("file://" + text, ref.toUri());
    }

    @Test
    void rejectsTextThatIsNotAFileUri() {
        assertThrows(IllegalArgumentException.class, () -> TrackRef.parse("http://example.com/a.flac"));
    }

    @Test
    void rejectsNegativeSubsong() {
        assertThrows(IllegalArgumentException.class, () -> new TrackRef(Path.of("C:\\a.iso"), -1));
    }
}
