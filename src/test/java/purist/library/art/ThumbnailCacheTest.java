package purist.library.art;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailCacheTest {

    private ThumbnailCache cache;

    @AfterEach
    void close() {
        if (cache != null) {
            cache.close();
        }
    }

    private static AlbumKey key(Path folder) {
        return new AlbumKey(folder.toString(), "Album", "Artist");
    }

    private static Path onlyThumb(Path thumbs) throws IOException {
        try (var s = Files.list(thumbs)) {
            return s.filter(p -> p.toString().endsWith(".jpg")).findFirst().orElseThrow();
        }
    }

    private static boolean isBlue(int rgb) {
        return (rgb & 0xFF) > 0xB0 && ((rgb >> 16) & 0xFF) < 0x50;
    }

    @Test
    void thumbnailWrittenAndReused(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 64);
        AlbumKey k = key(tmp.resolve("album"));
        AtomicInteger calls = new AtomicInteger();
        byte[] source = CoverArtTest.image("png", 300, 200, 0xCC3300);

        BufferedImage first = cache.load(k, () -> {
            calls.incrementAndGet();
            return Optional.of(source);
        }, 0).get().orElseThrow();
        BufferedImage second = cache.load(k, () -> {
            calls.incrementAndGet();
            return Optional.of(source);
        }, 0).get().orElseThrow();

        assertEquals(1, calls.get());
        assertEquals(64, first.getWidth());
        assertEquals(64, first.getHeight());
        assertEquals(64, second.getWidth());
        assertTrue(Files.isRegularFile(onlyThumb(thumbs)));
        assertTrue(cache.get(k).isPresent());

        // A fresh cache over the same directory reads the thumbnail from disk, not from the source.
        try (ThumbnailCache again = new ThumbnailCache(thumbs, 10, 64)) {
            assertTrue(again.get(k).isPresent());
            assertTrue(again.load(k, () -> {
                calls.incrementAndGet();
                return Optional.empty();
            }, 0).get().isPresent());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void diskFileIsSha1OfFolderAlbumAndArtist(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 32);
        AlbumKey k = new AlbumKey("C:\\Music\\Some Album", "Some Album", "Someone");

        cache.load(k, () -> Optional.of(CoverArtTest.image("jpg", 40, 40, 0x336699)), 0).get();

        // Known SHA-1 vector: sha1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ThumbnailCache.sha1Hex("abc"));
        assertTrue(Files.isRegularFile(thumbs.resolve(
                ThumbnailCache.sha1Hex("C:\\Music\\Some Album\0Some Album\0Someone") + ".jpg")));
    }

    @Test
    void albumsSharingAFolderKeepTheirOwnPictures(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 32);
        String folder = tmp.resolve("downloads").toString();
        AlbumKey red = new AlbumKey(folder, "Red", "A");
        AlbumKey blue = new AlbumKey(folder, "Blue", "A");

        cache.load(red, () -> Optional.of(CoverArtTest.image("png", 100, 100, 0xFF0000)), 0).get();
        cache.load(blue, () -> Optional.of(CoverArtTest.image("png", 100, 100, 0x0000FF)), 0).get();

        try (ThumbnailCache again = new ThumbnailCache(thumbs, 10, 32)) { // from disk, not from memory
            assertTrue(isBlue(again.get(blue).orElseThrow().getRGB(16, 16)));
            assertTrue(!isBlue(again.get(red).orElseThrow().getRGB(16, 16)));
        }
        try (var s = Files.list(thumbs)) {
            assertEquals(2, s.filter(p -> p.toString().endsWith(".jpg")).count());
        }
    }

    @Test
    void writesLeaveNoTempFilesAndAnOldOneIsCleanedUp(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        Files.writeString(thumbs.resolve("thumb-123.tmp"), "half a jpeg");
        cache = new ThumbnailCache(thumbs, 10, 32);
        for (int i = 0; i < 6; i++) {
            cache.load(new AlbumKey(tmp.toString(), "Album " + i, "A"),
                    () -> Optional.of(CoverArtTest.image("png", 100, 100, 0x00FF00)), 0).get();
        }
        try (var s = Files.list(thumbs)) {
            assertEquals(0, s.filter(p -> p.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void thumbnailsNamedByAnOlderKeySchemeAreDeletedOnceAndNewOnesAreKept(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        Path orphan = Files.writeString(thumbs.resolve(ThumbnailCache.sha1Hex("C:\\Music\\Old") + ".jpg"), "old key");
        cache = new ThumbnailCache(thumbs, 10, 32);
        assertFalse(Files.exists(orphan), "the folder-only thumbnail can never be hit again");

        AlbumKey k = new AlbumKey(tmp.toString(), "Album", "Artist");
        cache.load(k, () -> Optional.of(CoverArtTest.image("png", 100, 100, 0x00FF00)), 0).get();
        cache.close();
        try (ThumbnailCache again = new ThumbnailCache(thumbs, 10, 32)) {
            assertTrue(again.get(k).isPresent(), "a later start keeps the thumbnails of the current scheme");
        }
    }

    @Test
    void staleThumbnailRegenerated(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 32);
        AlbumKey k = key(tmp.resolve("album"));
        AtomicInteger calls = new AtomicInteger();
        cache.load(k, () -> {
            calls.incrementAndGet();
            return Optional.of(CoverArtTest.image("png", 100, 100, 0xFF0000));
        }, 0).get();
        long thumbMtime = Files.getLastModifiedTime(onlyThumb(thumbs)).toMillis();

        // Source not newer than the thumbnail: reused.
        cache.load(k, () -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, thumbMtime).get();
        assertEquals(1, calls.get());

        // Source newer: regenerated, with the new picture.
        BufferedImage fresh = cache.load(k, () -> {
            calls.incrementAndGet();
            return Optional.of(CoverArtTest.image("png", 100, 100, 0x0000FF));
        }, thumbMtime + 5_000).get().orElseThrow();

        assertEquals(2, calls.get());
        assertTrue(isBlue(fresh.getRGB(16, 16)), "expected blue, got " + Integer.toHexString(fresh.getRGB(16, 16)));
        try (ThumbnailCache again = new ThumbnailCache(thumbs, 10, 32)) {
            assertTrue(isBlue(again.get(k).orElseThrow().getRGB(16, 16)));
        }
    }

    @Test
    void staleByOldFileMtime(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 32);
        AlbumKey k = key(tmp.resolve("album"));
        AtomicInteger calls = new AtomicInteger();
        cache.load(k, () -> {
            calls.incrementAndGet();
            return Optional.of(CoverArtTest.image("png", 50, 50, 0x00FF00));
        }, 0).get();
        Files.setLastModifiedTime(onlyThumb(thumbs), FileTime.fromMillis(1_000_000L));

        cache.load(k, () -> {
            calls.incrementAndGet();
            return Optional.of(CoverArtTest.image("png", 50, 50, 0x00FF00));
        }, 2_000_000L).get();

        assertEquals(2, calls.get());
    }

    @Test
    void noSourceGivesEmptyAndWritesNothing(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 32);
        AlbumKey k = key(tmp.resolve("album"));

        assertFalse(cache.load(k, Optional::empty, 0).get().isPresent());
        assertFalse(cache.load(k, () -> Optional.of(new byte[] {1, 2, 3}), 0).get().isPresent());
        assertFalse(cache.get(k).isPresent());
        try (var s = Files.list(thumbs)) { // the key-scheme marker is the only file a cache writes by itself
            assertEquals(0, s.filter(p -> !p.getFileName().toString().equals("key-scheme-2")).count());
        }
    }

    @Test
    void sourceThatThrowsGivesEmpty(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 32);

        assertFalse(cache.load(key(tmp.resolve("a")), () -> {
            throw new IllegalStateException("disk gone");
        }, 0).get().isPresent());
    }

    @Test
    void scalingCropsToSquareFromTheCentre(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 40);
        // 400x200: left and right quarters red, the centre 200x200 blue. A centre crop is all blue.
        BufferedImage wide = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 400; x++) {
                wide.setRGB(x, y, x < 100 || x >= 300 ? 0xFF0000 : 0x0000FF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(wide, "png", out);

        BufferedImage t = cache.load(key(tmp.resolve("a")), () -> Optional.of(out.toByteArray()), 0).get().orElseThrow();

        assertEquals(40, t.getWidth());
        assertEquals(40, t.getHeight());
        for (int x : new int[] {2, 20, 37}) {
            assertTrue(isBlue(t.getRGB(x, 20)), "x=" + x + " rgb=" + Integer.toHexString(t.getRGB(x, 20)));
        }
    }

    @Test
    void smallArtIsNotUpscaled(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 10, 64);

        BufferedImage t = cache.load(key(tmp.resolve("a")),
                () -> Optional.of(CoverArtTest.image("png", 30, 20, 0x888888)), 0).get().orElseThrow();

        assertEquals(20, t.getWidth());
        assertEquals(20, t.getHeight());
    }

    @Test
    void memoryIsBoundedLru(@TempDir Path tmp) throws Exception {
        Path thumbs = Files.createDirectories(tmp.resolve("thumbs"));
        cache = new ThumbnailCache(thumbs, 2, 16);
        for (String name : new String[] {"a", "b", "c"}) {
            cache.load(key(tmp.resolve(name)), () -> Optional.of(CoverArtTest.image("png", 20, 20, 0x777777)), 0).get();
        }

        assertEquals(2, cache.memorySize());
    }

    @Test
    void placeholderSize() {
        BufferedImage p = ThumbnailCache.placeholder("The Dark Side", 320);
        assertEquals(320, p.getWidth());
        assertEquals(320, p.getHeight());
        assertEquals(64, ThumbnailCache.placeholder("", 64).getWidth());
    }

    @Test
    void initialsAreFirstLettersOfFirstTwoWords() {
        assertEquals("TD", ThumbnailCache.initials("the dark side"));
        assertEquals("A", ThumbnailCache.initials("Abbey"));
        assertEquals("1", ThumbnailCache.initials("1984"));
        assertEquals("?", ThumbnailCache.initials(""));
        assertEquals("?", ThumbnailCache.initials(null));
        assertEquals("?", ThumbnailCache.initials("--- ***"));
    }
}
