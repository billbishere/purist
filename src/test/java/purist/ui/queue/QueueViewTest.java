package purist.ui.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.DecoderRegistry;
import purist.playlist.Playlist;
import purist.playlist.PlaylistStore;
import purist.ui.PlaylistView;

class QueueViewTest {

    @TempDir
    Path tmp;

    private PlaylistStore store;
    private final List<String> events = new ArrayList<>();
    private QueueView view;

    @BeforeEach
    void open() throws Exception {
        store = new PlaylistStore(tmp.resolve("playlists"));
        SwingUtilities.invokeAndWait(() -> view = new QueueView(store,
                new PlaylistView(DecoderRegistry.standard(), i -> { }, () -> { }, () -> { }), null,
                new QueueView.Listener() {
                    @Override
                    public void selected(Playlist p) {
                        events.add("selected " + p.name());
                    }

                    @Override
                    public void deleted(Playlist p) {
                        events.add("deleted " + p.name());
                    }
                }));
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void aFreshStoreHasOneTabAndItIsSelected() {
        assertEquals(1, view.tabNames().size());
        assertSame(store.all().get(0), view.current());
    }

    @Test
    void thePlusMakesPlaylistNAndSelectsIt() {
        Playlist p = view.newPlaylist();
        assertEquals("Playlist 2", p.name());
        assertEquals(2, view.tabNames().size());
        assertSame(p, view.current());
        assertEquals(List.of("selected Playlist 2"), events);
        assertEquals("Playlist 3", view.newPlaylist().name());
    }

    @Test
    void aNewNameSkipsOneAlreadyTaken() {
        Playlist a = view.newPlaylist(); // Playlist 2
        view.rename(a, "Playlist 3");
        assertEquals("Playlist 4", view.newPlaylist().name(), "3 is taken, and there are now 3 playlists");
    }

    @Test
    void renamingChangesTheTabAndABlankNameIsIgnored() {
        Playlist p = view.current();
        view.rename(p, "Car");
        assertEquals(List.of("Car"), view.tabNames());
        view.rename(p, "   ");
        assertEquals(List.of("Car"), view.tabNames());
    }

    @Test
    void theLastPlaylistCannotBeDeleted() {
        assertFalse(view.delete(view.current()));
        assertEquals(1, view.tabNames().size());
    }

    @Test
    void deletingTheCurrentOneSelectsItsNeighbourAndTellsTheListener() {
        Playlist first = view.current();
        Playlist second = view.newPlaylist();
        events.clear();
        assertTrue(view.delete(second));
        assertSame(first, view.current());
        assertEquals(List.of("deleted Playlist 2", "selected " + first.name()), events);
        assertEquals(1, store.all().size());
    }

    @Test
    void deletingAnotherTabKeepsTheSelection() {
        Playlist first = view.current();
        Playlist second = view.newPlaylist();
        view.select(first);
        events.clear();
        assertTrue(view.delete(second));
        assertSame(first, view.current());
        assertEquals(List.of("deleted Playlist 2"), events);
    }
}
