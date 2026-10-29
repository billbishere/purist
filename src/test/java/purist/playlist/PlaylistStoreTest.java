package purist.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaylistStoreTest {

    @TempDir
    Path dir;

    private static TrackRef ref(String path) {
        return new TrackRef(Path.of(path), 0);
    }

    private static List<String> names(List<Playlist> all) {
        return all.stream().map(Playlist::name).collect(Collectors.toList());
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }

    private PlaylistStore store() {
        return new PlaylistStore(dir, Duration.ofMillis(150));
    }

    /** Waits (bounded) until {@code check} is true. */
    private static void await(java.util.function.BooleanSupplier check) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!check.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertTrue(check.getAsBoolean(), "timed out");
    }

    // ---- the store ----

    @Test
    void defaultCreatedOnFirstRun() throws Exception {
        try (PlaylistStore s = store()) {
            assertEquals(List.of("Default"), names(s.all()));
        }
        try (PlaylistStore again = store()) {
            assertEquals(List.of("Default"), names(again.all()), "not created a second time");
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(2, files.count(), "one playlist file and the index");
        }
    }

    @Test
    void saveLoadRoundTrip() throws Exception {
        UUID id;
        try (PlaylistStore s = store()) {
            Playlist p = s.create("Caf\u00e9 \u30c6\u30b9\u30c8 \u266b");
            id = p.id();
            p.add(List.of(
                    ref("C:\\Users\\me\\Music\\Track #1.flac"),
                    new TrackRef(Path.of("E:\\Music\\Ted Nugent\\Analogue Productions - Ted Nugent.iso"), 3),
                    ref("D:\\\u00dcber\\\u30de\u30a4\u30af.wav")));
            s.flush();
        }
        try (PlaylistStore s = store()) {
            Playlist p = s.all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertEquals("Caf\u00e9 \u30c6\u30b9\u30c8 \u266b", p.name());
            assertEquals(List.of(
                    ref("C:\\Users\\me\\Music\\Track #1.flac"),
                    new TrackRef(Path.of("E:\\Music\\Ted Nugent\\Analogue Productions - Ted Nugent.iso"), 3),
                    ref("D:\\\u00dcber\\\u30de\u30a4\u30af.wav")), p.items());
        }
    }

    @Test
    void fileFormatIsNameLineThenOneUriPerLineInUtf8WithoutBom() throws Exception {
        UUID id;
        try (PlaylistStore s = store()) {
            Playlist p = s.create("Caf\u00e9");
            id = p.id();
            p.add(List.of(ref("C:\\a#b.flac"), new TrackRef(Path.of("E:\\x.iso"), 2)));
            s.flush();
        }
        byte[] bytes = Files.readAllBytes(dir.resolve(id + ".txt"));
        assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB, "no BOM");
        assertEquals("#name:Caf\u00e9\nfile://C:\\a#b.flac\nfile://E:\\x.iso#2\n",
                new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void indexListsUuidsInTabOrder() throws Exception {
        UUID a;
        UUID b;
        try (PlaylistStore s = store()) {
            a = s.all().get(0).id();
            b = s.create("Second").id();
        }
        assertEquals(List.of(a.toString(), b.toString()),
                Files.readAllLines(dir.resolve("index.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void reorderPersists() throws Exception {
        UUID second;
        UUID third;
        UUID first;
        try (PlaylistStore s = store()) {
            first = s.all().get(0).id();
            second = s.create("Second").id();
            third = s.create("Third").id();
            s.reorder(List.of(third, first, second));
            assertEquals(List.of("Third", "Default", "Second"), names(s.all()));
        }
        try (PlaylistStore s = store()) {
            assertEquals(List.of("Third", "Default", "Second"), names(s.all()));
        }
    }

    @Test
    void reorderIgnoresUnknownIdsAndKeepsOmittedPlaylists() throws Exception {
        try (PlaylistStore s = store()) {
            UUID first = s.all().get(0).id();
            UUID second = s.create("Second").id();
            UUID third = s.create("Third").id();
            s.reorder(List.of(UUID.randomUUID(), third));
            assertEquals(List.of("Third", "Default", "Second"), names(s.all()));
            assertEquals(3, s.all().size());
            assertNotEquals(first, second);
        }
    }

    @Test
    void autosaveDebounced() throws Exception {
        try (PlaylistStore s = store()) {
            Playlist p = s.all().get(0);
            int before = s.writeCount();
            p.add(List.of(ref("C:\\one.flac")));
            s.changed(p);
            p.add(List.of(ref("C:\\two.flac")));
            s.changed(p);
            assertEquals(before, s.writeCount(), "nothing is written the moment a change is made");
            await(() -> s.writeCount() > before);
            Thread.sleep(400); // longer than the delay: no second write follows
            assertEquals(before + 1, s.writeCount(), "two changes inside the delay make one write");
            assertEquals(2, Files.readAllLines(dir.resolve(p.id() + ".txt")).size() - 1);
        }
    }

    @Test
    void mutationsSaveWithoutAnExplicitChangedCall() throws Exception {
        UUID id;
        try (PlaylistStore s = store()) {
            Playlist p = s.all().get(0);
            id = p.id();
            p.add(List.of(ref("C:\\auto.flac")));
            p.rename("Renamed");
            await(() -> s.writeCount() > 0 && read(dir.resolve(id + ".txt")).contains("auto.flac"));
        }
        try (PlaylistStore s = store()) {
            assertEquals("Renamed", s.all().get(0).name());
            assertEquals(List.of(ref("C:\\auto.flac")), s.all().get(0).items());
        }
    }

    @Test
    void flushWritesPendingChangesNow() throws Exception {
        try (PlaylistStore s = new PlaylistStore(dir, Duration.ofSeconds(30))) {
            Playlist p = s.all().get(0);
            p.add(List.of(ref("C:\\later.flac")));
            s.flush();
            assertTrue(Files.readString(dir.resolve(p.id() + ".txt")).contains("later.flac"));
        }
    }

    @Test
    void closeFlushes() throws Exception {
        UUID id;
        try (PlaylistStore s = new PlaylistStore(dir, Duration.ofSeconds(30))) {
            Playlist p = s.all().get(0);
            id = p.id();
            p.add(List.of(ref("C:\\onclose.flac")));
        }
        assertTrue(Files.readString(dir.resolve(id + ".txt")).contains("onclose.flac"));
    }

    @Test
    void deleteRemovesFileAndIndexEntryAndIsNotResurrectedByAPendingSave() throws Exception {
        try (PlaylistStore s = new PlaylistStore(dir, Duration.ofMillis(100))) {
            Playlist doomed = s.create("Doomed");
            doomed.add(List.of(ref("C:\\x.flac")));
            s.delete(doomed.id());
            Thread.sleep(400);
            assertFalse(Files.exists(dir.resolve(doomed.id() + ".txt")));
            assertEquals(List.of("Default"), names(s.all()));
            assertFalse(Files.readString(dir.resolve("index.txt")).contains(doomed.id().toString()));
        }
    }

    @Test
    void loadToleratesBomCrlfAndStrayFiles() throws Exception {
        UUID id = UUID.randomUUID();
        Files.writeString(dir.resolve(id + ".txt"),
                "\uFEFF#name:Hand made\r\nfile://C:\\a.flac\r\n\r\nnot a uri\r\nfile://E:\\y.iso#4\r\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("index.txt"), id + "\r\nnot-a-uuid\r\n" + UUID.randomUUID() + "\r\n");
        Files.writeString(dir.resolve("notes.txt"), "ignored");
        try (PlaylistStore s = store()) {
            assertEquals(List.of("Hand made"), names(s.all()));
            assertEquals(List.of(ref("C:\\a.flac"), new TrackRef(Path.of("E:\\y.iso"), 4)), s.all().get(0).items());
        }
    }

    @Test
    void playlistsNotInTheIndexAreAppendedAndAFreshIndexIsWritten() throws Exception {
        UUID listed = UUID.randomUUID();
        UUID stray = UUID.randomUUID();
        Files.writeString(dir.resolve(listed + ".txt"), "#name:Listed\n");
        Files.writeString(dir.resolve(stray + ".txt"), "#name:Stray\n");
        Files.writeString(dir.resolve("index.txt"), listed + "\n");
        try (PlaylistStore s = store()) {
            assertEquals(List.of("Listed", "Stray"), names(s.all()));
        }
    }

    @Test
    void aBrokenFileDoesNotStopTheRest() throws Exception {
        UUID good = UUID.randomUUID();
        Files.writeString(dir.resolve(good + ".txt"), "#name:Good\nfile://C:\\a.flac\n");
        Files.write(dir.resolve(UUID.randomUUID() + ".txt"), new byte[] {(byte) 0xC3, (byte) 0x28, 0, (byte) 0xFF});
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(PlaylistStore.class.getName());
        java.util.logging.Level old = log.getLevel();
        log.setLevel(java.util.logging.Level.OFF); // the store warns about the unreadable file; keep the build quiet
        try (PlaylistStore s = store()) {
            assertTrue(names(s.all()).contains("Good"));
        } finally {
            log.setLevel(old);
        }
    }

    @Test
    void noTempFilesLeftBehind() throws Exception {
        try (PlaylistStore s = store()) {
            s.all().get(0).add(List.of(ref("C:\\a.flac")));
            s.flush();
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void allIsASnapshot() throws Exception {
        try (PlaylistStore s = store()) {
            List<Playlist> before = s.all();
            s.create("More");
            assertEquals(1, before.size());
            assertThrows(UnsupportedOperationException.class, () -> before.add(null));
        }
    }

    @Test
    void newlinesInANameCannotBreakTheFile() throws Exception {
        UUID id;
        try (PlaylistStore s = store()) {
            Playlist p = s.create("two\nlines\r\nhere");
            id = p.id();
            p.add(List.of(ref("C:\\a.flac")));
        }
        try (PlaylistStore s = store()) {
            Playlist p = s.all().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertEquals("two lines here", p.name());
            assertEquals(1, p.items().size());
        }
    }

    private static void quietly(Runnable r) {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(PlaylistStore.class.getName());
        java.util.logging.Level old = log.getLevel();
        log.setLevel(java.util.logging.Level.OFF); // the store warns about damaged files; keep the build quiet
        try {
            r.run();
        } finally {
            log.setLevel(old);
        }
    }

    @Test
    void aZeroFilledEmptyOrGarbledFileIsReportedAndLeftAloneNotLoadedAsAnEmptyPlaylist() throws Exception {
        UUID good = UUID.randomUUID();
        UUID zeros = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID garbled = UUID.randomUUID();
        Files.writeString(dir.resolve(good + ".txt"), "#name:Good\nfile://C:\\a.flac\n");
        byte[] nul = new byte[64]; // what NTFS leaves after a power cut in the middle of a write
        Files.write(dir.resolve(zeros + ".txt"), nul);
        Files.write(dir.resolve(empty + ".txt"), new byte[0]);
        Files.writeString(dir.resolve(garbled + ".txt"), "this is not a playlist\nat all\n");

        quietly(() -> {
            try (PlaylistStore s = store()) {
                assertEquals(List.of("Good"), names(s.all()));
                List<String> problems = s.loadProblems();
                assertEquals(3, problems.size(), problems.toString());
                assertTrue(problems.stream().anyMatch(m -> m.contains(zeros.toString())));
                assertTrue(problems.stream().anyMatch(m -> m.contains(empty.toString())));
                assertTrue(problems.stream().anyMatch(m -> m.contains(garbled.toString())));
            }
        });
        assertEquals(64, Files.size(dir.resolve(zeros + ".txt")), "a damaged file is never overwritten");
        assertEquals(0, Files.size(dir.resolve(empty + ".txt")));
    }

    @Test
    void anEmptyPlaylistWithANameIsNotDamaged() throws Exception {
        UUID id = UUID.randomUUID();
        Files.writeString(dir.resolve(id + ".txt"), "#name:Nothing yet\n");
        try (PlaylistStore s = store()) {
            assertEquals(List.of("Nothing yet"), names(s.all()));
            assertEquals(List.of(), s.loadProblems());
        }
    }

    @Test
    void aSavedPlaylistIsCompleteOnDiskAfterFlush() throws Exception {
        try (PlaylistStore s = store()) {
            Playlist p = s.all().get(0);
            List<TrackRef> many = new java.util.ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                many.add(ref("C:\\music\\track" + i + ".flac"));
            }
            p.add(many);
            s.flush();
            assertEquals(2001, Files.readAllLines(dir.resolve(p.id() + ".txt")).size());
        }
    }
}
