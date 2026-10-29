package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.table.TableCellRenderer;
import org.junit.jupiter.api.Test;
import purist.decode.DecoderRegistry;
import purist.playlist.TrackRef;
import purist.ui.kit.PlainText;

class PlaylistViewTest {

    private static PlaylistView view() {
        PlaylistView v = new PlaylistView(DecoderRegistry.standard(), i -> { }, () -> { }, () -> { });
        v.setQueue(List.of(new TrackRef(Path.of("nowhere", "a.wav"), 0)));
        return v;
    }

    @Test
    void tagTextThatLooksLikeHtmlIsDrawnLiterally() {
        PlaylistView v = view();
        JTable t = v.table();
        TableCellRenderer r = t.getDefaultRenderer(Object.class);
        String evil = "<html><b>x<img src=\"http://example.invalid/p.png\">";
        JComponent c = (JComponent) r.getTableCellRendererComponent(t, evil, false, false, 0, 1);
        assertTrue(PlainText.is(c));
        assertNull(c.getClientProperty(BasicHTML.propertyKey), "no HTML view was built");
        assertEquals(evil, ((JLabel) c).getText());
    }

    @Test
    void tooltipsAreLiteralToo() {
        assertTrue(PlainText.is(view().table().createToolTip()));
    }

    @Test
    void theTableAndItsScrollPaneDoNotBindTheWindowShortcutKeys() {
        PlaylistView v = view();
        JTable t = v.table();
        JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, t);
        assertNotNull(sp);
        for (KeyStroke k : PlaylistView.SHORTCUT_KEYS) {
            for (JComponent c : new JComponent[] {t, sp}) {
                Object name = c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(k);
                assertTrue(name == null || c.getActionMap().get(name) == null,
                        k + " still runs " + name + " on " + c.getClass().getSimpleName());
            }
        }
    }

    private static PlaylistView five() {
        PlaylistView v = new PlaylistView(DecoderRegistry.standard(), i -> { }, () -> { }, () -> { });
        List<TrackRef> refs = new java.util.ArrayList<>();
        for (String n : new String[] {"a", "b", "c", "d", "e"}) {
            refs.add(new TrackRef(Path.of("nowhere", n + ".wav"), 0));
        }
        v.setQueue(refs);
        return v;
    }

    private static final class Told implements PlaylistView.EditListener {
        final List<String> log = new java.util.ArrayList<>();

        @Override
        public void moved(int[] rows, int to) {
            log.add("moved " + java.util.Arrays.toString(rows) + " to " + to);
        }

        @Override
        public void removed(int[] rows) {
            log.add("removed " + java.util.Arrays.toString(rows));
        }
    }

    @Test
    void droppingRowsReordersThemAndTellsTheListenerWhatHappened() {
        PlaylistView v = five();
        Told told = new Told();
        v.setEditListener(told);
        v.dropRows(new int[] {1, 3}, 0);
        assertEquals(List.of("b", "d", "a", "c", "e"), v.titles());
        assertEquals(List.of("moved [1, 3] to 0"), told.log);
        assertEquals(java.util.Arrays.toString(new int[] {0, 1}), java.util.Arrays.toString(v.table().getSelectedRows()),
                "the moved rows stay selected");
    }

    @Test
    void theMarkOnThePlayingRowFollowsItWhenRowsMove() {
        PlaylistView v = five();
        v.setPlaying(2); // c
        v.moveRows(new int[] {4}, 0); // e to the top: c is now third
        v.setPlaying(2);
        v.moveRows(new int[] {0}, 5); // e back to the end
        // the playing marker is kept on c throughout: index 2 again
        assertEquals(List.of("a", "b", "c", "d", "e"), v.titles());
    }

    @Test
    void deleteTakesOutTheSelectedRowsExceptThePlayingOne() {
        PlaylistView v = five();
        Told told = new Told();
        v.setEditListener(told);
        v.setPlaying(1);
        v.table().setRowSelectionInterval(0, 2);
        v.removeSelected();
        assertEquals(List.of("b", "d", "e"), v.titles(), "b is playing: it stays");
        assertEquals(List.of("removed [0, 2]"), told.log);
    }

    @Test
    void deleteWithOnlyThePlayingRowSelectedDoesNothing() {
        PlaylistView v = five();
        Told told = new Told();
        v.setEditListener(told);
        v.setPlaying(3);
        v.table().setRowSelectionInterval(3, 3);
        v.removeSelected();
        assertEquals(5, v.titles().size());
        assertEquals(List.of(), told.log);
    }

    @Test
    void appendingAddsRowsAtTheEndAndKeepsWhatWasProbed() {
        PlaylistView v = five();
        v.append(List.of(new TrackRef(Path.of("nowhere", "f.wav"), 0)));
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), v.titles());
    }

    /** Reads {@code *.tst}: the title is the file's name without extension; a probe waits until it is let go. */
    private static final class GatedHandler implements purist.decode.FormatHandler {
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch probed;

        GatedHandler(int expected) {
            probed = new java.util.concurrent.CountDownLatch(expected);
        }

        @Override
        public List<String> extensions() {
            return List.of("tst");
        }

        @Override
        public List<purist.decode.TrackInfo> probe(Path p) throws java.io.IOException {
            try {
                gate.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String name = p.getFileName().toString();
            purist.tags.TagMap tags = new purist.tags.TagMap();
            tags.add("TITLE", name.substring(0, name.lastIndexOf('.')));
            probed.countDown();
            return List.of(new purist.decode.TrackInfo(p, 0, "TST",
                    purist.audio.StreamFormat.pcm(44100, 16, 2, 441000), tags, true, null));
        }

        @Override
        public purist.decode.Decoder open(Path p, int subsong) {
            throw new AssertionError();
        }
    }

    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test
    void probeResultsStayWithTheirRowsWhenRowsAreDraggedOrDeletedMeanwhile() throws Exception {
        DecoderRegistry registry = new DecoderRegistry();
        GatedHandler handler = new GatedHandler(5);
        registry.register(handler);
        PlaylistView v = new PlaylistView(registry, i -> { }, () -> { }, () -> { });
        List<TrackRef> refs = new java.util.ArrayList<>();
        for (String n : new String[] {"a", "b", "c", "d", "e"}) {
            refs.add(new TrackRef(Path.of("nowhere", n + ".tst"), 0));
        }
        v.setQueue(refs);

        v.moveRows(new int[] {4}, 0); // e to the top while nothing is probed yet
        v.removeRows(new int[] {2}); // and b leaves (rows: e a b c d -> e a c d)
        handler.gate.countDown();
        assertTrue(handler.probed.await(10, java.util.concurrent.TimeUnit.SECONDS));
        drainEdt();
        drainEdt();

        assertEquals(List.of("e", "a", "c", "d"), v.titles(), "each result landed on its own row");
    }
}
