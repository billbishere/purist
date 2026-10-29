package purist.decode.dsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import purist.audio.BitOrder;
import purist.decode.Decoder;
import purist.decode.TrackInfo;
import purist.testutil.DsdDecode;

/**
 * Probes the user's real DSF album (read-only). Run with
 * {@code mvn test "-Dtest.excluded=" -Dgroups=library -Dtest=DsfLibraryTest}.
 */
@Tag("library")
class DsfLibraryTest {

    private static final Path ALBUM =
            Path.of(System.getProperty("purist.dsfAlbum",
                    "E:\\Music\\Michael Jackson - Thriller - 1982, DSF 128 (tracks), TAV"));

    @Test
    void everyDsfOfThrillerProbesAsDsd128() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(ALBUM)) {
            files = s.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".dsf")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no .dsf files under " + ALBUM);
        DsfHandler handler = new DsfHandler();
        for (Path f : files) {
            List<TrackInfo> infos = handler.probe(f);
            assertEquals(1, infos.size(), f.toString());
            TrackInfo t = infos.get(0);
            assertTrue(t.supported(), f + ": " + t.reason());
            assertEquals(5_644_800, t.format().sampleRate(), f.toString());
            assertEquals(2, t.format().channels(), f.toString());
            assertTrue(t.format().lengthFrames() > 0, f.toString());
            assertEquals(BitOrder.LSB_FIRST, t.format().dsdOrder(), f.toString());
            System.out.println(f.getFileName() + "  " + t.badge() + "  " + t.format().seconds(t.format().lengthFrames())
                    + " s  title=" + t.displayTitle() + "  artist=" + t.displayArtist());
            // the tail of the stream: seeking near the end yields exactly the frames that remain
            long frames = t.format().lengthFrames();
            try (Decoder d = handler.open(f, 0)) {
                d.seek(frames - 10_000);
                assertEquals(2 * 10_000, DsdDecode.all(d, 4096).length, f.toString());
                assertEquals(frames, d.position(), f.toString());
            }
        }
    }
}
