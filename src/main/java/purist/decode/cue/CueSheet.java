package purist.decode.cue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import purist.decode.DecodeException;
import purist.tags.TagMap;

/**
 * A parsed CUE sheet: the tracks of one or more audio files, each starting at an {@code INDEX 01} given in CD frames
 * (75 a second). Only reads the sheet itself; the audio files it names are resolved but never opened here.
 *
 * <p>The text is UTF-8 when it decodes as UTF-8 (with or without a BOM), otherwise the system's ANSI code page, which
 * is what the CD rippers that wrote it used.
 *
 * @param album  the sheet-level fields: ALBUM, ALBUMARTIST, DATE, GENRE, DISCNUMBER, TOTALDISCS, CATALOG
 * @param tracks the audio tracks in sheet order; data tracks are left out
 */
public record CueSheet(TagMap album, List<Track> tracks) {

    /** CD frames per second: the unit of {@code mm:ss:ff}. */
    public static final int FRAMES_PER_SECOND = 75;

    /** A sheet larger than this is not a CUE sheet. */
    private static final long MAX_BYTES = 1 << 20;

    /**
     * One audio track.
     *
     * @param number the track number written in the sheet
     * @param file   the audio file holding it, resolved against the sheet's folder
     * @param start  where {@code INDEX 01} puts it, in CD frames from the start of {@code file}
     * @param tags   TITLE, ARTIST, COMPOSER and ISRC as the sheet gives them
     */
    public record Track(int number, Path file, long start, TagMap tags) {
    }

    /** The distinct audio files the tracks are in, in sheet order. */
    public Set<Path> files() {
        Set<Path> out = new LinkedHashSet<>();
        for (Track t : tracks) {
            out.add(t.file());
        }
        return out;
    }

    /**
     * True if every file holds exactly one track: a sheet written next to a rip of separate track files (EAC and
     * friends, whose INDEX 01 can sit past a pre-gap at the start of the file). Those files are the tracks, played
     * whole, so such a sheet adds nothing.
     */
    public boolean perTrackFiles() {
        Set<Path> seen = new LinkedHashSet<>();
        for (Track t : tracks) {
            if (!seen.add(t.file())) {
                return false;
            }
        }
        return !tracks.isEmpty(); // a one-track sheet adds nothing either: its file plays whole
    }

    /** Reads and parses {@code cue}. */
    public static CueSheet read(Path cue) throws IOException {
        if (Files.size(cue) > MAX_BYTES) {
            throw new DecodeException("too large for a CUE sheet");
        }
        Path dir = cue.toAbsolutePath().getParent();
        return parse(text(Files.readAllBytes(cue)), dir);
    }

