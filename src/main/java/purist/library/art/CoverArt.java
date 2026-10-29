package purist.library.art;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import purist.decode.DecoderRegistry;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.win.FileAttributes;

/**
 * Finds a track's cover art (spec §7): the picture embedded in the file first, otherwise the first folder image
 * named {@code cover}, {@code folder} or {@code front} (in that order) with the extension {@code jpg},
 * {@code jpeg} or {@code png} (in that order), matched case-insensitively. Cloud-only files are never opened.
 */
public final class CoverArt {

    private static final String[] NAMES = {"cover", "folder", "front"};
    private static final String[] EXTENSIONS = {"jpg", "jpeg", "png"};

    private CoverArt() {
    }

    /**
     * The raw bytes of the best cover for {@code t}, or empty if there is none. A file that cannot be read
     * (damaged, locked, vanished) counts as having no embedded art, so the folder image is tried next.
     */
    public static Optional<byte[]> find(TrackInfo t, DecoderRegistry reg) {
        Path file = t.path();
        if (!FileAttributes.isCloudOnly(file)) {
            Optional<FormatHandler> handler = reg.handlerFor(file);
            if (handler.isPresent()) {
                try {
                    Optional<byte[]> embedded = handler.get().embeddedArt(file, t.subsong());
                    if (embedded.isPresent() && embedded.get().length > 0) {
                        return embedded;
                    }
                } catch (IOException | RuntimeException e) {
                    // Unreadable embedded art is not fatal: fall through to the folder image.
                }
            }
        }
        return folderImage(file.getParent());
    }

    /** Decodes JPEG, PNG or whatever else ImageIO reads; empty for bytes that are not a picture. */
    public static Optional<BufferedImage> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(bytes)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<byte[]> folderImage(Path folder) {
        if (folder == null) {
            return Optional.empty();
        }
        Map<String, Path> byLowerName = new HashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name != null) {
                    byLowerName.putIfAbsent(name.toString().toLowerCase(Locale.ROOT), entry);
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        for (String name : NAMES) {
            for (String ext : EXTENSIONS) {
                Path hit = byLowerName.get(name + "." + ext);
                if (hit != null && !FileAttributes.isCloudOnly(hit)) {
                    try {
                        return Optional.of(Files.readAllBytes(hit));
                    } catch (IOException e) {
                        // Try the next candidate.
                    }
                }
            }
        }
        return Optional.empty();
    }
}
