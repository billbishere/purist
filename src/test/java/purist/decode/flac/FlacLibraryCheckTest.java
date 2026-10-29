package purist.decode.flac;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import purist.decode.flac.FlacDamageException.Kind;

/**
 * Decodes every FLAC in the user's real library and compares it with its STREAMINFO MD5 (spec section 11, check 4).
 * It only reads. Run it with {@code mvn test "-Dtest.excluded=" -Dgroups=library -Dtest=FlacLibraryCheckTest}; the
 * roots default to the three library folders and can be replaced with {@code -Dpurist.libraryRoots=a;b;c}.
 *
 * <p>The library holds files that were damaged on disk (incomplete downloads, bit errors), so the criterion is not
 * "every file is OK" but (see {@link #gateFailures}): no MISMATCH, every ERROR a damage the decoder proved from the
 * bytes (a {@link Kind}), and at least {@code -Dpurist.libraryMinOk} files (default {@value #DEFAULT_MIN_OK}) OK, so
 * that a decoder that wrongly rejects good files cannot pass as "damage".
 */
@Tag("library")
class FlacLibraryCheckTest {

    private static final String DEFAULT_ROOTS = "E:\\Music;C:\\Users\\me\\Music;C:\\Users\\me\\OneDrive\\Music";
    static final int DEFAULT_MIN_OK = 1185;

    @Test
    void everyFlacDecodesToItsMd5() {
        List<Path> roots = Arrays.stream(System.getProperty("purist.libraryRoots", DEFAULT_ROOTS).split(";"))
                .filter(s -> !s.isBlank())
                .map(Path::of)
                .toList();
        int minOk = Integer.getInteger("purist.libraryMinOk", DEFAULT_MIN_OK);

        FlacVerify.Summary s = FlacVerify.scan(roots, System.out); // lists every non-OK file with its kind and detail

        Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
        for (FlacVerify.Result r : s.problems()) {
            if (r.damage() != null) {
                kinds.merge(r.damage(), 1, Integer::sum);
            }
        }
        System.out.println(s.line() + "; proven damage by kind: " + kinds + "; minimum OK: " + minOk);

        List<String> failures = gateFailures(s, minOk);
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /**
     * Why {@code s} does not pass the library gate; empty if it does. A missing root or any ERROR that is not a
     * damage the decoder proved (a {@code RuntimeException}, an I/O fault, a decoder rejection that is not provable
     * damage) fails it, as does a MISMATCH, and so does scanning too little: no checked file at all, or fewer than
     * {@code minOk} OK.
     */
    static List<String> gateFailures(FlacVerify.Summary s, int minOk) {
        List<String> failures = new ArrayList<>();
        if (s.mismatch() > 0) {
            failures.add(s.mismatch() + " MISMATCH: " + describe(s, FlacVerify.Status.MISMATCH));
        }
        List<FlacVerify.Result> unexplained = s.problems().stream()
                .filter(r -> r.status() == FlacVerify.Status.ERROR && r.damage() == null)
                .toList();
        if (!unexplained.isEmpty()) {
            failures.add(unexplained.size() + " ERROR that is not proven damage: " + unexplained);
        }
        if (s.ok() + s.mismatch() + s.noMd5() == 0) {
            failures.add("no file was checked: " + s.line());
        }
        if (s.ok() < minOk) {
            failures.add("only " + s.ok() + " OK, at least " + minOk + " expected: " + s.line());
        }
        return failures;
    }

    private static String describe(FlacVerify.Summary s, FlacVerify.Status status) {
        return s.problems().stream().filter(r -> r.status() == status).toList().toString();
    }
}
