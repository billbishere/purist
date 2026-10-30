package purist.playlist;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a playlist or the queue refers to one track: a file, plus the track number inside it for SACD ISOs and CUE
 * sheets ({@code subsong}: 0 for single-track files, the track number for an ISO or a sheet). Written as a foobar-style
 * {@code file://} URI: the path text is kept exactly as the user's system spells it (backslashes, spaces,
 * {@code #}, non-ASCII), never percent-encoded, normalised or canonicalised, and no file is touched, so paths over
 * 260 characters work.
 *
 * @param path    the file, as the user wrote it
 * @param subsong 0 for a single-track file; for an SACD ISO the track number, counted from 1; for a CUE sheet the
 *                track number the sheet gives
 */
public record TrackRef(Path path, int subsong) {

    private static final String SCHEME = "file://";

    /**
     * A subsong suffix is only recognised after {@code .iso} or {@code .cue}: {@code #} is an ordinary filename
     * character.
     */
    private static final Pattern ISO_SUBSONG = Pattern.compile("(.*\\.(?:iso|cue))#(\\d+)", Pattern.CASE_INSENSITIVE);

    public TrackRef {
        Objects.requireNonNull(path, "path");
        if (subsong < 0) {
            throw new IllegalArgumentException("subsong must not be negative: " + subsong);
        }
    }

    /** {@code file://} + the path, plus {@code #subsong} only when the file is an ISO or a CUE sheet. */
    public String toUri() {
        String text = path.toString();
        return isIso(text) ? SCHEME + text + "#" + subsong : SCHEME + text;
    }

    /**
     * The inverse of {@link #toUri}.
     *
     * @throws IllegalArgumentException if {@code uri} does not start with {@code file://}
     */
    public static TrackRef parse(String uri) {
        if (!uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("not a file:// URI: " + uri);
        }
        String text = uri.substring(SCHEME.length());
        Matcher m = ISO_SUBSONG.matcher(text);
        if (m.matches()) {
            return new TrackRef(Path.of(m.group(1)), Integer.parseInt(m.group(2)));
        }
        return new TrackRef(Path.of(text), 0);
    }

    private static boolean isIso(String pathText) {
        String lower = pathText.toLowerCase(Locale.ROOT);
        return lower.endsWith(".iso") || lower.endsWith(".cue");
    }
}
