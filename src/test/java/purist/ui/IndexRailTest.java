package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import purist.ui.MainWindow.View;

class IndexRailTest {

    @Test
    void theEntriesAreTheViewsInOrderWithTheirKeys() {
        assertEquals(List.of("ALBUMS", "ARTISTS", "FOLDERS", "DRIVES", "QUEUE", "SETTINGS"),
                java.util.Arrays.stream(View.values()).map(v -> v.label().toUpperCase()).toList());
        assertEquals(1, View.ALBUMS.digit());
        assertEquals(2, View.ARTISTS.digit());
        assertEquals(3, View.FOLDERS.digit());
        assertEquals(4, View.DRIVES.digit());
        assertEquals(5, View.QUEUE.digit());
        assertEquals(6, View.SETTINGS.digit());
    }

    @Test
    void aClickOnAnEntrySelectsItAndTellsTheListener() {
        List<View> got = new ArrayList<>();
        IndexRail rail = new IndexRail(got::add);
        rail.setSize(rail.getPreferredSize());
        int y = rail.entryTop(View.FOLDERS) + 3;
        rail.dispatchEvent(new MouseEvent(rail, MouseEvent.MOUSE_PRESSED, 0, 0, 10, y, 1, false));
        assertEquals(List.of(View.FOLDERS), got);
        assertEquals(View.FOLDERS, rail.selected());
    }

    @Test
    void selectingFromCodeDoesNotCallTheListener() {
        List<View> got = new ArrayList<>();
        IndexRail rail = new IndexRail(got::add);
        rail.select(View.QUEUE);
        assertEquals(View.QUEUE, rail.selected());
        assertEquals(List.of(), got);
    }

    @Test
    void aClickBetweenEntriesOrBelowThemDoesNothing() {
        List<View> got = new ArrayList<>();
        IndexRail rail = new IndexRail(got::add);
        rail.setSize(rail.getPreferredSize());
        rail.dispatchEvent(new MouseEvent(rail, MouseEvent.MOUSE_PRESSED, 0, 0, 10, rail.getPreferredSize().height + 50,
                1, false));
        assertEquals(List.of(), got);
    }

    @Test
    void theQueueSitsBelowADivider() {
        IndexRail rail = new IndexRail(v -> { });
        Dimension d = rail.getPreferredSize();
        assertTrue(d.width >= 100 && d.width <= 130, "about 110 px wide: " + d.width);
        assertTrue(rail.entryTop(View.QUEUE) - rail.entryTop(View.FOLDERS) > rail.entryTop(View.FOLDERS)
                - rail.entryTop(View.ARTISTS), "the divider adds a gap");
    }
}
