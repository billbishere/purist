package purist.library;

/**
 * Hears what the {@link LibraryService} is doing. Both methods are called on the service's own thread, never on
 * the UI thread: hand the work to the UI yourself.
 */
public interface LibraryListener {

    /** A scan has dealt with another file. */
    default void scanProgress(ScanProgress p) {
    }

    /**
     * A scan has something to tell the user, for example "Everything has no index for E: - scanning folders
     * instead".
     */
    default void scanNotice(String message) {
    }

    /**
     * A scan of {@code folder} has ended, with what it did. A scan that could not run at all is reported with one
     * error and nothing else.
     */
    default void scanFinished(java.nio.file.Path folder, ScanResult result) {
    }

    /** A scan changed the library (files added, updated or removed, or newly found unreadable). */
    default void libraryChanged() {
    }
}
