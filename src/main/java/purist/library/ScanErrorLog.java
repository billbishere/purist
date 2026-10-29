package purist.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * {@code scan-errors.log}: one line per file the scanner could not read, {@code "<ISO-8601 time> <path>: <message>"}.
 * Thread-safe. A log that cannot be written is silently skipped: there is nowhere left to report that.
 */
public final class ScanErrorLog {

    private final Path file;

    public ScanErrorLog(Path file) {
        this.file = file;
    }

    public synchronized void append(Path path, String message) {
        String flat = message == null ? "" : message.replaceAll("\\R", " ");
        String line = Instant.now() + " " + path + ": " + flat + System.lineSeparator();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Nothing useful to do.
        }
    }

    /** The last {@code n} lines, oldest first; empty if the log does not exist. */
    public synchronized List<String> tail(int n) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return List.copyOf(all.subList(Math.max(0, all.size() - Math.max(0, n)), all.size()));
        } catch (IOException e) {
            return List.of();
        }
    }
}
