package purist.ui.queue;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import purist.playlist.Playlist;
import purist.playlist.PlaylistStore;
import purist.ui.PlaylistView;
import purist.ui.kit.PlainText;
import purist.ui.kit.TextButton;

/**
 * The queue (spec §9): the playlists as a row of small text buttons (the selected one filled) with a {@code +} that
 * makes a new one, and under them the {@link PlaylistView} of the selected playlist. Right-click a tab to rename or
 * delete it. The window decides what a selection or a deletion means for the player; this class keeps the store and
 * the tabs. EDT only.
 */
public final class QueueView extends JPanel {

    /** What the window must know about. */
    public interface Listener {
        /** The user picked a tab (or a new playlist was made and picked). */
        void selected(Playlist p);

        /** {@code p} was deleted; the tab that replaced it is already selected. */
        void deleted(Playlist p);
    }

    private final PlaylistStore store;
    private final Listener listener;
    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JScrollPane tabScroll = new JScrollPane(tabs, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    private Playlist current;

    /**
     * @param initial the playlist to show first; the first one if null or no longer in the store
     */
    public QueueView(PlaylistStore store, PlaylistView list, Playlist initial, Listener listener) {
        super(new BorderLayout(0, 10));
        this.store = store;
        this.listener = listener;
        setOpaque(false);
        setBorder(new EmptyBorder(0, 12, 12, 12));
        list.setBorder(null);
        tabs.setOpaque(false);
        tabScroll.setOpaque(false);
        tabScroll.getViewport().setOpaque(false);
        tabScroll.setBorder(null);
        add(tabScroll, BorderLayout.NORTH);
        add(list, BorderLayout.CENTER);
        List<Playlist> all = store.all();
        current = initial != null && all.contains(initial) ? initial : all.get(0);
        rebuild();
    }

    public Playlist current() {
        return current;
    }

    /** Shows {@code p} as the selected tab without telling the listener. */
    public void select(Playlist p) {
        current = p;
        rebuild();
    }

    /** Draws the tabs again: playlists were added to the store from outside (the foobar2000 import). */
    public void refresh() {
        rebuild();
    }

    /** The names on the tabs, in order. */
    public List<String> tabNames() {
        List<String> out = new ArrayList<>();
        for (Playlist p : store.all()) {
            out.add(p.name());
        }
        return out;
    }

    /** The {@code +} button: a new empty playlist named {@code Playlist N}, selected. */
    public Playlist newPlaylist() {
        Set<String> used = new HashSet<>(tabNames());
        int n = store.all().size() + 1;
        while (used.contains("Playlist " + n)) {
            n++;
        }
        Playlist p = store.create("Playlist " + n);
        current = p;
        rebuild();
        listener.selected(p);
        return p;
    }

    /** Renames {@code p}; a blank name is ignored. */
    public void rename(Playlist p, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        p.rename(name);
        store.changed(p);
        rebuild();
    }

    /**
     * Deletes {@code p} unless it is the last playlist. The tab next to it is selected and the listener told.
     *
     * @return whether it was deleted
     */
    public boolean delete(Playlist p) {
        List<Playlist> all = store.all();
        if (all.size() <= 1) {
            return false;
        }
        int at = all.indexOf(p);
        store.delete(p.id());
        List<Playlist> left = store.all();
        boolean wasCurrent = p.equals(current);
        if (wasCurrent) {
            current = left.get(Math.max(0, Math.min(at, left.size() - 1)));
        }
        rebuild();
        listener.deleted(p);
        if (wasCurrent) {
            listener.selected(current);
        }
        return true;
    }

    private void pick(Playlist p) {
        if (p.equals(current)) {
            return;
        }
        current = p;
        rebuild();
        listener.selected(p);
    }

    private void rebuild() {
        tabs.removeAll();
        for (Playlist p : store.all()) {
            TextButton b = PlainText.of(new TextButton(p.name(), false));
            b.setSelected(p.equals(current));
            b.addActionListener(e -> pick(p));
            b.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    menu(e, p);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    menu(e, p);
                }
            });
            tabs.add(b);
        }
        TextButton plus = new TextButton("+", false);
        plus.setToolTipText("New playlist");
        plus.addActionListener(e -> newPlaylist());
        tabs.add(plus);
        tabs.revalidate();
        tabs.repaint();
    }

    private void menu(MouseEvent e, Playlist p) {
        if (!e.isPopupTrigger()) {
            return;
        }
        JPopupMenu m = new JPopupMenu();
        JMenuItem rename = new JMenuItem("Rename…");
        rename.addActionListener(a -> {
            Object name = JOptionPane.showInputDialog(this, "Playlist name", "Rename playlist",
                    JOptionPane.PLAIN_MESSAGE, null, null, p.name());
            if (name != null) {
                rename(p, name.toString());
            }
        });
        JMenuItem del = new JMenuItem("Delete");
        del.setEnabled(store.all().size() > 1);
        del.addActionListener(a -> {
            int yes = JOptionPane.showConfirmDialog(this, "Delete the playlist \"" + p.name() + "\"?",
                    "Delete playlist", JOptionPane.OK_CANCEL_OPTION);
            if (yes == JOptionPane.OK_OPTION) {
                delete(p);
            }
        });
        m.add(rename);
        m.add(del);
        m.show(e.getComponent(), e.getX(), e.getY());
    }
}
