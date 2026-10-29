package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.plaf.basic.BasicHTML;
import org.junit.jupiter.api.Test;
import purist.ui.kit.PlainText;

class ToastsTest {

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final JRootPane root = new JRootPane();
    private final Toasts toasts = new Toasts(root, clock::get);

    private List<JLabel> labels() {
        return java.util.Arrays.stream(root.getLayeredPane().getComponents())
                .filter(c -> c instanceof JLabel).map(c -> (JLabel) c).toList();
    }

    @Test
    void anIdenticalMessageWithinTwoSecondsIsCollapsed() {
        toasts.show("Skipped a.flac: damaged");
        clock.addAndGet(1_900_000_000L);
        toasts.show("Skipped a.flac: damaged");
        assertEquals(1, toasts.visibleCount());
        assertEquals(1, labels().size());
    }

    @Test
    void anIdenticalMessageAfterTwoSecondsShowsAgain() {
        toasts.show("same");
        clock.addAndGet(Toasts.COLLAPSE_NANOS + 1);
        toasts.show("same");
        assertEquals(2, toasts.visibleCount());
    }

    @Test
    void aCollapsedRepeatKeepsTheWindowOpenWhileItKeepsComing() {
        toasts.show("same");
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(1_500_000_000L);
            toasts.show("same");
        }
        assertEquals(1, toasts.visibleCount(), "each repeat was within two seconds of the last");
    }

    @Test
    void differentMessagesAreNotCollapsed() {
        toasts.show("one");
        toasts.show("two");
        assertEquals(List.of("two", "one"), toasts.messages());
    }

    @Test
    void atMostFourShowAndTheOldestMakesRoom() {
        for (int i = 1; i <= 7; i++) {
            toasts.show("message " + i);
        }
        assertEquals(Toasts.MAX_VISIBLE, toasts.visibleCount());
        assertEquals(List.of("message 7", "message 6", "message 5", "message 4"), toasts.messages());
        assertEquals(Toasts.MAX_VISIBLE, labels().size(), "the removed toasts leave the window too");
    }

    @Test
    void htmlInAMessageIsDrawnLiterally() {
        toasts.show("<html><b>bold<img src=\"http://example.invalid/x.png\">");
        JLabel t = labels().get(0);
        assertTrue(PlainText.is(t));
        assertNull(t.getClientProperty(BasicHTML.propertyKey), "no HTML view was built for the text");
        assertEquals("<html><b>bold<img src=\"http://example.invalid/x.png\">", t.getText());
    }

    @Test
    void layoutKeepsEveryVisibleToastInsideTheLayeredPane() {
        root.setSize(800, 600);
        root.getLayeredPane().setSize(800, 600);
        for (int i = 0; i < 4; i++) {
            toasts.show("m" + i);
        }
        for (Component c : labels()) {
            assertTrue(c.getY() >= 0 && c.getY() + c.getHeight() <= 600, "toast at y=" + c.getY());
        }
    }
}
