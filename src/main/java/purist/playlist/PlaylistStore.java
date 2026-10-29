package purist.playlist;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import purist.app.AppLog;

/**
 * The playlists on disk (spec §8). Each is {@code <uuid>.txt}: a first line {@code #name:<name>}, then one
 * {@link TrackRef#toUri()} per line, UTF-8 without a BOM, written atomically (a temp file, then a rename) so a
 * crash never leaves half a playlist. {@code index.txt} lists the UUIDs in tab order. On the first run, when
 * there are no playlists, one called {@code "Default"} is created.
 *
 * <p>A change to a playlist (through the playlist itself or {@link #changed}) is saved by a background thread
 * {@value #AUTOSAVE_SECONDS} second after the first change that is not yet saved, so a burst of edits makes one
 * write. {@link #flush} and {@link #close} save at once. The disk is only touched by the saver and by the calls
 * that create, delete or reorder playlists; {@code changed} never waits for it.
 *
 * <p>A save reaches the disk before the rename (the temp file is {@code force}d), so a power cut leaves either the
 * old playlist or the new one, never an empty or zero-filled file. Files that cannot be read as UTF-8, or that
 * are damaged (empty, containing NUL bytes, or with neither a name line nor a single track) are skipped, left
 * alone and listed by {@link #loadProblems}; lines that are not {@code file://} URIs are skipped. Thread-safe.
 */
public final class PlaylistStore implements AutoCloseable {

    /** How long after a change the autosave runs. */
    static final int AUTOSAVE_SECONDS = 1;

    private static final Logger LOG = AppLog.logger(PlaylistStore.class);
    private static final String INDEX = "index.txt";
    private static final String DEFAULT_NAME = "Default";
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String NAME_PREFIX = "#name:";

    private final Path dir;
    private final long delayNanos;
    private final ScheduledExecutorService saver;
    private final AtomicInteger writes = new AtomicInteger();

    /** Guards the in-memory state below; held only briefly, never across disk access. */
    private final Object lock = new Object();
    /** Serialises disk access, so a delete cannot interleave with a save of the same playlist. */
    private final Object ioLock = new Object();

    private final List<Playlist> playlists = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private ScheduledFuture<?> pending;
    private boolean closed;

    /** Opens (creating if need be) the store in {@code dir}, loading every playlist found there. */
    public PlaylistStore(Path dir) {
        this(dir, Duration.ofSeconds(AUTOSAVE_SECONDS));
    }

