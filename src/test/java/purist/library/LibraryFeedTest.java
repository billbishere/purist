package purist.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LibraryFeedTest {

    @Test
    void keepsOnlyTheLatestProgressAndClearsItWhenTheScanEnds() {
        LibraryFeed f = new LibraryFeed();
        assertNull(f.progress());
        f.scanProgress(new ScanProgress(1, 10, Path.of("E:\\a")));
        f.scanProgress(new ScanProgress(2, 10, Path.of("E:\\b")));
        assertEquals(2, f.progress().done());
        f.scanFinished(Path.of("E:\\"), new ScanResult(10, 0, 0, 0, 0));
        assertNull(f.progress());
    }

    @Test
    void changedIsReportedOncePerChange() {
        LibraryFeed f = new LibraryFeed();
        assertFalse(f.takeChanged());
        f.libraryChanged();
        f.libraryChanged();
        assertTrue(f.takeChanged());
        assertFalse(f.takeChanged());
    }

    @Test
    void noticesAndResultsAreKeptInOrderUntilTaken() {
        LibraryFeed f = new LibraryFeed();
        f.scanNotice("one");
        f.scanNotice("two");
        f.scanFinished(Path.of("E:\\"), new ScanResult(1, 2, 3, 4, 5));
        f.scanFinished(Path.of("G:\\"), new ScanResult(0, 0, 0, 9, 0));
        assertEquals(List.of("one", "two"), f.takeNotices());
        assertEquals(List.of(), f.takeNotices());
        List<LibraryFeed.Finished> done = f.takeFinished();
        assertEquals(2, done.size());
        assertEquals(Path.of("E:\\"), done.get(0).folder());
        assertEquals(9, done.get(1).result().unchanged());
        assertEquals(List.of(), f.takeFinished());
    }

    @Test
    void aRunningScanReportsTheLibraryAsChangedAboutEveryTenSecondsOnceItHasStoredABatch() {
        long[] now = {0};
        LibraryFeed f = new LibraryFeed(() -> now[0], 10_000_000_000L);
        Path file = Path.of("E:\\a");

        f.scanProgress(new ScanProgress(1, 100_000, file));
        now[0] = 20_000_000_000L; // slow start, but no batch stored yet
        f.scanProgress(new ScanProgress(50, 100_000, file));
        assertFalse(f.takeChanged(), "nothing has been committed yet");

        f.scanProgress(new ScanProgress(Scanner.BATCH, 100_000, file));
        assertTrue(f.takeChanged(), "first batch stored and ten seconds passed");
        now[0] += 3_000_000_000L;
        f.scanProgress(new ScanProgress(500, 100_000, file));
        assertFalse(f.takeChanged(), "not again so soon");
        now[0] += 8_000_000_000L;
        f.scanProgress(new ScanProgress(900, 100_000, file));
        assertTrue(f.takeChanged());
    }

    @Test
    void aFastScanReportsNothingExtra() {
        long[] now = {0};
        LibraryFeed f = new LibraryFeed(() -> now[0], 10_000_000_000L);
        for (int i = 1; i <= 1000; i++) {
            now[0] += 1_000_000; // 1 ms per file
            f.scanProgress(new ScanProgress(i, 1000, Path.of("E:\\a")));
        }
        assertFalse(f.takeChanged());
    }
}
