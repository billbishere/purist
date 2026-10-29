package purist.playlist;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One named, ordered list of tracks (spec §8). Every edit replaces the list wholesale, so {@link #items()} is a
 * stable, unmodifiable snapshot that may be read from any thread (the autosave thread reads it) while the UI
 * keeps editing. Edits that change nothing are not reported.
 *
 * <p>Each real change calls the listener a {@link PlaylistStore} installs, which is what schedules the autosave;
 * a playlist that does not belong to a store simply has no listener.
 */
public final class Playlist {

    private static final String DEFAULT_NAME = "Playlist";

    private final UUID id;
    private volatile String name;
    private volatile List<TrackRef> items = List.of();
    private volatile Runnable listener = () -> { };

    /** A new, empty playlist with a fresh id. A blank name becomes {@code "Playlist"}. */
    public Playlist(String name) {
        this(UUID.randomUUID(), name, List.of());
    }

    /** A playlist with a known id and contents (what the store loads from disk). */
    public Playlist(UUID id, String name, List<TrackRef> items) {
        this.id = Objects.requireNonNull(id, "id");
        String clean = clean(name);
        this.name = clean.isEmpty() ? DEFAULT_NAME : clean;
        this.items = List.copyOf(items);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Renames. Line breaks become spaces and surrounding blanks are dropped; a blank name is refused. */
    public void rename(String newName) {
        String clean = clean(newName);
        boolean changed;
        synchronized (this) {
            if (clean.isEmpty() || clean.equals(name)) {
                return;
            }
            name = clean;
            changed = true;
        }
        if (changed) {
            listener.run();
        }
    }

    /** The tracks in order, as an unmodifiable snapshot: later edits do not show in a list returned earlier. */
    public List<TrackRef> items() {
        return items;
    }

    /** Appends {@code refs} to the end. */
    public void add(List<TrackRef> refs) {
        if (refs.isEmpty()) {
            return;
        }
        synchronized (this) {
            // Built from the current list while holding the lock, so two threads adding at once lose nothing.
            List<TrackRef> next = new ArrayList<>(items.size() + refs.size());
            next.addAll(items);
            next.addAll(refs);
            items = List.copyOf(next);
        }
        listener.run();
    }

    /**
     * Inserts {@code refs} so the first of them ends up at {@code index}.
     *
     * @throws IndexOutOfBoundsException unless {@code 0 <= index <= size}
     */
    public void insert(int index, List<TrackRef> refs) {
        synchronized (this) {
            if (index < 0 || index > items.size()) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + items.size());
            }
            if (refs.isEmpty()) {
                return;
            }
            List<TrackRef> next = new ArrayList<>(items);
            next.addAll(index, refs);
            items = List.copyOf(next);
        }
        listener.run();
    }

    /** Removes the tracks at {@code indices}. Duplicate and out-of-range indices are ignored. */
    public void remove(int[] indices) {
        synchronized (this) {
            TreeSet<Integer> doomed = selection(indices, items.size());
            if (doomed.isEmpty()) {
                return;
            }
            List<TrackRef> next = new ArrayList<>(items.size() - doomed.size());
            for (int i = 0; i < items.size(); i++) {
                if (!doomed.contains(i)) {
                    next.add(items.get(i));
                }
            }
            items = List.copyOf(next);
        }
        listener.run();
    }

    /**
     * Moves the tracks at {@code indices} (in any order, duplicates and out-of-range indices ignored) to just
     * before the track that is at {@code to} now, as a drag-and-drop does; {@code to == size} drops them at the
     * end. The moved tracks keep their relative order. {@code to} is clamped into {@code [0, size]}.
     */
    public void move(int[] indices, int to) {
        synchronized (this) {
            TreeSet<Integer> picked = selection(indices, items.size());
            if (picked.isEmpty()) {
                return;
            }
            int target = Math.max(0, Math.min(items.size(), to));
            List<TrackRef> moved = new ArrayList<>(picked.size());
            List<TrackRef> rest = new ArrayList<>(items.size() - picked.size());
            int before = 0;
            for (int i = 0; i < items.size(); i++) {
                if (picked.contains(i)) {
                    moved.add(items.get(i));
                    if (i < target) {
                        before++;
                    }
                } else {
                    rest.add(items.get(i));
                }
            }
            rest.addAll(target - before, moved);
            if (rest.equals(items)) {
                return;
            }
            items = List.copyOf(rest);
        }
        listener.run();
    }

    /** Replaces every track with {@code refs}. */
    public void replace(List<TrackRef> refs) {
        publish(new ArrayList<>(refs));
    }

    /** Removes every track. */
    public void clear() {
        publish(new ArrayList<>());
    }

    /** Called after every real change; installed by the {@link PlaylistStore} that owns this playlist. */
    void onChange(Runnable listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    private void publish(List<TrackRef> next) {
        synchronized (this) {
            if (next.equals(items)) {
                return;
            }
            items = List.copyOf(next);
        }
        listener.run();
    }

    private static TreeSet<Integer> selection(int[] indices, int size) {
        TreeSet<Integer> picked = new TreeSet<>();
        for (int i : indices) {
            if (i >= 0 && i < size) {
                picked.add(i);
            }
        }
        return picked;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ").strip();
    }

    @Override
    public String toString() {
        return "Playlist[" + name + ", " + items.size() + " tracks]";
    }
}