    /** As above with a different autosave delay (tests use a short one). */
    PlaylistStore(Path dir, Duration autosaveDelay) {
        this.dir = dir;
        this.delayNanos = autosaveDelay.toNanos();
        this.saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "playlist-autosave");
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot create the playlists folder " + dir, e);
        }
        synchronized (ioLock) {
            load();
        }
    }

    // ---- the playlists ----

    /** Playlist files found damaged or unreadable at start-up (each left untouched on disk), one message each. */
    public List<String> loadProblems() {
        synchronized (lock) {
            return List.copyOf(problems);
        }
    }

    /** The playlists in tab order, as a snapshot. */
    public List<Playlist> all() {
        synchronized (lock) {
            return List.copyOf(playlists);
        }
    }

    /** Makes a new, empty playlist at the end of the tabs and saves it. */
    public Playlist create(String name) {
        Playlist p = new Playlist(name);
        synchronized (ioLock) {
            adopt(p);
            synchronized (lock) {
                playlists.add(p);
            }
            writePlaylist(p);
            writeIndex();
        }
        return p;
    }

    /** Deletes a playlist and its file. An unknown id does nothing. */
    public void delete(UUID id) {
        synchronized (ioLock) {
            synchronized (lock) {
                if (!playlists.removeIf(p -> p.id().equals(id))) {
                    return;
                }
                dirty.remove(id);
            }
            try {
                Files.deleteIfExists(file(id));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Cannot delete the playlist file of " + id, e);
            }
            writeIndex();
        }
    }

    /**
     * Puts the tabs in the order given. Ids that are not playlists are ignored, and playlists left out keep
     * their relative order after the listed ones.
     */
    public void reorder(List<UUID> order) {
        synchronized (ioLock) {
            synchronized (lock) {
                Map<UUID, Playlist> byId = new LinkedHashMap<>();
                for (Playlist p : playlists) {
                    byId.put(p.id(), p);
                }
                List<Playlist> next = new ArrayList<>(playlists.size());
                for (UUID id : order) {
                    Playlist p = byId.remove(id);
                    if (p != null) {
                        next.add(p);
                    }
                }
                next.addAll(byId.values());
                playlists.clear();
                playlists.addAll(next);
            }
            writeIndex();
        }
    }

    // ---- saving ----

    /**
     * Records that {@code p} changed. It is written about a second later, together with any other changes made
     * by then. Edits made through the playlist's own methods call this automatically.
     */
    public void changed(Playlist p) {
        synchronized (lock) {
            if (closed || !playlists.contains(p)) {
                return;
            }
            dirty.add(p.id());
            if (pending == null) {
                try {
                    pending = saver.schedule(this::autosave, delayNanos, TimeUnit.NANOSECONDS);
                } catch (RuntimeException e) { // the saver was shut down by a racing close()
                    LOG.log(Level.FINE, "Autosave not scheduled", e);
                }
            }
        }
    }

    /** Writes every unsaved playlist now. A failure is logged and the playlist stays unsaved for a retry. */
    public void flush() {
        ScheduledFuture<?> toCancel;
        synchronized (lock) {
            toCancel = pending;
            pending = null;
        }
        if (toCancel != null) {
            toCancel.cancel(false);
        }
        saveDirty();
    }

    /** Saves what is pending and stops the saver thread. */
    @Override
    public void close() {
        flush();
        synchronized (lock) {
            closed = true;
        }
        saver.shutdown();
        try {
            saver.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Playlist files written so far (the index is not counted). */
    int writeCount() {
        return writes.get();
    }

    private void autosave() {
        try {
            synchronized (lock) {
                pending = null;
            }
            saveDirty();
        } catch (Throwable t) { // a thrown error would silently end the saver
            LOG.log(Level.WARNING, "Playlist autosave failed", t);
        }
    }

    private void saveDirty() {
        synchronized (ioLock) {
            List<Playlist> toWrite = new ArrayList<>();
            synchronized (lock) {
                for (Playlist p : playlists) {
                    if (dirty.remove(p.id())) {
                        toWrite.add(p);
                    }
                }
                dirty.clear();
            }
            for (Playlist p : toWrite) {
                if (!writePlaylist(p)) {
                    synchronized (lock) {
                        dirty.add(p.id());
                    }
                }
            }
        }
    }

    private void adopt(Playlist p) {
        p.onChange(() -> changed(p));
    }

    // ---- disk ----

    private Path file(UUID id) {
        return dir.resolve(id + ".txt");
    }

    /** Must hold {@code ioLock}. Returns false if the write failed. */
    private boolean writePlaylist(Playlist p) {
        StringBuilder text = new StringBuilder(NAME_PREFIX).append(p.name()).append('\n');
        for (TrackRef ref : p.items()) {
            text.append(ref.toUri()).append('\n');
        }
        try {
            writeAtomically(file(p.id()), text.toString());
            writes.incrementAndGet();
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot save the playlist " + p.name(), e);
            return false;
        }
    }

    /** Must hold {@code ioLock}. */
    private void writeIndex() {
        StringBuilder text = new StringBuilder();
        synchronized (lock) {
            for (Playlist p : playlists) {
                text.append(p.id()).append('\n');
            }
        }
        try {
            writeAtomically(dir.resolve(INDEX), text.toString());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot save the playlist order", e);
        }
    }

    private void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(dir);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                ch.write(bytes);
            }
            ch.force(true); // on the platter before the rename makes it the playlist
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        try {
            moveIntoPlace(temp, target);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** Windows can refuse a rename for a moment while a virus scanner holds the target; try a few times. */
    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(20L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    // ---- loading ----

    /** Must hold {@code ioLock}; runs before the store is shared. */
    private void load() {
        Map<UUID, Playlist> found = new LinkedHashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path f : files) {
                String stem = f.getFileName().toString();
                stem = stem.substring(0, stem.length() - ".txt".length());
                if (!UUID_TEXT.matcher(stem).matches()) {
                    continue; // index.txt and anything else a user dropped here
                }
                UUID id = UUID.fromString(stem);
                Playlist p = read(id, f);
                if (p != null) {
                    found.put(id, p);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot list the playlists in " + dir, e);
        }

        List<UUID> indexOrder = readIndex();
        List<Playlist> ordered = new ArrayList<>();
        for (UUID id : indexOrder) {
            Playlist p = found.remove(id);
            if (p != null) {
                ordered.add(p);
            }
        }
        List<Playlist> strays = new ArrayList<>(found.values());
        strays.sort(Comparator.comparing((Playlist p) -> p.name().toLowerCase(Locale.ROOT))
                .thenComparing(Playlist::id));
        ordered.addAll(strays);

        boolean created = false;
        if (ordered.isEmpty()) {
            ordered.add(new Playlist(DEFAULT_NAME));
            created = true;
        }
        for (Playlist p : ordered) {
            adopt(p);
        }
        playlists.addAll(ordered);
        if (created) {
            writePlaylist(ordered.get(0));
        }
        List<UUID> now = new ArrayList<>();
        for (Playlist p : ordered) {
            now.add(p.id());
        }
        if (created || !now.equals(indexOrder)) {
            writeIndex();
        }
    }

    private Playlist read(UUID id, Path f) {
        String text;
        try {
            byte[] bytes = Files.readAllBytes(f);
            if (bytes.length == 0) {
                return problem(f, "it is empty", null);
            }
            text = decodeStrictly(bytes);
        } catch (IOException e) {
            return problem(f, "it cannot be read", e);
        }
        if (text.indexOf('\0') >= 0) {
            return problem(f, "it is filled with NUL bytes (an unfinished write)", null);
        }
        String name = "";
        List<TrackRef> items = new ArrayList<>();
        boolean first = true;
        for (String line : text.split("\\r?\\n")) {
            if (first && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            if (line.startsWith(NAME_PREFIX) && first) {
                name = line.substring(NAME_PREFIX.length());
            } else if (line.startsWith("file://")) {
                try {
                    items.add(TrackRef.parse(line));
                } catch (IllegalArgumentException e) {
                    LOG.log(Level.FINE, "Skipping an unusable playlist line: " + line, e);
                }
            }
            first = false;
        }
        if (name.isEmpty() && items.isEmpty() && !text.isBlank()) {
            return problem(f, "it is not a playlist", null);
        }
        return new Playlist(id, name, items);
    }

    /** Reports a playlist file that is not loaded. The file is left as it is, so nothing the user had is lost. */
    private Playlist problem(Path f, String why, Exception cause) {
        String message = "Playlist file " + f.getFileName() + " was not loaded: " + why;
        LOG.log(Level.WARNING, message, cause);
        problems.add(message);
        return null;
    }

    private List<UUID> readIndex() {
        Path index = dir.resolve(INDEX);
        if (!Files.isRegularFile(index)) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        try {
            String text = decodeStrictly(Files.readAllBytes(index));
            for (String line : text.split("\\r?\\n")) {
                String s = line.replace("﻿", "").strip();
                if (UUID_TEXT.matcher(s).matches()) {
                    UUID id = UUID.fromString(s);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read the playlist order; using name order", e);
        }
        return ids;
    }

    private static String decodeStrictly(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }
}