    /**
     * Parses sheet text. FILE names are resolved against {@code dir}.
     *
     * @throws DecodeException if the sheet has no audio track, or a track has no {@code INDEX 01} or a malformed one
     */
    public static CueSheet parse(String text, Path dir) throws DecodeException {
        TagMap album = new TagMap();
        List<Track> tracks = new ArrayList<>();
        Path file = null;
        int number = -1;
        boolean audio = false;
        long start = -1;
        TagMap tags = null;
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.strip();
            int sp = line.indexOf(' ');
            String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.ROOT);
            String rest = sp < 0 ? "" : line.substring(sp + 1).strip();
            switch (cmd) {
                case "FILE" -> {
                    if (number >= 0) {
                        add(tracks, number, file, audio, start, tags);
                        number = -1;
                    }
                    file = resolve(dir, fileName(rest));
                }
                case "TRACK" -> {
                    if (number >= 0) {
                        add(tracks, number, file, audio, start, tags);
                    }
                    String[] parts = rest.split("\\s+");
                    number = parseInt(parts[0], line);
                    audio = parts.length > 1 && parts[1].equalsIgnoreCase("AUDIO");
                    start = -1;
                    tags = new TagMap();
                    tags.add("TRACKNUMBER", Integer.toString(number));
                }
                case "INDEX" -> {
                    String[] parts = rest.split("\\s+");
                    if (number >= 0 && parts.length == 2 && parseInt(parts[0], line) == 1) {
                        start = time(parts[1], line);
                    }
                }
                case "TITLE" -> (number >= 0 ? tags : album).add(number >= 0 ? "TITLE" : "ALBUM", value(rest));
                case "PERFORMER" -> (number >= 0 ? tags : album).add(number >= 0 ? "ARTIST" : "ALBUMARTIST",
                        value(rest));
                case "SONGWRITER" -> (number >= 0 ? tags : album).add("COMPOSER", value(rest));
                case "ISRC" -> {
                    if (number >= 0) {
                        tags.add("ISRC", value(rest));
                    }
                }
                case "CATALOG" -> album.add("CATALOG", value(rest));
                case "REM" -> {
                    int s = rest.indexOf(' ');
                    if (s > 0 && number < 0) {
                        String key = rest.substring(0, s).toUpperCase(Locale.ROOT);
                        if (Set.of("DATE", "GENRE", "DISCNUMBER", "TOTALDISCS", "COMMENT").contains(key)) {
                            album.add(key, value(rest.substring(s + 1).strip()));
                        }
                    }
                }
                default -> {
                    // FLAGS, PREGAP, POSTGAP (silence that is not in the file), INDEX 00, blank lines, junk
                }
            }
        }
        if (number >= 0) {
            add(tracks, number, file, audio, start, tags);
        }
        if (tracks.isEmpty()) {
            throw new DecodeException("the CUE sheet lists no audio track");
        }
        return new CueSheet(album, List.copyOf(tracks));
    }

    private static void add(List<Track> tracks, int number, Path file, boolean audio, long start, TagMap tags)
            throws DecodeException {
        if (!audio) {
            return; // a data track of an enhanced CD
        }
        if (file == null) {
            throw new DecodeException("track " + number + " of the CUE sheet comes before any FILE");
        }
        if (start < 0) {
            throw new DecodeException("track " + number + " of the CUE sheet has no INDEX 01");
        }
        tracks.add(new Track(number, file, start, tags));
    }

    /** {@code "name.flac" WAVE} or {@code name.flac WAVE}: the name without the type. */
    private static String fileName(String rest) {
        if (rest.startsWith("\"")) {
            int end = rest.lastIndexOf('"');
            return end > 0 ? rest.substring(1, end) : rest.substring(1);
        }
        int sp = rest.lastIndexOf(' ');
        return sp > 0 ? rest.substring(0, sp) : rest;
    }

    private static Path resolve(Path dir, String name) throws DecodeException {
        try {
            Path p = Path.of(name);
            return p.isAbsolute() || dir == null ? p : dir.resolve(p);
        } catch (RuntimeException e) {
            throw new DecodeException("the CUE sheet names a file that is not a valid path: " + name);
        }
    }

    /** A quoted value without its quotes, or the bare text. */
    private static String value(String rest) {
        if (rest.length() >= 2 && rest.startsWith("\"") && rest.endsWith("\"")) {
            return rest.substring(1, rest.length() - 1);
        }
        return rest;
    }

    /** {@code mm:ss:ff} in CD frames. Minutes may exceed 99. */
    private static long time(String mmssff, String line) throws DecodeException {
        String[] p = mmssff.split(":");
        if (p.length != 3) {
            throw new DecodeException("bad CUE time: " + line);
        }
        long m = parseInt(p[0], line);
        long s = parseInt(p[1], line);
        long f = parseInt(p[2], line);
        if (s >= 60 || f >= FRAMES_PER_SECOND) {
            throw new DecodeException("bad CUE time: " + line);
        }
        return (m * 60 + s) * FRAMES_PER_SECOND + f;
    }

    private static int parseInt(String s, String line) throws DecodeException {
        try {
            int n = Integer.parseInt(s);
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new DecodeException("bad number in the CUE sheet: " + line);
        }
    }

    /** UTF-8 (minus a BOM) when the bytes are valid UTF-8, otherwise the system's ANSI code page. */
    static String text(byte[] bytes) {
        int off = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF
                ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, ansi());
        }
    }

    private static Charset ansi() {
        try {
            return Charset.forName(System.getProperty("native.encoding", "windows-1252"));
        } catch (RuntimeException e) {
            return Charset.forName("windows-1252");
        }
    }
}
