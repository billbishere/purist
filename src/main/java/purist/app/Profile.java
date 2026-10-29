package purist.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The per-user profile directory: {@code %USERPROFILE%\.purist}, or the directory named by the system property
 * {@value #PROPERTY} (tests always set it). It is deliberately not under {@code %APPDATA%}, which the Claude MSIX
 * package virtualises for processes it spawns (spec §8). Directories are created when first asked for.
 */
public final class Profile {

    /** System property that overrides the profile directory. */
    public static final String PROPERTY = "purist.profile";

    private final Path dir;

    private Profile(Path dir) {
        this.dir = dir;
    }

    /** The profile named by {@value #PROPERTY}, else {@code <user.home>\.purist}. */
    public static Profile current() {
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) {
            return new Profile(Path.of(override));
        }
        return new Profile(Path.of(System.getProperty("user.home"), ".purist"));
    }

    /** The profile directory, created if missing. */
    public Path dir() {
        return created(dir);
    }

    public Path libraryDb() {
        return dir().resolve("library.db");
    }

    public Path settings() {
        return dir().resolve("settings.properties");
    }

    /** The playlists directory, created if missing. */
    public Path playlists() {
        return created(dir.resolve("playlists"));
    }

    /** The cover-thumbnail cache directory, created if missing. */
    public Path thumbs() {
        return created(dir.resolve("thumbs"));
    }

    public Path log() {
        return dir().resolve("purist.log");
    }

    public Path scanErrors() {
        return dir().resolve("scan-errors.log");
    }

    private static Path created(Path d) {
        try {
            return Files.createDirectories(d);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + d, e);
        }
    }
}
