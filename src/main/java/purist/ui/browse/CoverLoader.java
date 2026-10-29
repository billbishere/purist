package purist.ui.browse;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import purist.app.AppLog;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.library.AlbumSummary;
import purist.library.art.AlbumKey;
import purist.library.art.CoverArt;
import purist.library.art.ThumbnailCache;

/**
 * Hands the grid the cover of an album without ever touching the disk on the UI thread. {@link #image} answers from
 * memory at once; on a miss it queues the album, and two worker threads read the cover (through the
 * {@link ThumbnailCache}, which keeps the 320 px thumbnails on disk), scale it to the size the grid draws, keep it
 * in a small least-recently-used map and ask the UI to repaint.
 *
 * <p>The grid says which albums are on screen ({@link #wanted}); workers always take the newest request first and
 * drop requests for albums that have scrolled away, so a fling through the library never queues a backlog in front
 * of what the user is looking at.
 */
public final class CoverLoader implements AutoCloseable {

    /** The answer for an album that has no art: the grid draws its placeholder. */
    public static final BufferedImage NO_ART = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

    private static final Logger LOG = AppLog.logger(CoverLoader.class);
    private static final int WORKERS = 2;
    private static final int MEMORY_ENTRIES = 300;

    private final ThumbnailCache cache;
    private final DecoderRegistry registry;
    private final Runnable repaint;
    private final int px;
    private final Object lock = new Object();
    private final Map<AlbumKey, BufferedImage> ready = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AlbumKey, BufferedImage> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };
    private final Set<AlbumKey> requested = new HashSet<>();
    private final LinkedBlockingDeque<Request> pending = new LinkedBlockingDeque<>();
    private volatile Set<AlbumKey> wanted = Set.of();
    private final java.util.List<Thread> threads = new java.util.ArrayList<>();
    private volatile boolean closed;

    private record Request(AlbumKey key, Path firstTrack, int subsong) {
    }

    /**
     * @param px      the edge of the scaled covers kept in memory
     * @param repaint run on the UI thread whenever a cover has arrived
     */
    public CoverLoader(ThumbnailCache cache, DecoderRegistry registry, int px, Runnable repaint) {
        this.cache = cache;
        this.registry = registry;
        this.px = px;
        this.repaint = repaint;
        AtomicInteger n = new AtomicInteger();
        for (int i = 0; i < WORKERS; i++) {
            Thread t = new Thread(this::work, "purist-covers-" + n.incrementAndGet());
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    /** What the cache and the placeholder call this album. */
    public static AlbumKey keyOf(AlbumSummary a) {
        return new AlbumKey(a.folder().toString(), a.album(), a.albumArtist());
    }

    /**
     * The scaled cover if it is ready, {@link #NO_ART} if the album has none, and null while it is still being
     * read (the album is queued). Never blocks.
     */
    public BufferedImage image(AlbumSummary a) {
        AlbumKey key = keyOf(a);
        synchronized (lock) {
            BufferedImage hit = ready.get(key);
            if (hit != null) {
                return hit;
            }
            if (requested.add(key)) {
                pending.addLast(new Request(key, a.firstTrack().path(), a.firstTrack().subsong()));
            }
        }
        return null;
    }

    /** Tells the loader which albums are on screen; requests for any others are dropped. */
    public void wanted(Set<AlbumKey> keys) {
        wanted = Set.copyOf(keys);
    }

    private void work() {
        try {
            while (!closed) {
                Request r = pending.takeLast();
                if (!wanted.contains(r.key())) {
                    forget(r.key());
                    continue;
                }
                BufferedImage scaled = load(r);
                synchronized (lock) {
                    ready.put(r.key(), scaled);
                    requested.remove(r.key());
                }
                SwingUtilities.invokeLater(repaint);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void forget(AlbumKey key) {
        synchronized (lock) {
            requested.remove(key);
        }
    }

    private BufferedImage load(Request r) {
        try {
            long mtime = modified(r.firstTrack());
            TrackInfo probe = new TrackInfo(r.firstTrack(), r.subsong(), "", null, null, true, null);
            Optional<BufferedImage> thumb = cache.load(r.key(), () -> CoverArt.find(probe, registry), mtime).join();
            return thumb.map(t -> ImageScale.square(t, px)).orElse(NO_ART);
        } catch (RuntimeException | OutOfMemoryError e) {
            LOG.log(Level.FINE, "No cover for " + r.key(), e);
            return NO_ART;
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Thread t : threads) {
            t.interrupt();
        }
    }
}
