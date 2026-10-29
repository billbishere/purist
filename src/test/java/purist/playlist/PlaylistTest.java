package purist.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PlaylistTest {

    private static TrackRef t(int n) {
        return new TrackRef(Path.of("C:\\m\\" + n + ".flac"), 0);
    }

    private static List<TrackRef> refs(int... ns) {
        List<TrackRef> out = new ArrayList<>();
        for (int n : ns) {
            out.add(t(n));
        }
        return out;
    }

    private static Playlist of(int... ns) {
        Playlist p = new Playlist("p");
        p.add(refs(ns));
        return p;
    }

    @Test
    void hasAnId() {
        assertNotNull(new Playlist("x").id());
    }

    @Test
    void itemsAreUnmodifiable() {
        Playlist p = of(1);
        assertThrows(UnsupportedOperationException.class, () -> p.items().add(t(2)));
    }

    @Test
    void itemsReturnedEarlierDoNotChangeLater() {
        Playlist p = of(1, 2);
        List<TrackRef> before = p.items();
        p.add(refs(3));
        assertEquals(refs(1, 2), before);
        assertEquals(refs(1, 2, 3), p.items());
    }

    @Test
    void addAppends() {
        Playlist p = of(1);
        p.add(refs(2, 3));
        assertEquals(refs(1, 2, 3), p.items());
    }

    @Test
    void insertPlacesBeforeTheGivenIndex() {
        Playlist p = of(1, 2, 3);
        p.insert(1, refs(8, 9));
        assertEquals(refs(1, 8, 9, 2, 3), p.items());
        p.insert(0, refs(7));
        p.insert(p.items().size(), refs(6));
        assertEquals(refs(7, 1, 8, 9, 2, 3, 6), p.items());
        assertThrows(IndexOutOfBoundsException.class, () -> p.insert(99, refs(5)));
        assertThrows(IndexOutOfBoundsException.class, () -> p.insert(-1, refs(5)));
    }

    @Test
    void removeTakesTheListedIndices() {
        Playlist p = of(1, 2, 3, 4, 5);
        p.remove(new int[] {3, 1});
        assertEquals(refs(1, 3, 5), p.items());
    }

    @Test
    void removeIgnoresDuplicatesAndOutOfRangeIndices() {
        Playlist p = of(1, 2, 3);
        p.remove(new int[] {1, 1, 9, -4});
        assertEquals(refs(1, 3), p.items());
    }

    @Test
    void moveDownDropsBeforeTheTargetPosition() {
        Playlist p = of(1, 2, 3, 4, 5);
        p.move(new int[] {0, 1}, 4); // drop before item 5
        assertEquals(refs(3, 4, 1, 2, 5), p.items());
    }

    @Test
    void moveUp() {
        Playlist p = of(1, 2, 3, 4, 5);
        p.move(new int[] {3, 4}, 1);
        assertEquals(refs(1, 4, 5, 2, 3), p.items());
    }

    @Test
    void moveToTheEnd() {
        Playlist p = of(1, 2, 3);
        p.move(new int[] {0}, 3);
        assertEquals(refs(2, 3, 1), p.items());
    }

    @Test
    void moveToTheStart() {
        Playlist p = of(1, 2, 3);
        p.move(new int[] {2}, 0);
        assertEquals(refs(3, 1, 2), p.items());
    }

    @Test
    void movedItemsKeepTheirRelativeOrderWhateverOrderTheIndicesComeIn() {
        Playlist p = of(1, 2, 3, 4, 5, 6);
        p.move(new int[] {4, 0, 2}, 3);
        assertEquals(refs(2, 1, 3, 5, 4, 6), p.items());
    }

    @Test
    void moveOntoItselfChangesNothing() {
        Playlist p = of(1, 2, 3);
        p.move(new int[] {1}, 1);
        assertEquals(refs(1, 2, 3), p.items());
        p.move(new int[] {1}, 2);
        assertEquals(refs(1, 2, 3), p.items());
    }

    @Test
    void replaceAndClear() {
        Playlist p = of(1, 2);
        p.replace(refs(7, 8, 9));
        assertEquals(refs(7, 8, 9), p.items());
        p.clear();
        assertEquals(List.of(), p.items());
    }

    @Test
    void renameKeepsTheIdAndCleansTheName() {
        Playlist p = new Playlist("a");
        var id = p.id();
        p.rename("  new\nname ");
        assertEquals("new name", p.name());
        assertEquals(id, p.id());
        p.rename("   ");
        assertEquals("new name", p.name(), "a blank name is refused");
    }

    @Test
    void everyRealChangeNotifiesAndNoOpsDoNot() {
        Playlist p = new Playlist("p");
        AtomicInteger changes = new AtomicInteger();
        p.onChange(changes::incrementAndGet);
        p.add(refs(1, 2));
        p.insert(0, refs(3));
        p.move(new int[] {0}, 3);
        p.remove(new int[] {0});
        p.replace(refs(4));
        p.rename("q");
        p.clear();
        assertEquals(7, changes.get());
        p.add(List.of());
        p.remove(new int[] {5});
        p.clear();
        p.rename("q");
        assertEquals(7, changes.get());
    }

    @Test
    void concurrentAddsAndInsertsLoseNothing() throws Exception {
        Playlist p = new Playlist("p");
        int threads = 4;
        int each = 500;
        Thread[] ts = new Thread[threads];
        for (int k = 0; k < threads; k++) {
            int base = k * each;
            ts[k] = new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    p.add(List.of(t(base + i)));
                }
            });
        }
        for (Thread t : ts) {
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(threads * each, p.items().size());
        assertEquals(threads * each, new java.util.HashSet<>(p.items()).size());
    }
}
