package purist.library;

/**
 * What the scanner compares to decide whether a file needs re-reading (spec §8): a file whose stamp equals the
 * stored one is skipped.
 *
 * @param size  length in bytes
 * @param mtime last-modified time in epoch milliseconds; stored and compared as given
 */
public record FileStamp(long size, long mtime) {
}
