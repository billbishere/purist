package purist.library.art;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * Album-grid thumbnails (spec §9). A thumbnail is cut once, on a background executor, from the album's cover art,
 * and stored as {@code <sha1(folder, album, album artist)>.jpg} so the grid never re-reads files from a spinning
 * disk. (Albums that share a folder, such as a downloads folder, therefore get their own pictures.) The
 * most recently used ones are also held in memory.
 *
 * <p>A stored thumbnail is regenerated when the source's modified time is later than the thumbnail file's.
 * Scaling crops the middle square out of the picture, then halves it with bilinear interpolation until it
 * reaches the target size. Art smaller than the target is only cropped: it is never enlarged.
 *
 * <p>Thread-safe. {@link #close} stops the executor; its threads are daemons, so forgetting it does not hold
 * the JVM open.
 */
public final class ThumbnailCache implements AutoCloseable {

    private static final float JPEG_QUALITY = 0.9f;

    private final Path dir;
    private final int px;
    private final Map<AlbumKey, BufferedImage> memory;
    private final Map<AlbumKey, CompletableFuture<Optional<BufferedImage>>> inFlight = new HashMap<>();
    private final ExecutorService executor;

    /**
     * @param dir           where the {@code .jpg} files live; created if missing
     * @param memoryEntries how many thumbnails to keep in memory (least recently used go first)
     * @param px            the thumbnail edge in pixels; production uses 320 so the grid stays sharp on 2x screens
     */
    public ThumbnailCache(Path dir, int memoryEntries, int px) {
        if (memoryEntries < 1 || px < 1) {
            throw new IllegalArgumentException("memoryEntries and px must be positive");
        }
        this.dir = dir;
        this.px = px;
        this.memory = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<AlbumKey, BufferedImage> eldest) {
                return size() > memoryEntries;
            }
        };
        AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "thumbnails-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(dir);
            try (var leftovers = Files.newDirectoryStream(dir, "thumb-*.tmp")) { // from a run that died mid-write
                for (Path stale : leftovers) {
                    Files.deleteIfExists(stale);
                }
            }
            dropThumbnailsOfAnOlderKeyScheme();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot create " + dir, e);
        }
    }

    /** Present once the folder holds only thumbnails named by the current key (folder, album, album artist). */
    private static final String SCHEME_MARKER = "key-scheme-2";

    /**
     * Thumbnails written before the key took the album and album artist were named by the folder alone; no lookup
     * can reach them now, so the first start of this scheme deletes them (they regenerate under the new names).
     */
    private void dropThumbnailsOfAnOlderKeyScheme() throws IOException {
        Path marker = dir.resolve(SCHEME_MARKER);
        if (Files.exists(marker)) {
            return;
        }
        try (var orphans = Files.newDirectoryStream(dir, "*.jpg")) {
            for (Path old : orphans) {
                Files.deleteIfExists(old);
            }
        }
        Files.write(marker, new byte[0]);
    }

    /** The thumbnail from memory, else from disk (without checking whether it is stale); empty if there is none. */
    public Optional<BufferedImage> get(AlbumKey key) {
        synchronized (memory) {
            BufferedImage hit = memory.get(key);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        return readDisk(key);
    }

    /**
     * Makes sure a thumbnail exists, on the background executor, and completes with it. If one is on disk and
     * not older than {@code sourceMtime} it is used and {@code source} is not called. Otherwise {@code source}
     * is asked for the cover bytes; the thumbnail is cut, written and returned. Completes with empty if the
     * source has no usable picture (and no earlier thumbnail exists to fall back on); never completes
     * exceptionally.
     *
     * @param sourceMtime modified time of the file the art comes from, in epoch milliseconds
     */
    public CompletableFuture<Optional<BufferedImage>> load(AlbumKey key, Supplier<Optional<byte[]>> source,
            long sourceMtime) {
        synchronized (inFlight) {
            CompletableFuture<Optional<BufferedImage>> running = inFlight.get(key);
            if (running != null) {
                return running;
            }
            CompletableFuture<Optional<BufferedImage>> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return build(key, source, sourceMtime);
                } catch (RuntimeException | OutOfMemoryError e) {
                    return Optional.<BufferedImage>empty();
                } finally {
                    synchronized (inFlight) {
                        inFlight.remove(key);
                    }
                }
            }, executor);
            inFlight.put(key, f);
            return f;
        }
    }

    /** How many thumbnails are held in memory right now. */
    int memorySize() {
        synchronized (memory) {
            return memory.size();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private Optional<BufferedImage> build(AlbumKey key, Supplier<Optional<byte[]>> source, long sourceMtime) {
        Path file = fileFor(key);
        if (isFresh(file, sourceMtime)) {
            Optional<BufferedImage> cached = get(key);
            if (cached.isPresent()) {
                return cached;
            }
        }
        Optional<BufferedImage> picture = source.get().flatMap(CoverArt::decode);
        if (picture.isEmpty()) {
            // Nothing readable now; an older thumbnail is better than a blank.
            return get(key);
        }
        BufferedImage thumb = scale(picture.get(), px);
        try {
            write(thumb, file);
        } catch (IOException e) {
            // The thumbnail still serves from memory; it is rebuilt next run.
        }
        remember(key, thumb);
        return Optional.of(thumb);
    }

    private static boolean isFresh(Path file, long sourceMtime) {
        try {
            return Files.isRegularFile(file) && sourceMtime <= Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<BufferedImage> readDisk(AlbumKey key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img == null) {
                return Optional.empty();
            }
            remember(key, img);
            return Optional.of(img);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private void remember(AlbumKey key, BufferedImage img) {
        synchronized (memory) {
            memory.put(key, img);
        }
    }

    private Path fileFor(AlbumKey key) {
        return dir.resolve(diskName(key));
    }

    /** The thumbnail's file name: the SHA-1 of folder, album and album artist, so albums in one folder do not collide. */
    static String diskName(AlbumKey key) {
        return sha1Hex(key.folderPath() + '\0' + key.album() + '\0' + key.albumArtist()) + ".jpg";
    }

    /** Lower-case hex SHA-1 of the UTF-8 text. */
    static String sha1Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", e);
        }
    }

    /** Centre-crops to a square, then halves with bilinear interpolation down to {@code target} (never enlarges). */
    static BufferedImage scale(BufferedImage src, int target) {
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage cur = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cur.createGraphics();
        try {
            // JPEG has no alpha: flatten transparent art onto white.
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, side, side);
            g.drawImage(src, -(src.getWidth() - side) / 2, -(src.getHeight() - side) / 2, null);
        } finally {
            g.dispose();
        }
        while (cur.getWidth() > target) {
            int next = Math.max(target, cur.getWidth() / 2);
            BufferedImage half = new BufferedImage(next, next, BufferedImage.TYPE_INT_RGB);
            Graphics2D hg = half.createGraphics();
            try {
                hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                hg.drawImage(cur, 0, 0, next, next, null);
            } finally {
                hg.dispose();
            }
            cur = half;
        }
        return cur;
    }

    private static void write(BufferedImage img, Path file) throws IOException {
        // A temp file of its own: two writers of one name (a rebuild racing a rebuild) must not interleave bytes.
        Path tmp = Files.createTempFile(file.getParent(), "thumb-", ".tmp");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(tmp.toFile())) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e instanceof IOException io ? io : new IOException(e);
        } finally {
            writer.dispose();
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /**
     * The picture shown for an album with no art: a square in the current skin's LCD colour with the album's
     * initials in the skin's segment colour, in the display font.
     */
    public static BufferedImage placeholder(String album, int px) {
        Skin skin = PuristTheme.skin();
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(skin.lcd);
            g.fillRect(0, 0, px, px);
            String text = initials(album);
            Font font = Fonts.forText(text, Fonts.display(Math.max(1f, px * 0.42f)));
            g.setFont(font);
            g.setColor(skin.segment);
            FontMetrics fm = g.getFontMetrics();
            int x = (px - fm.stringWidth(text)) / 2;
            int y = (px - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** The first letter or digit of each of the first two words, upper-cased; {@code "?"} if there are none. */
    public static String initials(String album) {
        StringBuilder sb = new StringBuilder();
        if (album != null) {
            for (String word : album.strip().split("\\s+")) {
                int at = 0;
                while (at < word.length()) {
                    int cp = word.codePointAt(at);
                    if (Character.isLetterOrDigit(cp)) {
                        sb.append(new String(Character.toChars(cp)).toUpperCase(Locale.ROOT));
                        break;
                    }
                    at += Character.charCount(cp);
                }
                if (sb.codePointCount(0, sb.length()) >= 2) {
                    break;
                }
            }
        }
        return sb.isEmpty() ? "?" : sb.toString();
    }
}
