package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import purist.library.LibraryFeed;
import purist.library.ScanProgress;
import purist.library.ScanResult;
import purist.playlist.FoobarImport.ImportedPlaylist;
import purist.playlist.TrackRef;
import purist.ui.LibrarySettings.Findings;

class LibrarySettingsTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();

    private final LibrarySettings.Backend backend = new LibrarySettings.Backend() {
        @Override
        public List<Path> folders() {
            threads.add(SwingUtilities.isEventDispatchThread() ? "edt" : "io");
            return List.of(Path.of("E:\\"), Path.of("C:\\Users\\me\\Music"));
        }

        @Override
        public void addFolder(Path folder) {
            calls.add("add " + folder);
        }

        @Override
        public void removeFolder(Path folder) {
            calls.add("remove " + folder);
        }

        @Override
        public void rescan() {
            calls.add("rescan");
        }

        @Override
        public Findings foobar() {
            return new Findings(List.of(), List.of());
        }

        @Override
        public void importFoobar(Findings f) {
            calls.add("import");
        }

        @Override
        public List<String> errorTail(int n) {
            threads.add(SwingUtilities.isEventDispatchThread() ? "edt" : "io");
            return List.of("2026-10-26T10:00:00Z E:\\bad.flac: truncated");
        }
    };

    private static void await(BooleanSupplier c, String what) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            boolean[] ok = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ok[0] = c.getAsBoolean());
            if (ok[0]) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void refreshReadsTheFoldersAndTheErrorLogOffTheUiThread() throws Exception {
        LibrarySettings[] s = new LibrarySettings[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new LibrarySettings(backend, () -> null);
            s[0].refresh();
        });
        await(() -> s[0].shownFolders().size() == 2, "the folders");
        assertEquals(List.of(Path.of("E:\\"), Path.of("C:\\Users\\me\\Music")), s[0].shownFolders());
        assertTrue(s[0].errorText().contains("bad.flac: truncated"));
        assertEquals(List.of("io", "io"), threads);
    }

    @Test
    void aBackendFailureIsShownInsteadOfLeavingTheStatusOnRescanning() throws Exception {
        LibrarySettings.Backend failing = new LibrarySettings.Backend() {
            @Override public List<Path> folders() { return List.of(); }
            @Override public void addFolder(Path folder) { }
            @Override public void removeFolder(Path folder) { }
            @Override public void rescan() { throw new IllegalStateException("the database is locked"); }
            @Override public Findings foobar() { throw new IllegalStateException("profile unreadable"); }
            @Override public void importFoobar(Findings f) { }
            @Override public List<String> errorTail(int n) { return List.of(); }
        };
        LibrarySettings[] s = new LibrarySettings[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new LibrarySettings(failing, () -> null);
            s[0].rescanNow();
        });
        await(() -> s[0].statusText().equals("FAILED: RESCAN: THE DATABASE IS LOCKED"), "the failure on the status line");

        SwingUtilities.invokeAndWait(() -> s[0].importNow());
        await(() -> s[0].statusText().startsWith("FAILED: READ THE FOOBAR2000 PROFILE"), "the import failure");
    }

    @Test
    void anEmptyLogSaysSo() throws Exception {
        LibrarySettings.Backend quiet = new LibrarySettings.Backend() {
            @Override public List<Path> folders() { return List.of(); }
            @Override public void addFolder(Path folder) { }
            @Override public void removeFolder(Path folder) { }
            @Override public void rescan() { }
            @Override public Findings foobar() { return new Findings(List.of(), List.of()); }
            @Override public void importFoobar(Findings f) { }
            @Override public List<String> errorTail(int n) { return List.of(); }
        };
        LibrarySettings[] s = new LibrarySettings[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new LibrarySettings(quiet, () -> null);
            s[0].refresh();
        });
        await(() -> s[0].errorText().equals("No errors."), "the log text");
    }

    @Test
    void theConfirmationListsTheFoldersAndThePlaylistsAndPromisesToOnlyRead() {
        Findings f = new Findings(List.of(Path.of("C:\\Users\\me\\Music"), Path.of("E:\\Music")),
                List.of(new ImportedPlaylist("Default Playlist",
                        List.of(new TrackRef(Path.of("E:\\a.flac"), 0), new TrackRef(Path.of("E:\\b.flac"), 0)))));
        String m = LibrarySettings.message(f);
        assertTrue(m.contains("C:\\Users\\me\\Music"));
        assertTrue(m.contains("E:\\Music"));
        assertTrue(m.contains("Default Playlist (2 tracks)"));
        assertTrue(m.contains("only read, never changed"));
    }

    @Test
    void progressAndTheEndOfAScanShowOnTheStatusLine() throws Exception {
        LibrarySettings[] s = new LibrarySettings[1];
        SwingUtilities.invokeAndWait(() -> {
            s[0] = new LibrarySettings(backend, () -> null);
            s[0].scanProgress(new ScanProgress(1200, 5000, Path.of("E:\\Music\\a.flac")));
            assertTrue(s[0].statusText().startsWith("SCANNING 1,200 / 5,000"), s[0].statusText());
            s[0].scanFinished(new LibraryFeed.Finished(Path.of("E:\\"), new ScanResult(5000, 0, 0, 0, 1)));
            assertTrue(s[0].statusText().startsWith("LAST SCAN E:\\"), s[0].statusText());
            assertTrue(s[0].statusText().contains("5,000 ADDED"));
        });
    }
}
