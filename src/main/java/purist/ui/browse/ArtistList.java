package purist.ui.browse;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import purist.library.AlbumSummary;
import purist.ui.kit.PlainText;
import purist.ui.kit.ShortcutKeys;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The album artists (spec §9): a dense list in the playlist's row style, each with its number of albums. The first
 * row is {@code ALL ARTISTS}. Selecting a row filters the album grid. EDT only.
 */
public final class ArtistList extends JPanel {

    /** One row; {@code name} is null for the "all artists" row. */
    public record Entry(String name, int albums) {
    }

    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final JScrollPane scroll = new JScrollPane(list);
    private Consumer<String> onSelect = name -> { };
    private boolean quiet;

    public ArtistList() {
        super(new BorderLayout());
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(26);
        list.setFocusable(true);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !quiet) {
                Entry en = list.getSelectedValue();
                if (en != null) {
                    onSelect.accept(en.name());
                }
            }
        });
        ShortcutKeys.release(list);
        ShortcutKeys.release(scroll);
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(230, 100));
        restyle();
        PuristTheme.addListener(s -> restyle());
    }

    /** One entry per album artist, sorted ignoring case, with how many albums each has. The spelling shown is the first seen. */
    public static List<Entry> entries(List<AlbumSummary> albums) {
        Map<String, String> spelling = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AlbumSummary a : albums) {
            String key = a.albumArtist().toLowerCase(Locale.ROOT);
            spelling.putIfAbsent(key, a.albumArtist());
            counts.merge(key, 1, Integer::sum);
        }
        List<Entry> out = new ArrayList<>();
        for (var e : counts.entrySet()) {
            out.add(new Entry(spelling.get(e.getKey()), e.getValue()));
        }
        out.sort((x, y) -> x.name().toLowerCase(Locale.ROOT).compareTo(y.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Called with the artist chosen, or null for {@code ALL ARTISTS}. */
    public void onSelect(Consumer<String> handler) {
        onSelect = handler;
    }

    /** Replaces the rows (the "all" row is added in front) and keeps the selected artist if it is still there. */
    public void setArtists(List<Entry> entries) {
        Entry before = list.getSelectedValue();
        quiet = true;
        try {
            model.clear();
            int total = 0;
            for (Entry e : entries) {
                total += e.albums();
            }
            model.addElement(new Entry(null, total));
            entries.forEach(model::addElement);
            int at = 0;
            if (before != null && before.name() != null) {
                for (int i = 1; i < model.size(); i++) {
                    if (model.get(i).name().equalsIgnoreCase(before.name())) {
                        at = i;
                    }
                }
            }
            list.setSelectedIndex(at);
        } finally {
            quiet = false;
        }
    }

    /** The selected artist, null for all. */
    public String selectedArtist() {
        Entry e = list.getSelectedValue();
        return e == null ? null : e.name();
    }

    /** Selects the all-artists row. */
    public void selectAll() {
        if (model.size() > 0) {
            list.setSelectedIndex(0);
        }
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        setBackground(s.chassis);
        list.setBackground(s.list);
        scroll.getViewport().setBackground(s.list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge));
        repaint();
    }

    private static final class Renderer extends JPanel implements ListCellRenderer<Entry> {
        private final JLabel name = PlainText.of(new JLabel());
        private final JLabel count = PlainText.of(new JLabel());

        Renderer() {
            super(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(0, 10, 0, 10));
            add(name, BorderLayout.CENTER);
            add(count, BorderLayout.EAST);
            count.setFont(Fonts.mono(12f));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Entry> l, Entry e, int index, boolean selected,
                boolean focus) {
            Skin s = PuristTheme.skin();
            String text = e.name() == null ? "ALL ARTISTS" : e.name();
            name.setText(text);
            name.setFont(Fonts.forText(text, Fonts.mono(13f)));
            count.setText(Integer.toString(e.albums()));
            setOpaque(true);
            setBackground(selected ? s.highlight : s.list);
            name.setForeground(selected ? s.highlightText : s.listText);
            count.setForeground(selected ? s.highlightText : s.listDim);
            return this;
        }
    }
}
