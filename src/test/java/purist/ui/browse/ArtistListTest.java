package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import purist.library.AlbumSummary;
import purist.playlist.TrackRef;
import purist.ui.browse.ArtistList.Entry;

class ArtistListTest {

    private static AlbumSummary album(String artist, String title) {
        Path folder = Path.of("E:\\Music", artist, title);
        return new AlbumSummary(artist, title, "", folder, 1, new TrackRef(folder.resolve("a.flac"), 0), "FLAC 16/44.1");
    }

    @Test
    void oneEntryPerArtistIgnoringCaseWithTheirAlbumCountsSortedByName() {
        List<Entry> got = ArtistList.entries(List.of(album("Ted Nugent", "Free-for-All"),
                album("ABBA", "Arrival"), album("ted nugent", "Weekend Warriors"), album("Michael Jackson", "Bad")));
        assertEquals(List.of(new Entry("ABBA", 1), new Entry("Michael Jackson", 1), new Entry("Ted Nugent", 2)), got);
    }

    @Test
    void noAlbumsNoEntries() {
        assertEquals(List.of(), ArtistList.entries(List.of()));
    }
}
