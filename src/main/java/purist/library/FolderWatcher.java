package purist.library;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import com.sun.nio.file.ExtendedWatchEventModifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import purist.app.AppLog;

/**
 * Watches folder trees for changes (spec §8). Each root gets one recursive registration
 * ({@code ExtendedWatchEventModifier.FILE_TREE}); an event names the directory that now holds something new,
 * changed or gone, and that directory is reported once its events have been quiet for {@code debounce}. Events
 * for one directory coalesce, so a ten-file copy is one report. An {@code OVERFLOW} (events were lost) reports
 * the root itself, so the owner rescans it whole.
 *
 * <p>A drive-root watch sees every file that changes anywhere on the drive, so the watcher can be given a filter
 * ({@code relevantFile}, in production "is an audio file"): events for other files (a browser cache, a log, a
 * temp file) are dropped before they cost a scan. A report also says whether a directory appeared or vanished
 * (its parent then needs a recursive rescan) or only files in it changed (a shallow rescan is enough).
 *
 * <p>Reports arrive on a private thread; {@code onChanged} should hand the work on rather than do it.
 */
public final class FolderWatcher implements AutoCloseable {

    /**
     * One batch of quiet directories. {@code deep} directories had a sub-directory appear or vanish and need a
     * recursive rescan; {@code shallow} ones only had files change in them. No directory is in both.
     */
    public record Changes(Set<Path> shallow, Set<Path> deep) {

        /** Every directory in the batch. */
        public Set<Path> all() {
            Set<Path> all = new LinkedHashSet<>(shallow);
            all.addAll(deep);
            return all;
        }
    }

    private static final Logger LOG = AppLog.logger(FolderWatcher.class);

    private final Consumer<Changes> onChanged;
    private final Predicate<Path> relevantFile;
    private final long debounceNanos;
    private final WatchService service;
    private final ScheduledExecutorService timer;
    private final Thread pump;

    /** Whether the library holds tracks below a path; see {@link #setStoredUnder}. */
    private volatile Predicate<Path> storedUnder = p -> false;

    private final Object lock = new Object();
    private final Map<Path, WatchKey> keys = new HashMap<>();
    private final Map<WatchKey, Path> roots = new HashMap<>();
    /** Directory to the {@link System#nanoTime} of its latest event. */
    private final Map<Path, Long> pending = new HashMap<>();
    /** The pending directories that need a recursive rescan. */
    private final Set<Path> pendingDeep = new HashSet<>();
    private boolean checkScheduled;
    private boolean closed;

    /** Reports every event's directory, with no file filter. */
    public FolderWatcher(Consumer<Set<Path>> onChanged, Duration debounce) {
        this(c -> onChanged.accept(c.all()), debounce, p -> true);
    }

