package purist.library.art;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;

class CoverArtTest {

    static byte[] image(String format, int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A handler for ".xyz" files whose embedded art is whatever the test says. */
    static DecoderRegistry registryWithArt(byte[] art) {
        DecoderRegistry reg = new DecoderRegistry();
        reg.register(new FormatHandler() {
            @Override
            public List<String> extensions() {
                return List.of("xyz");
            }

            @Override
            public List<TrackInfo> probe(Path p) {
                return List.of();
            }

            @Override
            public Decoder open(Path p, int subsong) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<byte[]> embeddedArt(Path p, int subsong) {
                return Optional.ofNullable(art);
            }
        });
        return reg;
    }

    static TrackInfo track(Path file) {
        return TrackInfo.unsupported(file, "XYZ", "test");
    }

    @Test
    void folderCoverFoundCaseInsensitive(@TempDir Path dir) throws IOException {
        byte[] jpg = image("jpg", 8, 8, 0xFF0000);
        Files.write(dir.resolve("Cover.JPG"), jpg);
        Path song = Files.write(dir.resolve("a.xyz"), new byte[] {1});

        Optional<byte[]> art = CoverArt.find(track(song), registryWithArt(null));

        assertTrue(art.isPresent());
        assertArrayEquals(jpg, art.get());
    }

    @Test
    void folderNamesAndExtensionsFollowPriorityOrder(@TempDir Path dir) throws IOException {
        Path song = Files.write(dir.resolve("a.xyz"), new byte[] {1});
        byte[] frontJpg = image("jpg", 3, 3, 0);
        byte[] frontPng = image("png", 4, 4, 0x00FF00);
        byte[] folderPng = image("png", 5, 5, 0x0000FF);
        byte[] folderJpg = image("jpg", 6, 6, 0x0000FF);
        Files.write(dir.resolve("front.jpg"), frontJpg);
        Files.write(dir.resolve("Folder.png"), folderPng);
        Files.write(dir.resolve("folder.jpeg"), folderJpg);
        DecoderRegistry reg = registryWithArt(null);

        // "folder" beats "front", and within "folder" jpg < jpeg < png.
        assertArrayEquals(folderJpg, CoverArt.find(track(song), reg).orElseThrow());
        Files.delete(dir.resolve("folder.jpeg"));
        assertArrayEquals(folderPng, CoverArt.find(track(song), reg).orElseThrow());
        Files.delete(dir.resolve("Folder.png"));
        Files.write(dir.resolve("front.png"), frontPng);
        // front.jpg beats front.png
        assertArrayEquals(frontJpg, CoverArt.find(track(song), reg).orElseThrow());
    }

    @Test
    void embeddedPreferredOverFolder(@TempDir Path dir) throws IOException {
        byte[] embedded = image("png", 7, 7, 0x123456);
        Files.write(dir.resolve("cover.jpg"), image("jpg", 8, 8, 0xFF0000));
        Path song = Files.write(dir.resolve("a.xyz"), new byte[] {1});

        Optional<byte[]> art = CoverArt.find(track(song), registryWithArt(embedded));

        assertArrayEquals(embedded, art.orElseThrow());
    }

    @Test
    void noArtAnywhereIsEmpty(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("notes.txt"), new byte[] {1});
        Path song = Files.write(dir.resolve("a.xyz"), new byte[] {1});

        assertTrue(CoverArt.find(track(song), registryWithArt(null)).isEmpty());
    }

    @Test
    void decodeReturnsImageOrEmpty() throws IOException {
        BufferedImage img = CoverArt.decode(image("png", 9, 5, 0x00FF00)).orElseThrow();
        assertEquals(9, img.getWidth());
        assertEquals(5, img.getHeight());
        assertFalse(CoverArt.decode(new byte[] {1, 2, 3}).isPresent());
        assertFalse(CoverArt.decode(new byte[0]).isPresent());
    }
}
