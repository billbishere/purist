package purist.library;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.decode.cue.CueHandler;

/**
 * Brings the library database in line with a folder on disk (spec §8). Only reads: nothing under the scanned folder
 * is ever written.
 *
 * <ul>
 *   <li>The folder is walked without following links, keeping the files {@link DecoderRegistry#isAudioFile}
 *       accepts.
 *   <li>A file whose {@code (size, mtime)} equals the stored stamp is skipped.
 *   <li>A cloud-only file is indexed from its name alone and never opened.
 *   <li>A file that fails to parse is kept as a greyed-out row and logged. A file that cannot be read at all (locked,
 *       access denied) keeps whatever rows it had and is retried by the next scan.
 *   <li>Rows are written 200 files to a transaction. A row whose file was not seen is deleted only when the disk
 *       itself confirms the file is gone (the file system says "no such file"). A list that merely omits it
 *       (Everything with an exclusion or a half-built index), an error other than "no such file", a cancelled
 *       scan, an unavailable folder, or a directory that could not be listed prove nothing about the file.
 * </ul>
 */
public final class Scanner {

    /** Files per database transaction. */
    static final int BATCH = 200;

    /**
     * Folders every NTFS volume has that never hold the user's music (the recycle bin would list deleted files).
     * Skipped wherever they appear directly under a scan root or under the volume root.
     */
    private static final Set<String> SYSTEM_FOLDERS = Set.of("$recycle.bin", "system volume information");

    /** More Windows folders that are never music; skipped only directly under the volume root (E:\Recovery). */
    private static final Set<String> VOLUME_ROOT_SYSTEM_FOLDERS = Set.of("recovery", "windowsapps", "$windows.~bt",
            "$windows.~ws", "config.msi", "found.000", "$sysreset", "$winreagent");

    private final LibraryDb db;
    private final DecoderRegistry registry;
    private final ScanErrorLog log;
    private final Predicate<Path> cloudOnly;
    private final FileSource driveSource;
    private final Stat stat;

    /** Reads a file's attributes; a seam so tests can make the disk fail in ways a temp dir cannot. */
    @FunctionalInterface
    interface Stat {
        BasicFileAttributes read(Path p) throws IOException;
    }

    /** A scanner whose drive scans ask the user's Everything, falling back to a walk when it is not running. */
    public Scanner(LibraryDb db, DecoderRegistry registry, ScanErrorLog log, Predicate<Path> cloudOnly) {
        this(db, registry, log, cloudOnly, EverythingSearch.source());
    }

    /** As above, with the source of drive file lists given (tests inject one). */
    public Scanner(LibraryDb db, DecoderRegistry registry, ScanErrorLog log, Predicate<Path> cloudOnly,
            FileSource driveSource) {
        this(db, registry, log, cloudOnly, driveSource, p -> Files.readAttributes(p, BasicFileAttributes.class));
    }

    Scanner(LibraryDb db, DecoderRegistry registry, ScanErrorLog log, Predicate<Path> cloudOnly,
            FileSource driveSource, Stat stat) {
        this.stat = stat;
        this.db = db;
        this.registry = registry;
        this.log = log;
        this.cloudOnly = cloudOnly;
        this.driveSource = driveSource;
    }

    /** True if {@link #scanDrive} would take its file list from the drive source instead of walking. */
    public boolean everythingAvailable() {
        return driveSource.available();
    }

    /** True if {@code p} is a bare drive root such as {@code E:\}, which is scanned with {@link #scanDrive}. */
    public static boolean isDriveRoot(Path p) {
        return p.getParent() == null && p.getRoot() != null && p.getRoot().equals(p);
    }

    /** One audio file found, with the stamp it has on disk now. */
    private record Found(Path path, FileStamp stamp) {
    }

    /** What discovery found. {@code complete} is false when the scan was cancelled before the list was whole. */
    private record Listing(List<Found> files, List<Path> unreadable, boolean complete, boolean fromSource) {
        Listing(List<Found> files, List<Path> unreadable, boolean complete) {
            this(files, unreadable, complete, false);
        }
    }

