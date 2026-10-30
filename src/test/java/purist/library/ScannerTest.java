package purist.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.tags.TagMap;

class ScannerTest {

    /** Reads {@code *.tst} files: a body starting with {@code BAD} is damaged, any other is a stereo PCM track. */
    static final class CountingHandler implements FormatHandler {
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();

        @Override
        public List<String> extensions() {
            return List.of("tst");
        }

        @Override
        public List<TrackInfo> probe(Path p) throws IOException {
            probes.incrementAndGet();
            String body = Files.readString(p, StandardCharsets.ISO_8859_1);
            if (body.startsWith("BAD")) {
                throw new DecodeException("bad header in " + p.getFileName());
            }
            if (body.startsWith("SOFLOW")) {
                throw new StackOverflowError("pathological nesting");
            }
            if (body.startsWith("LOCKED")) {
                throw new java.nio.file.AccessDeniedException(p.toString());
            }
            if (body.startsWith("IOERR")) {
                throw new IOException("device not ready");
            }
            if (body.startsWith("INTERRUPT")) {
                throw new java.nio.channels.ClosedByInterruptException();
            }
            TagMap tags = new TagMap();
            tags.add("TITLE", body);
            return List.of(new TrackInfo(p, 0, "TST", StreamFormat.pcm(44100, 16, 2, 441000), tags, true, null));
        }

        @Override
        public Decoder open(Path p, int subsong) {
            opens.incrementAndGet();
            throw new AssertionError("a scan must never open a decoder");
        }
    }

    @TempDir
    Path tmp;

