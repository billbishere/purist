package purist.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlaybackQueueTest {

    private static OptionalInt of(int i) {
        return OptionalInt.of(i);
    }

    @Test
    void repeatOffEnds() {
        PlaybackQueue q = new PlaybackQueue(3, RepeatMode.OFF, false, 1);
        assertEquals(of(1), q.next(0));
        assertEquals(of(2), q.next(1));
        assertEquals(OptionalInt.empty(), q.next(2), "the end of the queue");
        assertEquals(of(1), q.previous(2));
        assertEquals(OptionalInt.empty(), q.previous(0), "nothing before the first track");
    }

    @Test
    void repeatAllWraps() {
        PlaybackQueue q = new PlaybackQueue(3, RepeatMode.ALL, false, 1);
        assertEquals(of(1), q.next(0));
        assertEquals(of(0), q.next(2), "wraps to the first track");
        assertEquals(of(2), q.previous(0), "wraps back to the last track");
    }

    @Test
    void repeatTrackRepeats() {
        PlaybackQueue q = new PlaybackQueue(3, RepeatMode.TRACK, false, 1);
        assertEquals(of(1), q.next(1));
        assertEquals(of(1), q.previous(1));
        assertEquals(of(2), q.next(2), "even the last track");
    }

    @Test
    void shuffleVisitsAllOnce() {
        int n = 20;
        List<Integer> walk = walk(new PlaybackQueue(n, RepeatMode.OFF, true, 42));

        assertEquals(IntStream.range(0, n).boxed().toList(), walk.stream().sorted().toList(),
                "every track exactly once");
        assertNotEquals(IntStream.range(0, n).boxed().toList(), walk, "shuffled, not queue order");
        assertEquals(walk, walk(new PlaybackQueue(n, RepeatMode.OFF, true, 42)), "the seed fixes the order");
        assertNotEquals(walk, walk(new PlaybackQueue(n, RepeatMode.OFF, true, 43)), "another seed, another order");

        PlaybackQueue all = new PlaybackQueue(n, RepeatMode.ALL, true, 42);
        assertEquals(of(walk.get(0)), all.next(walk.get(n - 1)), "repeat all wraps to the start of the order");
        assertEquals(of(walk.get(n - 1)), all.previous(walk.get(0)));
    }

    /** The whole play order: back to its first track from track 0, then forward to the end. */
    private static List<Integer> walk(PlaybackQueue q) {
        int head = 0;
        for (OptionalInt p = q.previous(head); p.isPresent(); p = q.previous(head)) {
            head = p.getAsInt();
        }
        List<Integer> order = new ArrayList<>();
        for (OptionalInt i = OptionalInt.of(head); i.isPresent(); i = q.next(i.getAsInt())) {
            order.add(i.getAsInt());
        }
        return order;
    }

    @Test
    void anEmptyQueueHasNoNeighboursAndIndicesAreChecked() {
        PlaybackQueue q = new PlaybackQueue(2, RepeatMode.ALL, false, 1);
        assertThrows(IndexOutOfBoundsException.class, () -> q.next(2));
        assertThrows(IndexOutOfBoundsException.class, () -> q.previous(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> new PlaybackQueue(0, RepeatMode.ALL, false, 1).next(0));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackQueue(-1, RepeatMode.OFF, false, 1));
    }
}
