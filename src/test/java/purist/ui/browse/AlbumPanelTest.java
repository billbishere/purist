package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.library.AlbumSummary;
import purist.playlist.TrackRef;
import purist.tags.TagMap;

class AlbumPanelTest {

    private static final Path FOLDER = Path.of("E:\\Music\\Michael Jackson\\Thriller");

    private static AlbumSummary thriller(String year, int tracks, String format) {
        return new AlbumSummary("Michael Jackson", "Thriller", year, FOLDER, tracks,
                new TrackRef(FOLDER.resolve("01.flac"), 0), format);
    }

    private static List<TrackInfo> rows(int n) {
        List<TrackInfo> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            TagMap tags = new TagMap();
            tags.add("TITLE", "Song " + i);
            out.add(new TrackInfo(FOLDER.resolve(String.format("%02d.flac", i)), 0, "FLAC",
                    StreamFormat.pcm(96000, 24, 2, 200L * 96000), tags, true, null));
        }
        return out;
    }

    @Test
    void theMetaLineJoinsArtistYearTrackCountAndFormat() {
        assertEquals("Michael Jackson \u00B7 1982 \u00B7 9 tr \u00B7 DSD128",
                AlbumPanel.meta(thriller("1982", 9, "DSD128")));
    }

    @Test
    void anAlbumWithNoYearLeavesItOut() {
        assertEquals("Michael Jackson \u00B7 1 tr \u00B7 FLAC 24/96", AlbumPanel.meta(thriller("", 1, "FLAC 24/96")));
    }

    private static final class Fixture {
        final List<Object[]> plays = new ArrayList<>();
        final List<List<TrackRef>> adds = new ArrayList<>();
        final AlbumPanel panel;

        Fixture(int tracks) throws Exception {
            AlbumPanel[] p = new AlbumPanel[1];
            SwingUtilities.invokeAndWait(() -> p[0] = new AlbumPanel(null,
                    (album, done) -> done.accept(rows(tracks)),
                    (refs, from) -> plays.add(new Object[] {refs, from}), adds::add));
            panel = p[0];
        }
    }

    @Test
    void showFillsInTheTitleTheMetaAndTheTrackList() throws Exception {
        Fixture f = new Fixture(3);
        SwingUtilities.invokeAndWait(() -> f.panel.show(thriller("1982", 3, "FLAC 24/96")));
        assertTrue(f.panel.isVisible());
        assertEquals("THRILLER", f.panel.titleText());
        assertEquals(3, f.panel.trackCount());
    }

    @Test
    void nothingSelectedHidesThePanel() throws Exception {
        Fixture f = new Fixture(3);
        SwingUtilities.invokeAndWait(() -> f.panel.show(thriller("1982", 3, "FLAC 24/96")));
        SwingUtilities.invokeAndWait(() -> f.panel.show(null));
        assertFalse(f.panel.isVisible());
    }

    @Test
    void playReplacesTheQueueWithTheWholeAlbumFromTheFirstTrack() throws Exception {
        Fixture f = new Fixture(3);
        SwingUtilities.invokeAndWait(() -> {
            f.panel.show(thriller("1982", 3, "FLAC 24/96"));
            f.panel.playButton().doClick();
        });
        assertEquals(1, f.plays.size());
        assertEquals(3, ((List<?>) f.plays.get(0)[0]).size());
        assertEquals(0, f.plays.get(0)[1]);
        assertEquals(List.of(), f.adds);
    }

    @Test
    void queueAppendsTheWholeAlbum() throws Exception {
        Fixture f = new Fixture(3);
        SwingUtilities.invokeAndWait(() -> {
            f.panel.show(thriller("1982", 3, "FLAC 24/96"));
            f.panel.queueButton().doClick();
        });
        assertEquals(1, f.adds.size());
        assertEquals(3, f.adds.get(0).size());
        assertEquals(new TrackRef(FOLDER.resolve("02.flac"), 0), f.adds.get(0).get(1));
        assertEquals(List.of(), f.plays);
    }

    @Test
    void doubleClickingATrackPlaysTheAlbumFromThatTrack() throws Exception {
        Fixture f = new Fixture(5);
        SwingUtilities.invokeAndWait(() -> {
            f.panel.show(thriller("1982", 5, "FLAC 24/96"));
            JTable t = f.panel.table();
            t.setSize(300, 300);
            int y = t.getRowHeight() * 3 + 4; // the fourth row
            t.dispatchEvent(new MouseEvent(t, MouseEvent.MOUSE_CLICKED, 0, 0, 20, y, 2, false,
                    MouseEvent.BUTTON1));
        });
        assertEquals(1, f.plays.size());
        assertEquals(5, ((List<?>) f.plays.get(0)[0]).size());
        assertEquals(3, f.plays.get(0)[1]);
    }

    @Test
    void aSlowLoadForAnAlbumNoLongerShownIsDropped() throws Exception {
        List<java.util.function.Consumer<List<TrackInfo>>> pending = new ArrayList<>();
        AlbumPanel[] p = new AlbumPanel[1];
        SwingUtilities.invokeAndWait(() -> p[0] = new AlbumPanel(null, (album, done) -> pending.add(done),
                (refs, from) -> { }, refs -> { }));
        SwingUtilities.invokeAndWait(() -> {
            p[0].show(thriller("1982", 3, "FLAC 24/96"));
            p[0].show(new AlbumSummary("ABBA", "Arrival", "1976", Path.of("E:\\Music\\ABBA"), 10,
                    new TrackRef(Path.of("E:\\Music\\ABBA\\01.flac"), 0), "FLAC 16/44.1"));
            pending.get(0).accept(rows(3)); // the first album's tracks arrive late
            assertEquals(0, p[0].trackCount());
            pending.get(1).accept(rows(10));
            assertEquals(10, p[0].trackCount());
        });
    }
}
