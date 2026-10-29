package purist.library;

import java.nio.file.Path;

/**
 * How far a scan has got.
 *
 * @param done    files dealt with so far (read, skipped as unchanged, or kept as errors)
 * @param total   files the scan found to deal with
 * @param current the file just dealt with
 * @param root    the folder or drive being scanned; null if not known
 */
public record ScanProgress(int done, int total, Path current, Path root) {

    /** Progress of a scan whose root is not named. */
    public ScanProgress(int done, int total, Path current) {
        this(done, total, current, null);
    }
}
