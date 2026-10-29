package purist.ui.drives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import purist.library.ScanProgress;
import purist.library.ScanResult;
import purist.ui.drives.DriveListModel.Drive;
import purist.ui.drives.DriveListModel.Row;
import purist.ui.drives.DriveListModel.State;

class DriveListModelTest {

    private static final Drive C = new Drive(Path.of("C:\\"), "Windows", 953L * 1024 * 1024 * 1024);
    private static final Drive E = new Drive(Path.of("E:\\"), "Data", 2_000_398_934_016L);
    private static final Drive G = new Drive(Path.of("G:\\"), "", 500L * 1024 * 1024 * 1024);

    private DriveListModel model() {
        DriveListModel m = new DriveListModel();
        m.setDrives(List.of(C, E, G));
        return m;
    }

    @Test
    void everyDriveIsARowAndNoneIsTickedAtFirst() {
        DriveListModel m = model();
        assertEquals(3, m.rows().size());
        assertTrue(m.rows().stream().noneMatch(Row::ticked));
        assertEquals(State.IDLE, m.rows().get(0).state());
    }

    @Test
    void theWatchedRootsAreTickedIgnoringCaseAndATrailingBackslash() {
        DriveListModel m = model();
        m.setWatched(List.of(Path.of("e:\\"), Path.of("C:\\Users\\me\\Music")));
        assertFalse(m.rows().get(0).ticked(), "a folder on C: is not the drive");
        assertTrue(m.rows().get(1).ticked());
        assertFalse(m.rows().get(2).ticked());
    }

    @Test
    void tickingQueuesTheDriveAndUntickingForgetsEverythingAboutIt() {
        DriveListModel m = model();
        m.tick(E.root());
        assertTrue(m.rows().get(1).ticked());
        assertEquals(State.QUEUED, m.rows().get(1).state());
        assertEquals("QUEUED", m.rows().get(1).status());
        m.untick(E.root());
        assertFalse(m.rows().get(1).ticked());
        assertEquals(State.IDLE, m.rows().get(1).state());
        assertEquals("", m.rows().get(1).status());
    }

    @Test
    void progressMarksTheDriveHoldingTheFileAndShowsDoneOverTotal() {
        DriveListModel m = model();
        m.tick(E.root());
        m.progress(new ScanProgress(12345, 40000, Path.of("e:\\Music\\a\\b.flac"), Path.of("E:\\")));
        Row e = m.rows().get(1);
        assertEquals(State.SCANNING, e.state());
        assertEquals("SCANNING 12,345 / 40,000", e.status());
        assertEquals(State.IDLE, m.rows().get(0).state(), "other drives are not touched");
    }

    @Test
    void aFinishedScanShowsTheResultLine() {
        DriveListModel m = model();
        m.tick(E.root());
        m.progress(new ScanProgress(5, 5, Path.of("E:\\x.flac"), E.root()));
        m.finished(E.root(), new ScanResult(12, 1, 3, 40000, 2));
        Row e = m.rows().get(1);
        assertEquals(State.DONE, e.state());
        assertEquals("12 ADDED  1 UPDATED  3 REMOVED  40,000 UNCHANGED  2 ERRORS", e.status());
    }

    @Test
    void aScanOfAFolderNotADriveIsIgnored() {
        DriveListModel m = model();
        m.finished(Path.of("C:\\Users\\me\\Music"), new ScanResult(1, 0, 0, 0, 0));
        assertEquals(State.IDLE, m.rows().get(0).state());
    }

    @Test
    void aScanOfAFolderOnTheDriveOrOfUnknownRootLeavesTheDriveRowAlone() {
        DriveListModel m = model();
        m.tick(E.root());
        m.progress(new ScanProgress(7, 9, Path.of("E:\\Music\\a.flac"), Path.of("E:\\Music")));
        m.progress(new ScanProgress(8, 9, Path.of("E:\\Music\\b.flac")));
        m.finished(Path.of("E:\\Music"), new ScanResult(1, 0, 0, 0, 0));
        assertEquals(State.QUEUED, m.rows().get(1).state(), "still just queued: no drive scan has reported");
        assertEquals("QUEUED", m.rows().get(1).status());
    }

    @Test
    void theNoticeForAnUnindexedDriveBelongsToThatDriveAndGoesWithTheNextScan() {
        DriveListModel m = model();
        m.tick(E.root());
        m.progress(new ScanProgress(1, 2, Path.of("E:\\x.flac"), E.root()));
        m.finished(E.root(), new ScanResult(2, 0, 0, 0, 0, true));

        assertEquals("Everything has no index for E: \u2014 scanning folders instead", m.rows().get(1).notice());
        assertEquals("", m.rows().get(0).notice(), "other drives have none");

        m.progress(new ScanProgress(1, 2, Path.of("E:\\x.flac"), E.root()));
        assertEquals("", m.rows().get(1).notice());
    }

    @Test
    void tickingAgainClearsTheOldNotice() {
        DriveListModel m = model();
        m.tick(E.root());
        m.finished(E.root(), new ScanResult(2, 0, 0, 0, 0, true));
        assertFalse(m.rows().get(1).notice().isEmpty());
        m.untick(E.root());
        m.tick(E.root());
        assertEquals("", m.rows().get(1).notice());
    }

    @Test
    void sizesReadLikeADriveSize() {
        assertEquals("1.8 TB", DriveListModel.sizeText(2_000_398_934_016L));
        assertEquals("953 GB", DriveListModel.sizeText(953L * 1024 * 1024 * 1024));
        assertEquals("512 MB", DriveListModel.sizeText(512L * 1024 * 1024));
        assertEquals("0 B", DriveListModel.sizeText(0));
        assertEquals("10.0 TB", DriveListModel.sizeText(10_995_116_277_760L));
    }

    @Test
    void theTitleIsTheLetterTheLabelAndTheSize() {
        assertEquals("E:\\  DATA  1.8 TB", DriveListModel.title(E));
        assertEquals("G:\\  500 GB", DriveListModel.title(G), "no label, no gap");
    }

    @Test
    void rebuildingTheDriveListKeepsWhatWeKnowAboutEachDrive() {
        DriveListModel m = model();
        m.tick(E.root());
        m.progress(new ScanProgress(1, 2, Path.of("E:\\x.flac"), E.root()));
        m.setDrives(List.of(C, E));
        assertEquals(2, m.rows().size());
        assertEquals(State.SCANNING, m.rows().get(1).state());
        assertTrue(m.rows().get(1).ticked());
    }
}
