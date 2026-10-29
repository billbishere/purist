package purist.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.DecoderRegistry;

@Timeout(60)
class LibraryServiceTest {

    @TempDir
    Path tmp;

    private Path root;
    private LibraryDb db;
    private ScannerTest.CountingHandler handler;
    private LibraryService service;
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final List<ScanProgress> progress = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Object[]> finished = new LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger finishedCount = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("music")).toRealPath();
        db = LibraryDb.open(tmp.resolve("db").resolve("library.db"));
        handler = new ScannerTest.CountingHandler();
        DecoderRegistry registry = new DecoderRegistry();
        registry.register(handler);
        Scanner scanner = new Scanner(db, registry, new ScanErrorLog(tmp.resolve("scan-errors.log")), p -> false);
        service = new LibraryService(db, scanner,
                onChanged -> new FolderWatcher(onChanged, Duration.ofMillis(200), registry::isAudioFile));
        service.addListener(new LibraryListener() {
            @Override
            public void scanProgress(ScanProgress p) {
                progress.add(p);
            }

            @Override
            public void libraryChanged() {
                events.add("changed");
            }

            @Override
            public void scanFinished(Path folder, ScanResult result) {
                finished.add(new Object[] {folder, result});
                finishedCount.incrementAndGet();
            }
        });
    }

    @AfterEach
    void tearDown() {
        service.close();
        db.close();
    }

    private void file(String relative, String body) throws Exception {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.ISO_8859_1);
    }

    /** Waits until no scan has finished for 600 ms (start() and addFolder() may each scan the folder). */
    private void awaitQuiet() throws Exception {
        int last = -1;
        long since = System.nanoTime();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            int now = finishedCount.get();
            if (now != last) {
                last = now;
                since = System.nanoTime();
            } else if (System.nanoTime() - since > TimeUnit.MILLISECONDS.toNanos(600)) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private void awaitChange() throws Exception {
        assertNotNull(events.poll(10, TimeUnit.SECONDS), "libraryChanged was not called within 10 s");
    }

    @Test
    void addFolderScansAndNotifies() throws Exception {
        file("a.tst", "alpha");
        file("album\\b.tst", "bravo");

        service.addFolder(root);

        awaitChange();
        assertEquals(2, db.trackCount());
        assertEquals(List.of(root), db.folders());
        assertFalse(progress.isEmpty());
        assertEquals(2, progress.get(progress.size() - 1).done());
    }

    @Test
    void everyScanEndsWithScanFinishedNamingTheFolderAndWhatItDid() throws Exception {
        file("a.tst", "alpha");
        file("album\\b.tst", "bravo");

        service.addFolder(root);

        Object[] first = finished.poll(10, TimeUnit.SECONDS);
        assertNotNull(first, "scanFinished was not called within 10 s");
        assertEquals(root, first[0]);
        assertEquals(2, ((ScanResult) first[1]).added());

        service.rescanAll();
        Object[] again = finished.poll(10, TimeUnit.SECONDS);
        assertNotNull(again);
        assertEquals(2, ((ScanResult) again[1]).unchanged(), "a rescan with no change reports the files skipped");
    }

    @Test
    void newFileInWatchedFolderIndexed() throws Exception {
        file("album\\b.tst", "bravo");
        service.start();
        service.addFolder(root);
        awaitChange();
        assertEquals(1, db.trackCount());

        file("album\\c.tst", "charlie");

        awaitChange();
        assertEquals(2, db.trackCount());
    }

    @Test
    void aDeletedFileIsRemovedByTheWatcher() throws Exception {
        file("album\\b.tst", "bravo");
        file("album\\c.tst", "charlie");
        service.start();
        service.addFolder(root);
        awaitChange();

        Files.delete(root.resolve("album").resolve("c.tst"));

        awaitChange();
        assertEquals(1, db.trackCount());
    }

    @Test
    void startScansTheFoldersAlreadyStored() throws Exception {
        file("a.tst", "alpha");
        db.addFolder(root);

        service.start();

        awaitChange();
        assertEquals(1, db.trackCount());
    }

    @Test
    void startWatchesTheFoldersAlreadyStored() throws Exception {
        file("album\\a.tst", "alpha");
        db.addFolder(root);
        service.start();
        awaitChange();

        file("album\\b.tst", "bravo");

        awaitChange();
        assertEquals(2, db.trackCount());
    }

    @Test
    void removeFolderDeletesItsTracksAndNotifies() throws Exception {
        file("a.tst", "alpha");
        service.addFolder(root);
        awaitChange();

        service.removeFolder(root);

        awaitChange();
        assertEquals(0, db.trackCount());
        assertTrue(db.folders().isEmpty());
    }

    @Test
    void rescanAllPicksUpChangesMadeWhileNotWatching() throws Exception {
        file("a.tst", "alpha");
        service.addFolder(root); // not started, so not watched
        awaitChange();
        file("b.tst", "bravo");

        service.rescanAll();

        awaitChange();
        assertEquals(2, db.trackCount());
    }

    @Test
    void anUnchangedRescanDoesNotNotify() throws Exception {
        file("a.tst", "alpha");
        service.addFolder(root);
        awaitChange();

        service.rescanAll();
        service.rescanAll();

        assertNull(events.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void scansNeverOverlap() throws Exception {
        for (int i = 0; i < 30; i++) {
            file("d\\t" + i + ".tst", "n" + i);
        }
        service.addFolder(root);
        service.rescanAll();
        service.rescanAll();
        awaitChange();
        service.close();

        assertEquals(30, db.trackCount());
        assertEquals(30, handler.probes.get(), "each file is read once: the later scans saw it unchanged");
    }

    @Test
    void aDriveRootIsScannedThroughTheFileSourceAndStoredAsAWatchedRoot() throws Exception {
        file("a.tst", "alpha");
        file("b.tst", "bravo");
        Path drive = root.getRoot();
        DecoderRegistry registry = new DecoderRegistry();
        registry.register(handler);
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        FileSource source = new FileSource() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<Path> audioFiles(Path driveRoot, java.util.Set<String> extensions) {
                asked.incrementAndGet();
                return List.of(root.resolve("a.tst")); // the index knows only a.tst
            }
        };
        Scanner scanner = new Scanner(db, registry, new ScanErrorLog(tmp.resolve("drive-errors.log")), p -> false, source);
        try (LibraryService drives = new LibraryService(db, scanner,
                c -> new FolderWatcher(c, Duration.ofMillis(200), registry::isAudioFile))) {
            drives.addListener(new LibraryListener() {
                @Override
                public void libraryChanged() {
                    events.add("changed");
                }
            });

            drives.addFolder(drive);

            awaitChange();
            assertEquals(1, asked.get(), "the drive was listed by the source, not walked");
            assertEquals(1, db.trackCount());
            assertEquals(List.of(drive), db.folders());
        }
    }

    @Test
    void closeStopsWatchingAndScanning() throws Exception {
        file("a.tst", "alpha");
        service.start();
        service.addFolder(root);
        awaitChange();
        service.close();

        file("b.tst", "bravo");

        assertNull(events.poll(1, TimeUnit.SECONDS));
        assertEquals(1, db.trackCount());
    }

    @Test
    void aWatcherReportReadsOnlyTheDirectoryThatChanged() throws Exception {
        file("album\\a.tst", "alpha");
        file("other\\b.tst", "bravo");
        file("other\\c.tst", "charlie");
        service.start();
        service.addFolder(root);
        awaitChange();
        awaitQuiet(); // start() and addFolder() may each scan the folder
        progress.clear();

        file("album\\d.tst", "delta");

        awaitChange();
        assertEquals(4, db.trackCount());
        assertFalse(progress.isEmpty());
        assertTrue(progress.stream().allMatch(p -> p.total() == 2), "only album\\ was read: " + progress);
    }

    @Test
    void changesInTheRecycleBinAreNeverScanned() throws Exception {
        file("a.tst", "alpha");
        service.start();
        service.addFolder(root);
        awaitChange();
        int probes = handler.probes.get();

        file("$RECYCLE.BIN\\S-1-5\\$R1.tst", "deleted");
        file("a2.tst", "after"); // a later, real change proves the watcher has been through the bin's event
        awaitChange();

        assertEquals(2, db.trackCount());
        assertEquals(probes + 1, handler.probes.get(), "the file in the recycle bin was never read");
    }

    @Test
    void aFolderWithADotInItsNameMovedAwayTakesItsTracksOutOfTheLibrary() throws Exception {
        file("keep.tst", "alpha");
        file("R.E.M/Automatic/a.tst", "bravo");
        service.start();
        service.addFolder(root);
        awaitChange();
        awaitQuiet();
        assertEquals(2, db.trackCount());

        // One ENTRY_DELETE for the folder itself, named like a file: no per-file events follow.
        Files.move(root.resolve("R.E.M"), tmp.resolve("elsewhere"));

        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (db.trackCount() != 1 && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertEquals(1, db.trackCount(), "the ghost album is gone without a restart");
    }

    @Test
    void aFileWithoutAnAudioExtensionDoesNotTriggerAScan() throws Exception {
        file("a.tst", "alpha");
        file("cache\\old.html", "<html>"); // the folder exists before the watch starts
        service.start();
        service.addFolder(root);
        awaitChange();
        awaitQuiet();
        int finishedBefore = finishedCount.get();

        file("cache\\page.html", "<html>");
        file("log.txt", "noise");

        assertNull(events.poll(1, TimeUnit.SECONDS));
        assertEquals(finishedBefore, finishedCount.get(), "no scan ran for files that are not audio");
    }

    @Test
    void closingMidScanNeverInterruptsTheFileBeingReadOrMarksItBroken() throws Exception {
        java.util.concurrent.CountDownLatch inProbe = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        DecoderRegistry slow = new DecoderRegistry();
        slow.register(new purist.decode.FormatHandler() {
            @Override
            public List<String> extensions() {
                return List.of("tst");
            }

            @Override
            public List<purist.decode.TrackInfo> probe(Path p) throws java.io.IOException {
                inProbe.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    throw new java.nio.channels.ClosedByInterruptException();
                }
                interrupted.compareAndSet(false, Thread.currentThread().isInterrupted());
                return List.of(new purist.decode.TrackInfo(p, 0, "TST",
                        purist.audio.StreamFormat.pcm(44100, 16, 2, 441000), new purist.tags.TagMap(), true, null));
            }

            @Override
            public purist.decode.Decoder open(Path p, int subsong) {
                throw new AssertionError();
            }
        });
        file("a.tst", "alpha");
        file("b.tst", "bravo");
        Scanner scanner = new Scanner(db, slow, new ScanErrorLog(tmp.resolve("slow-errors.log")), p -> false);
        LibraryService closing = new LibraryService(db, scanner, c -> new FolderWatcher(c, Duration.ofMillis(200), slow::isAudioFile));
        closing.addFolder(root);
        assertTrue(inProbe.await(10, TimeUnit.SECONDS));
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                // ignored
            }
            release.countDown();
        }).start();

        closing.close(); // waits for the file being read; does not interrupt it

        assertFalse(interrupted.get(), "the read was interrupted by close()");
        assertEquals(0, db.tracks("t.supported = 0", List.of(), null).size(), "no good file was marked broken");
    }
}