    /**
     * Scans {@code root} by walking it.
     *
     * @param progress  told after every file
     * @param cancelled polled between files; a cancelled scan stores what it has and deletes nothing
     */
    public ScanResult scan(Path root, Consumer<ScanProgress> progress, BooleanSupplier cancelled) {
        if (!Files.isDirectory(root)) {
            return unavailable(root);
        }
        return process(root, walk(root, cancelled, Integer.MAX_VALUE), true, progress, cancelled);
    }

    /**
     * Scans only the files directly in {@code dir}, not its sub-folders: what a watcher needs when files in one
     * directory changed. Rows of files that have gone from {@code dir} are removed; rows below it are not touched.
     */
    public ScanResult scanShallow(Path dir, Consumer<ScanProgress> progress, BooleanSupplier cancelled) {
        if (!Files.isDirectory(dir)) {
            return unavailable(dir);
        }
        return process(dir, walk(dir, cancelled, 1), false, progress, cancelled);
    }

    /**
     * Scans a whole drive. When the drive source is available (Everything is running) it supplies the file list in
     * about a second; if it is not running, fails, or lists no audio file at all (a drive it does not index), the
     * drive is walked like any folder, and the last case is flagged in the result. Everything after discovery
     * is the same: the stamp skip, cloud-only files, probes, batching, and removal of rows not seen.
     */
    public ScanResult scanDrive(Path driveRoot, Consumer<ScanProgress> progress, BooleanSupplier cancelled) {
        if (!Files.isDirectory(driveRoot)) {
            return unavailable(driveRoot);
        }
        Listing listing = null;
        boolean unindexed = false;
        if (driveSource.available()) {
            try {
                listing = fromSource(driveRoot, cancelled);
                if (listing != null && listing.files().isEmpty() && listing.complete()) {
                    // Everything answered "no audio files" for this drive. That is also what it says for a drive
                    // it does not index (E: and G: on the user's machine), and trusting it would delete every
                    // row of the drive, so only the disk can say the drive is really empty.
                    listing = null;
                    unindexed = true;
                }
            } catch (RuntimeException e) {
                listing = null; // the index failed mid-query: walk instead
            }
        }
        if (listing == null) {
            listing = walk(driveRoot, cancelled, Integer.MAX_VALUE);
        }
        ScanResult result = process(driveRoot, listing, true, progress, cancelled);
        // A drive that really is empty is not worth mentioning.
        return unindexed && !listing.files().isEmpty() ? result.flaggedUnindexed() : result;
    }

    private ScanResult unavailable(Path root) {
        log.append(root, "Folder is not available; its tracks are kept");
        return new ScanResult(0, 0, 0, 0, 1);
    }

    // ---- discovery ----

