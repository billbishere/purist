package purist.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import purist.app.AppLog;

/**
 * Keeps the library current: scans the watched folders, watches them for changes, and tells listeners. Every
 * scan, removal and watcher reaction runs on one thread, {@code "purist-library"}, so scans never overlap and a
 * folder is never scanned while it is being removed.
 */
public final class LibraryService implements AutoCloseable {

    private static final Logger LOG = AppLog.logger(LibraryService.class);

    private final LibraryDb db;
    private final Scanner scanner;
    private final Function<Consumer<FolderWatcher.Changes>, FolderWatcher> watcherFactory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "purist-library");
        t.setDaemon(true);
        return t;
    });
    private final List<LibraryListener> listeners = new CopyOnWriteArrayList<>();
    /** Folders being removed; a scan of one stops at its next file. */
    private final Set<Path> removing = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    // Touched only on the executor thread (close() reads the watcher after the executor stops).
    private volatile FolderWatcher watcher;
    private boolean started;

    /**
     * @param watcherFactory makes the watcher, given what to call with each batch of changed directories;
     *                       production passes {@code c -> new FolderWatcher(c, Duration.ofSeconds(2), isAudio)}
     */
    public LibraryService(LibraryDb db, Scanner scanner,
            Function<Consumer<FolderWatcher.Changes>, FolderWatcher> watcherFactory) {
        this.db = db;
        this.scanner = scanner;
        this.watcherFactory = watcherFactory;
    }

    public void addListener(LibraryListener l) {
        listeners.add(l);
    }

    /** Scans every stored folder in the background, and watches them all for changes. */
    public void start() {
        run(() -> {
            started = true;
            for (Path folder : db.folders()) {
                watch(folder);
            }
            // Watching first means a file that appears during a long first scan is not missed.
            for (Path folder : db.folders()) {
                scanFolder(folder);
            }
        });
    }

    /**
     * Adds a folder (a whole drive such as {@code E:\} is one) to the library: it is stored now, then scanned in
     * the background and, once {@link #start} has run, watched.
     */
    public void addFolder(Path folder) {
        Path p = folder.toAbsolutePath().normalize();
        db.addFolder(p);
        run(() -> {
            removing.remove(p);
            if (started) {
                watch(p);
            }
            scanFolder(p);
        });
    }

    /** Stops watching a folder and deletes its tracks. */
    public void removeFolder(Path folder) {
        Path p = folder.toAbsolutePath().normalize();
        removing.add(p);
        run(() -> {
            try {
                FolderWatcher w = watcher;
                if (w != null) {
                    w.unwatch(p);
                }
                db.removeFolder(p);
                fireChanged();
            } finally {
                removing.remove(p);
            }
        });
    }

    /** Scans every stored folder again; unchanged files are skipped, so this is cheap when nothing changed. */
    public void rescanAll() {
        run(() -> {
            for (Path folder : db.folders()) {
                scanFolder(folder);
            }
        });
    }

    /**
     * Stops the watcher and any scan in progress. Safe to call twice. A scan in progress is told to stop by the
     * {@code closed} flag, which it polls between files; the thread is not interrupted, because an interrupt
     * closes the channel a file is being read through and would make a good file look broken.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        FolderWatcher w = watcher;
        if (w != null) {
            w.close();
        }
    }

    // ---- on the executor thread ----

    private void run(Runnable work) {
        if (closed) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Library work failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // closed in the meantime
        }
    }

    private void watch(Path folder) {
        try {
            if (watcher == null) {
                watcher = watcherFactory.apply(this::onChanged);
                watcher.setStoredUnder(db::hasTracksUnder);
            }
            watcher.watch(folder);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot watch " + folder, e);
        }
    }

    private void onChanged(FolderWatcher.Changes changes) {
        run(() -> {
            for (Target target : targets(changes)) {
                scanFolder(target.dir(), target.deep());
            }
        });
    }

    /** A directory to rescan; {@code deep} means with its sub-folders. */
    private record Target(Path dir, boolean deep) {
    }

    /**
     * The directories to rescan for a batch of changed ones: kept only if inside a stored folder and not in a
     * system folder (the recycle bin, System Volume Information, ...). One that no longer exists is lifted to the
     * nearest one that does, and then needs its sub-folders rescanned (that is where the vanished files were). A
     * directory is dropped when a recursive rescan of one above it is also due.
     */
    private List<Target> targets(FolderWatcher.Changes changes) {
        List<Path> folders = db.folders();
        java.util.Map<Path, Boolean> lifted = new java.util.LinkedHashMap<>();
        collect(changes.deep(), true, folders, lifted);
        collect(changes.shallow(), false, folders, lifted);
        List<Path> sorted = new ArrayList<>(lifted.keySet());
        sorted.sort(Comparator.comparingInt(Path::getNameCount));
        List<Target> out = new ArrayList<>();
        for (Path d : sorted) {
            boolean covered = false;
            for (Target kept : out) {
                if (kept.deep() && d.startsWith(kept.dir())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                out.add(new Target(d, lifted.get(d)));
            }
        }
        return out;
    }

    private void collect(Set<Path> dirs, boolean deep, List<Path> folders, java.util.Map<Path, Boolean> into) {
        for (Path dir : dirs) {
            Path folder = null;
            for (Path f : folders) {
                if (dir.startsWith(f)) {
                    folder = f;
                    break;
                }
            }
            if (folder == null || removing.contains(folder) || Scanner.isSystemFolder(folder, dir)) {
                continue;
            }
            Path d = dir;
            boolean needsDeep = deep;
            while (!d.equals(folder) && !Files.isDirectory(d) && d.getParent() != null) {
                d = d.getParent();
                needsDeep = true;
            }
            into.merge(d, needsDeep, Boolean::logicalOr);
        }
    }

    private void scanFolder(Path dir) {
        scanFolder(dir, true);
    }

    private void scanFolder(Path dir, boolean deep) {
        if (closed) {
            return;
        }
        ScanResult result;
        try {
            // A whole drive is listed through Everything when it is running; anything else is walked. A watcher
            // report of "only files in this directory changed" reads just that directory.
            result = !deep
                    ? scanner.scanShallow(dir, this::fireProgress, () -> closed || isBeingRemoved(dir))
                    : Scanner.isDriveRoot(dir)
                    ? scanner.scanDrive(dir, this::fireProgress, () -> closed || isBeingRemoved(dir))
                    : scanner.scan(dir, this::fireProgress, () -> closed || isBeingRemoved(dir));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Scanning " + dir + " failed", e);
            fireFinished(dir, new ScanResult(0, 0, 0, 0, 1));
            return;
        }
        fireFinished(dir, result);
        if (result.walkedBecauseUnindexed()) {
            fireNotice(unindexedNotice(dir));
        }
        if (result.changedLibrary()) {
            fireChanged();
        }
    }

    /** The line the drives view and the toast show for a drive Everything does not index. */
    public static String unindexedNotice(Path drive) {
        return "Everything has no index for " + drivePrefix(drive) + " — scanning folders instead";
    }

    /** {@code E:} for {@code E:\}. */
    private static String drivePrefix(Path root) {
        String s = root.toString();
        return s.endsWith("\\") ? s.substring(0, s.length() - 1) : s;
    }

    private void fireFinished(Path folder, ScanResult result) {
        for (LibraryListener l : listeners) {
            try {
                l.scanFinished(folder, result);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A library listener failed", e);
            }
        }
    }

    private void fireNotice(String message) {
        for (LibraryListener l : listeners) {
            try {
                l.scanNotice(message);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A library listener failed", e);
            }
        }
    }

    private boolean isBeingRemoved(Path dir) {
        for (Path r : removing) {
            if (dir.startsWith(r)) {
                return true;
            }
        }
        return false;
    }

    private void fireProgress(ScanProgress p) {
        for (LibraryListener l : listeners) {
            try {
                l.scanProgress(p);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A library listener failed", e);
            }
        }
    }

    private void fireChanged() {
        for (LibraryListener l : listeners) {
            try {
                l.libraryChanged();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A library listener failed", e);
            }
        }
    }
}
