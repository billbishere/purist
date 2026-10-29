package purist.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.playlist.FoobarImport.ImportedPlaylist;

class FoobarImportTest {

    @TempDir
    Path profile;

    /** The binary layout foobar writes: a count, then per entry a length, the URL, and four zero bytes. */
    private static byte[] foldersFile(String... urls) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(le(urls.length));
        for (String url : urls) {
            byte[] b = url.getBytes(StandardCharsets.UTF_8);
            out.write(le(b.length));
            out.write(b);
            out.write(le(0));
        }
        return out.toByteArray();
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }

    private void writeFolders(byte[] bytes) throws IOException {
        Path dir = Files.createDirectories(profile.resolve("library-v2.0"));
        Files.write(dir.resolve("folders"), bytes);
    }

    private void writePlaylists(String index, String guid, String fplite) throws IOException {
        Path dir = Files.createDirectories(profile.resolve("playlists-v2.0"));
        Files.writeString(dir.resolve("index.txt"), index, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("playlist-" + guid + ".fplite"), fplite, StandardCharsets.UTF_8);
    }

    private static final String GUID = "533F6F82-B0A5-4137-9E65-E714571B2E14";

    // ---- folders ----

    @Test
    void foldersParsed() throws Exception {
        writeFolders(foldersFile(
                "file://C:\\Users\\me\\Music\\",
                "file://C:\\Users\\me\\OneDrive\\Music\\",
                "file://E:\\Music\\"));

        assertEquals(List.of(
                Path.of("C:\\Users\\me\\Music"),
                Path.of("C:\\Users\\me\\OneDrive\\Music"),
                Path.of("E:\\Music")), FoobarImport.folders(profile));
    }

    @Test
    void foldersParsedFromTheBytesFoobarReallyWrites() throws Exception {
        // 03000000 1c000000 file://C:\Users\me\Music\ 00000000 25000000 ... as in a real profile.
        writeFolders(foldersFile("file://C:\\Users\\me\\Music\\", "file://C:\\Users\\me\\OneDrive\\Music\\",
                "file://E:\\Music\\"));
        byte[] raw = Files.readAllBytes(profile.resolve("library-v2.0").resolve("folders"));
        assertEquals(3, raw[0]);
        assertEquals("file://C:\\Users\\me\\Music\\".length(), raw[4]);
        assertEquals(3, FoobarImport.folders(profile).size());
    }

    @Test
    void folderPathsKeepNonAsciiAndSpaces() throws Exception {
        writeFolders(foldersFile("file://D:\\M\u00fasica \u30c6\u30b9\u30c8\\Caf\u00e9 Del Mar\\"));
        assertEquals(List.of(Path.of("D:\\M\u00fasica \u30c6\u30b9\u30c8\\Caf\u00e9 Del Mar")),
                FoobarImport.folders(profile));
    }

    @Test
    void aDriveRootKeepsItsBackslash() throws Exception {
        writeFolders(foldersFile("file://G:\\"));
        assertEquals(List.of(Path.of("G:\\")), FoobarImport.folders(profile));
    }

    @Test
    void duplicateFoldersAppearOnce() throws Exception {
        writeFolders(foldersFile("file://E:\\a\\", "file://E:\\a", "file://E:\\b\\"));
        assertEquals(List.of(Path.of("E:\\a"), Path.of("E:\\b")), FoobarImport.folders(profile));
    }

    @Test
    void noFoldersFileMeansNoFolders() {
        assertEquals(List.of(), FoobarImport.folders(profile));
    }

    @Test
    void garbageFoldersFileMeansNoFolders() throws Exception {
        writeFolders(new byte[] {0, 1, 2, 3, 4});
        assertEquals(List.of(), FoobarImport.folders(profile));
    }

    // ---- playlists ----

    @Test
    void fplitePlaylistParsed() throws Exception {
        writePlaylists("\uFEFF" + GUID + ":Default Playlist\r\n", GUID,
                "\uFEFFfile://E:\\Music\\Album\\01. One.flac\nfile://E:\\Music\\Album\\02. Two.flac\n");

        List<ImportedPlaylist> lists = FoobarImport.playlists(profile);

        assertEquals(1, lists.size());
        assertEquals("Default Playlist", lists.get(0).name());
        assertEquals(List.of(
                new TrackRef(Path.of("E:\\Music\\Album\\01. One.flac"), 0),
                new TrackRef(Path.of("E:\\Music\\Album\\02. Two.flac"), 0)), lists.get(0).items());
    }

    @Test
    void crlfLinesAndBlankLinesTolerated() throws Exception {
        writePlaylists(GUID + ":Mix\r\n", GUID, "file://C:\\a.flac\r\n\r\nfile://C:\\b.flac\r\n");
        assertEquals(2, FoobarImport.playlists(profile).get(0).items().size());
    }

    @Test
    void subsongPrefixMapsToTheIsoTrackNumber() throws Exception {
        writePlaylists(GUID + ":Sacd\n", GUID,
                "3+file://E:\\Music\\Ted Nugent\\Analogue Productions - Ted Nugent.iso\n"
                        + "0+file://E:\\Music\\plain.flac\n"
                        + "9+file://E:\\Music\\Ted Nugent\\Analogue Productions - Ted Nugent.ISO\n");

        List<TrackRef> items = FoobarImport.playlists(profile).get(0).items();

        assertEquals(new TrackRef(Path.of("E:\\Music\\Ted Nugent\\Analogue Productions - Ted Nugent.iso"), 3),
                items.get(0));
        assertEquals(new TrackRef(Path.of("E:\\Music\\plain.flac"), 0), items.get(1));
        assertEquals(9, items.get(2).subsong());
        assertEquals("file://E:\\Music\\Ted Nugent\\Analogue Productions - Ted Nugent.iso#3",
                items.get(0).toUri());
    }

    @Test
    void anImageWithoutATrackNumberMapsToTrackOneBecauseSacdTracksStartAtOne() throws Exception {
        writePlaylists(GUID + ":Whole\n", GUID,
                "0+file://E:\\sacd\\a.iso\nfile://E:\\sacd\\b.iso\n2+file://E:\\sacd\\c.iso\n");

        List<TrackRef> items = FoobarImport.playlists(profile).get(0).items();

        assertEquals(new TrackRef(Path.of("E:\\sacd\\a.iso"), 1), items.get(0));
        assertEquals(new TrackRef(Path.of("E:\\sacd\\b.iso"), 1), items.get(1));
        assertEquals(new TrackRef(Path.of("E:\\sacd\\c.iso"), 2), items.get(2));
    }

    @Test
    void tracksBeyondTheImagesTrackCountAreDroppedAndUnknownImagesAreKept() {
        Path a = Path.of("E:\\sacd\\a.iso");
        Path unknown = Path.of("E:\\sacd\\new.iso");
        Path flac = Path.of("E:\\x.flac");
        List<TrackRef> in = List.of(new TrackRef(a, 1), new TrackRef(a, 12), new TrackRef(a, 13),
                new TrackRef(unknown, 40), new TrackRef(flac, 0));

        List<TrackRef> out = FoobarImport.dropMissingTracks(in, p -> p.equals(a) ? 12 : -1);

        assertEquals(List.of(new TrackRef(a, 1), new TrackRef(a, 12), new TrackRef(unknown, 40),
                new TrackRef(flac, 0)), out);
    }

    @Test
    void anImageWhoseStoredRowsGiveNoTrackCountIsTreatedAsUnknownNotEmpty() {
        // An ISO stored as one unsupported / error / cloud-only row (subsong 0) says nothing about its tracks.
        Path unread = Path.of("E:\\sacd\\unread.iso");
        List<TrackRef> in = List.of(new TrackRef(unread, 1), new TrackRef(unread, 7));

        assertEquals(in, FoobarImport.dropMissingTracks(in, p -> 0));
    }

    @Test
    void aPrefixOnANonIsoFileIsDroppedBecauseOnlyIsosHaveSubsongs() throws Exception {
        writePlaylists(GUID + ":X\n", GUID, "2+file://E:\\a.flac\n");
        assertEquals(new TrackRef(Path.of("E:\\a.flac"), 0), FoobarImport.playlists(profile).get(0).items().get(0));
    }

    @Test
    void pathsWithHashAndPlusAreKeptIntact() throws Exception {
        writePlaylists(GUID + ":X\n", GUID, "file://E:\\C++ & #1\\a+b.flac\n");
        assertEquals(new TrackRef(Path.of("E:\\C++ & #1\\a+b.flac"), 0),
                FoobarImport.playlists(profile).get(0).items().get(0));
    }

    @Test
    void nonFileLocationsAndInvalidPathsAreSkipped() throws Exception {
        writePlaylists(GUID + ":X\n", GUID,
                "http://example.com/stream\ncdda://1\nfile://E:\\bad?name.flac\nfile://E:\\ok.flac\n");
        assertEquals(List.of(new TrackRef(Path.of("E:\\ok.flac"), 0)),
                FoobarImport.playlists(profile).get(0).items());
    }

    @Test
    void severalPlaylistsKeepIndexOrderAndColonsInNames() throws Exception {
        String second = "11111111-2222-3333-4444-555555555555";
        Path dir = Files.createDirectories(profile.resolve("playlists-v2.0"));
        Files.writeString(dir.resolve("index.txt"), GUID + ":First\n" + second + ":Rock: the sequel\n");
        Files.writeString(dir.resolve("playlist-" + GUID + ".fplite"), "file://C:\\a.flac\n");
        Files.writeString(dir.resolve("playlist-" + second + ".fplite"), "file://C:\\b.flac\n");

        List<ImportedPlaylist> lists = FoobarImport.playlists(profile);

        assertEquals(List.of("First", "Rock: the sequel"), lists.stream().map(ImportedPlaylist::name).toList());
    }

    @Test
    void aPlaylistWithNoFileIsStillListedAsEmpty() throws Exception {
        Path dir = Files.createDirectories(profile.resolve("playlists-v2.0"));
        Files.writeString(dir.resolve("index.txt"), GUID + ":Ghost\n");
        List<ImportedPlaylist> lists = FoobarImport.playlists(profile);
        assertEquals(1, lists.size());
        assertEquals(List.of(), lists.get(0).items());
    }

    @Test
    void indexAndPlaylistFileGuidsMatchIgnoringCase() throws Exception {
        writePlaylists(GUID.toLowerCase() + ":Lower\n", GUID, "file://C:\\a.flac\n");
        assertEquals(1, FoobarImport.playlists(profile).get(0).items().size());
    }

    @Test
    void noPlaylistsFolderMeansNoPlaylists() {
        assertEquals(List.of(), FoobarImport.playlists(profile));
    }

    @Test
    void importNeverWritesToTheProfile() throws Exception {
        writeFolders(foldersFile("file://E:\\a\\"));
        writePlaylists(GUID + ":X\n", GUID, "file://C:\\a.flac\n");
        Map<Path, FileTime> before = mtimes(profile);
        List<Path> filesBefore = listing(profile);

        FoobarImport.folders(profile);
        FoobarImport.playlists(profile);

        assertEquals(before, mtimes(profile));
        assertEquals(filesBefore, listing(profile));
    }

    @Test
    void defaultProfileIsUnderAppData() {
        Path p = FoobarImport.defaultProfile();
        assertEquals("foobar2000-v2", p.getFileName().toString());
        assertTrue(p.isAbsolute());
    }

    // ---- the user's real profile (read-only) ----

    @Test
    @Tag("library")
    void realProfileReadOnly() throws Exception {
        Path real = FoobarImport.defaultProfile();
        Map<Path, FileTime> before = mtimes(real);
        List<Path> filesBefore = listing(real);

        List<Path> folders = FoobarImport.folders(real);
        List<ImportedPlaylist> playlists = FoobarImport.playlists(real);

        assertEquals(3, folders.size(), "folders: " + folders);
        assertTrue(folders.contains(Path.of("C:\\Users\\me\\Music")));
        assertTrue(folders.contains(Path.of("C:\\Users\\me\\OneDrive\\Music")));
        assertTrue(folders.contains(Path.of("E:\\Music")));
        ImportedPlaylist def = playlists.stream().filter(p -> p.name().equals("Default Playlist")).findFirst()
                .orElseThrow(() -> new AssertionError("no Default Playlist in " + playlists));
        assertFalse(def.items().isEmpty());
        assertNotEquals(0, def.items().size());
        System.out.println("foobar import: folders=" + folders + " playlists="
                + playlists.stream().map(p -> p.name() + "(" + p.items().size() + ")").toList());

        assertEquals(before, mtimes(real), "no file in the foobar profile was touched");
        assertEquals(filesBefore, listing(real), "no file was added to or removed from the foobar profile");
    }

    private static Map<Path, FileTime> mtimes(Path root) throws IOException {
        Map<Path, FileTime> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                out.put(p, Files.getLastModifiedTime(p));
            }
        }
        return out;
    }

    private static List<Path> listing(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return new ArrayList<>(walk.sorted().toList());
        }
    }
}
