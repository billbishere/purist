package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.library.FileStamp;
import purist.library.LibraryDb;
import purist.library.TrackRow;
import purist.library.query.SqlBuilder.Sql;
import purist.tags.TagMap;

class FolderQueryTest {

    @TempDir
    Path tmp;

    private LibraryDb db;

    @BeforeEach
    void open() throws SQLException {
        db = LibraryDb.open(tmp.resolve("library.db"));
    }

    @AfterEach
    void close() {
        db.close();
    }

    private void add(String path) {
        db.upsert(List.of(new TrackInfo(Path.of(path), 0, "FLAC", StreamFormat.pcm(44100, 16, 2, 100), new TagMap(),
                true, null)), new FileStamp(1, 1));
    }

    private List<String> in(Path folder) {
        Sql sql = FolderQuery.directTracks(folder);
        return db.tracks(sql.where(), sql.params(), FolderQuery.ORDER).stream()
                .map(r -> r.ref().path().toString()).toList();
    }

    @Test
    void onlyTheFilesDirectlyInTheFolderCount() {
        add("E:\\Music\\A\\1.flac");
        add("E:\\Music\\A\\2.flac");
        add("E:\\Music\\A\\Disc 2\\3.flac");
        add("E:\\Music\\AB\\4.flac");
        add("E:\\Music\\5.flac");
        assertEquals(List.of("E:\\Music\\A\\1.flac", "E:\\Music\\A\\2.flac"), in(Path.of("E:\\Music\\A")));
        assertEquals(List.of("E:\\Music\\5.flac"), in(Path.of("E:\\Music")));
    }

    @Test
    void aDriveRootHasItsOwnFilesToo() {
        add("E:\\root.flac");
        add("E:\\Music\\a.flac");
        assertEquals(List.of("E:\\root.flac"), in(Path.of("E:\\")));
    }

    @Test
    void wildcardsAndEscapesInFolderNamesAreLiteral() {
        add("E:\\Music\\100%_Real\\1.flac");
        add("E:\\Music\\100xxReal\\2.flac");
        add("E:\\Music\\a^b\\3.flac");
        add("E:\\Music\\axb\\4.flac");
        assertEquals(List.of("E:\\Music\\100%_Real\\1.flac"), in(Path.of("E:\\Music\\100%_Real")));
        assertEquals(List.of("E:\\Music\\a^b\\3.flac"), in(Path.of("E:\\Music\\a^b")));
    }

    @Test
    void theFolderNameMatchesIgnoringCase() {
        add("E:\\Music\\Jazz\\1.flac");
        assertEquals(List.of("E:\\Music\\Jazz\\1.flac"), in(Path.of("e:\\music\\JAZZ")));
    }

    @Test
    void filesComeInNameOrderIgnoringCase() {
        add("E:\\M\\b.flac");
        add("E:\\M\\A.flac");
        add("E:\\M\\c.flac");
        List<TrackRow> rows = db.tracks(FolderQuery.directTracks(Path.of("E:\\M")).where(),
                FolderQuery.directTracks(Path.of("E:\\M")).params(), FolderQuery.ORDER);
        assertEquals(List.of("A.flac", "b.flac", "c.flac"),
                rows.stream().map(r -> r.ref().path().getFileName().toString()).toList());
    }
}
