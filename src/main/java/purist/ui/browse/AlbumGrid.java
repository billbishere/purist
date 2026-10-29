package purist.ui.browse;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JToolTip;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import purist.library.AlbumSummary;
import purist.library.art.AlbumKey;
import purist.ui.kit.PlainText;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The album grid (spec §9): square covers in as many columns as fit, the title and the artist beneath each. It is
 * virtualised: it computes where every cell is from its index ({@link AlbumGridModel}) and paints only the cells
 * the viewport shows, so a library of thousands of albums costs no more than a screenful. Covers come from the
 * {@link CoverLoader} in the background; until one arrives, and for an album without art, the cell shows the LCD
 * tile with the album's initials. The selected album has a 2 px highlight outline. Click selects, double-click
 * plays, a click on nothing clears the selection. A click also takes the keyboard focus (out of the search box,
 * where Space and the arrows would type), and the grid binds none of the window's shortcut keys. EDT only.
 */
public final class AlbumGrid extends JComponent implements Scrollable {

    private final AlbumGridModel model;
    private final CoverLoader covers;
    private final PlaceholderCache placeholders = new PlaceholderCache(AlbumGridModel.COVER);
    private Consumer<AlbumSummary> onSelect = a -> { };
    private Consumer<AlbumSummary> onPlay = a -> { };
    private AlbumSummary selected;
    private String emptyText = "";
    private int lastWidth = -1;

    public AlbumGrid(AlbumGridModel model, CoverLoader covers) {
        this.model = model;
        this.covers = covers;
        setOpaque(true);
        setFocusable(true);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(this);
        PuristTheme.addListener(s -> {
            placeholders.clear();
            repaint();
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (getWidth() != lastWidth) {
                    lastWidth = getWidth();
                    revalidate();
                }
            }
        });
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                requestFocusInWindow();
                int i = model.indexAt(e.getX(), e.getY(), getWidth());
                AlbumSummary hit = i < 0 ? null : model.get(i);
                if (!java.util.Objects.equals(hit, selected)) {
                    setSelected(hit);
                    onSelect.accept(hit);
                }
                if (hit != null && e.getClickCount() == 2) {
                    onPlay.accept(hit);
                }
            }
        };
        addMouseListener(m);
    }

    /** Called with the album the user selected, or null when the selection was cleared. */
    public void onSelect(Consumer<AlbumSummary> handler) {
        onSelect = handler;
    }

    /** Called when the user double-clicks an album. */
    public void onPlay(Consumer<AlbumSummary> handler) {
        onPlay = handler;
    }

    public AlbumSummary selected() {
        return selected;
    }

    /** Selects {@code a} (null for none) without telling the listener; scrolls to it when it is shown. */
    public void setSelected(AlbumSummary a) {
        selected = a;
        int i = model.indexOf(a);
        if (i >= 0) {
            scrollRectToVisible(model.cellBounds(i, getWidth()));
        }
        repaint();
    }

    /** The line drawn in the middle when there are no albums. */
    public void setEmptyText(String text) {
        emptyText = text;
        repaint();
    }

    /** The model changed: lay out and repaint. */
    public void modelChanged() {
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int w = getParent() instanceof JViewport vp ? vp.getWidth() : 600;
        return new Dimension(w, model.heightFor(w));
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int i = model.indexAt(e.getX(), e.getY(), getWidth());
        if (i < 0) {
            return null;
        }
        AlbumSummary a = model.get(i);
        StringBuilder sb = new StringBuilder(a.albumArtist()).append(" - ").append(a.album());
        if (!a.year().isEmpty()) {
            sb.append(" (").append(a.year()).append(')');
        }
        sb.append("  ").append(a.trackCount()).append(a.trackCount() == 1 ? " track  " : " tracks  ")
                .append(a.formatLabel());
        return sb.toString();
    }

    @Override
    public JToolTip createToolTip() {
        return PlainText.of(super.createToolTip()); // album and artist come from tags
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Skin s = PuristTheme.skin();
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setColor(s.list);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (model.size() == 0) {
                paintEmpty(g, s);
                return;
            }
            Rectangle clip = g.getClipBounds() == null ? new Rectangle(0, 0, getWidth(), getHeight())
                    : g.getClipBounds();
            int[] range = model.visibleRange(clip.y, clip.y + clip.height, getWidth());
            Rectangle view = getVisibleRect();
            int[] onScreen = model.visibleRange(view.y, view.y + view.height, getWidth());
            Set<AlbumKey> keys = new HashSet<>();
            for (int i = onScreen[0]; i < onScreen[1]; i++) {
                keys.add(CoverLoader.keyOf(model.get(i)));
            }
            covers.wanted(keys);
            Font title = Fonts.monoMedium(11f);
            Font artist = Fonts.mono(11f);
            for (int i = range[0]; i < range[1]; i++) {
                paintCell(g, s, i, title, artist);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintEmpty(Graphics2D g, Skin s) {
        if (emptyText.isEmpty()) {
            return;
        }
        g.setFont(Fonts.forText(emptyText, Fonts.mono(12f)));
        g.setColor(s.listDim);
        FontMetrics fm = g.getFontMetrics();
        int x = Math.max(AlbumGridModel.MARGIN, (getWidth() - fm.stringWidth(emptyText)) / 2);
        g.drawString(emptyText, x, Math.min(getHeight() / 3, 120) + fm.getAscent());
    }

    private void paintCell(Graphics2D g, Skin s, int i, Font titleFont, Font artistFont) {
        AlbumSummary a = model.get(i);
        Rectangle cover = model.coverBounds(i, getWidth());
        BufferedImage img = covers.image(a);
        if (img == null || img == CoverLoader.NO_ART) {
            img = placeholder(a);
        }
        g.drawImage(img, cover.x, cover.y, cover.width, cover.height, null);
        g.setColor(s.edge);
        g.drawRect(cover.x, cover.y, cover.width - 1, cover.height - 1);
        if (a.equals(selected)) {
            g.setColor(s.highlight);
            g.setStroke(new java.awt.BasicStroke(2f));
            g.drawRect(cover.x - 3, cover.y - 3, cover.width + 5, cover.height + 5);
            g.setStroke(new java.awt.BasicStroke(1f));
        }
        int y = cover.y + cover.height + 6;
        drawClipped(g, a.album(), titleFont, s.listText, cover.x, y, cover.width);
        drawClipped(g, a.albumArtist(), artistFont, s.listDim, cover.x, y + 15, cover.width);
    }

    private BufferedImage placeholder(AlbumSummary a) {
        return placeholders.get(a.album());
    }

    /** Draws one line of {@code text} in at most {@code width} px, ending in an ellipsis if it is cut. */
    private static void drawClipped(Graphics2D g, String text, Font font, java.awt.Color color, int x, int top,
            int width) {
        Font f = Fonts.forText(text, font);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(color);
        g.drawString(fit(text, fm, width), x, top + fm.getAscent());
    }

    /** {@code text} cut with an ellipsis so that it is at most {@code width} px wide. */
    static String fit(String text, FontMetrics fm, int width) {
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(text.substring(0, mid).stripTrailing() + ellipsis) <= width) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, lo).stripTrailing() + ellipsis;
    }

    // ---- Scrollable ----

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return new Dimension(600, 420);
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? AlbumGridModel.CELL_H / 3 : 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? Math.max(AlbumGridModel.CELL_H, visible.height - 20)
                : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport vp && vp.getHeight() > getPreferredSize().height;
    }
}
