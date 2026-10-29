package purist.ui;

import java.awt.Dimension;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import purist.ui.kit.PlainText;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;

/**
 * Short messages in the bottom-right corner, drawn like the LCD (skin display colours). They stack upwards and
 * each disappears after a few seconds (spec §9, §10). An identical message shown again within two seconds is
 * collapsed into the toast already there, and at most four show at once (the oldest makes room), so a run of
 * skipped tracks cannot bury the window. EDT only.
 */
public final class Toasts {

    static final int SHOW_MS = 5000;
    static final int MAX_VISIBLE = 4;
    /** An identical message shown again within this long of its last showing is not shown twice. */
    static final long COLLAPSE_NANOS = 2_000_000_000L;
    private static final int MARGIN = 14;
    private static final int GAP = 6;

    private static final class Toast {
        final String message;
        final JLabel label;
        final Timer timer;
        long shownAt;

        Toast(String message, JLabel label, Timer timer, long shownAt) {
            this.message = message;
            this.label = label;
            this.timer = timer;
            this.shownAt = shownAt;
        }
    }

    private final JRootPane root;
    private final LongSupplier nanos;
    /** Newest first. */
    private final List<Toast> shown = new ArrayList<>();

    public Toasts(JRootPane root) {
        this(root, System::nanoTime);
    }

    /** @param nanos the clock for the two-second collapse window */
    Toasts(JRootPane root, LongSupplier nanos) {
        this.root = root;
        this.nanos = nanos;
    }

    public void show(String message) {
        long now = nanos.getAsLong();
        for (Toast existing : shown) {
            if (existing.message.equals(message) && now - existing.shownAt < COLLAPSE_NANOS) {
                existing.shownAt = now;
                existing.timer.restart(); // still current: keep it up a little longer instead of stacking a copy
                return;
            }
        }
        while (shown.size() >= MAX_VISIBLE) {
            remove(shown.getLast());
        }
        JLabel t = PlainText.of(new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(PuristTheme.skin().lcd);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(PuristTheme.skin().edge);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                super.paintComponent(g);
            }
        });
        t.setText(message); // after PlainText: the label must already be marked when it first sees the text
        t.setFont(Fonts.forText(message, Fonts.mono(12f)));
        t.setForeground(PuristTheme.skin().segment);
        t.setBorder(new EmptyBorder(8, 12, 8, 12));
        Dimension d = t.getPreferredSize();
        t.setSize(Math.min(d.width, Math.max(200, root.getWidth() - 2 * MARGIN)), d.height);
        Timer timer = new Timer(SHOW_MS, null);
        Toast toast = new Toast(message, t, timer, now);
        timer.addActionListener(e -> {
            remove(toast);
            root.getLayeredPane().repaint();
        });
        timer.setRepeats(false);
        shown.add(0, toast);
        root.getLayeredPane().add(t, JLayeredPane.POPUP_LAYER);
        layout();
        timer.start();
    }

    /** How many toasts are showing. */
    int visibleCount() {
        return shown.size();
    }

    /** The messages showing, newest first. */
    List<String> messages() {
        return shown.stream().map(t -> t.message).toList();
    }

    private void remove(Toast t) {
        t.timer.stop();
        shown.remove(t);
        root.getLayeredPane().remove(t.label);
        layout();
    }

    private void layout() {
        int y = root.getLayeredPane().getHeight() - MARGIN;
        for (Toast toast : shown) {
            JLabel t = toast.label;
            y -= t.getHeight();
            t.setLocation(root.getLayeredPane().getWidth() - MARGIN - t.getWidth(), y);
            y -= GAP;
        }
        root.getLayeredPane().repaint();
    }
}
