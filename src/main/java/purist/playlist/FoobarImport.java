package purist.playlist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import purist.app.AppLog;

/**
 * A one-time, strictly read-only import from the user's foobar2000 profile (spec §8): the music folders and the
 * playlists. Nothing under the profile is ever created, written, renamed or locked: each file is read once with
 * {@link Files#readAllBytes}, which opens it for reading with full sharing, so a running foobar2000 is not
 * disturbed.
 *
 * <p>Formats (verified against a real profile on 2026-10-25):
 * <ul>
 *   <li>{@code library-v2.0\folders} is binary: a count, then per folder a 4-byte length, a {@code file://}
 *       URL and four zero bytes. The URLs are found by scanning for {@code file://} up to the next control byte.
 *   <li>{@code playlists-v2.0\index.txt} has a line {@code GUID:Name} per playlist (UTF-8, usually with a BOM and
 *       CRLF), and {@code playlist-GUID.fplite} holds one location per line.
 *   <li>A location may carry a subsong prefix, {@code 3+file://...\x.iso}. foo_input_sacd numbers the tracks of
 *       an SACD ISO from 1, exactly as {@link TrackRef} does, so the number is kept as is for {@code .iso}
 *       files. Single-track files are written {@code 0+} (or with no prefix) and map to subsong 0; an image written
 *       that way maps to track 1.
 * </ul>
 */
public final class FoobarImport {

    private static final Logger LOG = AppLog.logger(FoobarImport.class);

    /** One imported playlist: its name in foobar2000, and its tracks in order. */
    public record ImportedPlaylist(String name, List<TrackRef> items) {
        public ImportedPlaylist {
            items = List.copyOf(items);
        }
    }

    /** {@code file://} then everything up to the next control byte (the lengths and padding around it). */
    private static final Pattern FOLDER_URL = Pattern.compile("file://([^\\x00-\\x1F]+?)(?=[\\x00-\\x1F]|$)");

    private static final Pattern INDEX_LINE =
            Pattern.compile("([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}):(.*)");

    private static final Pattern LOCATION = Pattern.compile("(?:(\\d+)\\+)?(file://.+)");

    private FoobarImport() {
    }

    /** {@code %APPDATA%\foobar2000-v2}. */
    public static Path defaultProfile() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData)
                : Path.of(System.getProperty("user.home"), "AppData", "Roaming");
        return base.resolve("foobar2000-v2");
    }

    /**
     * The folders foobar2000 watches, in its order, without duplicates and without a trailing backslash (a
     * drive root such as {@code G:\} keeps its one). Empty if the file is missing or unreadable.
     */
    public static List<Path> folders(Path profile) {
        byte[] bytes = readBytes(profile.resolve("library-v2.0").resolve("folders"));
        if (bytes == null) {
            return List.of();
        }
        // Scan as Latin-1 so one char is one byte, then decode each match as the UTF-8 it really is.
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        Set<Path> out = new LinkedHashSet<>();
        Matcher m = FOLDER_URL.matcher(raw);
        while (m.find()) {
            String text = new String(m.group(1).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            while (text.endsWith("\\") && text.length() > 3) {
                text = text.substring(0, text.length() - 1);
            }
            try {
                out.add(Path.of(text));
            } catch (RuntimeException e) { // InvalidPathException
                LOG.log(Level.FINE, "Skipping an unusable foobar2000 folder: " + text, e);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The playlists in foobar2000's tab order. A playlist whose file is missing comes back empty; a location
     * that is not a local file, or not a valid Windows path, is skipped.
     */
    public static List<ImportedPlaylist> playlists(Path profile) {
        Path dir = profile.resolve("playlists-v2.0");
        byte[] index = readBytes(dir.resolve("index.txt"));
        if (index == null) {
            return List.of();
        }
        List<ImportedPlaylist> out = new ArrayList<>();
        for (String line : lines(index)) {
            Matcher m = INDEX_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            Path file = findPlaylistFile(dir, m.group(1));
            byte[] content = file == null ? null : readBytes(file);
            out.add(new ImportedPlaylist(m.group(2), content == null ? List.of() : tracks(content)));
        }
        return out;
    }

    /**
     * {@code items} without the SACD tracks that cannot exist: a track number above the number of tracks the image
     * has. Everything else is kept, including images whose size is not known (their count is negative, or 0: an image stored as a single
     * unsupported, damaged or not-yet-read row has no tracks recorded, which is not the same as having none).
     *
     * @param trackCount the number of tracks of an image, or a number below 1 if it is not known
     */
    public static List<TrackRef> dropMissingTracks(List<TrackRef> items, ToIntFunction<Path> trackCount) {
        List<TrackRef> out = new ArrayList<>(items.size());
        for (TrackRef ref : items) {
            if (ref.subsong() > 0) {
                int count = trackCount.applyAsInt(ref.path());
                if (count > 0 && ref.subsong() > count) {
                    LOG.log(Level.FINE, "Skipping track " + ref.subsong() + " of " + ref.path() + ": it has " + count);
                    continue;
                }
            }
            out.add(ref);
        }
        return out;
    }

    // ---- internals ----

    private static List<TrackRef> tracks(byte[] fplite) {
        List<TrackRef> out = new ArrayList<>();
        for (String line : lines(fplite)) {
            Matcher m = LOCATION.matcher(line);
            if (!m.matches()) {
                continue; // blank, or a location that is not a local file (http://, cdda://)
            }
            String pathText = m.group(2).substring("file://".length());
            try {
                Path path = Path.of(pathText);
                int subsong = 0;
                boolean iso = pathText.toLowerCase(Locale.ROOT).endsWith(".iso");
                if (m.group(1) != null && iso) {
                    subsong = Integer.parseInt(m.group(1));
                }
                if (iso && subsong < 1) {
                    // An SACD image has tracks 1..N and no track 0; foobar2000 writes 0+ (or nothing) for an image
                    // added whole. The first track is the closest meaning.
                    LOG.log(Level.FINE, "foobar2000 location without a track number, using track 1: " + line);
                    subsong = 1;
                }
                out.add(new TrackRef(path, subsong));
            } catch (RuntimeException e) { // InvalidPathException, or a prefix too big for an int
                LOG.log(Level.FINE, "Skipping an unusable foobar2000 location: " + line, e);
            }
        }
        return out;
    }

    /** {@code playlist-<GUID>.fplite} in {@code dir}, matched ignoring the GUID's case; null if absent. */
    private static Path findPlaylistFile(Path dir, String guid) {
        String wanted = ("playlist-" + guid + ".fplite").toLowerCase(Locale.ROOT);
        Path direct = dir.resolve("playlist-" + guid + ".fplite");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "playlist-*.fplite")) {
            for (Path f : files) {
                if (f.getFileName().toString().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return f;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Cannot list " + dir, e);
        }
        return null;
    }

    /** The lines of a UTF-8 text with the BOM, CRLF and trailing blank lines tolerated. */
    private static List<String> lines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read " + file, e);
            return null;
        }
    }
}
