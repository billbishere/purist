package purist.library;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Collects what the {@link LibraryService} reports so that the UI can read it on its own schedule. A scan reports
 * every file it deals with, from the service's thread; posting each one to the UI thread would bury it, so this
 * keeps only the latest progress and the things that must not be lost (notices, results), and the UI polls
 * ({@code Timer}, 10 times a second). While a scan runs, the library is also reported as changed about every ten
 * seconds once it has stored its first batch, so the albums appear during a long first scan of a big drive instead
 * of only at its end. Thread-safe.
 */
public final class LibraryFeed implements LibraryListener {

    /** How one scan ended. */
    public record Finished(Path folder, ScanResult result) {
    }

    /** How often a running scan reports the library as changed. */
    static final long REFRESH_NANOS = 10_000_000_000L;

    private final LongSupplier nanos;
    private final long refreshNanos;
    /** When the running scan last reported a change (or started); touched only by the service's thread. */
    private long lastRefresh;
    private volatile ScanProgress progress;
    private final AtomicBoolean changed = new AtomicBoolean();
    private final Queue<String> notices = new ConcurrentLinkedQueue<>();
    private final Queue<Finished> finished = new ConcurrentLinkedQueue<>();

    public LibraryFeed() {
        this(System::nanoTime, REFRESH_NANOS);
    }

    LibraryFeed(LongSupplier nanos, long refreshNanos) {
        this.nanos = nanos;
        this.refreshNanos = refreshNanos;
    }

    @Override
    public void scanProgress(ScanProgress p) {
        long now = nanos.getAsLong();
        if (progress == null) {
            lastRefresh = now; // a scan just began
        }
        progress = p;
        // The scanner commits 200 files at a time; before the first commit there is nothing new to show.
        if (p.done() >= Scanner.BATCH && now - lastRefresh >= refreshNanos) {
            lastRefresh = now;
            changed.set(true);
        }
    }

    @Override
    public void scanNotice(String message) {
        notices.add(message);
    }

    @Override
    public void scanFinished(Path folder, ScanResult result) {
        progress = null;
        finished.add(new Finished(folder, result));
    }

    @Override
    public void libraryChanged() {
        changed.set(true);
    }

    /** How far the scan that is running has got; null when none is. */
    public ScanProgress progress() {
        return progress;
    }

    /** True once after each change of the library since the last call. */
    public boolean takeChanged() {
        return changed.getAndSet(false);
    }

    /** The notices since the last call, oldest first. */
    public List<String> takeNotices() {
        return drain(notices);
    }

    /** The scans that ended since the last call, oldest first. */
    public List<Finished> takeFinished() {
        return drain(finished);
    }

    private static <T> List<T> drain(Queue<T> q) {
        List<T> out = new ArrayList<>();
        for (T t; (t = q.poll()) != null; ) {
            out.add(t);
        }
        return out;
    }
}
