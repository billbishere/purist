package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.StreamFormat;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.library.FileStamp;
import purist.library.LibraryDb;
import purist.library.art.ThumbnailCache;
import purist.tags.TagMap;

/** The browser against a real database: what the grid shows after a load and after a search. */
class BrowseViewTest {

    @TempDir
    Path tmp;

    private LibraryDb db;
    private ThumbnailCache thumbs;
    private BrowseView view;

    @BeforeEach
    void open() throws Exception {
        db = LibraryDb.open(tmp.resolve("library.db"));
        thumbs = new ThumbnailCache(tmp.resolve("thumbs"), 8, 64);
    }

    @AfterEach
    void close() {
        thumbs.close();
        db.close();
    }

    private void track(String artist, String album, int n) {
        TagMap t = new TagMap();
        t.add("ARTIST", artist);
        t.add("ALBUM", album);
        t.add("TITLE", "Song " + n);
        Path file = Path.of("E:\\Music", artist, album, String.format("%02d.flac", n));
        db.upsert(List.of(new TrackInfo(file, 0, "FLAC", StreamFormat.pcm(44100, 16, 2, 441000), t, true, null)),
                new FileStamp(1, 1));
    }

    private BrowseView view() throws Exception {
        BrowseView[] v = new BrowseView[1];
        SwingUtilities.invokeAndWait(() -> {
            v[0] = new BrowseView(db, thumbs, DecoderRegistry.standard(), (refs, from) -> { }, refs -> { });
            v[0].setSize(new Dimension(900, 500));
        });
        view = v[0];
        return view;
    }

