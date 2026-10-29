package purist.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import purist.decode.DecoderRegistry;
import purist.library.FolderWatcher;
import purist.library.LibraryDb;
import purist.library.LibraryFeed;
import purist.library.LibraryService;
import purist.library.ScanErrorLog;
import purist.library.Scanner;
import purist.library.art.ThumbnailCache;
import purist.win.FileAttributes;

/**
 * Everything the music library needs, opened together from the profile: the database, the scanner and its service
 * (which also watches the folders), the cover thumbnails and the log of files that could not be read. The UI reads
 * the library through {@link #db()} and hears the service through {@link #feed()}.
 */
public final class AppLibrary implements AutoCloseable {

    private static final Logger LOG = AppLog.logger(AppLibrary.class);

    /** How many 320 px thumbnails the cache keeps in memory; the cover grid keeps its own scaled copies. */
    private static final int THUMB_MEMORY_ENTRIES = 64;
    private static final int THUMB_PX = 320;

    private final LibraryDb db;
    private final ThumbnailCache thumbs;
    private final LibraryService service;
    private final ScanErrorLog errors;
    private final LibraryFeed feed = new LibraryFeed();
    private final boolean firstRun;

    private AppLibrary(LibraryDb db, ThumbnailCache thumbs, LibraryService service, ScanErrorLog errors) {
        this.db = db;
        this.firstRun = db.folders().isEmpty();
        this.thumbs = thumbs;
        this.service = service;
        this.errors = errors;
        service.addListener(feed);
    }

    /**
     * Opens the library in {@code profile}. A database that will not open is moved aside as
     * {@code library.db.broken} and a fresh one is made: it only holds what the files say, and the next scan
     * rebuilds it. The service is not started; call {@link #start} once the window is up.
     */
    public static AppLibrary open(Profile profile, DecoderRegistry registry) throws SQLException {
        Path file = profile.libraryDb();
        LibraryDb db;
        try {
            db = LibraryDb.open(file);
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "The library database " + file + " would not open; starting a new one", e);
            moveAside(file);
            db = LibraryDb.open(file);
        }
        ScanErrorLog errors = new ScanErrorLog(profile.scanErrors());
        Scanner scanner = new Scanner(db, registry, errors, FileAttributes::isCloudOnly);
        LibraryService service = new LibraryService(db, scanner,
                c -> new FolderWatcher(c, Duration.ofSeconds(2), registry::isAudioFile));
        ThumbnailCache thumbs = new ThumbnailCache(profile.thumbs(), THUMB_MEMORY_ENTRIES, THUMB_PX);
        return new AppLibrary(db, thumbs, service, errors);
    }

    private static void moveAside(Path file) {
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path from = file.resolveSibling(file.getFileName() + suffix);
            try {
                if (Files.exists(from)) {
                    Files.move(from, file.resolveSibling(file.getFileName() + ".broken" + suffix),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot move " + from + " aside", e);
            }
        }
    }

    /** True when the library had no folders when it was opened: nothing to browse yet. */
    public boolean firstRun() {
        return firstRun;
    }

    public LibraryDb db() {
        return db;
    }

    public ThumbnailCache thumbs() {
        return thumbs;
    }

    public LibraryService service() {
        return service;
    }

    public ScanErrorLog errors() {
        return errors;
    }

    public LibraryFeed feed() {
        return feed;
    }

    /** Starts scanning and watching the stored folders, in the background. */
    public void start() {
        service.start();
    }

    @Override
    public void close() {
        service.close();
        thumbs.close();
        db.close();
    }
}