    /**
     * @param relevantFile whether a changed file matters; an event for a file it rejects is dropped (a deleted
     *                     name it rejects is still reported when it could have been a directory)
     */
    public FolderWatcher(Consumer<Changes> onChanged, Duration debounce, Predicate<Path> relevantFile) {
        this.onChanged = onChanged;
        this.relevantFile = relevantFile;
        this.debounceNanos = debounce.toNanos();
        try {
            this.service = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start the file watcher", e);
        }
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "purist-watch-debounce");
            t.setDaemon(true);
            return t;
        });
        this.pump = new Thread(this::pump, "purist-watcher");
        this.pump.setDaemon(true);
        this.pump.start();
    }

    /** Starts watching {@code root} and everything under it, now and later. Watching a root twice is harmless. */
    public void watch(Path root) throws IOException {
        synchronized (lock) {
            if (closed || keys.containsKey(root)) {
                return;
            }
            WatchKey key = root.register(service, new WatchEvent.Kind<?>[] {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY},
                    ExtendedWatchEventModifier.FILE_TREE);
            keys.put(root, key);
            roots.put(key, root);
        }
    }

    /** Stops watching {@code root}; reports already waiting under it are dropped. */
    public void unwatch(Path root) {
        synchronized (lock) {
            WatchKey key = keys.remove(root);
            if (key != null) {
                roots.remove(key);
                key.cancel();
            }
            pending.keySet().removeIf(d -> d.startsWith(root));
            pendingDeep.removeIf(d -> d.startsWith(root));
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
            pendingDeep.clear();
        }
        try {
            service.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Closing the watch service failed", e);
        }
        timer.shutdownNow();
        pump.interrupt();
    }

    /**
     * Tells the watcher how to recognise a vanished folder whose name looks like a file ({@code R.E.M},
     * {@code Vol.2}): a deleted non-audio name below which the library stores tracks was a folder, so its parent
     * needs a recursive rescan. Called on the watcher thread for each such deleted name, so it must be cheap.
     */
    public void setStoredUnder(Predicate<Path> storedUnder) {
        this.storedUnder = storedUnder;
    }

    // ---- events ----

    private void pump() {
        try {
            while (true) {
                WatchKey key = service.take();
                Path root;
                synchronized (lock) {
                    root = roots.get(key);
                }
                if (root != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        handle(event, root);
                    }
                }
                if (!key.reset()) {
                    synchronized (lock) {
                        Path gone = roots.remove(key);
                        if (gone != null) {
                            keys.remove(gone);
                            pending.put(gone, System.nanoTime()); // the root itself vanished or became unreadable
                            pendingDeep.add(gone);
                            scheduleCheck(debounceNanos);
                        }
                    }
                }
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            // closed: done
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "The folder watcher stopped", e);
        }
    }

    /**
     * Records the directory an event concerns. Package-private so tests can feed synthetic events.
     *
     * <p>A change to a directory's own entry is ignored (Windows reports one whenever something inside it
     * changes, and the inner event already says so); only a directory appearing or disappearing counts, and it
     * makes its parent need a recursive rescan. A file the filter rejects is ignored. A deleted name cannot be
     * looked at, so it counts as a vanished directory unless it is an audio file or has a short extension and nothing
     * is stored below it (see {@link #setStoredUnder}).
     */
    void handle(WatchEvent<?> event, Path root) {
        Path dir;
        boolean deep = false;
        if (event.kind() == OVERFLOW) {
            dir = root;
            deep = true;
        } else if (event.context() instanceof Path relative) {
            Path full = root.resolve(relative);
            boolean modify = event.kind() == ENTRY_MODIFY;
            if (event.kind() == ENTRY_DELETE) {
                if (!relevantFile.test(full)) {
                    if (!couldBeDirectory(full) && !wasFolderWithStoredTracks(full)) {
                        return;
                    }
                    deep = true;
                }
            } else if (Files.isDirectory(full)) {
                if (modify) {
                    return;
                }
                deep = true;
            } else if (!relevantFile.test(full)) {
                return;
            }
            dir = full.getParent() == null ? root : full.getParent();
        } else {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            pending.put(dir, System.nanoTime());
            if (deep) {
                pendingDeep.add(dir);
            }
            scheduleCheck(debounceNanos);
        }
    }

    private boolean wasFolderWithStoredTracks(Path full) {
        try {
            return storedUnder.test(full);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Checking for tracks below " + full + " failed", e);
            return false;
        }
    }

    /** A name with no extension, or with a long or odd one ("Vol. 2 Remastered"), may have been a directory. */
    private static boolean couldBeDirectory(Path full) {
        Path name = full.getFileName();
        if (name == null) {
            return true;
        }
        String n = name.toString();
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || dot == n.length() - 1) {
            return true;
        }
        String ext = n.substring(dot + 1);
        if (ext.length() > 4) {
            return true;
        }
        for (int i = 0; i < ext.length(); i++) {
            if (!Character.isLetterOrDigit(ext.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Must hold {@code lock}. */
    private void scheduleCheck(long delayNanos) {
        if (checkScheduled || closed) {
            return;
        }
        checkScheduled = true;
        try {
            timer.schedule(this::check, Math.max(1, delayNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) { // the timer was shut down by close()
            checkScheduled = false;
        }
    }

    /** Reports the directories quiet for the debounce time and schedules a look at the rest. */
    private void check() {
        Set<Path> shallow = new LinkedHashSet<>();
        Set<Path> deep = new LinkedHashSet<>();
        synchronized (lock) {
            checkScheduled = false;
            if (closed) {
                return;
            }
            long now = System.nanoTime();
            long nextWait = Long.MAX_VALUE;
            for (Iterator<Map.Entry<Path, Long>> it = pending.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Path, Long> e = it.next();
                long age = now - e.getValue();
                if (age >= debounceNanos) {
                    (pendingDeep.remove(e.getKey()) ? deep : shallow).add(e.getKey());
                    it.remove();
                } else {
                    nextWait = Math.min(nextWait, debounceNanos - age);
                }
            }
            if (nextWait != Long.MAX_VALUE) {
                scheduleCheck(nextWait);
            }
        }
        if (!shallow.isEmpty() || !deep.isEmpty()) {
            try {
                onChanged.accept(new Changes(shallow, deep));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "A folder-change handler failed", e);
            }
        }
    }
}