    private Listing walk(Path root, BooleanSupplier cancelled, int depth) {
        List<Found> files = new ArrayList<>();
        List<Path> unreadable = new ArrayList<>();
        boolean[] complete = {true};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), depth,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (cancelled.getAsBoolean()) {
                                complete[0] = false;
                                return FileVisitResult.TERMINATE;
                            }
                            if (isSystemFolder(root, dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile() && registry.isAudioFile(file)) {
                                files.add(new Found(file, new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis())));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            unreadable.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            unreadable.add(root); // the walk itself broke: prove nothing about what was not reached
        }
        return new Listing(files, unreadable, complete[0]);
    }

    /** The drive source's list, each file stamped from disk. A file that vanished since the index was read is dropped. */
    private Listing fromSource(Path root, BooleanSupplier cancelled) {
        List<Path> paths = driveSource.audioFiles(root, registry.audioExtensions());
        List<Found> files = new ArrayList<>(paths.size());
        List<Path> unreadable = new ArrayList<>();
        Set<Path> listed = new HashSet<>();
        for (Path p : paths) {
            if (cancelled.getAsBoolean()) {
                return new Listing(files, unreadable, false, true);
            }
            if (!p.startsWith(root) || isSystemFolder(root, p) || !registry.isAudioFile(p) || !listed.add(p)) {
                continue;
            }
            try {
                BasicFileAttributes attrs = stat.read(p);
                if (attrs.isRegularFile()) {
                    files.add(new Found(p, new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis())));
                }
            } catch (NoSuchFileException | NotDirectoryException e) {
                // Gone since Everything last looked: not listed, and the removal check confirms it again.
            } catch (IOException e) {
                unreadable.add(p); // locked, offline, access denied: this proves nothing, so its row stays
            }
        }
        return new Listing(files, unreadable, true, true);
    }

    /**
     * True if {@code p} is, or is inside, a system folder: directly under {@code root}, or directly under the volume
     * root (so a watcher event in {@code E:\$RECYCLE.BIN\S-1-5} is recognised though the scan root is deeper).
     */
    static boolean isSystemFolder(Path root, Path p) {
        if (p.startsWith(root) && p.getNameCount() > root.getNameCount()) {
            String first = p.getName(root.getNameCount()).toString().toLowerCase(Locale.ROOT);
            if (SYSTEM_FOLDERS.contains(first)) {
                return true;
            }
        }
        // A root the user added inside such a folder (E:\Recovery\Music) is music, and so is everything below it.
        return !inVolumeRootSystemFolder(root) && inVolumeRootSystemFolder(p);
    }

    /** True if {@code p} is, or is inside, a Windows system folder directly under its volume root. */
    public static boolean inVolumeRootSystemFolder(Path p) {
        if (p.getRoot() == null || p.getNameCount() == 0) {
            return false;
        }
        String first = p.getName(0).toString().toLowerCase(Locale.ROOT);
        return SYSTEM_FOLDERS.contains(first) || VOLUME_ROOT_SYSTEM_FOLDERS.contains(first);
    }

    // ---- the pipeline shared by folders and drives ----

    private ScanResult process(Path root, Listing listing, boolean recursive, Consumer<ScanProgress> progress,
            BooleanSupplier cancelled) {
        Map<Path, FileStamp> stored = recursive ? db.stamps(root) : db.stampsIn(root);
        // A CUE sheet over a whole-album file lists that file as its tracks; the file's own row would list the album
        // a second time, as one long track, so it is left out and any row it had is removed.
        Map<Path, List<Path>> sheets = cueSheets(listing.files());
        Set<Path> claimed = new HashSet<>();
        sheets.values().forEach(claimed::addAll);
        List<Found> files = new ArrayList<>(listing.files().size());
        for (Found f : listing.files()) {
            if (claimed.contains(f.path())) {
                continue;
            }
            // A sheet's tracks change when its audio file does, so the sheet's stamp covers both.
            List<Path> audio = sheets.get(f.path());
            files.add(audio == null ? f : new Found(f.path(), combinedStamp(f.stamp(), audio)));
        }
        List<Path> claimedRows = claimed.stream().filter(stored::containsKey).toList();
        if (!claimedRows.isEmpty()) {
            db.deleteFiles(claimedRows);
        }
        int total = files.size();
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int errors = 0;
        int done = 0;
        boolean complete = listing.complete();
        Set<Path> seen = new HashSet<>(Math.max(16, total * 2));
        List<LibraryDb.FileRows> batch = new ArrayList<>(BATCH);

        for (Found f : files) {
            if (cancelled.getAsBoolean()) {
                complete = false;
                break;
            }
            Path path = f.path();
            seen.add(path);
            FileStamp before = stored.get(path);
            if (f.stamp().equals(before)) {
                unchanged++;
            } else {
                List<TrackInfo> infos = null;
                try {
                    infos = read(path);
                } catch (Exception | StackOverflowError | OutOfMemoryError e) {
                    // DecodeException, I/O, a parser bug, or a pathological file: this one file is kept, greyed out,
                    // and the scan goes on. An interrupt (the service is closing) is not the file's fault.
                    if (Thread.currentThread().isInterrupted() || causedByInterrupt(e)) {
                        complete = false;
                        break;
                    }
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    if (!isContentFault(e)) {
                        // The file could not be read (locked, access denied, offline): that says nothing about
                        // its content. Its rows and stamp stay as they were, so the next scan tries again.
                        message = "Could not read, will retry on the next scan: " + message;
                    } else {
                        db.markError(path, f.stamp(), message);
                    }
                    log.append(path, message);
                    errors++;
                }
                if (infos != null && !infos.isEmpty()) {
                    batch.add(new LibraryDb.FileRows(infos, f.stamp()));
                    if (before == null) {
                        added++;
                    } else {
                        updated++;
                    }
                    if (batch.size() >= BATCH) {
                        db.upsertBatch(batch);
                        batch.clear();
                    }
                }
            }
            done++;
            progress.accept(new ScanProgress(done, total, path, root));
        }
        if (!batch.isEmpty()) {
            db.upsertBatch(batch);
        }

        int removed = 0;
        // A cancel that arrived after the last file proves nothing, nor does a root that has vanished.
        if (complete && !cancelled.getAsBoolean() && Files.isDirectory(root)) {
            List<Path> gone = new ArrayList<>();
            for (Path p : stored.keySet()) {
                if (seen.contains(p) || underAny(p, listing.unreadable())) {
                    continue;
                }
                // Rows inside system folders (indexed by an older build) go whether or not the files exist; any
                // other row goes only when the disk itself says its file is gone.
                if (isSystemFolder(root, p) || confirmedGone(p)) {
                    gone.add(p);
                }
            }
            for (int from = 0; from < gone.size(); from += 1000) {
                db.deleteFiles(gone.subList(from, Math.min(gone.size(), from + 1000)));
            }
            removed = gone.size();
        }
        return new ScanResult(added, updated, removed, unchanged, errors);
    }

    /**
     * True only if the file system itself says {@code p} does not exist. A list that omits a file (Everything with
     * an excluded folder or a half-built index) does not, and neither does any other error (access denied, a
     * sharing violation, an offline volume).
     */
    private boolean confirmedGone(Path p) {
        try {
            stat.read(p);
            return false;
        } catch (NoSuchFileException | NotDirectoryException e) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * True if {@code e} says the file's content is bad (damaged, unsupported, a parser bug, a pathological file),
     * as opposed to a failure to read it at all: an {@link IOException} that is not about the content (sharing
     * violation, access denied, a device that went away).
     */
    private static boolean isContentFault(Throwable e) {
        if (e instanceof IOException) {
            return e instanceof purist.decode.DecodeException
                    || e instanceof purist.decode.UnsupportedFormatException
                    || e instanceof java.io.EOFException;
        }
        return true;
    }

    private static boolean causedByInterrupt(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ClosedByInterruptException || c instanceof InterruptedException
                    || c instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private List<TrackInfo> read(Path path) throws IOException {
        if (registry.handlerFor(path).orElse(null) instanceof CueHandler cue) {
            if (cloudOnly.test(path)) {
                return List.of(); // the sheet itself is not on disk yet; its tracks are listed once it is
            }
            // An audio file still in the cloud is never opened: its tracks come from the sheet alone.
            boolean onDisk = cue.audioFiles(path).stream().noneMatch(cloudOnly);
            return cue.probe(path, onDisk);
        }
        if (registry.handlerFor(path).isPresent() && cloudOnly.test(path)) {
            return List.of(TrackInfo.cloudOnly(path, codecOf(path)));
        }
        return registry.probe(path);
    }

    /**
     * Every CUE sheet among {@code files} that plays from whole-album files, with those files. A sheet that cannot be
     * read claims nothing (its error shows when it is probed), and a sheet still in the cloud is not opened.
     */
    private Map<Path, List<Path>> cueSheets(List<Found> files) {
        Map<Path, List<Path>> out = new java.util.HashMap<>();
        for (Found f : files) {
            if (registry.handlerFor(f.path()).orElse(null) instanceof CueHandler cue && !cloudOnly.test(f.path())) {
                try {
                    List<Path> audio = cue.audioFiles(f.path());
                    if (!audio.isEmpty()) {
                        out.put(f.path(), audio);
                    }
                } catch (IOException | RuntimeException e) {
                    // left to the probe, which records why
                }
            }
        }
        return out;
    }

    /** The sheet's stamp with its audio files' folded in: their sizes added, the latest time kept. */
    private FileStamp combinedStamp(FileStamp sheet, List<Path> audio) {
        long size = sheet.size();
        long mtime = sheet.mtime();
        for (Path p : audio) {
            try {
                BasicFileAttributes a = stat.read(p);
                size += a.size();
                mtime = Math.max(mtime, a.lastModifiedTime().toMillis());
            } catch (IOException e) {
                // a missing audio file: the probe reports it
            }
        }
        return new FileStamp(size, mtime);
    }

    private static boolean underAny(Path p, List<Path> dirs) {
        for (Path d : dirs) {
            if (p.startsWith(d)) {
                return true;
            }
        }
        return false;
    }

    private static String codecOf(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }
}