    private static <T> T onEdt(java.util.function.Supplier<T> s) throws Exception {
        Object[] out = new Object[1];
        SwingUtilities.invokeAndWait(() -> out[0] = s.get());
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static void awaitTrue(BooleanSupplier c, String what) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (onEdt(c::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private int albums() throws Exception {
        return onEdt(() -> view.model().size());
    }

    @Test
    void aLibraryOfFiveHundredAlbumsYieldsFiveHundredGridEntries() throws Exception {
        for (int i = 0; i < 500; i++) {
            track("Artist " + i % 40, "Album " + String.format("%03d", i), 1);
        }
        BrowseView v = view();
        SwingUtilities.invokeAndWait(v::reload);
        awaitTrue(() -> v.model().size() == 500, "500 albums in the grid");
        assertEquals(500, albums());
        assertTrue(onEdt(() -> v.countText().startsWith("500 ")), "the count says so: " + onEdt(v::countText));
    }

    @Test
    void typingAQueryNarrowsTheGridAndTheCountAndClearingRestoresIt() throws Exception {
        track("Ted Nugent", "Weekend Warriors", 1);
        track("Ted Nugent", "Weekend Warriors", 2);
        track("Ted Nugent", "Free-for-All", 1);
        track("Michael Jackson", "Thriller", 1);
        track("ABBA", "Arrival", 1);
        track("ABBA", "Arrival", 2);
        BrowseView v = view();
        SwingUtilities.invokeAndWait(v::reload);
        awaitTrue(() -> v.model().size() == 4, "the full library");

        SwingUtilities.invokeAndWait(() -> v.search().setText("artist HAS nugent"));
        awaitTrue(() -> v.searching() && v.model().size() == 2, "the grid narrowed to the two Nugent albums");
        assertEquals("3 TRACKS \u00B7 2 ALBUMS", onEdt(v::countText));

        SwingUtilities.invokeAndWait(() -> v.search().setText("song 2"));
        awaitTrue(() -> v.model().size() == 2 && v.countText().startsWith("2 TRACKS"),
                "the second query replaces the first");
        assertEquals("2 TRACKS \u00B7 2 ALBUMS", onEdt(v::countText));

        SwingUtilities.invokeAndWait(() -> v.search().clear());
        awaitTrue(() -> !v.searching() && v.model().size() == 4, "the full grid is back");
        assertEquals("4 ALBUMS", onEdt(v::countText));
    }

    @Test
    void aQueryThatMatchesNothingEmptiesTheGridAndABadOneKeepsTheLastResults() throws Exception {
        track("ABBA", "Arrival", 1);
        track("Burial", "Untrue", 1);
        BrowseView v = view();
        SwingUtilities.invokeAndWait(v::reload);
        awaitTrue(() -> v.model().size() == 2, "the library");

        SwingUtilities.invokeAndWait(() -> v.search().setText("artist HAS zzzz"));
        awaitTrue(() -> v.searching() && v.model().size() == 0, "no match");
        assertTrue(onEdt(v::countText).startsWith("0 TRACKS"));

        SwingUtilities.invokeAndWait(() -> v.search().setText("artist HAS abba"));
        awaitTrue(() -> v.model().size() == 1, "one album");
        SwingUtilities.invokeAndWait(() -> v.search().setText("artist HAS"));
        awaitTrue(() -> v.countText().startsWith("QUERY"), "a query error is shown");
        assertEquals(1, albums(), "the last good results stay while the query is incomplete");
    }

    @Test
    void theSearchQueryRunsOffTheEventThread() throws Exception {
        track("ABBA", "Arrival", 1);
        BrowseView v = view();
        SwingUtilities.invokeAndWait(v::reload);
        awaitTrue(() -> v.model().size() == 1, "the library");
        List<String> threads = new ArrayList<>();
        // The database reads happen on the browse thread: it is named, and it is not the EDT.
        SwingUtilities.invokeAndWait(() -> v.search().setText("abba"));
        awaitTrue(() -> v.searching(), "results");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            threads.add(t.getName());
        }
        assertTrue(threads.contains("purist-browse"), threads.toString());
        assertFalse(SwingUtilities.isEventDispatchThread());
    }

    @Test
    void everyAlbumIsReachableByScrollingTheGrid() throws Exception {
        for (int i = 0; i < 500; i++) {
            track("Artist " + i, "Album", 1);
        }
        BrowseView v = view();
        SwingUtilities.invokeAndWait(v::reload);
        awaitTrue(() -> v.model().size() == 500, "500 albums");
        IntSupplier[] heights = new IntSupplier[1];
        SwingUtilities.invokeAndWait(() -> {
            AlbumGrid grid = new AlbumGrid(v.model(), null);
            JScrollPane sp = new JScrollPane(grid);
            sp.setSize(800, 400);
            sp.doLayout();
            sp.getViewport().setSize(783, 400);
            grid.revalidate();
            sp.doLayout();
            heights[0] = () -> grid.getPreferredSize().height;
        });
        int cols = AlbumGridModel.columns(783);
        int rows = (500 + cols - 1) / cols;
        assertEquals(2 * AlbumGridModel.MARGIN + rows * AlbumGridModel.CELL_H, heights[0].getAsInt(),
                "the grid is tall enough for all 500 albums, not one page");
    }

    @Test
    void aCappedSearchStillNarrowsTheGridToEveryAlbumItTouchesAndSaysTheListIsCapped() throws Exception {
        List<LibraryDb.FileRows> batch = new ArrayList<>();
        for (int i = 0; i < BrowseView.RESULT_CAP + 1; i++) {
            TagMap t = new TagMap();
            t.add("ARTIST", "Aaa");
            t.add("ALBUM", "Big");
            t.add("TITLE", "Song " + i);
            Path file = Path.of("E:\\Music\\Aaa\\Big\\" + String.format("%05d.flac", i));
            batch.add(new LibraryDb.FileRows(List.of(new TrackInfo(file, 0, "FLAC",
                    StreamFormat.pcm(44100, 16, 2, 441000), t, true, null)), new FileStamp(1, 1)));
        }
        db.upsertBatch(batch);
        track("Zzz", "Late", 1); // sorts after the cap
        BrowseView v = view();
        SwingUtilities.invokeAndWait(v::reload);
        awaitTrue(() -> v.model().size() == 2, "the library");

        SwingUtilities.invokeAndWait(() -> v.search().setText("song"));

        awaitTrue(() -> v.searching() && v.countText().contains("ALBUMS"), "the capped search");
        assertEquals(2, albums(), "the album past the cap is in the grid too");
        assertEquals("5,000+ TRACKS \u00B7 2 ALBUMS", onEdt(v::countText));
    }

    @Test
    void theGridCanHoldTheFocusAndNeitherItNorTheSplitPaneKeepsTheWindowShortcutKeys() throws Exception {
        BrowseView v = view();
        assertTrue(onEdt(() -> v.grid().isFocusable()), "a click on the grid takes the focus out of the search box");
        for (javax.swing.JComponent c : new javax.swing.JComponent[] {v.grid(), v.searchSplit()}) {
            for (javax.swing.KeyStroke k : purist.ui.kit.ShortcutKeys.KEYS) {
                Object name = c.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(k);
                assertTrue(name == null || c.getActionMap().get(name) == null,
                        k + " still runs " + name + " on " + c.getClass().getSimpleName());
            }
        }
    }

    @Test
    void afterCloseTheViewReadsNothingAndAskingItToDoSoIsHarmless() throws Exception {
        track("ABBA", "Arrival", 1);
        BrowseView v = view();
        SwingUtilities.invokeAndWait(v::reload);
        awaitTrue(() -> v.model().size() == 1, "the library");

        SwingUtilities.invokeAndWait(v::close);
        db.close(); // the window's shutdown closes the database right after the workers
        SwingUtilities.invokeAndWait(() -> {
            v.reload(); // a late tick of the window
            v.search().setText("artist HAS abba");
        });
        Thread.sleep(500); // a read on the closed database would be logged as a failure and could not show
        assertEquals(1, albums());
        db = LibraryDb.open(tmp.resolve("library.db")); // for the @AfterEach
    }
}
