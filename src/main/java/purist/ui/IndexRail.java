package purist.ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import purist.ui.MainWindow.View;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The left index (spec §9): about 110 px wide, a list-coloured panel with a 1 px edge and one UPPERCASE Plex Mono
 * entry per {@link View}. The active entry is inverted (highlight fill, highlight text). A divider separates the
 * entries that open the library from the queue and settings. EDT only.
 */
public final class IndexRail extends JComponent {

    static final int WIDTH = 112;
    private static final int TOP = 8;
    private static final int ROW = 30;
    private static final int DIVIDER = 18;

    private final Consumer<View> onSelect;
    private View selected = View.ALBUMS;
    private View hover;

    public IndexRail(Consumer<View> onSelect) {
        this.onSelect = onSelect;
        setOpaque(false);
        setFocusable(false); // window-level shortcuts must always fire
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                View v = viewAt(e.getY());
                if (v != null && !javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    selected = v;
                    repaint();
                    onSelect.accept(v);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                setHover(viewAt(e.getY()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setHover(null);
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    /** Marks {@code v} as the active entry without telling the listener. */
    public void select(View v) {
        selected = v;
        repaint();
    }

    public View selected() {
        return selected;
    }

    /** The y of the top of {@code v}'s entry. */
    int entryTop(View v) {
        int y = TOP;
        for (View each : View.values()) {
            if (each.dividerBefore() && each != View.values()[0]) {
                y += DIVIDER;
            }
            if (each == v) {
                return y;
            }
            y += ROW;
        }
        throw new IllegalArgumentException(String.valueOf(v));
    }

    /** The entry under a y coordinate, or null between entries and below them. */
    private View viewAt(int y) {
        for (View v : View.values()) {
            int top = entryTop(v);
            if (y >= top && y < top + ROW) {
                return v;
            }
        }
        return null;
    }

    private void setHover(View v) {
        if (v != hover) {
            hover = v;
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        View last = View.values()[View.values().length - 1];
        return new Dimension(WIDTH, entryTop(last) + ROW + TOP);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Skin s = PuristTheme.skin();
            g.setColor(s.list);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(s.edge);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            g.setFont(Fonts.monoMedium(12f).deriveFont(Map.of(TextAttribute.TRACKING, 0.12f)));
            FontMetrics fm = g.getFontMetrics();
            for (View v : View.values()) {
                int top = entryTop(v);
                if (v.dividerBefore() && v != View.values()[0]) {
                    g.setColor(s.edge);
                    int ly = top - DIVIDER / 2;
                    g.drawLine(10, ly, getWidth() - 11, ly);
                }
                if (v == selected) {
                    g.setColor(s.highlight);
                    g.fillRect(1, top, getWidth() - 2, ROW);
                } else if (v == hover) {
                    g.setColor(s.chassis);
                    g.fillRect(1, top, getWidth() - 2, ROW);
                }
                g.setColor(v == selected ? s.highlightText : s.listText);
                g.drawString(v.label().toUpperCase(Locale.ROOT), 14, top + (ROW - fm.getHeight()) / 2 + fm.getAscent());
            }
        } finally {
            g.dispose();
        }
    }
}
