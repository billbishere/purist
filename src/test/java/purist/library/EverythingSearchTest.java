package purist.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parts of {@link EverythingSearch} that need no running Everything. */
class EverythingSearchTest {

    @TempDir
    Path tmp;

    @Test
    void queryNamesTheRootAndEveryExtensionInOrder() {
        Set<String> exts = new LinkedHashSet<>(java.util.List.of("wav", "flac", "iso"));

        assertEquals("\"E:\\\" file: ext:flac;iso;wav", EverythingSearch.query(Path.of("E:\\"), exts));
    }

    @Test
    void aFolderRootGetsATrailingSeparatorSoASiblingIsNotMatched() {
        assertEquals("\"D:\\My Music\\\" file: ext:flac",
                EverythingSearch.query(Path.of("D:\\My Music"), Set.of("flac")));
    }

    @Test
    void theBundledDllIsExtractedOnceAndLeftAlone() throws Exception {
        Path lib = tmp.resolve("lib");

        Path first = EverythingSearch.extract(lib);

        assertEquals(lib.resolve("Everything64.dll"), first);
        byte[] expected;
        try (InputStream in = EverythingSearch.class.getResourceAsStream("/native/Everything64.dll")) {
            assertNotNull(in, "Everything64.dll is not bundled");
            expected = in.readAllBytes();
        }
        assertTrue(expected.length > 10_000);
        assertArrayEquals(expected, Files.readAllBytes(first));

        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L));
        Path second = EverythingSearch.extract(lib);
        assertEquals(first, second);
        assertEquals(1_000_000_000_000L, Files.getLastModifiedTime(second).toMillis(), "an intact copy is not rewritten");
    }

    @Test
    void aDamagedCopyIsReplaced() throws Exception {
        Path lib = tmp.resolve("lib");
        Path dll = EverythingSearch.extract(lib);
        Files.write(dll, new byte[] {1, 2, 3});

        EverythingSearch.extract(lib);

        assertTrue(Files.size(dll) > 10_000);
    }

    @Test
    void theLicenceIsBundledBesideTheDll() throws Exception {
        try (InputStream in = EverythingSearch.class.getResourceAsStream("/native/Everything-SDK-LICENSE.txt")) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes()).contains("Permission is hereby granted"));
        }
    }

    @Test
    void aCallThatNeverReturnsTimesOutInsteadOfBlockingTheCaller() {
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        long start = System.nanoTime();

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> EverythingSearch.onWorker(() -> {
                    never.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    return true;
                }, 300));

        assertTrue(e.getMessage().contains("did not answer"), e.getMessage());
        assertTrue((System.nanoTime() - start) < 5_000_000_000L, "returned long before the call would have");
        never.countDown();
    }
}
