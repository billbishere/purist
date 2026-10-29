package purist.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a save on a thread of its own, so the UI thread never waits for the disk (a temp-file write and a rename can
 * stall behind a virus scanner). Saves run one at a time, in the order asked. Whatever the save reads must be safe to
 * read from that thread; {@link Settings} is.
 */
public final class BackgroundSaver implements AutoCloseable {

    /** What to run; may throw. */
    @FunctionalInterface
    public interface Save {
        void run() throws Exception;
    }

    private static final Logger LOG = AppLog.logger(BackgroundSaver.class);

    private final Save save;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "purist-save");
        t.setDaemon(true);
        return t;
    });

    public BackgroundSaver(Save save) {
        this.save = save;
    }

    /** Asks for a save and returns at once. A failure is logged; the next request still runs. */
    public void saveLater() {
        try {
            executor.execute(() -> {
                try {
                    save.run();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Cannot save settings", e);
                }
            });
        } catch (RejectedExecutionException e) {
            // closed: the final save has been (or is being) made by the owner
        }
    }

    /** Lets the saves already asked for finish (up to five seconds), then stops. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.log(Level.WARNING, "A settings save did not finish in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
