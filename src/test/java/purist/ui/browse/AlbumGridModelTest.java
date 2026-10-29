package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import purist.library.AlbumSummary;
import purist.playlist.TrackRef;

class AlbumGridModelTest {

    private static AlbumSummary album(String artist, String title) {
        Path folder = Path.of("E:\\Music", artist, title);
        return new AlbumSummary(artist, title, "1982", folder, 9, new TrackRef(folder.resolve("01.flac"), 0),
                "FLAC 24/96");
    }

    private static List<AlbumSummary> many(int n) {
        List<AlbumSummary> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(album("Artist " + String.format("%03d", i), "Album"));
        }
        return out;
    }

    @Test
    void sortsByAlbumArtistThenAlbumIgnoringCase() {
        AlbumGridModel m = new AlbumGridModel();
        m.setAlbums(List.of(album("ted nugent", "Weekend Warriors"), album("Michael Jackson", "thriller"),
                album("Ted Nugent", "ted nugent"), album("michael jackson", "Bad"),
                album("ABBA", "Arrival")));
        List<String> got = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) {
            got.add(m.get(i).albumArtist() + " / " + m.get(i).album());
        }
        assertEquals(List.of("ABBA / Arrival", "michael jackson / Bad", "Michael Jackson / thriller",
                "Ted Nugent / ted nugent", "ted nugent / Weekend Warriors"), got);
    }

    @Test
    void anArtistFilterKeepsOnlyThatArtistIgnoringCase() {
        AlbumGridModel m = new AlbumGridModel();
        m.setAlbums(List.of(album("ABBA", "Arrival"), album("Michael Jackson", "Bad"),
                album("Michael Jackson", "Thriller")));
        m.setArtistFilter("michael JACKSON");
        assertEquals(2, m.size());
        assertEquals("Bad", m.get(0).album());
        assertEquals("michael JACKSON", m.artistFilter());
        m.setArtistFilter(null);
        assertEquals(3, m.size());
    }

    @Test
    void indexOfFindsAnAlbumInTheShownList() {
        AlbumGridModel m = new AlbumGridModel();
        AlbumSummary bad = album("Michael Jackson", "Bad");
        m.setAlbums(List.of(album("ABBA", "Arrival"), bad));
        assertEquals(1, m.indexOf(bad));
        assertEquals(-1, m.indexOf(album("X", "Y")));
        assertEquals(-1, m.indexOf(null));
    }

    @Test
    void asManyColumnsAsTheWidthAllows() {
        int w = AlbumGridModel.CELL_W;
        int m = AlbumGridModel.MARGIN;
        assertEquals(1, AlbumGridModel.columns(10), "never fewer than one");
        assertEquals(1, AlbumGridModel.columns(2 * m + w));
        assertEquals(1, AlbumGridModel.columns(2 * m + 2 * w - 1));
        assertEquals(2, AlbumGridModel.columns(2 * m + 2 * w));
        assertEquals(5, AlbumGridModel.columns(2 * m + 5 * w + 30));
    }

    @Test
    void heightCoversEveryRow() {
        AlbumGridModel m = new AlbumGridModel();
        int width = 2 * AlbumGridModel.MARGIN + 4 * AlbumGridModel.CELL_W;
        m.setAlbums(many(9)); // 4 columns: 3 rows
        assertEquals(2 * AlbumGridModel.MARGIN + 3 * AlbumGridModel.CELL_H, m.heightFor(width));
        m.setAlbums(many(8)); // exactly 2 rows
        assertEquals(2 * AlbumGridModel.MARGIN + 2 * AlbumGridModel.CELL_H, m.heightFor(width));
        m.setAlbums(List.of());
        assertEquals(0, m.heightFor(width));
    }

    @Test
    void aCellIsWhereTheRowAndColumnPutIt() {
        AlbumGridModel m = new AlbumGridModel();
        int width = 2 * AlbumGridModel.MARGIN + 4 * AlbumGridModel.CELL_W;
        m.setAlbums(many(9));
        Rectangle c0 = m.cellBounds(0, width);
        assertEquals(new Rectangle(AlbumGridModel.MARGIN, AlbumGridModel.MARGIN, AlbumGridModel.CELL_W,
                AlbumGridModel.CELL_H), c0);
        Rectangle c5 = m.cellBounds(5, width); // row 1, column 1
        assertEquals(AlbumGridModel.MARGIN + AlbumGridModel.CELL_W, c5.x);
        assertEquals(AlbumGridModel.MARGIN + AlbumGridModel.CELL_H, c5.y);
        Rectangle cover = m.coverBounds(5, width);
        assertEquals(AlbumGridModel.COVER, cover.width);
        assertEquals(AlbumGridModel.COVER, cover.height);
        assertTrue(c5.contains(cover));
    }

    @Test
    void aPointFindsItsAlbumOrNone() {
        AlbumGridModel m = new AlbumGridModel();
        int width = 2 * AlbumGridModel.MARGIN + 4 * AlbumGridModel.CELL_W;
        m.setAlbums(many(6));
        Rectangle c5 = m.cellBounds(5, width);
        assertEquals(5, m.indexAt(c5.x + 3, c5.y + 3, width));
        assertEquals(5, m.indexAt(c5.x + c5.width - 1, c5.y + c5.height - 1, width));
        assertEquals(-1, m.indexAt(1, 1, width), "the outer margin");
        Rectangle empty = m.cellBounds(6, width); // the slot after the last album
        assertEquals(-1, m.indexAt(empty.x + 5, empty.y + 5, width));
        assertEquals(-1, m.indexAt(width + 5, 10, width), "right of the last column");
    }

    @Test
    void theVisibleRangeIsTheRowsTheViewportTouches() {
        AlbumGridModel m = new AlbumGridModel();
        int width = 2 * AlbumGridModel.MARGIN + 4 * AlbumGridModel.CELL_W;
        m.setAlbums(many(40)); // 10 rows of 4
        int[] top = m.visibleRange(0, 10, width);
        assertEquals(0, top[0]);
        assertEquals(4, top[1], "exclusive end: only the first row is touched");
        int y = AlbumGridModel.MARGIN + 2 * AlbumGridModel.CELL_H; // the top of row 2
        int[] mid = m.visibleRange(y, y + AlbumGridModel.CELL_H + 1, width);
        assertEquals(8, mid[0]);
        assertEquals(16, mid[1], "rows 2 and 3");
        int[] past = m.visibleRange(100_000, 100_100, width);
        assertEquals(past[0], past[1], "nothing visible past the end");
    }

    @Test
    void aKeyFilterKeepsOnlyTheAlbumsNamedAndCombinesWithTheArtistFilter() {
        AlbumGridModel m = new AlbumGridModel();
        m.setAlbums(List.of(album("ABBA", "Arrival"), album("Michael Jackson", "Bad"),
                album("Michael Jackson", "Thriller")));
        m.setKeyFilter(java.util.Set.of(AlbumGridModel.keyOf("michael jackson", "THRILLER"),
                AlbumGridModel.keyOf("ABBA", "Arrival")));
        assertEquals(2, m.size());
        m.setArtistFilter("abba");
        assertEquals(1, m.size());
        assertEquals("Arrival", m.get(0).album());
        m.setArtistFilter(null);
        m.setKeyFilter(null);
        assertEquals(3, m.size(), "clearing the filters restores every album");
    }

    @Test
    void aLibraryOfFiveHundredAlbumsHasFiveHundredCellsAllReachableByScrolling() {
        AlbumGridModel m = new AlbumGridModel();
        m.setAlbums(many(500));
        assertEquals(500, m.size());
        int width = 2 * AlbumGridModel.MARGIN + 5 * AlbumGridModel.CELL_W;
        int height = m.heightFor(width);
        assertEquals(2 * AlbumGridModel.MARGIN + 100 * AlbumGridModel.CELL_H, height, "100 rows of 5");
        int[] bottom = m.visibleRange(height - 50, height, width);
        assertEquals(500, bottom[1], "the last album is drawn when scrolled to the end");
        assertEquals(499, m.indexAt(m.cellBounds(499, width).x + 4, m.cellBounds(499, width).y + 4, width));
        // every album is in some row's visible range as the viewport walks down
        boolean[] seen = new boolean[500];
        for (int y = 0; y < height; y += 300) {
            int[] r = m.visibleRange(y, y + 400, width);
            for (int i = r[0]; i < r[1]; i++) {
                seen[i] = true;
            }
        }
        for (int i = 0; i < 500; i++) {
            assertTrue(seen[i], "album " + i + " was never in view");
        }
    }
}
