package purist.library.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.library.FileStamp;
import purist.library.LibraryDb;
import purist.tags.TagMap;

class SqlBuilderTest {

    private static SqlBuilder.Sql sql(String query) {
        return SqlBuilder.build(QueryParser.parse(query));
    }

    private static long marks(String where) {
        return where.chars().filter(ch -> ch == '?').count();
    }

    @Test
    void emptyMatchesEverything() {
        SqlBuilder.Sql s = sql("");
        assertEquals("1", s.where());
        assertEquals(List.of(), s.params());
    }

    @Test
    void pseudoFieldSamplerate() {
        SqlBuilder.Sql s = sql("samplerate GREATER 96000");
        assertTrue(s.where().contains("t.rate > ?"), s.where());
        assertEquals(List.of(96000), s.params());
    }

    @Test
    void pseudoFieldsMapToColumns() {
        assertTrue(sql("format IS flac").where().contains("t.format = ?"));
        assertTrue(sql("bitdepth LESS 24").where().contains("t.bits < ?"));
        assertTrue(sql("channels IS 2").where().contains("t.channels = ?"));
        assertTrue(sql("kind IS dsd").where().contains("t.kind = ?"));
        assertTrue(sql("path HAS Music").where().contains("t.path LIKE ?"));
    }

    @Test
    void likeEscaping() {
        SqlBuilder.Sql s = sql("title HAS 100%");
        assertEquals(List.of("TITLE", "%100\\%%"), s.params());
        assertTrue(s.where().contains("ESCAPE '\\'"), s.where());
        assertEquals(List.of("TITLE", "%a\\_b\\\\c%"), sql("title HAS a_b\\c").params());
    }

    @Test
    void tagHasTranslation() {
        SqlBuilder.Sql s = sql("artist HAS ted");
        assertEquals("EXISTS(SELECT 1 FROM tags g WHERE g.track_id=t.id AND g.field=? AND g.value LIKE ? ESCAPE '\\')",
                s.where());
        assertEquals(List.of("ARTIST", "%ted%"), s.params());
    }

    @Test
    void tagIsIsCaseInsensitiveEquality() {
        SqlBuilder.Sql s = sql("artist IS \"Ted Nugent\"");
        assertTrue(s.where().contains("g.value = ? COLLATE NOCASE"), s.where());
        assertEquals(List.of("ARTIST", "Ted Nugent"), s.params());
    }

    @Test
    void tagGreaterAndLessCompareAsIntegers() {
        SqlBuilder.Sql s = sql("date GREATER 1999");
        assertTrue(s.where().contains("CAST(g.value AS INTEGER) > ?"), s.where());
        assertEquals(List.of("DATE", 1999), s.params());
        assertTrue(sql("date LESS 2001").where().contains("CAST(g.value AS INTEGER) < ?"));
    }

    @Test
    void tagFieldAliasesFoldLikeTagMap() {
        assertEquals(List.of("DATE", 1999), sql("year GREATER 1999").params());
        assertEquals(List.of("TRACKNUMBER", "%1%"), sql("track HAS 1").params());
    }

    @Test
    void bareTermSearchesAnyTag() {
        SqlBuilder.Sql s = sql("nugent");
        assertEquals("EXISTS(SELECT 1 FROM tags g WHERE g.track_id=t.id AND g.value LIKE ? ESCAPE '\\')", s.where());
        assertEquals(List.of("%nugent%"), s.params());
    }

    @Test
    void booleanStructureAndParameterOrder() {
        SqlBuilder.Sql s = sql("a OR b c");
        assertEquals(List.of("%a%", "%b%", "%c%"), s.params());
        assertEquals(s.params().size(), marks(s.where()));
        assertTrue(s.where().contains(" OR "));
        assertTrue(s.where().contains(" AND "));
        assertTrue(sql("NOT a").where().startsWith("NOT"));
    }

