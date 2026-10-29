package purist.app;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * {@code settings.properties}: string key/value pairs. {@link #save} writes a temporary file beside the real one and
 * moves it into place atomically, so a crash mid-save never leaves a half-written file. Thread-safe.
 */
public final class Settings {

    private final Path file;
    private final Properties props = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    /** Reads {@code file} if it exists; a missing file gives empty settings. */
    public static Settings load(Path file) throws IOException {
        Settings s = new Settings(file);
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                s.props.load(r);
            }
        }
        return s;
    }

    public synchronized String get(String key, String def) {
        return props.getProperty(key, def);
    }

    /** The value as an int, or {@code def} when it is missing or not a number. */
    public synchronized int getInt(String key, int def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Sets {@code key}; a null value removes it. Not written until {@link #save}. */
    public synchronized void put(String key, String value) {
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
    }

    public synchronized void save() throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "settings", ".tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                props.store(w, "Purist settings");
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
