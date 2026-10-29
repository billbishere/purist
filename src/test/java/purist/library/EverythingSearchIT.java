package purist.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link EverythingSearch} against the user's real, running Everything. Read-only: it only asks Everything for a
 * list, and walks one folder to compare. Run with
 * {@code mvn test "-Dtest.excluded=" -Dgroups=hardware -Dtest=EverythingSearchIT}.
 */
@Tag("hardware")
@Timeout(120)
class EverythingSearchIT {

    private static boolean isFlac(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".flac");
    }

    @Test
    void listsTheFlacFilesOfTheEDrive() {
        assertTrue(EverythingSearch.available(), "Everything must be running with its index loaded");
        Path root = Path.of("E:\\");

        long start = System.nanoTime();
        List<Path> flacs = EverythingSearch.audioFiles(root, Set.of("flac"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("EverythingSearchIT: " + flacs.size() + " FLAC files under E:\\ in " + millis + " ms");

        assertTrue(flacs.size() > 1000, "expected more than 1000 FLAC files on E:\\ but Everything's index has "
                + flacs.size() + (flacs.isEmpty() ? " (is E:\\ included under Everything > Tools > Options > Indexes > NTFS?)" : ""));
        assertTrue(flacs.stream().allMatch(p -> p.startsWith(root)), "every result is on E:\\");
        assertTrue(flacs.stream().allMatch(EverythingSearchIT::isFlac), "every result is a .flac file");
    }

    /** The FLAC list of the drive whose index holds the most. */
    private static List<Path> anyIndexedFlacs() {
        List<Path> best = List.of();
        for (char letter = 'C'; letter <= 'Z'; letter++) {
            Path root = Path.of(letter + ":\\");
            if (Files.isDirectory(root)) {
                List<Path> flacs = EverythingSearch.audioFiles(root, Set.of("flac"));
                if (flacs.size() > best.size()) {
                    best = flacs;
                }
            }
        }
        return best;
    }

    @Test
    void agreesWithAWalkOfOneFolder() throws IOException {
        List<Path> flacs = anyIndexedFlacs();
        assertFalse(flacs.isEmpty(), "no drive's index holds a FLAC file");
        Path sample = flacs.get(0);
        // The album folder's parent: a few folders deep, so the walk is quick.
        Path folder = sample.getParent().getParent() != null && sample.getParent().getParent().getNameCount() > 1
                ? sample.getParent().getParent() : sample.getParent();

        Set<Path> fromEverything = new HashSet<>(EverythingSearch.audioFiles(folder, Set.of("flac")));
        Set<Path> fromWalk = new HashSet<>();
        try (Stream<Path> walk = Files.walk(folder)) {
            walk.filter(Files::isRegularFile).filter(EverythingSearchIT::isFlac).forEach(fromWalk::add);
        }

        System.out.println("EverythingSearchIT: " + folder + " has " + fromWalk.size() + " FLAC files by walk, "
                + fromEverything.size() + " by Everything");
        assertFalse(fromWalk.isEmpty());
        assertEquals(fromWalk, fromEverything);
    }

    @Test
    void severalExtensionsAreTheUnionOfEachAndOnlyFilesAreListed() {
        Path root = Path.of("D:\\");
        assertNotNull(root);

        long start = System.nanoTime();
        List<Path> flac = EverythingSearch.audioFiles(root, Set.of("flac"));
        List<Path> wav = EverythingSearch.audioFiles(root, Set.of("wav"));
        List<Path> both = EverythingSearch.audioFiles(root, Set.of("flac", "wav"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("EverythingSearchIT: D:\\ flac=" + flac.size() + " wav=" + wav.size() + " both=" + both.size()
                + " (three queries in " + millis + " ms)");

        assertFalse(flac.isEmpty());
        assertFalse(wav.isEmpty());
        assertEquals(flac.size() + wav.size(), both.size());
        Set<Path> union = new HashSet<>(flac);
        union.addAll(wav);
        assertEquals(union, new HashSet<>(both));
    }

    @Test
    void aFolderWithNoAudioGivesAnEmptyList() {
        assertTrue(EverythingSearch.audioFiles(Path.of("C:\\Windows\\System32\\drivers\\etc"), Set.of("flac", "dsf")).isEmpty());
    }
}
