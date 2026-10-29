package purist.ui.kit;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolTip;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.ui.kit.TrackTableModel.Column;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * A dense track table in the playlist's row style (spec §9): no header, no grid, Plex Mono rows, the playing row
 * inverted and the rows of unplayable files dim. It backs the search results, a folder's files and an album's
 * track list. Double-click or Enter plays the list from that row; the context menu adds the selection to the queue.
 * Every cell draws its text literally ({@link PlainText}). EDT only.
 */
public final class TrackTable extends JPanel {

    private final TrackTableModel model;
    private final JTable table;
    private final JScrollPane scroll;
    private final int fontPx;
    private BiConsumer<List<TrackRef>, Integer> onPlay = (refs, from) -> { };
    private Consumer<List<TrackRef>> onAdd = refs -> { };

    /**
     * @param fontPx    the row font size; 13 for a full table, 12 for the compact list beside an album
     * @param rowHeight the row height in px
     */
    public TrackTable(List<Column> columns, int fontPx, int rowHeight) {
        super(new BorderLayout());
        this.fontPx = fontPx;
        model = new TrackTableModel(columns);
        table = new JTable(model) {
            @Override
            public JToolTip createToolTip() {
                return PlainText.of(super.createToolTip()); // a reason may carry file text
            }
        };
        table.setFont(Fonts.mono(fontPx));
        table.setRowHeight(rowHeight);
        table.setTableHeader(null);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        sizeColumns(columns);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1 && row >= 0) {
                    playFrom(row);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "playRow");
        table.getActionMap().put("playRow", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    playFrom(row);
                }
            }
        });
        scroll = new JScrollPane(table);
        ShortcutKeys.release(table);
        ShortcutKeys.release(scroll);
        add(scroll, BorderLayout.CENTER);
        restyle();
        PuristTheme.addListener(s -> restyle());
    }

    /** Called with the whole list and the row to start from when the user plays. */
    public void onPlay(BiConsumer<List<TrackRef>, Integer> handler) {
        onPlay = handler;
    }

    /** Called with the tracks to append when the user adds some to the queue. */
    public void onAdd(Consumer<List<TrackRef>> handler) {
        onAdd = handler;
    }

    public void setRows(List<TrackInfo> rows) {
        model.setRows(rows);
    }

    public void setPlaying(TrackRef ref) {
        model.setPlaying(ref);
    }

    public int rowCount() {
        return model.getRowCount();
    }

    /** Every row as a queue entry, in order. */
    public List<TrackRef> allRefs() {
        List<TrackRef> out = new ArrayList<>(model.getRowCount());
        for (int i = 0; i < model.getRowCount(); i++) {
            out.add(model.ref(i));
        }
        return out;
    }

    /** The selected rows as queue entries, in table order. */
    public List<TrackRef> selectedRefs() {
        List<TrackRef> out = new ArrayList<>();
        for (int i : table.getSelectedRows()) {
            out.add(model.ref(i));
        }
        return out;
    }

    /** The table, for tests (which live in other packages). */
    public JTable tableForTest() {
        return table;
    }

    /** Test access. */
    JTable table() {
        return table;
    }

    /** Test access. */
    TrackTableModel model() {
        return model;
    }

    private void playFrom(int row) {
        onPlay.accept(allRefs(), row);
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        if (!table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem play = new JMenuItem("Play from here");
        play.addActionListener(a -> playFrom(row));
        JMenuItem add = new JMenuItem(table.getSelectedRowCount() > 1 ? "Add selection to queue" : "Add to queue");
        add.addActionListener(a -> onAdd.accept(selectedRefs()));
        menu.add(play);
        menu.add(add);
        menu.show(table, e.getX(), e.getY());
    }

    private void sizeColumns(List<Column> columns) {
        TableColumnModel cols = table.getColumnModel();
        for (int i = 0; i < columns.size(); i++) {
            var c = cols.getColumn(i);
            switch (columns.get(i)) {
                case PLAYING -> fixed(c, 26);
                case NUMBER -> fixed(c, 40);
                case LENGTH -> fixed(c, 64);
                case FORMAT -> {
                    c.setPreferredWidth(112);
                    c.setMaxWidth(150);
                }
                case TITLE -> c.setPreferredWidth(320);
                case ARTIST -> c.setPreferredWidth(200);
                case ALBUM -> c.setPreferredWidth(220);
            }
        }
    }

    private static void fixed(javax.swing.table.TableColumn c, int w) {
        c.setMinWidth(w);
        c.setMaxWidth(w);
        c.setPreferredWidth(w);
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        setBackground(s.chassis);
        table.setBackground(s.list);
        table.setForeground(s.listText);
        scroll.getViewport().setBackground(s.list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge));
        repaint();
    }

    private final class Renderer extends DefaultTableCellRenderer {
        Renderer() {
            PlainText.of(this); // titles come from tags: draw `<html>...` literally
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                int row, int col) {
            super.getTableCellRendererComponent(t, value, false, false, row, col);
            Skin s = PuristTheme.skin();
            String text = String.valueOf(value);
            Column kind = model.column(col);
            setFont(Fonts.forText(text, Fonts.mono(fontPx)));
            boolean right = kind == Column.LENGTH || kind == Column.FORMAT;
            setHorizontalAlignment(right ? SwingConstants.RIGHT : SwingConstants.LEFT);
            boolean first = col == 0;
            boolean last = col == model.getColumnCount() - 1;
            setBorder(new EmptyBorder(0, first ? 10 : 6, 0, last ? 10 : 6));
            boolean unplayable = model.isUnsupported(row);
            if (model.isPlaying(row)) {
                setBackground(s.highlight);
                setForeground(s.highlightText);
            } else {
                setBackground(selected ? s.chassis : s.list);
                boolean dim = unplayable || right || kind == Column.NUMBER || kind == Column.PLAYING;
                setForeground(dim ? s.listDim : s.listText);
            }
            setToolTipText(unplayable ? model.row(row).reason() : null);
            return this;
        }
    }
}
