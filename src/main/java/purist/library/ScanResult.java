package purist.library;

/**
 * What a scan did. A file that failed to parse counts only in {@code errors}: it is kept as a greyed-out row.
 *
 * @param added                  files new to the library
 * @param updated                files read again because their size or modified time changed
 * @param removed                files that were in the library but no longer on disk
 * @param unchanged              files skipped because their stamp matched
 * @param errors                 files that could not be read (or, for a missing folder, the folder itself)
 * @param walkedBecauseUnindexed true if Everything was running but listed no audio file for the drive, so the
 *                               folders were walked instead (the drive is probably not in Everything's index)
 */
public record ScanResult(int added, int updated, int removed, int unchanged, int errors,
        boolean walkedBecauseUnindexed) {

    /** A result of a scan that took its file list from a normal source. */
    public ScanResult(int added, int updated, int removed, int unchanged, int errors) {
        this(added, updated, removed, unchanged, errors, false);
    }

    /** True if the library's contents differ after this scan. */
    public boolean changedLibrary() {
        return added + updated + removed + errors > 0;
    }

    /** The same result, flagged as having walked because Everything had no index for the drive. */
    ScanResult flaggedUnindexed() {
        return new ScanResult(added, updated, removed, unchanged, errors, true);
    }
}
