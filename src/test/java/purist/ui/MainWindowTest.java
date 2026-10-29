package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainWindowTest {

    @Test
    void resumesFromTheFirstTrackWhenNothingEverStarted() {
        // The DAC was off at launch: the first open failed, so no index was ever reported (lastIndex stayed -1).
        assertEquals(0, MainWindow.resumeIndex(-1, 12));
    }

    @Test
    void resumesFromTheLastIndexClampedToTheQueue() {
        assertEquals(5, MainWindow.resumeIndex(5, 12));
        assertEquals(11, MainWindow.resumeIndex(40, 12));
        assertEquals(0, MainWindow.resumeIndex(0, 1));
    }

    @Test
    void anEmptyQueueHasNothingToResume() {
        assertEquals(-1, MainWindow.resumeIndex(-1, 0));
        assertEquals(-1, MainWindow.resumeIndex(3, 0));
    }

    @Test
    void afterTheQueueRanOutPlayContinuesWithWhatWasAddedNotTheOldLastTrack() {
        assertEquals(12, MainWindow.resumeIndex(11, 15, true), "three tracks were added after the last");
        assertEquals(11, MainWindow.resumeIndex(11, 12, true), "nothing was added: the last track again");
        assertEquals(5, MainWindow.resumeIndex(5, 12, false), "a stop mid-queue resumes where it was");
        assertEquals(-1, MainWindow.resumeIndex(3, 0, true));
        assertEquals(0, MainWindow.resumeIndex(-1, 4, true));
    }

    @Test
    void aFailingShutdownStepDoesNotSkipTheOnesAfterIt() {
        java.util.List<String> ran = new java.util.ArrayList<>();

        int failed = MainWindow.runAll(
                new MainWindow.CloseStep("stop the workers", () -> {
                    ran.add("workers");
                    throw new IllegalStateException("a pool refused");
                }),
                new MainWindow.CloseStep("close the playlists", () -> ran.add("playlists")),
                new MainWindow.CloseStep("close the library", () -> ran.add("library")));

        assertEquals(1, failed);
        assertEquals(java.util.List.of("workers", "playlists", "library"), ran,
                "the playlist flush and the DB close still run");
    }
}
