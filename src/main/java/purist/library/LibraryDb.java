package purist.library;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import purist.audio.BitOrder;
import purist.audio.Kind;
import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.tags.TagMap;

/**
 * The library database (spec §8): SQLite through plain {@code java.sql}, in WAL mode so the browser can read
 * while the scanner writes. Holds the watched folders (a whole drive is a folder such as {@code E:\}), one
 * {@code tracks} row per playable unit of a file (a subsong of an SACD ISO is its own row), and their tags.
 *
 * <p>Every method is thread-safe. Writes share one connection and each is one transaction; reads use a second,
 * read-only connection, so a long scan never blocks the UI. Writes are visible to reads once they return.
 *
 * <p>Columns: {@code format} holds the codec ({@code FLAC}), {@code kind} holds {@code pcm} or {@code dsd}. A
 * row whose {@code rate} is NULL has no stream format: a cloud-only file (probed at play time), an unsupported
 * format, or a file that failed to parse ({@code supported = 0} with {@code error} set).
 *
 * <p>Failures surface as {@link DbException}.
 */
public final class LibraryDb implements AutoCloseable {

    /** A database failure, wrapped so callers need not handle {@link SQLException} on every call. */
    public static final class DbException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DbException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String SCHEMA_TRACKS = """
            CREATE TABLE IF NOT EXISTS tracks(
              id INTEGER PRIMARY KEY, path TEXT, subsong INT, size INT, mtime INT, format TEXT, kind TEXT,
              rate INT, bits INT, channels INT, length_frames INT, supported INT, error TEXT,
              UNIQUE(path, subsong))""";
    private static final String SCHEMA_TAGS = "CREATE TABLE IF NOT EXISTS tags(track_id INT, field TEXT, value TEXT)";
    private static final String SCHEMA_TAGS_FIELD_VALUE =
            "CREATE INDEX IF NOT EXISTS tags_field_value ON tags(field, value COLLATE NOCASE)";
    private static final String SCHEMA_TAGS_TRACK = "CREATE INDEX IF NOT EXISTS tags_track ON tags(track_id)";
    /** Windows paths ignore case, so "is this file already stored" must too (and still use an index). */
    private static final String SCHEMA_TRACKS_PATH_NOCASE =
            "CREATE INDEX IF NOT EXISTS tracks_path_nocase ON tracks(path COLLATE NOCASE)";
    private static final String SCHEMA_FOLDERS = "CREATE TABLE IF NOT EXISTS folders(path TEXT PRIMARY KEY)";

    private static final String TRACK_COLUMNS = "t.id, t.path, t.subsong, t.format, t.kind, t.rate, t.bits, "
            + "t.channels, t.length_frames, t.supported, t.error";

    /** The first value of a tag of {@code t}, in the order the values were stored. */
    private static String firstTag(String field) {
        return "(SELECT g.value FROM tags g WHERE g.track_id = t.id AND g.field = '" + field
                + "' ORDER BY g.rowid LIMIT 1)";
    }

    /** The prefix test on a path column: {@code path} starts with the bound text, ignoring case. */
    private static final String UNDER_FOLDER = "substr(path, 1, ?) = ? COLLATE NOCASE";

    private static final String UNKNOWN_ARTIST = "Unknown artist";
    private static final String MIXED = "MIXED";

    private final Object writeLock = new Object();
    private final Object readLock = new Object();
    private final Connection writer;
    private final Connection reader;

    private LibraryDb(Connection writer, Connection reader) {
        this.writer = writer;
        this.reader = reader;
    }

