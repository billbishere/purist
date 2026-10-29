package purist.ui.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import purist.playlist.Playlist;
import purist.playlist.TrackRef;

class QueueReorderTest {

    private static final List<String> ABCDE = List.of("a", "b", "c", "d", "e");

    @Test
    void moveIndicesReorders() {
        assertEquals(List.of("b", "d", "a", "c", "e"), QueueReorder.move(ABCDE, new int[] {1, 3}, 0));
    }

    @Test
    void movingDownDropsTheItemsBeforeTheTargetRow() {
        // dropped before row 4 ("e"): c and a leave their places first, so they land between d and e
        assertEquals(List.of("b", "d", "a", "c", "e"), QueueReorder.move(ABCDE, new int[] {0, 2}, 4));
        assertEquals(List.of("b", "c", "d", "e", "a"), QueueReorder.move(ABCDE, new int[] {0}, 5), "5 is the end");
    }

    @Test
    void indicesInAnyOrderWithDuplicatesAndStraysAreTolerated() {
        assertEquals(List.of("b", "d", "a", "c", "e"), QueueReorder.move(ABCDE, new int[] {3, 1, 3, 9, -1}, 0));
        assertEquals(ABCDE, QueueReorder.move(ABCDE, new int[] {}, 2));
    }

    @Test
    void theInputListIsNotChanged() {
        List<String> in = new ArrayList<>(ABCDE);
        QueueReorder.move(in, new int[] {4}, 0);
        assertEquals(ABCDE, in);
    }

    @Test
    void theOldToNewMapSaysWhereEveryTrackWent() {
        assertArrayEquals(new int[] {2, 0, 3, 1, 4}, QueueReorder.oldToNewAfterMove(5, new int[] {1, 3}, 0));
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, QueueReorder.oldToNewAfterMove(5, new int[] {}, 3));
    }

    @Test
    void removalMapShiftsTheSurvivorsAndMarksTheRemoved() {
        assertArrayEquals(new int[] {0, -1, 1, -1, 2}, QueueReorder.oldToNewAfterRemove(5, new int[] {3, 1}));
    }

    @Test
    void deleteAdjustsPlayingIndex() {
        assertEquals(2, QueueReorder.adjustPlaying(4, new int[] {0, 3}), "two tracks before it are gone");
        assertEquals(4, QueueReorder.adjustPlaying(4, new int[] {5, 6}), "tracks after it do not matter");
        assertEquals(-1, QueueReorder.adjustPlaying(4, new int[] {4}), "the playing track itself was removed");
        assertEquals(-1, QueueReorder.adjustPlaying(-1, new int[] {0}), "nothing playing stays nothing");
        assertEquals(2, QueueReorder.adjustPlaying(3, new int[] {2, 2, 7}), "a duplicate counts once");
        assertEquals(3, QueueReorder.adjustPlaying(4, new int[] {0, 0}), "a duplicate counts once");
    }

    @Test
    void itAgreesWithWhatThePlaylistDoes() {
        List<TrackRef> refs = new ArrayList<>();
        for (String s : ABCDE) {
            refs.add(new TrackRef(java.nio.file.Path.of("E:\\" + s + ".flac"), 0));
        }
        int[][] cases = {{1, 3}, {0}, {4, 2, 0}, {2, 2}};
        for (int[] idx : cases) {
            for (int to = 0; to <= 5; to++) {
                Playlist p = new Playlist("x");
                p.add(refs);
                p.move(idx, to);
                assertEquals(p.items(), QueueReorder.move(refs, idx, to),
                        "move " + java.util.Arrays.toString(idx) + " to " + to);
            }
            Playlist p = new Playlist("x");
            p.add(refs);
            p.remove(idx);
            List<TrackRef> kept = new ArrayList<>();
            int[] map = QueueReorder.oldToNewAfterRemove(5, idx);
            for (int i = 0; i < 5; i++) {
                if (map[i] >= 0) {
                    kept.add(refs.get(i));
                }
            }
            assertEquals(p.items(), kept, "remove " + java.util.Arrays.toString(idx));
        }
    }
}
