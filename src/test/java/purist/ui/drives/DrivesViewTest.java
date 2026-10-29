package purist.ui.drives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import purist.ui.drives.DriveListModel.Drive;
import purist.ui.drives.DriveListModel.State;

class DrivesViewTest {

    private static final Drive C = new Drive(Path.of("C:\\"), "Windows", 953L * 1024 * 1024 * 1024);
    private static final Drive E = new Drive(Path.of("E:\\"), "Data", 2L * 1024 * 1024 * 1024 * 1024);

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> threads = new CopyOnWriteArrayList<>();
    private volatile boolean everything = true;

    private final DrivesView.Backend backend = new DrivesView.Backend() {
        @Override
        public List<Drive> drives() {
            threads.add(SwingUtilities.isEventDispatchThread() ? "edt" : "io");
            return List.of(C, E);
        }

        @Override
        public boolean everythingRunning() {
            threads.add(SwingUtilities.isEventDispatchThread() ? "edt" : "io");
            return everything;
        }

        @Override
        public List<Path> watched() {
            return List.of(Path.of("C:\\"));
        }

        @Override
        public void add(Path drive) {
            calls.add("add " + drive);
        }

        @Override
        public void remove(Path drive) {
            calls.add("remove " + drive);
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

    private DrivesView opened() throws Exception {
        DrivesView[] v = new DrivesView[1];
        SwingUtilities.invokeAndWait(() -> {
            v[0] = new DrivesView(backend);
            v[0].refresh();
        });
        await(() -> v[0].rows().size() == 2, "the drives");
        return v[0];
    }

    @Test
    void theDrivesAreListedAndAskedForOffTheUiThread() throws Exception {
        DrivesView v = opened();
        assertEquals(List.of("io", "io"), threads);
        assertTrue(v.rows().get(0).ticked(), "C: is a watched root");
        assertFalse(v.rows().get(1).ticked());
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("C:\\  WINDOWS  953 GB", v.box(0).getText());
            assertTrue(v.box(0).isSelected());
            assertFalse(v.box(1).isSelected());
        });
    }

    @Test
    void thereIsAOneLineHintToTickTheDrives() {
        assertEquals("Tick the drives that hold your music.", DrivesView.HINT);
    }

    @Test
    void tickingADriveAddsItAsARootAndQueuesIt() throws Exception {
        DrivesView v = opened();
        SwingUtilities.invokeAndWait(() -> v.box(1).doClick());
        await(() -> calls.contains("add E:\\"), "the add");
        assertEquals(State.QUEUED, v.rows().get(1).state());
        SwingUtilities.invokeAndWait(() -> assertEquals(true, v.box(1).isSelected()));
    }

    @Test
    void progressAndResultAppearOnTheRow() throws Exception {
        DrivesView v = opened();
        SwingUtilities.invokeAndWait(() -> {
            v.scanProgress(new ScanProgress(12345, 40000, Path.of("E:\\Music\\a.flac"), Path.of("E:\\")));
            assertEquals("SCANNING 12,345 / 40,000", v.rows().get(1).status());
            v.scanFinished(new LibraryFeed.Finished(Path.of("E:\\"), new ScanResult(40000, 0, 0, 0, 0)));
            assertEquals(State.DONE, v.rows().get(1).state());
            assertTrue(v.rows().get(1).status().startsWith("40,000 ADDED"));
        });
    }

    @Test
    void theUnindexedNoticeIsShownOnThatDrivesRowOnly() throws Exception {
        DrivesView v = opened();
        SwingUtilities.invokeAndWait(() -> v.scanFinished(new LibraryFeed.Finished(Path.of("E:\\"),
                new ScanResult(3, 0, 0, 0, 0, true))));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Everything has no index for E: \u2014 scanning folders instead", v.rows().get(1).notice());
            assertEquals("", v.rows().get(0).notice());
            assertEquals("E:\\  DATA  2.0 TB", v.box(1).getText(), "the row still starts with its check box");
        });
    }

    @Test
    void theTickTheDrivesHintIsForTheFirstRunOnly() throws Exception {
        boolean[] first = new boolean[2];
        SwingUtilities.invokeAndWait(() -> {
            first[0] = new DrivesView(backend, true).hintShown();
            first[1] = new DrivesView(backend, false).hintShown();
        });
        assertTrue(first[0]);
        assertFalse(first[1]);
    }

    @Test
    void theEverythingLineSaysWhetherItRuns() throws Exception {
        everything = false;
        DrivesView v = opened();
        await(() -> v.everythingText().startsWith("EVERYTHING IS NOT RUNNING"), "the status line");
    }
}
