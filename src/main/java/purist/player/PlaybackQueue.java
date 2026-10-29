package purist.player;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.SplittableRandom;

/**
 * The play order of a queue of {@code size} tracks: which index comes after (or before) another under a repeat
 * mode, in queue order or shuffled. Immutable; the player makes a new one when the queue or the modes change.
 *
 * <p>Shuffle is one seeded permutation of the queue, walked like the queue itself: {@link RepeatMode#OFF} ends
 * at the permutation's last track, {@link RepeatMode#ALL} wraps to its first, so every track is heard exactly
 * once per pass. The same seed always gives the same order.
 */
public final class PlaybackQueue {

    private final RepeatMode mode;
    /** {@code order[k]} is the k-th queue index to play. */
    private final int[] order;
    /** {@code position[i]} is where queue index {@code i} sits in {@link #order}. */
    private final int[] position;

    /**
     * @throws IllegalArgumentException if {@code size} is negative
     */
    public PlaybackQueue(int size, RepeatMode mode, boolean shuffle, long seed) {
        if (size < 0) {
            throw new IllegalArgumentException("negative queue size: " + size);
        }
        this.mode = Objects.requireNonNull(mode, "mode");
        order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        if (shuffle) {
            SplittableRandom rnd = new SplittableRandom(seed);
            for (int i = size - 1; i > 0; i--) { // Fisher-Yates
                int j = rnd.nextInt(i + 1);
                int t = order[i];
                order[i] = order[j];
                order[j] = t;
            }
        }
        position = new int[size];
        for (int k = 0; k < size; k++) {
            position[order[k]] = k;
        }
    }

    /**
     * The track to play after {@code current}: {@code current} itself under {@link RepeatMode#TRACK}, empty at
     * the end of the order under {@link RepeatMode#OFF}.
     *
     * @throws IndexOutOfBoundsException if {@code current} is not an index of the queue
     */
    public OptionalInt next(int current) {
        return step(current, 1);
    }

    /**
     * The track before {@code current}, mirroring {@link #next}: empty before the start of the order under
     * {@link RepeatMode#OFF}, the last track under {@link RepeatMode#ALL}.
     *
     * @throws IndexOutOfBoundsException if {@code current} is not an index of the queue
     */
    public OptionalInt previous(int current) {
        return step(current, -1);
    }

    private OptionalInt step(int current, int direction) {
        Objects.checkIndex(current, order.length);
        if (mode == RepeatMode.TRACK) {
            return OptionalInt.of(current);
        }
        int k = position[current] + direction;
        if (k >= 0 && k < order.length) {
            return OptionalInt.of(order[k]);
        }
        if (mode == RepeatMode.ALL) {
            return OptionalInt.of(order[Math.floorMod(k, order.length)]);
        }
        return OptionalInt.empty();
    }
}