    /** Opens (creating if need be) the database at {@code file}. */
    public static LibraryDb open(Path file) throws SQLException {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new SQLException("Cannot create the folder of " + file, e);
        }
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        Connection writer = DriverManager.getConnection(url);
        Connection reader = null;
        try {
            try (Statement s = writer.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute(SCHEMA_TRACKS);
                s.execute(SCHEMA_TAGS);
                s.execute(SCHEMA_TAGS_FIELD_VALUE);
                s.execute(SCHEMA_TAGS_TRACK);
                s.execute(SCHEMA_TRACKS_PATH_NOCASE);
                s.execute(SCHEMA_FOLDERS);
            }
            reader = DriverManager.getConnection(url);
            try (Statement s = reader.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA query_only=ON");
            }
            return new LibraryDb(writer, reader);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(reader);
            closeQuietly(writer);
            throw e;
        }
    }

    // ---- folders ----

    /** The watched folders (a drive root counts), sorted by path ignoring case. */
    public List<Path> folders() {
        return read(c -> {
            List<Path> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT path FROM folders ORDER BY path COLLATE NOCASE")) {
                while (rs.next()) {
                    out.add(Path.of(rs.getString(1)));
                }
            }
            return out;
        });
    }

    /** Watches {@code p}; adding one that is already there changes nothing. */
    public void addFolder(Path p) {
        write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO folders(path) VALUES(?)")) {
                ps.setString(1, p.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Stops watching {@code p} and deletes every track under it, with its tags. */
    public void removeFolder(Path p) {
        write(c -> {
            String prefix = prefix(p);
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM tags WHERE track_id IN (SELECT id FROM tracks WHERE " + UNDER_FOLDER + ")")) {
                bindPrefix(ps, prefix);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM tracks WHERE " + UNDER_FOLDER)) {
                bindPrefix(ps, prefix);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM folders WHERE path = ?")) {
                ps.setString(1, p.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- scanning ----

    /** The stamp stored for every file under {@code folder}, which the scanner compares with the disk. */
    public Map<Path, FileStamp> stamps(Path folder) {
        return read(c -> {
            Map<Path, FileStamp> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT path, size, mtime FROM tracks WHERE " + UNDER_FOLDER + " GROUP BY path")) {
                bindPrefix(ps, prefix(folder));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(Path.of(rs.getString(1)), new FileStamp(rs.getLong(2), rs.getLong(3)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * True if any stored track lies below the folder {@code dir} (a file named {@code dir} does not count). A range
     * test on the case-insensitive path index, so it is cheap enough to ask for every deleted name a watcher sees.
     */
    public boolean hasTracksUnder(Path dir) {
        String prefix = prefix(dir);
        String upper = prefix.substring(0, prefix.length() - 1) + (char) (prefix.charAt(prefix.length() - 1) + 1);
        return read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM tracks WHERE path COLLATE NOCASE >= ? "
                    + "AND path COLLATE NOCASE < ? LIMIT 1")) {
                ps.setString(1, prefix);
                ps.setString(2, upper);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    /** The stamp stored for every file directly in {@code dir} (not in its sub-folders). */
    public Map<Path, FileStamp> stampsIn(Path dir) {
        return read(c -> {
            Map<Path, FileStamp> out = new LinkedHashMap<>();
            String prefix = prefix(dir);
            int n = prefix.codePointCount(0, prefix.length());
            try (PreparedStatement ps = c.prepareStatement("SELECT path, size, mtime FROM tracks WHERE "
                    + UNDER_FOLDER + " AND instr(substr(path, ?), ?) = 0 GROUP BY path")) {
                bindPrefix(ps, prefix);
                ps.setInt(3, n + 1);
                ps.setString(4, File.separator);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(Path.of(rs.getString(1)), new FileStamp(rs.getLong(2), rs.getLong(3)));
                    }
                }
            }
            return out;
        });
    }

    /**
     * Stores what was read from one or more files, in one transaction. For each path in {@code infos} every
     * existing subsong and tag of that path is replaced, so a changed ISO never keeps stale tracks.
     */
    public void upsert(List<TrackInfo> infos, FileStamp stamp) {
        upsertBatch(List.of(new FileRows(infos, stamp)));
    }

    /** What was read from one file, and the stamp to store with it. */
    public record FileRows(List<TrackInfo> infos, FileStamp stamp) {
    }

    /**
     * {@link #upsert} for several files in one transaction (the scanner commits 200 files at a time). Each entry
     * keeps its own stamp.
     */
    public void upsertBatch(List<FileRows> files) {
        if (files.stream().allMatch(f -> f.infos().isEmpty())) {
            return;
        }
        write(c -> {
            String insertTrack = "INSERT INTO tracks(path, subsong, size, mtime, format, kind, rate, bits, channels, "
                    + "length_frames, supported, error) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement track = c.prepareStatement(insertTrack, Statement.RETURN_GENERATED_KEYS);
                    PreparedStatement tag = c.prepareStatement(
                            "INSERT INTO tags(track_id, field, value) VALUES(?,?,?)")) {
                for (FileRows file : files) {
                    Set<String> replaced = new LinkedHashSet<>();
                    for (TrackInfo info : file.infos()) {
                        replaced.add(info.path().toString());
                    }
                    for (String path : replaced) {
                        deletePath(c, path);
                    }
                    for (TrackInfo info : file.infos()) {
                        insertRow(c, track, tag, info, file.stamp());
                    }
                }
            }
            return null;
        });
    }

    /**
     * Keeps a file that failed to parse as a greyed-out row: no format, no tags, {@code supported = 0}, with the
     * message in {@code error}. The stamp is stored so the file is not re-read until it changes.
     */
    public void markError(Path p, FileStamp stamp, String error) {
        write(c -> {
            String path = p.toString();
            deletePath(c, path);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO tracks(path, subsong, size, mtime, format, "
                    + "supported, error) VALUES(?,0,?,?,?,0,?)")) {
                ps.setString(1, path);
                ps.setLong(2, stamp.size());
                ps.setLong(3, stamp.mtime());
                ps.setString(4, codecOf(p));
                ps.setString(5, error);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Deletes every track of each file, with its tags. Files that are not in the database are ignored. */
    public void deleteFiles(Collection<Path> paths) {
        if (paths.isEmpty()) {
            return;
        }
        write(c -> {
            for (Path p : paths) {
                deletePath(c, p.toString());
            }
            return null;
        });
    }

    // ---- queries ----

    /**
     * Tracks matching a condition. {@code whereSql} and {@code orderBy} are SQL fragments written by the
     * program, never by the user; they see {@code tracks} as {@code t} (and may reach {@code tags} through
     * subqueries on {@code t.id}). Every value goes in {@code params}, bound in order. A null or blank
     * {@code whereSql} matches every track; a null or blank {@code orderBy} orders by id.
     */
    public List<TrackRow> tracks(String whereSql, List<Object> params, String orderBy) {
        String where = whereSql == null || whereSql.isBlank() ? "1" : whereSql;
        String order = orderBy == null || orderBy.isBlank() ? "t.id" : orderBy;
        // One read transaction, so the rows and their tags are one snapshot even while the scanner writes.
        return readSnapshot(c -> {
            List<TrackRow> rows = new ArrayList<>();
            Map<Long, TagMap> tagsById = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT " + TRACK_COLUMNS + " FROM tracks t WHERE " + where + " ORDER BY " + order)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        TagMap tags = new TagMap();
                        tagsById.put(id, tags);
                        rows.add(new TrackRow(id, new TrackRef(Path.of(rs.getString(2)), rs.getInt(3)),
                                rs.getString(4), formatOf(rs.getString(5), rs), rs.getInt(10) != 0,
                                rs.getString(11), tags));
                    }
                }
            }
            // The tags of the rows fetched, and no others: the condition may match far more rows than a LIMIT keeps.
            final int chunk = 500;
            for (int from = 0; from < rows.size(); from += chunk) {
                List<TrackRow> part = rows.subList(from, Math.min(rows.size(), from + chunk));
                String marks = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT track_id, field, value FROM tags WHERE track_id IN (" + marks + ") ORDER BY rowid")) {
                    for (int i = 0; i < part.size(); i++) {
                        ps.setLong(i + 1, part.get(i).id());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            TagMap tags = tagsById.get(rs.getLong(1));
                            if (tags != null) {
                                tags.add(rs.getString(2), rs.getString(3));
                            }
                        }
                    }
                }
            }
            return rows;
        });
    }

    /** An album as the browser names it: album artist and album, with the fallbacks of {@link #albums()}. */
    public record AlbumName(String albumArtist, String album) {
    }

    /**
     * The albums that hold at least one track matching a condition, with no limit on how many tracks match (a
     * search whose track list is capped still narrows the grid to every album it touches). The condition is as for
     * {@link #tracks}. Names are spelled as the first matching track has them.
     */
    public Set<AlbumName> albumsMatching(String whereSql, List<Object> params) {
        String where = whereSql == null || whereSql.isBlank() ? "1" : whereSql;
        String artist = "COALESCE(" + firstTag("ALBUMARTIST") + ", " + firstTag("ARTIST") + ", '" + UNKNOWN_ARTIST + "')";
        String album = firstTag("ALBUM");
        // Tracks without an ALBUM tag are named after their folder, so they need their path; the others collapse.
        String sql = "SELECT DISTINCT " + artist + ", " + album + ", CASE WHEN " + album + " IS NULL THEN t.path END "
                + "FROM tracks t WHERE " + where;
        return readSnapshot(c -> {
            Set<AlbumName> out = new LinkedHashSet<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        if (name == null) {
                            Path parent = Path.of(rs.getString(3)).getParent();
                            Path folderName = parent == null ? null : parent.getFileName();
                            name = folderName == null ? "" : folderName.toString();
                        }
                        out.add(new AlbumName(rs.getString(1), name));
                    }
                }
            }
            return out;
        });
    }

    /**
     * The tracks of one album in play order (disc, then track number, then path). Names are matched ignoring
     * case, using the same fallbacks as {@link #albums()}: no ALBUMARTIST means ARTIST, then
     * {@code "Unknown artist"}; no ALBUM means the folder name.
     */
    public List<TrackRow> album(String albumArtist, String album) {
        String artistKey = key(albumArtist);
        String albumKey = key(album);
        List<Brief> hits = new ArrayList<>();
        for (Brief b : briefs()) {
            if (b.artistKey().equals(artistKey) && b.albumKey().equals(albumKey)) {
                hits.add(b);
            }
        }
        hits.sort(PLAY_ORDER);
        Map<Long, TrackRow> byId = new HashMap<>();
        final int chunk = 500;
        for (int from = 0; from < hits.size(); from += chunk) {
            List<Brief> part = hits.subList(from, Math.min(hits.size(), from + chunk));
            List<Object> ids = new ArrayList<>();
            for (Brief b : part) {
                ids.add(b.id());
            }
            String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            for (TrackRow row : tracks("t.id IN (" + marks + ")", ids, null)) {
                byId.put(row.id(), row);
            }
        }
        List<TrackRow> out = new ArrayList<>(hits.size());
        for (Brief b : hits) {
            TrackRow row = byId.get(b.id());
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * Every album: tracks grouped by album artist (ALBUMARTIST, else ARTIST, else {@code "Unknown artist"}) then
     * ALBUM (else the folder name), ignoring case, sorted by artist then album ignoring case. When spellings
     * differ the first track by path decides which is shown.
     */
    public List<AlbumSummary> albums() {
        List<Brief> all = briefs();
        all.sort(Comparator.comparing((Brief b) -> b.path().toLowerCase(Locale.ROOT)).thenComparingInt(Brief::subsong));
        Map<String, String> artistSpelling = new HashMap<>();
        Map<String, List<Brief>> groups = new LinkedHashMap<>();
        for (Brief b : all) {
            artistSpelling.putIfAbsent(b.artistKey(), b.artist());
            groups.computeIfAbsent(b.artistKey() + '\0' + b.albumKey(), k -> new ArrayList<>()).add(b);
        }
        List<AlbumSummary> out = new ArrayList<>(groups.size());
        for (List<Brief> g : groups.values()) {
            Brief spelled = g.get(0);
            List<Brief> playOrder = new ArrayList<>(g);
            playOrder.sort(PLAY_ORDER);
            Brief first = playOrder.get(0);
            String year = "";
            for (Brief b : playOrder) {
                if (!b.year().isEmpty()) {
                    year = b.year();
                    break;
                }
            }
            Path firstPath = Path.of(first.path());
            Path folder = firstPath.getParent() == null ? firstPath : firstPath.getParent();
            out.add(new AlbumSummary(artistSpelling.get(spelled.artistKey()), spelled.album(), year, folder,
                    g.size(), new TrackRef(firstPath, first.subsong()), formatLabel(g)));
        }
        out.sort(Comparator
                .comparing((AlbumSummary a) -> key(a.albumArtist()))
                .thenComparing(a -> key(a.album()))
                .thenComparing(AlbumSummary::albumArtist)
                .thenComparing(AlbumSummary::album));
        return out;
    }

    /**
     * The album artists (ALBUMARTIST, else ARTIST, else {@code "Unknown artist"}), each once whatever its case,
     * sorted ignoring case, spelled as {@link #albums()} spells them.
     */
    public List<String> artists() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AlbumSummary a : albums()) {
            if (seen.add(key(a.albumArtist()))) {
                out.add(a.albumArtist());
            }
        }
        return out;
    }

    /** The number of rows, counting unsupported and damaged files. */
    public int trackCount() {
        return read(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM tracks")) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    @Override
    public void close() {
        synchronized (writeLock) {
            synchronized (readLock) {
                closeQuietly(reader);
                closeQuietly(writer);
            }
        }
    }

    // ---- internals ----

    /** The columns of a track that grouping and ordering need; built without loading tags. */
    private record Brief(long id, String path, int subsong, String codec, StreamFormat format, String artist,
            String album, String year, int disc, int number) {

        String artistKey() {
            return key(artist);
        }

        String albumKey() {
            return key(album);
        }
    }

    /** Disc, then track number (none last), then path, then subsong. */
    private static final Comparator<Brief> PLAY_ORDER = Comparator
            .comparingInt(Brief::disc)
            .thenComparingInt(Brief::number)
            .thenComparing((Brief b) -> b.path().toLowerCase(Locale.ROOT))
            .thenComparingInt(Brief::subsong);

    private List<Brief> briefs() {
        String sql = "SELECT t.id, t.path, t.subsong, t.format, t.kind, t.rate, t.bits, t.channels, t.length_frames, "
                + "COALESCE(" + firstTag("ALBUMARTIST") + ", " + firstTag("ARTIST") + ", '" + UNKNOWN_ARTIST + "'), "
                + firstTag("ALBUM") + ", " + firstTag("DATE") + ", " + firstTag("DISCNUMBER") + ", "
                + firstTag("TRACKNUMBER") + " FROM tracks t";
        return read(c -> {
            List<Brief> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    String path = rs.getString(2);
                    String album = rs.getString(11);
                    if (album == null) {
                        Path folderName = Path.of(path).getParent() == null ? null : Path.of(path).getParent().getFileName();
                        album = folderName == null ? "" : folderName.toString();
                    }
                    String date = rs.getString(12);
                    out.add(new Brief(rs.getLong(1), path, rs.getInt(3), rs.getString(4),
                            formatOf(rs.getString(5), 6, rs), rs.getString(10), album, yearOf(date),
                            leadingInt(rs.getString(13), 0), leadingInt(rs.getString(14), Integer.MAX_VALUE)));
                }
            }
            return out;
        });
    }

    private static String formatLabel(List<Brief> group) {
        Set<String> labels = new LinkedHashSet<>();
        for (Brief b : group) {
            if (b.format() != null) {
                labels.add(b.format().kind() == Kind.PCM ? b.codec() + " " + b.format().label() : b.format().label());
            }
        }
        if (labels.isEmpty()) {
            return group.get(0).codec() == null ? "" : group.get(0).codec();
        }
        return labels.size() == 1 ? labels.iterator().next() : MIXED;
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** The first four characters when they are digits, else the empty string. */
    private static String yearOf(String date) {
        if (date == null || date.length() < 4) {
            return "";
        }
        for (int i = 0; i < 4; i++) {
            if (!Character.isDigit(date.charAt(i))) {
                return "";
            }
        }
        return date.substring(0, 4);
    }

    /** The digits at the start of {@code s} (so {@code "3/12"} gives 3), or {@code none} if there are none. */
    private static int leadingInt(String s, int none) {
        if (s == null) {
            return none;
        }
        String t = s.strip();
        int end = 0;
        while (end < t.length() && end < 9 && Character.isDigit(t.charAt(end))) {
            end++;
        }
        return end == 0 ? none : Integer.parseInt(t.substring(0, end));
    }

    private static void insertRow(Connection c, PreparedStatement track, PreparedStatement tag, TrackInfo info,
            FileStamp stamp) throws SQLException {
        StreamFormat f = info.format();
        track.setString(1, info.path().toString());
        track.setInt(2, info.subsong());
        track.setLong(3, stamp.size());
        track.setLong(4, stamp.mtime());
        track.setString(5, info.codec());
        if (f == null) {
            track.setNull(6, java.sql.Types.VARCHAR);
            track.setNull(7, java.sql.Types.INTEGER);
            track.setNull(8, java.sql.Types.INTEGER);
            track.setNull(9, java.sql.Types.INTEGER);
            track.setNull(10, java.sql.Types.INTEGER);
        } else {
            track.setString(6, f.kind() == Kind.DSD ? "dsd" : "pcm");
            track.setInt(7, f.sampleRate());
            track.setInt(8, f.bits());
            track.setInt(9, f.channels());
            track.setLong(10, f.lengthFrames());
        }
        track.setInt(11, info.supported() ? 1 : 0);
        track.setString(12, info.reason());
        track.executeUpdate();
        long id;
        try (ResultSet keys = track.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No row id for " + info.path());
            }
            id = keys.getLong(1);
        }
        for (Map.Entry<String, List<String>> e : info.tags().asMap().entrySet()) {
            for (String value : e.getValue()) {
                tag.setLong(1, id);
                tag.setString(2, e.getKey());
                tag.setString(3, value);
                tag.addBatch();
            }
        }
        tag.executeBatch();
    }

    /** Rebuilds the stream format of a row, or null when the row has none (rate NULL). */
    private static StreamFormat formatOf(String kind, ResultSet rs) throws SQLException {
        return formatOf(kind, 6, rs);
    }

    /** {@code rateColumn} is the 1-based column of rate; bits, channels and length follow it. */
    private static StreamFormat formatOf(String kind, int rateColumn, ResultSet rs) throws SQLException {
        int rate = rs.getInt(rateColumn);
        if (rs.wasNull()) {
            return null;
        }
        int bits = rs.getInt(rateColumn + 1);
        int channels = rs.getInt(rateColumn + 2);
        long frames = rs.getLong(rateColumn + 3);
        if (rs.wasNull()) {
            frames = -1;
        }
        if ("dsd".equals(kind)) {
            return StreamFormat.dsd(rate, channels, frames, BitOrder.MSB_FIRST);
        }
        return StreamFormat.pcm(rate, bits, channels, frames);
    }

    private static String codecOf(Path p) {
        Path name = p.getFileName();
        String s = name == null ? "" : name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 && dot < s.length() - 1 ? s.substring(dot + 1).toUpperCase(Locale.ROOT) : "UNKNOWN";
    }

    private static void deletePath(Connection c, String path) throws SQLException {
        // NOCASE: E:\Music\a.flac and e:\music\A.FLAC are one file (tracks_path_nocase serves the lookup).
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM tags WHERE track_id IN (SELECT id FROM tracks WHERE path = ? COLLATE NOCASE)")) {
            ps.setString(1, path);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM tracks WHERE path = ? COLLATE NOCASE")) {
            ps.setString(1, path);
            ps.executeUpdate();
        }
    }

    /** The folder's path text ending in a separator, so {@code E:\Music} does not claim {@code E:\Music2}. */
    private static String prefix(Path folder) {
        String s = folder.toString();
        return s.endsWith(File.separator) ? s : s + File.separator;
    }

    private static void bindPrefix(PreparedStatement ps, String prefix) throws SQLException {
        // SQLite's substr counts characters (code points), not UTF-16 units.
        ps.setInt(1, prefix.codePointCount(0, prefix.length()));
        ps.setString(2, prefix);
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T read(Work<T> work) {
        synchronized (readLock) {
            try {
                return work.run(reader);
            } catch (SQLException e) {
                throw new DbException("Library database read failed: " + e.getMessage(), e);
            }
        }
    }

    /** As {@link #read}, inside one transaction so several statements see the same snapshot. */
    private <T> T readSnapshot(Work<T> work) {
        synchronized (readLock) {
            try {
                reader.setAutoCommit(false);
                try {
                    return work.run(reader);
                } finally {
                    try {
                        reader.rollback(); // nothing was written; this ends the read transaction
                    } finally {
                        reader.setAutoCommit(true);
                    }
                }
            } catch (SQLException e) {
                throw new DbException("Library database read failed: " + e.getMessage(), e);
            }
        }
    }

    private <T> T write(Work<T> work) {
        synchronized (writeLock) {
            try {
                writer.setAutoCommit(false);
                try {
                    T result = work.run(writer);
                    writer.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        writer.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                } finally {
                    writer.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new DbException("Library database write failed: " + e.getMessage(), e);
            }
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException e) {
                // Nothing useful to do while closing.
            }
        }
    }
}
