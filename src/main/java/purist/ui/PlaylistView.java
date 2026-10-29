package purist.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.TransferHandler;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumnModel;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.ui.kit.PlainText;
import purist.ui.kit.SectionLabel;
import purist.ui.kit.ShortcutKeys;
import purist.ui.kit.TextButton;
import purist.ui.queue.QueueReorder;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The queue as a dense playlist (spec §9): {@code #.}, title, format, length, with the playing row inverted.
 * Track details are probed on a background thread and fill in as they arrive. An empty queue shows
 * {@code OPEN FILES…} and {@code OPEN FOLDER…}. Double-click plays a row. EDT only, except the probe thread.
 */
public final class PlaylistView extends JPanel {

    private static final String LIST = "list";
    private static final String EMPTY = "empty";

    private final DecoderRegistry registry;
    private final Model model = new Model();
    private final JTable table = new JTable(model) {
        @Override
        public javax.swing.JToolTip createToolTip() {
            return PlainText.of(super.createToolTip()); // a reason may carry file text
        }
    };
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JLabel footer = PlainText.of(new JLabel(" "));
    private final ExecutorService probes = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "purist-queue-probe");
        t.setDaemon(true);
        return t;
    });
    private int generation;

    public PlaylistView(DecoderRegistry registry, IntConsumer play, Runnable openFiles, Runnable openFolder) {
        super(new BorderLayout());
        this.registry = registry;
        setBorder(new EmptyBorder(0, 12, 12, 12));
        table.setFont(Fonts.mono(13f));
        table.setRowHeight(26);
        table.setTableHeader(null);
        table.setShowGrid(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && row >= 0) {
                    play.accept(row);
                }
            }
        });
        if (!java.awt.GraphicsEnvironment.isHeadless()) { // dragging needs a display
            table.setDragEnabled(true);
        }
        table.setDropMode(DropMode.INSERT_ROWS);
        table.setTransferHandler(new RowMover());
        table.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "removeRows");
        table.getActionMap().put("removeRows", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelected();
            }
        });
        TableColumnModel cols = table.getColumnModel();
        cols.getColumn(0).setMaxWidth(48);
        cols.getColumn(2).setPreferredWidth(110);
        cols.getColumn(2).setMaxWidth(140);
        cols.getColumn(3).setMaxWidth(80);
        JScrollPane scroll = new JScrollPane(table);
        // The window-level shortcuts (SPACE pause, arrows seek and skip) must fire whatever has the focus, so the
        // table and its scroll pane give up their own use of those keys.
        ShortcutKeys.release(table);
        ShortcutKeys.release(scroll);
        JPanel list = new JPanel(new BorderLayout());
        list.add(scroll, BorderLayout.CENTER);
        footer.setFont(Fonts.mono(11f));
        footer.setBorder(new EmptyBorder(6, 10, 4, 10));
        list.add(footer, BorderLayout.SOUTH);

        JPanel empty = new JPanel(new GridBagLayout());
        JPanel stack = new JPanel(new BorderLayout(0, 14));
        stack.setOpaque(false);
        stack.add(new SectionLabel("Queue is empty"), BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.setOpaque(false);
        TextButton files = new TextButton("Open files…", true);
        files.addActionListener(e -> openFiles.run());
        TextButton folder = new TextButton("Open folder…", false);
        folder.addActionListener(e -> openFolder.run());
        buttons.add(files);
        buttons.add(folder);
        stack.add(buttons, BorderLayout.CENTER);
        empty.add(stack);

        body.add(list, LIST);
        body.add(empty, EMPTY);
        add(body, BorderLayout.CENTER);
        restyle();
        PuristTheme.addListener(s -> restyle());
        setQueue(List.of());
    }

    /** The keys MainWindow binds for the whole window: pause, seek, previous and next. */
    static final KeyStroke[] SHORTCUT_KEYS = ShortcutKeys.KEYS;

    /** Told, after the list on screen has changed, what the user did to it (drag to reorder, Delete). */
    public interface EditListener {
        /** The rows at {@code rows} were dropped before row {@code to} (of the list as it was). */
        void moved(int[] rows, int to);

        /** The rows at {@code rows} were taken out. */
        void removed(int[] rows);
    }

    private EditListener edits = new EditListener() {
        @Override
        public void moved(int[] rows, int to) {
        }

        @Override
        public void removed(int[] rows) {
        }
    };

    public void setEditListener(EditListener l) {
        edits = l;
    }

    /** What a drop of rows does: reorders the list, then reports it. */
    void dropRows(int[] rows, int to) {
        if (rows.length == 0) {
            return;
        }
        int[] map = QueueReorder.oldToNewAfterMove(model.rows.size(), rows, to);
        moveRows(rows, to);
        table.clearSelection();
        for (int old : rows) {
            if (old >= 0 && old < map.length) {
                table.addRowSelectionInterval(map[old], map[old]);
            }
        }
        edits.moved(rows, to);
    }

    /** Delete: takes out the selected rows, except the one that is playing (it goes on until the user stops it). */
    void removeSelected() {
        int[] sel = table.getSelectedRows();
        List<Integer> keep = new ArrayList<>();
        for (int r : sel) {
            if (r != model.playing) {
                keep.add(r);
            }
        }
        if (keep.isEmpty()) {
            return;
        }
        int[] rows = keep.stream().mapToInt(Integer::intValue).toArray();
        removeRows(rows);
        edits.removed(rows);
    }

    /** Reorders the rows on screen like {@code Playlist.move}; the probed details travel with their rows. */
    public void moveRows(int[] rows, int to) {
        int[] map = QueueReorder.oldToNewAfterMove(model.rows.size(), rows, to);
        List<Row> moved = QueueReorder.move(model.rows, rows, to);
        model.playing = model.playing >= 0 && model.playing < map.length ? map[model.playing] : model.playing;
        model.rows.clear();
        model.rows.addAll(moved);
        model.fireTableDataChanged();
    }

    /** Takes the rows out like {@code Playlist.remove}. */
    public void removeRows(int[] rows) {
        int[] map = QueueReorder.oldToNewAfterRemove(model.rows.size(), rows);
        model.playing = QueueReorder.adjustPlaying(model.playing, rows);
        List<Row> kept = new ArrayList<>();
        for (int i = 0; i < map.length; i++) {
            if (map[i] >= 0) {
                kept.add(model.rows.get(i));
            }
        }
        model.rows.clear();
        model.rows.addAll(kept);
        model.fireTableDataChanged();
        cards.show(body, model.rows.isEmpty() ? EMPTY : LIST);
        updateFooter();
    }

    /** The titles of the rows in order, for tests. */
    List<String> titles() {
        List<String> out = new ArrayList<>();
        for (Row r : model.rows) {
            out.add(r.title());
        }
        return out;
    }

    /** Drags rows by their numbers: the transferable holds the row indices, so a drop only reads them back. */
    private final class RowMover extends TransferHandler {
        private final DataFlavor flavor = new DataFlavor(int[].class, "Purist queue rows");

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            int[] rows = table.getSelectedRows();
            return rows.length == 0 ? null : new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {flavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor f) {
                    return flavor.equals(f);
                }

                @Override
                public Object getTransferData(DataFlavor f) {
                    return rows;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(flavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                int[] rows = (int[]) support.getTransferable().getTransferData(flavor);
                dropRows(rows, ((JTable.DropLocation) support.getDropLocation()).getRow());
                return true;
            } catch (UnsupportedFlavorException | IOException | RuntimeException e) {
                return false;
            }
        }
    }

    /** Test access. */
    JTable table() {
        return table;
    }

    /** Replaces the rows and starts probing them for title, format and length. */
    public void setQueue(List<TrackRef> refs) {
        generation++;
        model.rows.clear();
        model.fireTableDataChanged();
        addRows(refs);
    }

    /** Adds {@code refs} at the end and probes only them; the rows already shown keep what was probed. */
    public void append(List<TrackRef> refs) {
        addRows(refs);
    }

    private void addRows(List<TrackRef> refs) {
        int gen = generation;
        int first = model.rows.size();
        for (TrackRef r : refs) {
            model.rows.add(new Row(r, model.rows.size()));
        }
        if (!refs.isEmpty()) {
            model.fireTableRowsInserted(first, first + refs.size() - 1);
        }
        cards.show(body, model.rows.isEmpty() ? EMPTY : LIST);
        updateFooter();
        for (int i = 0; i < refs.size(); i++) {
            Row row = model.rows.get(first + i); // the Row itself: its index changes when rows move or go
            TrackRef ref = refs.get(i);
            probes.execute(() -> {
                TrackInfo info = probe(ref);
                SwingUtilities.invokeLater(() -> {
                    int at = gen == generation ? indexOfRow(row) : -1;
                    if (at >= 0) {
                        row.info = info;
                        model.fireTableRowsUpdated(at, at);
                        updateFooter();
                    }
                });
            });
        }
    }

    /** Where {@code row} is now, or -1 if it has been taken out (by identity: two rows may hold the same track). */
    private int indexOfRow(Row row) {
        // Nearly always still where it was added (or where it was last found), so a large import is not O(n^2).
        if (row.hint >= 0 && row.hint < model.rows.size() && model.rows.get(row.hint) == row) {
            return row.hint;
        }
        for (int i = 0; i < model.rows.size(); i++) {
            if (model.rows.get(i) == row) {
                row.hint = i;
                return i;
            }
        }
        return -1;
    }

    /** Marks {@code index} as playing (-1 for none). */
    public void setPlaying(int index) {
        if (index != model.playing) {
            int old = model.playing;
            model.playing = index;
            if (old >= 0 && old < model.rows.size()) {
                model.fireTableRowsUpdated(old, old);
            }
            if (index >= 0 && index < model.rows.size()) {
                model.fireTableRowsUpdated(index, index);
                table.scrollRectToVisible(table.getCellRect(index, 0, true));
            }
        }
    }

    private TrackInfo probe(TrackRef ref) {
        try {
            for (TrackInfo t : registry.probe(ref.path())) {
                if (t.subsong() == ref.subsong()) {
                    return t;
                }
            }
        } catch (IOException | RuntimeException e) {
            // The row keeps its file name; playing it reports the real error.
        }
        return null;
    }

    private void updateFooter() {
        double total = 0;
        for (Row r : model.rows) {
            total += r.seconds();
        }
        int n = model.rows.size();
        footer.setText(n + (n == 1 ? " track" : " tracks") + " · " + Deck.time(total));
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        setBackground(s.chassis);
        body.setBackground(s.chassis);
        for (Component c : body.getComponents()) {
            c.setBackground(s.chassis);
        }
        table.setBackground(s.list);
        table.setForeground(s.listText);
        footer.setForeground(s.listDim);
        footer.setOpaque(true);
        footer.setBackground(s.list);
        if (table.getParent() != null && table.getParent().getParent() instanceof JScrollPane sp) {
            sp.setBorder(BorderFactory.createMatteBorder(1, 1, 0, 1, s.edge));
            sp.getViewport().setBackground(s.list);
        }
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge),
                new EmptyBorder(6, 10, 4, 10)));
        repaint();
    }

    private static final class Row {
        final TrackRef ref;
        volatile TrackInfo info;
        /** Where the row was last known to be; only a first guess for {@link #indexOfRow}. Touched on the EDT. */
        int hint;

        Row(TrackRef ref, int hint) {
            this.ref = ref;
            this.hint = hint;
        }

        String title() {
            if (info != null) {
                var t = info.tags().first("TITLE");
                if (t.isPresent()) {
                    return info.tags().first("ARTIST").map(a -> a + " — " + t.get()).orElse(t.get());
                }
            }
            String f = ref.path().getFileName().toString();
            int dot = f.lastIndexOf('.');
            return (dot > 0 ? f.substring(0, dot) : f) + (ref.subsong() > 0 ? " #" + ref.subsong() : "");
        }

        double seconds() {
            if (info == null || info.format() == null || info.format().lengthFrames() <= 0) {
                return 0;
            }
            return info.format().seconds(info.format().lengthFrames());
        }
    }

    private static final class Model extends AbstractTableModel {
        final List<Row> rows = new ArrayList<>();
        int playing = -1;

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            return switch (c) {
                case 0 -> (r + 1) + ".";
                case 1 -> row.title();
                case 2 -> row.info == null ? "" : row.info.supported() ? row.info.badge() : row.info.codec();
                default -> row.seconds() > 0 ? Deck.time(row.seconds()) : "";
            };
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {
        Renderer() {
            PlainText.of(this); // titles come from tags: draw `<html>…` literally
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                int row, int col) {
            super.getTableCellRendererComponent(t, value, false, false, row, col);
            Skin s = PuristTheme.skin();
            String text = String.valueOf(value);
            setFont(Fonts.forText(text, Fonts.mono(13f)));
            setHorizontalAlignment(col >= 2 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            setBorder(new EmptyBorder(0, col == 0 ? 10 : 6, 0, col == 3 ? 10 : 6));
            Row r = model.rows.get(row);
            boolean unplayable = r.info != null && !r.info.supported();
            if (row == model.playing) {
                setBackground(s.highlight);
                setForeground(s.highlightText);
            } else {
                setBackground(selected ? s.chassis : s.list);
                setForeground(unplayable || col >= 2 || col == 0 ? s.listDim : s.listText);
            }
            setToolTipText(unplayable ? r.info.reason() : null);
            return this;
        }
    }
}
