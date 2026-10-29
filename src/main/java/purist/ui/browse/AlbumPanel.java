package purist.ui.browse;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import purist.decode.TrackInfo;
import purist.library.AlbumSummary;
import purist.library.art.ThumbnailCache;
import purist.playlist.TrackRef;
import purist.ui.kit.TextButton;
import purist.ui.kit.TrackTable;
import purist.ui.kit.TrackTableModel.Column;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The album panel (spec §9), on the right of the grid and shown only while an album is selected: a 70 px cover,
 * the title in Share Tech Mono UPPERCASE, {@code artist · year · N tr · format} in dim text, {@code PLAY} (replaces
 * the queue with the album and plays track 1) and {@code + QUEUE} (appends), and a compact track list. A
 * double-click on a track plays the album from it. EDT only.
 */
public final class AlbumPanel extends JPanel {

    /** Reads an album's tracks off the UI thread and hands them to {@code onLoaded} on it. */
    @FunctionalInterface
    public interface TrackLoader {
        void load(AlbumSummary album, Consumer<List<TrackInfo>> onLoaded);
    }

    static final int WIDTH = 240;
    private static final int COVER = 70;

    private final CoverLoader covers;
    private final TrackLoader loader;
    private final CoverBox cover = new CoverBox();
    private final JTextArea title = text(Fonts.display(15f));
    private final JTextArea meta = text(Fonts.mono(11f));
    private final TextButton play = new TextButton("Play", true);
    private final TextButton queue = new TextButton("+ Queue", false);
    private final TrackTable tracks = new TrackTable(List.of(Column.NUMBER, Column.TITLE, Column.LENGTH), 12, 22);
    private AlbumSummary current;

    /** @param covers where the cover comes from; null shows the placeholder only (tests) */
    public AlbumPanel(CoverLoader covers, TrackLoader loader, BiConsumer<List<TrackRef>, Integer> onPlay,
            Consumer<List<TrackRef>> onAdd) {
        super(new BorderLayout(0, 10));
        this.covers = covers;
        this.loader = loader;
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(WIDTH, 100));

        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        cover.setPreferredSize(new Dimension(COVER, COVER));
        JPanel coverSlot = new JPanel(new BorderLayout());
        coverSlot.setOpaque(false);
        coverSlot.add(cover, BorderLayout.NORTH);
        head.add(coverSlot, BorderLayout.WEST);
        JPanel words = new JPanel(new BorderLayout(0, 4));
        words.setOpaque(false);
        words.add(title, BorderLayout.NORTH);
        words.add(meta, BorderLayout.CENTER);
        head.add(words, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(play);
        buttons.add(queue);
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.CENTER);

        tracks.onPlay(onPlay);
        tracks.onAdd(onAdd);
        play.addActionListener(e -> {
            if (tracks.rowCount() > 0) {
                onPlay.accept(tracks.allRefs(), 0);
            }
        });
        queue.addActionListener(e -> {
            if (tracks.rowCount() > 0) {
                onAdd.accept(tracks.allRefs());
            }
        });
        add(top, BorderLayout.NORTH);
        add(tracks, BorderLayout.CENTER);
        setVisible(false);
        restyle();
        PuristTheme.addListener(s -> restyle());
    }

    /** {@code artist · year · N tr · format}; the year is left out when the album has none. */
    static String meta(AlbumSummary a) {
        StringBuilder sb = new StringBuilder(a.albumArtist());
        if (!a.year().isEmpty()) {
            sb.append(" · ").append(a.year());
        }
        sb.append(" · ").append(a.trackCount()).append(" tr · ").append(a.formatLabel());
        return sb.toString();
    }

    /** Shows {@code album} and loads its tracks; null hides the panel. */
    public void show(AlbumSummary album) {
        current = album;
        tracks.setRows(List.of());
        if (album == null) {
            setVisible(false);
            return;
        }
        String t = album.album().toUpperCase(Locale.ROOT);
        title.setText(t);
        title.setFont(Fonts.forText(t, Fonts.display(15f)));
        String m = meta(album);
        meta.setText(m);
        meta.setFont(Fonts.forText(m, Fonts.mono(11f)));
        cover.set(album);
        setVisible(true);
        loader.load(album, rows -> {
            if (album.equals(current)) {
                tracks.setRows(new ArrayList<>(rows));
                play.setEnabled(!rows.isEmpty());
                queue.setEnabled(!rows.isEmpty());
            }
        });
    }

    public void setPlaying(TrackRef ref) {
        tracks.setPlaying(ref);
    }

    /** The cover may have arrived. */
    public void coverMayHaveArrived() {
        cover.repaint();
    }

    String titleText() {
        return title.getText();
    }

    int trackCount() {
        return tracks.rowCount();
    }

    TextButton playButton() {
        return play;
    }

    TextButton queueButton() {
        return queue;
    }

    JTable table() {
        return tracks.tableForTest();
    }

    private static JTextArea text(java.awt.Font font) {
        JTextArea a = new JTextArea();
        a.setFont(font);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setFocusable(false);
        a.setOpaque(false);
        a.setBorder(BorderFactory.createEmptyBorder());
        return a;
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        setBackground(s.list);
        setOpaque(true);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge),
                new EmptyBorder(10, 10, 10, 10)));
        title.setForeground(s.listText);
        meta.setForeground(s.listDim);
        repaint();
    }

    /** The cover at 70 px: the album's art once the loader has it, else the LCD initials tile. */
    private final class CoverBox extends JComponent {
        private AlbumSummary album;
        private BufferedImage source;
        private BufferedImage scaled;

        void set(AlbumSummary a) {
            album = a;
            source = null;
            scaled = null;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (album == null) {
                return;
            }
            BufferedImage img = covers == null ? null : covers.image(album);
            if (img != null && img != CoverLoader.NO_ART && img != source) {
                source = img;
                scaled = ImageScale.square(img, COVER);
            }
            BufferedImage shown = img != null && img != CoverLoader.NO_ART ? scaled
                    : ThumbnailCache.placeholder(album.album(), COVER);
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(shown, 0, 0, COVER, COVER, null);
                g.setColor(PuristTheme.skin().edge);
                g.drawRect(0, 0, COVER - 1, COVER - 1);
            } finally {
                g.dispose();
            }
        }
    }
}
