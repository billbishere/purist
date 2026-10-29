package purist.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.tags.TagMap;

class LibraryDbTest {

    @TempDir
    Path tmp;

    private Path dbFile;
    private LibraryDb db;

    @BeforeEach
    void open() throws SQLException {
        dbFile = tmp.resolve("library.db");
        db = LibraryDb.open(dbFile);
    }

    @AfterEach
    void close() {
        db.close();
    }

    private static final Path MUSIC = Path.of("E:\\Music");

    private static TagMap tags(String... kv) {
        TagMap t = new TagMap();
        for (int i = 0; i < kv.length; i += 2) {
            t.add(kv[i], kv[i + 1]);
        }
        return t;
    }

    private static TrackInfo flac(Path p, TagMap tags) {
        return new TrackInfo(p, 0, "FLAC", StreamFormat.pcm(96000, 24, 2, 960000), tags, true, null);
    }

    private static TrackInfo flac(Path p, String... kv) {
        return flac(p, tags(kv));
    }

    private void add(TrackInfo info, long size, long mtime) {
        db.upsert(List.of(info), new FileStamp(size, mtime));
    }

    private List<TrackRow> byPath() {
        return db.tracks(null, List.of(), "t.path, t.subsong");
    }

    private int rawCount(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void upsertReplacesSubsongsAndTags() throws SQLException {
        Path iso = MUSIC.resolve("Album.iso");
        StreamFormat dsd = StreamFormat.dsd(2822400, 2, 1_000_000, BitOrder.MSB_FIRST);
        db.upsert(List.of(
                new TrackInfo(iso, 1, "DSD", dsd, tags("TITLE", "One", "ARTIST", "Old Artist"), true, null),
                new TrackInfo(iso, 2, "DSD", dsd, tags("TITLE", "Two"), true, null),
                new TrackInfo(iso, 3, "DSD", dsd, tags("TITLE", "Three"), true, null)),
                new FileStamp(100, 1));
        assertEquals(3, db.trackCount());
        assertEquals(4, rawCount("SELECT COUNT(*) FROM tags"));

        db.upsert(List.of(
                new TrackInfo(iso, 1, "DSD", dsd, tags("TITLE", "Uno"), true, null),
                new TrackInfo(iso, 2, "DSD", dsd, tags("TITLE", "Dos", "ALBUM", "New"), true, null)),
                new FileStamp(200, 2));

        List<TrackRow> rows = byPath();
        assertEquals(2, rows.size());
        assertEquals(new TrackRef(iso, 1), rows.get(0).ref());
        assertEquals("Uno", rows.get(0).tags().first("TITLE").orElseThrow());
        assertTrue(rows.get(0).tags().first("ARTIST").isEmpty(), "old tags must be gone");
        assertEquals("New", rows.get(1).tags().first("ALBUM").orElseThrow());
        assertEquals(3, rawCount("SELECT COUNT(*) FROM tags"), "no orphaned tag rows");
        assertEquals(Map.of(iso, new FileStamp(200, 2)), db.stamps(MUSIC));
    }

    @Test
    void stampsReflectUpserts() {
        Path a = MUSIC.resolve("a.flac");
        Path b = MUSIC.resolve("Sub").resolve("b.flac");
        Path other = Path.of("E:\\Music2\\c.flac");
        add(flac(a), 10, 100);
        add(flac(b), 20, 200);
        add(flac(other), 30, 300);

        assertEquals(Map.of(a, new FileStamp(10, 100), b, new FileStamp(20, 200)), db.stamps(MUSIC));
        assertEquals(Map.of(b, new FileStamp(20, 200)), db.stamps(MUSIC.resolve("sub")), "folder match ignores case");
        assertEquals(Map.of(other, new FileStamp(30, 300)), db.stamps(Path.of("E:\\Music2")));

        add(flac(a), 11, 101);
        assertEquals(new FileStamp(11, 101), db.stamps(MUSIC).get(a));

        db.deleteFiles(Set.of(a, other));
        assertEquals(Map.of(b, new FileStamp(20, 200)), db.stamps(Path.of("E:\\")));
    }

    @Test
    void stampsOfADriveRoot() {
        Path a = Path.of("E:\\a.flac");
        add(flac(a), 1, 2);
        assertEquals(Map.of(a, new FileStamp(1, 2)), db.stamps(Path.of("E:\\")));
    }

    @Test
    void hasTracksUnderIsAFolderPrefixTestNotAStringPrefixTest() {
        add(flac(Path.of("E:\\Music\\R.E.M\\Automatic\\01.flac"), "TITLE", "A"), 1, 1);
        add(flac(Path.of("E:\\Music\\Vol.2 b\\02.flac"), "TITLE", "B"), 1, 1);

        assertTrue(db.hasTracksUnder(Path.of("E:\\Music\\R.E.M")));
        assertTrue(db.hasTracksUnder(Path.of("e:\\music\\r.e.m\\Automatic")), "case-insensitive like the volume");
        assertTrue(db.hasTracksUnder(Path.of("E:\\Music")));
        assertFalse(db.hasTracksUnder(Path.of("E:\\Music\\R.E")), "R.E.M is not under R.E");
        assertFalse(db.hasTracksUnder(Path.of("E:\\Music\\cover.jpg")));
        assertFalse(db.hasTracksUnder(Path.of("E:\\Music\\R.E.M\\Automatic\\01.flac")), "a file is not a folder");
    }

    @Test
    void removeFolderDeletesTracks() throws SQLException {
        Path keep = Path.of("F:\\Keep\\k.flac");
        db.addFolder(MUSIC);
        db.addFolder(Path.of("F:\\Keep"));
        db.addFolder(MUSIC);
        add(flac(MUSIC.resolve("x.flac"), "TITLE", "X"), 1, 1);
        add(flac(MUSIC.resolve("Deep").resolve("y.flac"), "TITLE", "Y"), 1, 1);
        add(flac(Path.of("E:\\Music2\\z.flac"), "TITLE", "Z"), 1, 1);
        add(flac(keep, "TITLE", "K"), 1, 1);
        assertEquals(List.of(Path.of("E:\\Music"), Path.of("F:\\Keep")), db.folders());

        db.removeFolder(MUSIC);

        assertEquals(List.of(Path.of("F:\\Keep")), db.folders());
        assertEquals(List.of(Path.of("E:\\Music2\\z.flac"), keep),
                byPath().stream().map(r -> r.ref().path()).toList());
        assertEquals(2, rawCount("SELECT COUNT(*) FROM tags"), "tags of removed tracks are deleted too");
    }

    @Test
    void albumsGroupedWithArtistFallback() {
        add(flac(MUSIC.resolve("A").resolve("1.flac"), "ALBUMARTIST", "Zed", "ARTIST", "Guest", "ALBUM", "Beta",
                "DATE", "1999-05-01", "TRACKNUMBER", "2"), 1, 1);
        add(flac(MUSIC.resolve("A").resolve("2.flac"), "ALBUMARTIST", "zed", "ARTIST", "Other", "ALBUM", "BETA",
                "TRACKNUMBER", "1"), 1, 1);
        // No ALBUMARTIST: falls back to ARTIST.
        add(flac(MUSIC.resolve("B").resolve("3.flac"), "ARTIST", "alpha", "ALBUM", "Gamma", "DATE", "2001"), 1, 1);
        // Neither artist tag: "Unknown artist"; no album tag: the folder name.
        add(flac(MUSIC.resolve("Loose").resolve("4.flac")), 1, 1);
        // Same artist, a second album that sorts before Beta.
        add(flac(MUSIC.resolve("C").resolve("5.flac"), "ALBUMARTIST", "Zed", "ALBUM", "alpha"), 1, 1);

        List<AlbumSummary> albums = db.albums();

        assertEquals(List.of("alpha", "Unknown artist", "Zed", "Zed"),
                albums.stream().map(AlbumSummary::albumArtist).toList());
        assertEquals(List.of("Gamma", "Loose", "alpha", "Beta"), albums.stream().map(AlbumSummary::album).toList());
        AlbumSummary beta = albums.get(3);
        assertEquals("Zed", beta.albumArtist());
        assertEquals(2, beta.trackCount());
        assertEquals("1999", beta.year());
        assertEquals(MUSIC.resolve("A"), beta.folder());
        assertEquals(new TrackRef(MUSIC.resolve("A").resolve("2.flac"), 0), beta.firstTrack(), "track number 1 first");
        assertEquals("FLAC 24/96", beta.formatLabel());
        assertEquals("2001", albums.get(0).year());
        assertEquals("", albums.get(1).year());
        assertEquals(List.of("alpha", "Unknown artist", "Zed"), db.artists());
    }

    @Test
    void albumTracksAreOrderedByDiscAndTrackNumber() {
        add(flac(MUSIC.resolve("d2t1.flac"), "ALBUMARTIST", "A", "ALBUM", "X", "DISCNUMBER", "2", "TRACKNUMBER", "1"), 1, 1);
        add(flac(MUSIC.resolve("d1t10.flac"), "ALBUMARTIST", "A", "ALBUM", "X", "DISCNUMBER", "1", "TRACKNUMBER", "10/12"), 1, 1);
        add(flac(MUSIC.resolve("d1t2.flac"), "ALBUMARTIST", "A", "ALBUM", "X", "DISCNUMBER", "1", "TRACKNUMBER", "2"), 1, 1);
        add(flac(MUSIC.resolve("other.flac"), "ALBUMARTIST", "A", "ALBUM", "Y"), 1, 1);

        List<TrackRow> album = db.album("a", "x");

        assertEquals(List.of("d1t2.flac", "d1t10.flac", "d2t1.flac"),
                album.stream().map(r -> r.ref().path().getFileName().toString()).toList());
        assertEquals(List.of(), db.album("A", "nope"));
        assertEquals("d1t2.flac", db.albums().get(0).firstTrack().path().getFileName().toString());
    }

    @Test
    void mixedFormatsLabelledMixed() {
        add(flac(MUSIC.resolve("a.flac"), "ALBUMARTIST", "A", "ALBUM", "X"), 1, 1);
        add(new TrackInfo(MUSIC.resolve("b.dsf"), 0, "DSF", StreamFormat.dsd(5644800, 2, 10, BitOrder.LSB_FIRST),
                tags("ALBUMARTIST", "A", "ALBUM", "X"), true, null), 1, 1);

        assertEquals("MIXED", db.albums().get(0).formatLabel());
    }

    @Test
    void errorRowsKept() {
        Path bad = MUSIC.resolve("bad.flac");
        Path mp3 = MUSIC.resolve("song.mp3");
        add(flac(bad, "TITLE", "Was fine"), 5, 5);
        add(TrackInfo.unsupported(mp3, "MP3", "MP3 is not supported in v1"), 9, 9);

        db.markError(bad, new FileStamp(6, 6), "FLAC frame CRC mismatch");

        List<TrackRow> rows = byPath();
        assertEquals(2, rows.size());
        TrackRow badRow = rows.get(0);
        assertEquals(bad, badRow.ref().path());
        assertFalse(badRow.supported());
        assertEquals("FLAC frame CRC mismatch", badRow.error());
        assertEquals("FLAC", badRow.codec());
        assertNull(badRow.format());
        assertTrue(badRow.tags().isEmpty());
        TrackRow mp3Row = rows.get(1);
        assertFalse(mp3Row.supported());
        assertEquals("MP3 is not supported in v1", mp3Row.error());
        assertEquals("MP3 is not supported in v1", mp3Row.toInfo().reason());
        assertEquals(new FileStamp(6, 6), db.stamps(MUSIC).get(bad), "the stamp is stored so it is not re-read");

        // A later good read replaces the error row.
        add(flac(bad, "TITLE", "Fixed"), 7, 7);
        TrackRow fixed = byPath().get(0);
        assertTrue(fixed.supported());
        assertNull(fixed.error());
        assertEquals(2, db.trackCount());
    }

    @Test
    void cloudOnlyRowHasNullFormat() throws SQLException {
        Path p = MUSIC.resolve("cloud.flac");
        add(TrackInfo.cloudOnly(p, "FLAC"), 1000, 7);

        TrackRow row = byPath().get(0);

        assertNull(row.format());
        assertTrue(row.supported());
        assertNull(row.error());
        assertEquals("FLAC", row.codec());
        assertEquals(1, rawCount("SELECT COUNT(*) FROM tracks WHERE rate IS NULL AND kind IS NULL AND supported = 1"));
        TrackInfo info = row.toInfo();
        assertNull(info.format());
        assertTrue(info.supported());
    }

    @Test
    void formatsAndTagsRoundTrip() {
        Path pcm = MUSIC.resolve("a.flac");
        Path dsd = MUSIC.resolve("b.dsf");
        TagMap t = tags("ARTIST", "One", "TITLE", "Song");
        t.add("ARTIST", "Two");
        t.add("COMMENT", "caf\u00e9 \u65e5\u672c\u8a9e");
        TrackInfo pcmInfo = flac(pcm, t);
        add(pcmInfo, 1, 1);
        add(new TrackInfo(dsd, 0, "DSF", StreamFormat.dsd(5644800, 2, 123456, BitOrder.LSB_FIRST), tags(), true, null), 1, 1);

        List<TrackRow> rows = byPath();

        assertEquals(pcmInfo, rows.get(0).toInfo());
        assertEquals(List.of("One", "Two"), rows.get(0).tags().all("ARTIST"));
        assertEquals(List.of("ARTIST", "TITLE", "COMMENT"), List.copyOf(rows.get(0).tags().asMap().keySet()));
        StreamFormat f = rows.get(1).format();
        assertEquals(purist.audio.Kind.DSD, f.kind());
        assertEquals(5644800, f.sampleRate());
        assertEquals(2, f.channels());
        assertEquals(123456, f.lengthFrames());
        assertEquals("DSD128", rows.get(1).toInfo().badge());
        assertTrue(rows.get(0).id() > 0);
    }

    @Test
    void tracksFilterAndOrder() {
        add(flac(MUSIC.resolve("b.flac"), "TITLE", "Bravo"), 1, 1);
        add(flac(MUSIC.resolve("a.flac"), "TITLE", "alpha"), 1, 1);
        add(new TrackInfo(MUSIC.resolve("c.dsf"), 0, "DSF", StreamFormat.dsd(2822400, 2, 1, BitOrder.LSB_FIRST),
                tags("TITLE", "Charlie"), true, null), 1, 1);

        List<TrackRow> dsf = db.tracks("t.format = ?", List.of("DSF"), "t.path");
        List<TrackRow> byTitle = db.tracks("t.kind = ? AND EXISTS (SELECT 1 FROM tags g WHERE g.track_id = t.id "
                + "AND g.field = ? AND g.value LIKE ? ESCAPE '\\')", List.of("pcm", "TITLE", "%a%"), "t.path DESC");
        List<TrackRow> none = db.tracks("t.path = ?", List.of("'; DROP TABLE tracks; --"), null);

        assertEquals(1, dsf.size());
        assertEquals("Charlie", dsf.get(0).tags().first("TITLE").orElseThrow());
        assertEquals(List.of("b.flac", "a.flac"),
                byTitle.stream().map(r -> r.ref().path().getFileName().toString()).toList());
        assertTrue(none.isEmpty());
        assertEquals(3, db.trackCount());
    }

    @Test
    void persistsAcrossReopenWithWalAndSchema() throws SQLException {
        db.addFolder(MUSIC);
        add(flac(MUSIC.resolve("a.flac"), "TITLE", "A"), 4, 5);
        db.close();

        db = LibraryDb.open(dbFile);

        assertEquals(1, db.trackCount());
        assertEquals(List.of(MUSIC), db.folders());
        assertEquals("A", byPath().get(0).tags().first("TITLE").orElseThrow());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("PRAGMA journal_mode")) {
                rs.next();
                assertEquals("wal", rs.getString(1));
            }
            try (ResultSet rs = s.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type IN ('table','index') AND name NOT LIKE 'sqlite_%'")) {
                Set<String> names = new java.util.HashSet<>();
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
                assertTrue(names.containsAll(Set.of("tracks", "tags", "folders")), names.toString());
                assertTrue(names.size() >= 5, "indexes on tags exist: " + names);
            }
        }
    }

    @Test
    void upsertBatchStoresSeveralFilesWithTheirOwnStampsInOneCall() {
        db.upsertBatch(List.of(
                new LibraryDb.FileRows(List.of(flac(MUSIC.resolve("a.flac"), "TITLE", "A")), new FileStamp(10, 100)),
                new LibraryDb.FileRows(List.of(flac(MUSIC.resolve("b.flac"), "TITLE", "B")), new FileStamp(20, 200))));

        assertEquals(2, db.trackCount());
        assertEquals(new FileStamp(10, 100), db.stamps(MUSIC).get(MUSIC.resolve("a.flac")));
        assertEquals(new FileStamp(20, 200), db.stamps(MUSIC).get(MUSIC.resolve("b.flac")));

        db.upsertBatch(List.of(new LibraryDb.FileRows(
                List.of(flac(MUSIC.resolve("a.flac"), "TITLE", "A2")), new FileStamp(11, 101))));
        assertEquals(2, db.trackCount());
        assertEquals("A2", byPath().get(0).tags().first("TITLE").orElseThrow());
        db.upsertBatch(List.of());
    }

    @Test
    void readsDoNotWaitForAWriteTransaction() throws Exception {
        add(flac(MUSIC.resolve("a.flac"), "TITLE", "A"), 1, 1);
        // A large upsert in another thread must not make reads fail; both just have to finish.
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                db.upsert(List.of(flac(MUSIC.resolve("w" + i + ".flac"), "TITLE", "W" + i)), new FileStamp(i, i));
            }
        });
        writer.start();
        for (int i = 0; i < 50; i++) {
            assertNotNull(db.albums());
            assertTrue(db.trackCount() >= 1);
        }
        writer.join();
        assertEquals(51, db.trackCount());
    }

    @Test
    void aPathDifferingOnlyInCaseIsTheSameFile() throws SQLException {
        add(flac(MUSIC.resolve("Album\\A.flac"), "TITLE", "first"), 1, 1);

        add(flac(Path.of("e:\\music\\album\\a.FLAC"), "TITLE", "second"), 2, 2); // as Everything spells it

        assertEquals(1, db.trackCount(), "one file, one row");
        assertEquals("second", byPath().get(0).tags().first("TITLE").orElseThrow());
        db.markError(Path.of("E:\\MUSIC\\ALBUM\\a.flac"), new FileStamp(3, 3), "damaged");
        assertEquals(1, db.trackCount());
        db.deleteFiles(List.of(Path.of("E:\\music\\album\\A.FLAC")));
        assertEquals(0, db.trackCount());
        assertEquals(0, rawCount("SELECT COUNT(*) FROM tags"));
    }

    @Test
    void stampsInListsOnlyTheFilesDirectlyInTheFolder() {
        add(flac(MUSIC.resolve("a.flac")), 1, 1);
        add(flac(MUSIC.resolve("album\\b.flac")), 2, 2);
        add(flac(MUSIC.resolve("album\\deep\\c.flac")), 3, 3);
        add(flac(Path.of("E:\\Music2\\d.flac")), 4, 4);

        assertEquals(Set.of(MUSIC.resolve("album\\b.flac")), db.stampsIn(MUSIC.resolve("album")).keySet());
        assertEquals(Set.of(MUSIC.resolve("a.flac")), db.stampsIn(MUSIC).keySet());
        assertEquals(Set.of(MUSIC.resolve("a.flac"), MUSIC.resolve("album\\b.flac"),
                MUSIC.resolve("album\\deep\\c.flac")), db.stamps(MUSIC).keySet());
        assertEquals(Set.of(Path.of("E:\\Music2\\d.flac")), db.stampsIn(Path.of("E:\\Music2")).keySet());
        assertEquals(Set.of(MUSIC.resolve("a.flac")), db.stampsIn(Path.of("e:\\MUSIC\\\\")).keySet());
    }

    @Test
    void aLimitedQueryLoadsTagsForTheRowsItReturns() {
        for (int i = 0; i < 1200; i++) {
            add(flac(MUSIC.resolve(String.format("t%04d.flac", i)), "TITLE", "T" + i, "ARTIST", "X"), i, i);
        }

        List<TrackRow> rows = db.tracks("t.path LIKE 'E:\\Music\\%'", List.of(), "t.id LIMIT 700");

        assertEquals(700, rows.size());
        assertEquals("T0", rows.get(0).tags().first("TITLE").orElseThrow());
        assertEquals("T699", rows.get(699).tags().first("TITLE").orElseThrow());
        assertEquals("X", rows.get(650).tags().first("ARTIST").orElseThrow());
    }

    @Test
    void tracksAndTheirTagsComeFromOneSnapshotWhileTheScannerWrites() throws Exception {
        for (int i = 0; i < 40; i++) {
            add(flac(MUSIC.resolve("t" + i + ".flac"), "TITLE", "v0"), i, i);
        }
        Thread writer = new Thread(() -> {
            for (int round = 1; round <= 30; round++) {
                for (int i = 0; i < 40; i++) {
                    db.upsert(List.of(flac(MUSIC.resolve("t" + i + ".flac"), "TITLE", "v" + round)),
                            new FileStamp(i, round));
                }
            }
        });
        writer.start();
        while (writer.isAlive()) {
            for (TrackRow r : db.tracks(null, List.of(), "t.path")) {
                assertEquals(1, r.tags().all("TITLE").size(), "a row never shows no tag or two generations of them");
            }
        }
        writer.join();
    }

    @Test
    void albumsMatchingNamesEveryAlbumWithAMatchWithoutALimit() {
        for (int i = 0; i < 30; i++) {
            add(flac(MUSIC.resolve("A" + i + ".flac"), "ARTIST", "Aaa", "ALBUM", "Big", "TITLE", "x"), i, i);
        }
        add(flac(MUSIC.resolve("Late\\z.flac"), "ARTIST", "Zzz", "ALBUM", "Late", "TITLE", "x"), 1, 1);
        add(flac(MUSIC.resolve("Folder Name\\y.flac"), "ALBUMARTIST", "Yyy", "TITLE", "x"), 1, 1); // no ALBUM tag
        add(flac(MUSIC.resolve("Nope\\n.flac"), "ARTIST", "Nnn", "ALBUM", "Nope", "TITLE", "other"), 1, 1);

        var names = db.albumsMatching("EXISTS (SELECT 1 FROM tags g WHERE g.track_id = t.id AND g.field = 'TITLE' "
                + "AND g.value = ?)", List.of("x"));

        assertEquals(Set.of(new LibraryDb.AlbumName("Aaa", "Big"), new LibraryDb.AlbumName("Zzz", "Late"),
                new LibraryDb.AlbumName("Yyy", "Folder Name")), names);
    }
}