    private Path root;
    private LibraryDb db;
    private CountingHandler handler;
    private DecoderRegistry registry;
    private ScanErrorLog log;
    private Predicate<Path> cloud = p -> false;
    private final List<ScanProgress> progress = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("music"));
        db = LibraryDb.open(tmp.resolve("db").resolve("library.db"));
        handler = new CountingHandler();
        registry = new DecoderRegistry();
        registry.register(handler);
        log = new ScanErrorLog(tmp.resolve("scan-errors.log"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private Scanner scanner() {
        return new Scanner(db, registry, log, p -> cloud.test(p));
    }

    private ScanResult scan() {
        progress.clear();
        return scanner().scan(root, progress::add, () -> false);
    }

    private Path file(String relative, String body) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, body, StandardCharsets.ISO_8859_1);
        return p;
    }

    private TrackRow row(Path p) {
        return db.tracks("t.path = ?", List.of(p.toString()), null).get(0);
    }

    @Test
    void firstScanAddsAll() throws Exception {
        Path a = file("a.tst", "alpha");
        file("album\\b.tst", "bravo");
        file("album\\deep\\c.tst", "charlie");
        file("album\\cover.jpg", "not audio");
        file("notes.txt", "not audio");

        ScanResult r = scan();

        assertEquals(new ScanResult(3, 0, 0, 0, 0), r);
        assertEquals(3, db.trackCount());
        assertEquals(3, handler.probes.get());
        assertEquals("alpha", row(a).tags().first("TITLE").orElseThrow());
    }

    @Test
    void rescanUnchangedDoesNotReprobe() throws Exception {
        file("a.tst", "alpha");
        file("b.tst", "bravo");
        scan();
        handler.probes.set(0);

        ScanResult r = scan();

        assertEquals(new ScanResult(0, 0, 0, 2, 0), r);
        assertEquals(0, handler.probes.get());
    }

    @Test
    void modifiedFileReprobed() throws Exception {
        Path a = file("a.tst", "alpha");
        file("b.tst", "bravo");
        scan();
        handler.probes.set(0);

        Files.writeString(a, "alpha two", StandardCharsets.ISO_8859_1); // a new size

        ScanResult r = scan();

        assertEquals(new ScanResult(0, 1, 0, 1, 0), r);
        assertEquals(1, handler.probes.get());
        assertEquals("alpha two", row(a).tags().first("TITLE").orElseThrow());
    }

    @Test
    void sameSizeNewMtimeIsReprobed() throws Exception {
        Path a = file("a.tst", "alpha");
        scan();
        handler.probes.set(0);
        Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 60_000));

        ScanResult r = scan();

        assertEquals(1, r.updated());
        assertEquals(1, handler.probes.get());
    }

    @Test
    void deletedFileRemoved() throws Exception {
        Path a = file("a.tst", "alpha");
        file("b.tst", "bravo");
        scan();

        Files.delete(a);

        ScanResult r = scan();

        assertEquals(new ScanResult(0, 0, 1, 1, 0), r);
        assertEquals(1, db.trackCount());
        assertTrue(db.tracks("t.path = ?", List.of(a.toString()), null).isEmpty());
    }

    @Test
    void corruptFileLoggedAndKept() throws Exception {
        file("good.tst", "fine");
        Path bad = file("bad.tst", "BAD data");

        ScanResult r = scan();

        assertEquals(1, r.added());
        assertEquals(1, r.errors());
        assertEquals(2, db.trackCount());
        TrackRow row = row(bad);
        assertFalse(row.supported());
        assertNotNull(row.error());
        assertTrue(row.error().contains("bad header"), row.error());
        List<String> lines = log.tail(10);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains(bad + ": bad header"), lines.get(0));

        handler.probes.set(0);
        ScanResult again = scan(); // the stamp is stored, so the damaged file is not re-read
        assertEquals(0, handler.probes.get());
        assertEquals(new ScanResult(0, 0, 0, 2, 0), again);
    }

    @Test
    void anErrorFromOneFileMarksThatFileAndTheScanContinues() throws Exception {
        file("a.tst", "alpha");
        Path deep = file("b.tst", "SOFLOW");
        file("c.tst", "charlie");

        ScanResult r = scan();

        assertEquals(2, r.added());
        assertEquals(1, r.errors());
        assertEquals(3, db.trackCount());
        assertFalse(row(deep).supported());
        assertEquals(1, log.tail(10).size());
    }

    @Test
    void aFileReadInterruptedByCloseIsNeitherMarkedBrokenNorDeletesAnything() throws Exception {
        file("a.tst", "alpha");
        Path victim = file("b.tst", "bravo");
        Path gone = file("c.tst", "charlie");
        scan(); // stores all three
        Files.writeString(victim, "INTERRUPT", StandardCharsets.ISO_8859_1); // b changed: re-read, and the read is cut off
        Files.delete(gone); // c is not on disk any more, but the interrupted scan must not remove anything

        ScanResult r = scan();

        assertEquals(0, r.errors());
        assertEquals(0, r.removed());
        assertEquals(3, db.trackCount(), "c's row survives the interrupted scan");
        TrackRow b = row(victim);
        assertTrue(b.supported(), "an interrupted read leaves no error row for the file");
        assertEquals("bravo", b.tags().first("TITLE").orElseThrow());
        assertEquals(0, log.tail(10).size());
    }

    @Test
    void aTransientIoErrorOnAnExistingFileKeepsItsRowsAndIsRetriedNextScan() throws Exception {
        Path a = file("a.tst", "alpha");
        file("b.tst", "bravo");
        scan();
        // A tagger or antivirus scanner now holds a exclusively (and a changed, so it is due for a re-read).
        Files.writeString(a, "LOCKED by another process", StandardCharsets.ISO_8859_1);
        handler.probes.set(0);

        ScanResult r = scan();

        assertEquals(1, r.errors());
        assertEquals(0, r.updated());
        assertEquals(2, db.trackCount());
        TrackRow kept = row(a);
        assertTrue(kept.supported(), "the good row is untouched");
        assertEquals("alpha", kept.tags().first("TITLE").orElseThrow());
        assertEquals(1, log.tail(10).size());

        // The stamp was not stored, so the next scan reads the file again (and still fails while it is locked).
        handler.probes.set(0);
        assertEquals(1, scan().errors());
        assertEquals(1, handler.probes.get(), "a retry, not a skip as unchanged");
        assertTrue(row(a).supported());

        // Once the lock is gone the file is read and its rows replaced.
        Files.writeString(a, "alpha unlocked", StandardCharsets.ISO_8859_1);
        ScanResult ok = scan();
        assertEquals(1, ok.updated());
        assertEquals(0, ok.errors());
        assertEquals("alpha unlocked", row(a).tags().first("TITLE").orElseThrow());
    }

    @Test
    void anIoErrorOfAnyKindOnANewFileStoresNothingAndIsRetried() throws Exception {
        Path locked = file("a.tst", "LOCKED");
        Path flaky = file("b.tst", "IOERR");

        ScanResult r = scan();

        assertEquals(2, r.errors());
        assertEquals(0, db.trackCount());
        assertTrue(db.tracks("t.path = ?", List.of(locked.toString()), null).isEmpty());
        assertTrue(db.tracks("t.path = ?", List.of(flaky.toString()), null).isEmpty());
        handler.probes.set(0);
        scan();
        assertEquals(2, handler.probes.get());
    }

    @Test
    void aNonSacdIsoIsAnUnsupportedRowNotAnErrorOrALogLine() throws Exception {
        Path iso = file("dvd.iso", "x".repeat(5000));

        ScanResult r = new Scanner(db, DecoderRegistry.standard(), log, p -> false).scan(root, x -> { }, () -> false);

        assertEquals(new ScanResult(1, 0, 0, 0, 0), r);
        TrackRow row = row(iso);
        assertFalse(row.supported());
        assertEquals("not an SACD image", row.error());
        assertEquals(0, log.tail(10).size());
    }

    @Test
    void cloudOnlyNeverOpened() throws Exception {
        Path a = file("a.tst", "alpha");
        cloud = p -> true;

        ScanResult r = scan();

        assertEquals(new ScanResult(1, 0, 0, 0, 0), r);
        assertEquals(0, handler.probes.get());
        assertEquals(0, handler.opens.get());
        TrackRow row = row(a);
        assertTrue(row.supported());
        assertNull(row.format());
        assertEquals("TST", row.codec());
    }

    @Test
    void unsupportedFormatsAreStoredGreyedOutWithoutReadingThem() throws Exception {
        Path mp3 = file("song.mp3", "anything");

        ScanResult r = new Scanner(db, DecoderRegistry.standard(), log, p -> false).scan(root, x -> { }, () -> false);

        assertEquals(1, r.added());
        TrackRow row = row(mp3);
        assertFalse(row.supported());
        assertEquals("MP3 is not supported in v1", row.error());
    }

    @Test
    void progressCountsEveryCandidate() throws Exception {
        file("a.tst", "alpha");
        file("b.tst", "bravo");
        file("c.tst", "charlie");

        scan();

        assertEquals(3, progress.size());
        assertEquals(3, progress.get(progress.size() - 1).done());
        assertTrue(progress.stream().allMatch(p -> p.total() == 3));
        assertNotNull(progress.get(0).current());
    }

    @Test
    void manyFilesAreAllStoredAcrossBatches() throws Exception {
        for (int i = 0; i < 450; i++) {
            file("d" + (i % 7) + "\\t" + i + ".tst", "n" + i);
        }

        ScanResult r = scan();

        assertEquals(450, r.added());
        assertEquals(450, db.trackCount());
    }

    @Test
    void tracksOutsideTheRootAreUntouched() throws Exception {
        file("a.tst", "alpha");
        Path other = Files.createDirectories(tmp.resolve("music2"));
        Files.writeString(other.resolve("z.tst"), "zulu");
        Scanner s = scanner();
        s.scan(other, x -> { }, () -> false);
        s.scan(root, x -> { }, () -> false);

        Files.delete(root.resolve("a.tst"));
        ScanResult r = s.scan(root, x -> { }, () -> false);

        assertEquals(1, r.removed());
        assertEquals(1, db.trackCount()); // z.tst survives; "music2" is not under "music"
    }

    @Test
    void cancelledScanKeepsUnseenTracks() throws Exception {
        file("a.tst", "alpha");
        file("b.tst", "bravo");
        file("c.tst", "charlie");
        scan();
        int[] calls = {0};

        ScanResult r = scanner().scan(root, p -> { }, () -> ++calls[0] > 1);

        assertEquals(0, r.removed());
        assertEquals(3, db.trackCount());
    }

    @Test
    void anAbsentRootRemovesNothing() throws Exception {
        file("a.tst", "alpha");
        scan();
        Path gone = tmp.resolve("unplugged");

        ScanResult r = scanner().scan(gone, p -> { }, () -> false);

        assertEquals(0, r.removed());
        assertEquals(1, r.errors());
        assertEquals(1, db.trackCount());
        assertEquals(1, log.tail(10).size());
    }

    @Test
    void recycleBinAndSystemVolumeInformationAreSkipped() throws Exception {
        file("a.tst", "alpha");
        file("$RECYCLE.BIN\\S-1-5\\$R1.tst", "deleted");
        file("System Volume Information\\x.tst", "system");

        ScanResult r = scan();

        assertEquals(1, r.added());
    }

    @Test
    void volumeRootSystemFoldersAreRecognisedWhereverTheScanRootIs() {
        assertTrue(Scanner.inVolumeRootSystemFolder(Path.of("E:\\$RECYCLE.BIN\\S-1-5\\$R1.flac")));
        assertTrue(Scanner.inVolumeRootSystemFolder(Path.of("E:\\System Volume Information")));
        assertTrue(Scanner.inVolumeRootSystemFolder(Path.of("E:\\Recovery\\x.flac")));
        assertTrue(Scanner.inVolumeRootSystemFolder(Path.of("E:\\WindowsApps\\a\\b.flac")));
        assertFalse(Scanner.inVolumeRootSystemFolder(Path.of("E:\\Music\\Recovery\\x.flac")),
                "an album folder called Recovery is music");
        assertFalse(Scanner.inVolumeRootSystemFolder(Path.of("E:\\Music\\a.flac")));
        assertFalse(Scanner.inVolumeRootSystemFolder(Path.of("relative\\a.flac")));
    }

    @Test
    void aScanRootInsideAVolumeRootSystemFolderIsStillScanned() {
        Path inRecovery = Path.of("E:\\Recovery\\Music");
        assertFalse(Scanner.isSystemFolder(inRecovery, inRecovery), "the root the user added is music");
        assertFalse(Scanner.isSystemFolder(inRecovery, inRecovery.resolve("album\\a.flac")));
        Path share = Path.of("\\\\nas\\music\\Recovery");
        assertFalse(Scanner.isSystemFolder(share, share));
        assertFalse(Scanner.isSystemFolder(share, share.resolve("a.flac")));
        // Other roots keep the rule: a watcher event deep in the recycle bin or Recovery is still ignored.
        assertTrue(Scanner.isSystemFolder(Path.of("E:\\Music"), Path.of("E:\\Recovery\\x.flac")));
        assertTrue(Scanner.isSystemFolder(Path.of("E:\\"), Path.of("E:\\Recovery\\x.flac")));
        assertTrue(Scanner.isSystemFolder(inRecovery, inRecovery.resolve("$RECYCLE.BIN\\x.flac")),
                "a recycle bin directly under the root is still skipped");
    }

    @Test
    void rowsAnOlderBuildStoredInsideTheRecycleBinAreRemovedEvenThoughTheFilesExist() throws Exception {
        file("a.tst", "alpha");
        Path binned = file("$RECYCLE.BIN\\S-1-5\\$R1.tst", "deleted");
        scanner().scan(binned.getParent(), x -> { }, () -> false); // an old build indexed it as its own root
        assertEquals(1, db.trackCount());

        ScanResult r = scan();

        assertEquals(1, r.added());
        assertEquals(1, r.removed());
        assertEquals(1, db.trackCount());
        assertTrue(Files.exists(binned), "the scanner never touches the files");
    }

    // ---- shallow scans (what a watcher event needs) ----

    @Test
    void scanShallowReadsOnlyTheFilesDirectlyInTheFolder() throws Exception {
        file("album\\a.tst", "alpha");
        file("album\\deep\\b.tst", "bravo");
        file("c.tst", "charlie");

        ScanResult r = scanner().scanShallow(root.resolve("album"), x -> { }, () -> false);

        assertEquals(new ScanResult(1, 0, 0, 0, 0), r);
        assertEquals(1, db.trackCount());
        assertEquals(1, handler.probes.get());
    }

    @Test
    void scanShallowRemovesGoneFilesOfThatFolderAndLeavesSubfoldersAlone() throws Exception {
        Path a = file("album\\a.tst", "alpha");
        Path deep = file("album\\deep\\b.tst", "bravo");
        scan();
        Files.delete(a);
        Files.delete(deep); // gone too, but a shallow scan of album must not notice

        ScanResult r = scanner().scanShallow(root.resolve("album"), x -> { }, () -> false);

        assertEquals(1, r.removed());
        assertEquals(1, db.trackCount());
        assertEquals(0, db.tracks("t.path = ?", List.of(a.toString()), null).size());
    }

    // ---- whole-drive discovery ----

    /** A file-list source that answers from a fixed list and records what it was asked. */
    private static final class FakeSource implements FileSource {
        boolean available = true;
        List<Path> files = new ArrayList<>();
        RuntimeException failure;
        final AtomicInteger queries = new AtomicInteger();
        java.util.Set<String> lastExtensions;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<Path> audioFiles(Path driveRoot, java.util.Set<String> extensions) {
            queries.incrementAndGet();
            lastExtensions = extensions;
            if (failure != null) {
                throw failure;
            }
            return files;
        }
    }

    private Scanner driveScanner(FakeSource source) {
        return new Scanner(db, registry, log, p -> cloud.test(p), source);
    }

    private ScanResult scanDrive(FakeSource source) {
        progress.clear();
        return driveScanner(source).scanDrive(root, progress::add, () -> false);
    }

    @Test
    void scanDriveTakesItsFileListFromTheSourceInsteadOfWalking() throws Exception {
        Path a = file("a.tst", "alpha");
        file("b.tst", "bravo"); // on disk, but the index does not list it
        FakeSource source = new FakeSource();
        source.files.add(a);

        ScanResult r = scanDrive(source);

        assertEquals(new ScanResult(1, 0, 0, 0, 0), r);
        assertEquals(1, db.trackCount());
        assertEquals(1, source.queries.get());
        assertTrue(source.lastExtensions.contains("tst"));
        assertTrue(source.lastExtensions.contains("mp3"));
    }

    @Test
    void scanDriveFallsBackToWalkingWhenTheSourceIsNotAvailable() throws Exception {
        file("a.tst", "alpha");
        file("album\\b.tst", "bravo");
        FakeSource source = new FakeSource();
        source.available = false;

        ScanResult r = scanDrive(source);

        assertEquals(new ScanResult(2, 0, 0, 0, 0), r);
        assertEquals(0, source.queries.get());
    }

    @Test
    void scanDriveFallsBackToWalkingWhenTheSourceFails() throws Exception {
        file("a.tst", "alpha");
        FakeSource source = new FakeSource();
        source.failure = new IllegalStateException("Everything went away");

        ScanResult r = scanDrive(source);

        assertEquals(1, r.added());
    }

    @Test
    void scanDriveKeepsTheRowOfAFileTheIndexNoLongerListsButTheDiskStillHas() throws Exception {
        Path a = file("a.tst", "alpha");
        Path b = file("b.tst", "bravo");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, b));
        scanDrive(source);
        handler.probes.set(0);

        source.files.remove(b); // an exclusion, a hidden file or a half-built index: b is still on disk
        ScanResult r = scanDrive(source);

        assertEquals(new ScanResult(0, 0, 0, 1, 0), r);
        assertEquals(0, handler.probes.get());
        assertEquals(2, db.trackCount());
    }

    @Test
    void scanDriveRemovesAnUnlistedFileOnlyOnceTheDiskConfirmsItIsGone() throws Exception {
        Path a = file("a.tst", "alpha");
        Path b = file("b.tst", "bravo");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, b));
        scanDrive(source);
        handler.probes.set(0);

        source.files.remove(b);
        Files.delete(b); // deleted for real
        ScanResult r = scanDrive(source);

        assertEquals(new ScanResult(0, 0, 1, 1, 0), r);
        assertEquals(0, handler.probes.get());
        assertEquals(1, db.trackCount());
    }

    @Test
    void aPartialIndexAnswerNeverDeletesRowsOfFilesThatStillExist() throws Exception {
        List<Path> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(file("d\\t" + i + ".tst", "n" + i));
        }
        FakeSource source = new FakeSource();
        source.files.addAll(all);
        scanDrive(source);

        source.files.subList(1, 20).clear(); // the index now lists a single file
        ScanResult r = scanDrive(source);

        assertEquals(0, r.removed());
        assertEquals(20, db.trackCount());
    }

    @Test
    void anErrorOtherThanNoSuchFileNeverProvesAFileIsGone() throws Exception {
        Path a = file("a.tst", "alpha");
        Path b = file("b.tst", "bravo");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, b));
        scanDrive(source);

        // The disk now answers "access denied" for b: unlisted by the index, and the disk cannot say it is gone.
        Scanner s = new Scanner(db, registry, log, p -> false, source, p -> {
            if (p.equals(b)) {
                throw new java.nio.file.AccessDeniedException(p.toString());
            }
            return Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
        });
        source.files.remove(b);
        assertEquals(0, s.scanDrive(root, x -> { }, () -> false).removed());
        assertEquals(2, db.trackCount());

        // ... and if the index still lists b but stat fails with such an error, the row is also kept.
        source.files.add(b);
        assertEquals(0, s.scanDrive(root, x -> { }, () -> false).removed());
        assertEquals(2, db.trackCount());
    }



    @Test
    void scanDriveRereadsAChangedFile() throws Exception {
        Path a = file("a.tst", "alpha");
        FakeSource source = new FakeSource();
        source.files.add(a);
        scanDrive(source);
        Files.writeString(a, "alpha two", StandardCharsets.ISO_8859_1);

        ScanResult r = scanDrive(source);

        assertEquals(1, r.updated());
        assertEquals("alpha two", row(a).tags().first("TITLE").orElseThrow());
    }

    @Test
    void scanDriveDropsListedFilesThatNoLongerExist() throws Exception {
        Path a = file("a.tst", "alpha");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, root.resolve("ghost.tst")));

        ScanResult r = scanDrive(source);

        assertEquals(new ScanResult(1, 0, 0, 0, 0), r);
    }

    @Test
    void scanDriveIgnoresListedPathsOutsideTheRootAndNonAudioFiles() throws Exception {
        Path a = file("a.tst", "alpha");
        Path notes = file("notes.txt", "text");
        Path outside = Files.createDirectories(tmp.resolve("elsewhere")).resolve("z.tst");
        Files.writeString(outside, "zulu");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, notes, outside));

        ScanResult r = scanDrive(source);

        assertEquals(1, r.added());
    }

    @Test
    void scanDriveSkipsTheRecycleBinEvenIfTheIndexListsIt() throws Exception {
        Path a = file("a.tst", "alpha");
        Path deleted = file("$RECYCLE.BIN\\S-1-5\\$R1.tst", "deleted");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, deleted));

        assertEquals(1, scanDrive(source).added());
    }

    @Test
    void scanDriveListsEachFileOnceAndCountsProgressOverTheList() throws Exception {
        Path a = file("a.tst", "alpha");
        Path b = file("b.tst", "bravo");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, b, a));

        ScanResult r = scanDrive(source);

        assertEquals(2, r.added());
        assertEquals(2, progress.size());
        assertEquals(2, progress.get(1).total());
    }

    @Test
    void scanDriveHandlesCloudOnlyAndCorruptFilesLikeAWalk() throws Exception {
        Path good = file("good.tst", "fine");
        Path bad = file("bad.tst", "BAD data");
        Path cloudy = file("cloudy.tst", "in the cloud");
        cloud = p -> p.equals(cloudy);
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(good, bad, cloudy));

        ScanResult r = scanDrive(source);

        assertEquals(2, r.added());
        assertEquals(1, r.errors());
        assertEquals(2, handler.probes.get()); // good and bad; the cloud-only file was never opened
        assertNull(row(cloudy).format());
        assertFalse(row(bad).supported());
        assertEquals(1, log.tail(10).size());
    }

    @Test
    void scanDriveOfAnAbsentDriveRemovesNothing() throws Exception {
        Path a = file("a.tst", "alpha");
        FakeSource source = new FakeSource();
        source.files.add(a);
        scanDrive(source);

        ScanResult r = driveScanner(source).scanDrive(tmp.resolve("unplugged"), p -> { }, () -> false);

        assertEquals(1, r.errors());
        assertEquals(1, db.trackCount());
    }

    @Test
    void scanDriveCancelledKeepsUnlistedTracks() throws Exception {
        Path a = file("a.tst", "alpha");
        Path b = file("b.tst", "bravo");
        FakeSource source = new FakeSource();
        source.files.addAll(List.of(a, b));
        scanDrive(source);
        source.files.clear();

        ScanResult r = driveScanner(source).scanDrive(root, p -> { }, () -> true);

        assertEquals(0, r.removed());
        assertEquals(2, db.trackCount());
    }

    @Test
    void scanDriveWalksWhenEverythingAnswersEmptyForARootThatHasFiles() throws Exception {
        file("a.tst", "alpha");
        file("album\\b.tst", "bravo");
        FakeSource source = new FakeSource(); // Everything is running but does not index this drive

        ScanResult r = scanDrive(source);

        assertEquals(1, source.queries.get());
        assertEquals(2, r.added());
        assertEquals(2, db.trackCount());
        assertTrue(r.walkedBecauseUnindexed());
    }

    @Test
    void scanDriveNeverRemovesRowsBecauseEverythingAnswersEmpty() throws Exception {
        Path a = file("a.tst", "alpha");
        Path b = file("album\\b.tst", "bravo");
        FakeSource first = new FakeSource();
        first.files.addAll(List.of(a, b));
        scanDrive(first);
        assertEquals(2, db.trackCount());

        FakeSource empty = new FakeSource(); // now Everything knows nothing about the drive
        ScanResult r = scanDrive(empty);

        assertEquals(0, r.removed());
        assertEquals(2, r.unchanged());
        assertEquals(2, db.trackCount());
        assertTrue(r.walkedBecauseUnindexed());
    }

    @Test
    void scanDriveStillRemovesFilesThatReallyAreGoneWhenTheWalkFallbackRuns() throws Exception {
        Path a = file("a.tst", "alpha");
        Path b = file("b.tst", "bravo");
        FakeSource first = new FakeSource();
        first.files.addAll(List.of(a, b));
        scanDrive(first);
        Files.delete(b);

        ScanResult r = scanDrive(new FakeSource()); // empty answer: the walk decides what is gone

        assertEquals(1, r.removed());
        assertEquals(1, db.trackCount());
    }

    @Test
    void scanDriveOfAGenuinelyEmptyDriveIsNotReportedAsUnindexed() throws Exception {
        ScanResult r = scanDrive(new FakeSource());

        assertEquals(new ScanResult(0, 0, 0, 0, 0), r);
        assertFalse(r.walkedBecauseUnindexed());
    }

    @Test
    void scanDriveFromAWorkingIndexIsNotFlagged() throws Exception {
        Path a = file("a.tst", "alpha");
        FakeSource source = new FakeSource();
        source.files.add(a);

        assertFalse(scanDrive(source).walkedBecauseUnindexed());
    }

    @Test
    void isDriveRootRecognisesOnlyBareRoots() {
        assertTrue(Scanner.isDriveRoot(Path.of("E:\\")));
        assertFalse(Scanner.isDriveRoot(Path.of("E:\\Music")));
        assertFalse(Scanner.isDriveRoot(Path.of("music")));
    }

    // ---- CUE sheets ----

    private static final String CUE = """
            TITLE "Album"
            FILE "album.wav" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                INDEX 01 00:00:30
            """;

    /** A silent 16-bit stereo 44.1 kHz WAV of {@code frames} frames. */
    private Path wav(String relative, int frames) throws IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 4)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 2).putInt(44100).putInt(44100 * 4).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 4);
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.write(p, b.array());
        return p;
    }

    private Scanner standard() {
        return new Scanner(db, DecoderRegistry.standard(), log, p -> cloud.test(p));
    }

    private List<TrackRow> rowsOf(Path p) {
        return db.tracks("t.path = ?", List.of(p.toString()), null);
    }

    @Test
    void aCueSheetListsItsTracksInPlaceOfTheWholeAlbumFile() throws Exception {
        Path audio = wav("Album/album.wav", 44100);
        Path cue = file("Album/album.cue", CUE);

        standard().scan(root, x -> { }, () -> false);

        assertTrue(rowsOf(audio).isEmpty(), "the whole-album file is not listed a second time");
        List<TrackRow> tracks = rowsOf(cue);
        assertEquals(List.of(1, 2), tracks.stream().map(t -> t.ref().subsong()).sorted().toList());
        assertEquals(List.of(17640L, 26460L),
                tracks.stream().map(t -> t.format().lengthFrames()).sorted().toList());
    }

    @Test
    void aSheetAddedLaterReplacesTheWholeFilesRow() throws Exception {
        Path audio = wav("Album/album.wav", 44100);
        standard().scan(root, x -> { }, () -> false);
        assertEquals(1, rowsOf(audio).size());

        Path cue = file("Album/album.cue", CUE);
        standard().scan(root, x -> { }, () -> false);

        assertTrue(rowsOf(audio).isEmpty());
        assertEquals(2, rowsOf(cue).size());
    }

    @Test
    void theSheetIsRereadWhenItsAudioFileChanges() throws Exception {
        wav("Album/album.wav", 44100);
        Path cue = file("Album/album.cue", CUE);
        standard().scan(root, x -> { }, () -> false);
        assertEquals(new ScanResult(0, 0, 0, 1, 0), standard().scan(root, x -> { }, () -> false));

        wav("Album/album.wav", 88200);
        ScanResult r = standard().scan(root, x -> { }, () -> false);

        assertEquals(1, r.updated());
        assertTrue(rowsOf(cue).stream().anyMatch(t -> t.format().lengthFrames() == 88200 - 17640));
    }

    @Test
    void aSheetOverSeparateTrackFilesLeavesThoseFilesListed() throws Exception {
        Path one = wav("Rip/01.wav", 4410);
        Path two = wav("Rip/02.wav", 4410);
        Path cue = file("Rip/rip.cue", """
                FILE "01.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                FILE "02.wav" WAVE
                TRACK 02 AUDIO
                INDEX 01 00:00:00
                """);

        standard().scan(root, x -> { }, () -> false);

        assertEquals(1, rowsOf(one).size());
        assertEquals(1, rowsOf(two).size());
        assertTrue(rowsOf(cue).isEmpty());
    }

    @Test
    void aCloudOnlyAlbumFileIsNeverOpenedForItsSheet() throws Exception {
        Path audio = wav("Album/album.wav", 44100);
        Files.write(audio, new byte[0]); // were it opened, it would be a damaged WAV
        Path cue = file("Album/album.cue", CUE);
        cloud = p -> p.equals(audio);

        standard().scan(root, x -> { }, () -> false);

        List<TrackRow> tracks = rowsOf(cue);
        assertEquals(2, tracks.size());
        assertTrue(tracks.stream().allMatch(t -> t.supported() && t.format() == null));
        assertTrue(rowsOf(audio).isEmpty());
    }
}