    @Test
    void injectionStaysInParams() {
        String evil = "x'; DROP TABLE tracks; --";
        SqlBuilder.Sql s = sql("title IS \"" + evil + "\" OR \"" + evil + "\"");
        assertFalse(s.where().contains("DROP"), s.where());
        assertFalse(s.where().contains("--"), s.where());
        assertTrue(s.params().contains(evil));
        assertEquals(s.params().size(), marks(s.where()));
        // The field name is bound too, so it cannot inject either.
        SqlBuilder.Sql f = SqlBuilder.build(new QueryNode.Field("x' OR '1'='1", QueryNode.Op.IS, "y"));
        assertFalse(f.where().contains("1'='1"), f.where());
        assertTrue(f.params().contains("X' OR '1'='1"));
    }

    // ---- against a real database ----

    @TempDir
    Path tmp;

    private LibraryDb db;

    @BeforeEach
    void open() throws Exception {
        db = LibraryDb.open(tmp.resolve("library.db"));
        add("E:\\m\\one.flac", "FLAC", StreamFormat.pcm(44100, 16, 2, 441000),
                "ARTIST", "Ted Nugent", "TITLE", "Stranglehold", "DATE", "1975", "TRACKNUMBER", "3/12");
        add("E:\\m\\two.dsf", "DSF", StreamFormat.dsd(2822400, 2, 2822400, BitOrder.MSB_FIRST),
                "ARTIST", "Nina Simone", "TITLE", "100% Pure", "DATE", "1966");
        add("E:\\m\\three.wav", "WAV", StreamFormat.pcm(192000, 24, 2, 1920000),
                "ARTIST", "ted nugent", "TITLE", "Cat Scratch Fever", "DATE", "1977");
    }

    @AfterEach
    void close() {
        db.close();
    }

    private void add(String path, String codec, StreamFormat f, String... kv) {
        TagMap tags = new TagMap();
        for (int i = 0; i < kv.length; i += 2) {
            tags.add(kv[i], kv[i + 1]);
        }
        db.upsert(List.of(new TrackInfo(Path.of(path), 0, codec, f, tags, true, null)), new FileStamp(1, 1));
    }

    private List<String> run(String query) {
        SqlBuilder.Sql s = sql(query);
        return db.tracks(s.where(), s.params(), "t.path").stream()
                .map(r -> r.ref().path().getFileName().toString())
                .collect(Collectors.toList());
    }

    @Test
    void runsAgainstLibraryDb() {
        assertEquals(List.of("two.dsf"), run("format IS dsf"));
        assertEquals(List.of("one.flac", "three.wav", "two.dsf"), run(""));
    }

    @Test
    void databaseSemantics() {
        assertEquals(List.of("one.flac", "three.wav"), run("artist IS \"ted nugent\""), "IS ignores case");
        assertEquals(List.of("one.flac", "three.wav"), run("nugent"), "bare terms search any tag");
        assertEquals(List.of("three.wav", "two.dsf"), run("samplerate GREATER 96000"));
        assertEquals(List.of("one.flac", "two.dsf"), run("bitdepth LESS 24")); // a DSD stream is 1 bit deep
        assertEquals(List.of("two.dsf"), run("kind IS dsd"));
        assertEquals(List.of("two.dsf"), run("title HAS 100%"), "% is literal, not a wildcard");
        assertEquals(List.of("three.wav"), run("date GREATER 1976"));
        assertEquals(List.of("one.flac"), run("tracknumber GREATER 2"), "3/12 compares as 3");
        assertEquals(List.of("one.flac", "three.wav"), run("NOT kind IS dsd"));
        assertEquals(List.of("two.dsf"), run("NOT nugent"));
        assertEquals(List.of("one.flac", "two.dsf"), run("stranglehold OR simone"));
        assertEquals(List.of("three.wav"), run("(stranglehold OR cat) samplerate GREATER 48000"));
        assertEquals(List.of("one.flac", "three.wav", "two.dsf"), run("path HAS e:\\m\\"),
                "a backslash in the query is literal");
        assertEquals(List.of(), run("title IS \"x'; DROP TABLE tracks; --\""));
        assertEquals(3, db.trackCount(), "the table survived");
    }

    @Test
    void notIncludesRowsWhoseColumnIsUnknown() {
        db.upsert(List.of(TrackInfo.cloudOnly(Path.of("E:\\m\\cloud.flac"), "FLAC")), new FileStamp(1, 1));
        assertTrue(run("NOT samplerate GREATER 96000").contains("cloud.flac"));
    }
}
