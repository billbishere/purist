package purist.library;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Somewhere other than the disk to ask for "every audio file under this drive": in production, the user's running
 * Everything ({@link EverythingSearch#source()}); in tests, a fixed list. The scanner still stats and reads every
 * path it is given, so a stale answer costs a skipped file, never a wrong row.
 */
public interface FileSource {

    /** True if {@link #audioFiles} can answer right now. */
    boolean available();

    /**
     * Every file under {@code driveRoot} whose extension (lower-case, no dot) is in {@code extensions}.
     *
     * @throws RuntimeException if the source fails; the scanner then walks the drive instead
     */
    List<Path> audioFiles(Path driveRoot, Set<String> extensions);
}
