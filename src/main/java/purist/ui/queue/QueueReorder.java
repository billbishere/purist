package purist.ui.queue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The arithmetic of editing a queue: moving rows, removing rows, and following the track that is playing through
 * either. It matches {@code Playlist.move} and {@code Playlist.remove}: indices may come in any order, with
 * duplicates and strays, and are cleaned first; {@code to} is the position in the list as it is now before which the
 * moved rows are dropped ({@code size} is the end), their relative order kept.
 */
public final class QueueReorder {

    private QueueReorder() {
    }

    /** A copy of {@code list} with the rows at {@code indices} moved to just before row {@code to}. */
    public static <T> List<T> move(List<T> list, int[] indices, int to) {
        int[] order = newOrder(list.size(), indices, to);
        List<T> out = new ArrayList<>(list.size());
        for (int old : order) {
            out.add(list.get(old));
        }
        return out;
    }

    /** For each old index, the index its row has after {@link #move}; the list a player holds is remapped by it. */
    public static int[] oldToNewAfterMove(int size, int[] indices, int to) {
        int[] order = newOrder(size, indices, to);
        int[] map = new int[size];
        for (int now = 0; now < size; now++) {
            map[order[now]] = now;
        }
        return map;
    }

    /** For each old index, its index after the rows at {@code removed} are taken out, or -1 if it was one of them. */
    public static int[] oldToNewAfterRemove(int size, int[] removed) {
        boolean[] gone = flags(size, removed);
        int[] map = new int[size];
        int next = 0;
        for (int i = 0; i < size; i++) {
            map[i] = gone[i] ? -1 : next++;
        }
        return map;
    }

    /**
     * Where the playing track is after the rows at {@code removed} are taken out: its index less the number of
     * distinct removed rows before it; -1 if it is itself removed, or if nothing was playing.
     */
    public static int adjustPlaying(int playing, int[] removed) {
        if (playing < 0) {
            return -1;
        }
        int before = 0;
        boolean[] seen = new boolean[playing + 1];
        for (int r : removed) {
            if (r < 0 || r > playing || seen[r]) {
                continue;
            }
            seen[r] = true;
            if (r == playing) {
                return -1;
            }
            before++;
        }
        return playing - before;
    }

    /** {@code order[k]} is the old index of the row that is at position k after the move. */
    private static int[] newOrder(int size, int[] indices, int to) {
        boolean[] moved = flags(size, indices);
        int target = Math.clamp(to, 0, size);
        int movedBefore = 0;
        for (int i = 0; i < Math.min(target, size); i++) {
            if (moved[i]) {
                movedBefore++;
            }
        }
        int insertAt = target - movedBefore;
        int[] rest = new int[size];
        int restCount = 0;
        for (int i = 0; i < size; i++) {
            if (!moved[i]) {
                rest[restCount++] = i;
            }
        }
        int[] order = new int[size];
        int k = 0;
        for (int i = 0; i < insertAt; i++) {
            order[k++] = rest[i];
        }
        for (int i = 0; i < size; i++) {
            if (moved[i]) {
                order[k++] = i;
            }
        }
        for (int i = insertAt; i < restCount; i++) {
            order[k++] = rest[i];
        }
        return order;
    }

    private static boolean[] flags(int size, int[] indices) {
        boolean[] f = new boolean[size];
        Arrays.stream(indices).filter(i -> i >= 0 && i < size).forEach(i -> f[i] = true);
        return f;
    }
}
