package purist.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class FolderWatcherTest {

    private static final Duration DEBOUNCE = Duration.ofMillis(200);

    @TempDir
    Path tmp;

    private final BlockingQueue<Set<Path>> emitted = new LinkedBlockingQueue<>();
    private FolderWatcher watcher;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("music")).toRealPath();
        watcher = new FolderWatcher(emitted::add, DEBOUNCE);
    }

    @AfterEach
    void tearDown() {
        watcher.close();
    }

    /** A synthetic event, as the {@code WatchService} would deliver it. */
    private static WatchEvent<?> event(WatchEvent.Kind<?> kind, Path context) {
        return new WatchEvent<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Kind<Object> kind() {
                return (Kind<Object>) kind;
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public Object context() {
                return context;
            }
        };
    }

    @Test
    void createFileEmitsParentAfterDebounce() throws Exception {
        Path album = Files.createDirectories(root.resolve("album"));
        watcher.watch(root);

        Files.writeString(album.resolve("new.flac"), "x");

        Set<Path> got = emitted.poll(5, TimeUnit.SECONDS);
        assertNotNull(got, "no callback within 5 s");
        assertTrue(got.contains(album), got.toString());
    }

    @Test
    void aFileDeepInsideTheTreeIsSeenThroughOneRegistration() throws Exception {
        Path deep = Files.createDirectories(root.resolve("a").resolve("b").resolve("c"));
        watcher.watch(root);

        Files.writeString(deep.resolve("x.flac"), "x");

        Set<Path> got = emitted.poll(5, TimeUnit.SECONDS);
        assertNotNull(got, "no callback within 5 s");
        assertTrue(got.contains(deep), got.toString());
    }

    @Test
    void burstCoalesced() throws Exception {
        Path album = Files.createDirectories(root.resolve("album"));
        watcher.watch(root);

        for (int i = 0; i < 10; i++) {
            Files.writeString(album.resolve("t" + i + ".flac"), "x");
        }

        Set<Path> first = emitted.poll(5, TimeUnit.SECONDS);
        assertNotNull(first, "no callback within 5 s");
        assertTrue(first.contains(album));
        assertNull(emitted.poll(DEBOUNCE.toMillis() * 4, TimeUnit.MILLISECONDS), "the burst must emit once");
    }

    @Test
    void overflowEmitsRoot() throws Exception {
        watcher.handle(event(StandardWatchEventKinds.OVERFLOW, null), root);

        Set<Path> got = emitted.poll(5, TimeUnit.SECONDS);
        assertEquals(Set.of(root), got);
    }

    @Test
    void handleMapsAnEventToItsParentDirectory() throws Exception {
        watcher.handle(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("x", "y", "t.flac")), root);

        assertEquals(Set.of(root.resolve("x").resolve("y")), emitted.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void aFileDirectlyInTheRootEmitsTheRoot() throws Exception {
        watcher.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("t.flac")), root);

        assertEquals(Set.of(root), emitted.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void unwatchStopsEvents() throws Exception {
        Path album = Files.createDirectories(root.resolve("album"));
        watcher.watch(root);
        watcher.unwatch(root);

        Files.writeString(album.resolve("late.flac"), "x");

        assertNull(emitted.poll(DEBOUNCE.toMillis() * 5, TimeUnit.MILLISECONDS));
    }

    @Test
    void closeStopsEventsAndIsRepeatable() throws Exception {
        Path album = Files.createDirectories(root.resolve("album"));
        watcher.watch(root);
        watcher.close();
        watcher.close();

        Files.writeString(album.resolve("late.flac"), "x");

        assertNull(emitted.poll(DEBOUNCE.toMillis() * 5, TimeUnit.MILLISECONDS));
    }

    // ---- the file filter and the deep/shallow split ----

    private final BlockingQueue<FolderWatcher.Changes> changes = new LinkedBlockingQueue<>();

    private FolderWatcher audioOnly() {
        return new FolderWatcher(changes::add, DEBOUNCE, p -> p.toString().endsWith(".flac"));
    }

    @Test
    void aFileTheFilterRejectsCostsNothing() throws Exception {
        try (FolderWatcher w = audioOnly()) {
            w.handle(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("cache", "page.html")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("cache", "page.html")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("cache", "page.html")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_MODIFY, Path.of("log.txt")), root);

            assertNull(changes.poll(DEBOUNCE.toMillis() * 4, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void anAudioFileChangeIsAShallowReportOfItsFolder() throws Exception {
        try (FolderWatcher w = audioOnly()) {
            w.handle(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("album", "t.flac")), root);

            FolderWatcher.Changes got = changes.poll(5, TimeUnit.SECONDS);
            assertNotNull(got);
            assertEquals(Set.of(root.resolve("album")), got.shallow());
            assertEquals(Set.of(), got.deep());
        }
    }

    @Test
    void aDirectoryAppearingOrVanishingNeedsItsParentRescannedDeep() throws Exception {
        Files.createDirectories(root.resolve("new album"));
        try (FolderWatcher w = audioOnly()) {
            w.handle(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("new album")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("old album")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("x", "Vol. 2 Remastered")), root);

            FolderWatcher.Changes got = changes.poll(5, TimeUnit.SECONDS);
            assertNotNull(got);
            assertEquals(Set.of(root, root.resolve("x")), got.deep());
            assertEquals(Set.of(), got.shallow());
        }
    }

    @Test
    void aVanishedFolderWithADotInItsNameIsDeepWhenTheLibraryHoldsTracksBelowIt() throws Exception {
        try (FolderWatcher w = audioOnly()) {
            w.setStoredUnder(p -> p.equals(root.resolve("R.E.M")) || p.equals(root.resolve("x").resolve("Vol.2")));
            w.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("R.E.M")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("x", "Vol.2")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("y", "cover.jpg")), root); // nothing stored

            FolderWatcher.Changes got = changes.poll(5, TimeUnit.SECONDS);
            assertNotNull(got);
            assertEquals(Set.of(root, root.resolve("x")), got.deep());
            assertEquals(Set.of(), got.shallow());
        }
    }

    @Test
    void aDeletedNonAudioFileWithNothingStoredBelowItStillCostsNothing() throws Exception {
        try (FolderWatcher w = audioOnly()) {
            w.setStoredUnder(p -> false);
            w.handle(event(StandardWatchEventKinds.ENTRY_DELETE, Path.of("Mr.Big")), root);

            assertNull(changes.poll(DEBOUNCE.toMillis() * 4, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void aDirectoryReportedBothWaysIsDeep() throws Exception {
        Files.createDirectories(root.resolve("sub"));
        try (FolderWatcher w = audioOnly()) {
            w.handle(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("t.flac")), root);
            w.handle(event(StandardWatchEventKinds.ENTRY_CREATE, Path.of("sub")), root);

            FolderWatcher.Changes got = changes.poll(5, TimeUnit.SECONDS);
            assertNotNull(got);
            assertEquals(Set.of(root), got.deep());
            assertEquals(Set.of(), got.shallow());
        }
    }
}
