package purist.ui.browse;

import java.awt.BorderLayout;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import purist.ui.kit.PlainText;
import purist.ui.kit.ShortcutKeys;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The watched folders as a tree (spec §9), for material the tags do not describe. The roots are the library's
 * folders; a folder's sub-folders are listed the first time it is opened, on the {@code io} executor, never on
 * the UI thread (a spun-down HDD can take seconds to answer). Selecting a folder reports it. EDT only, except
 * the listing.
 */
public final class FolderTree extends JPanel {

    private static final String LOADING = "…";

    private enum State { NOT_LOADED, LOADING, LOADED }

    /** A folder; every unloaded one holds a placeholder child so that the tree shows its expand handle. */
    private static final class Node extends DefaultMutableTreeNode {
        final Path path;
        final String label;
        State state = State.NOT_LOADED;

        Node(Path path, String label) {
            super(label);
            this.path = path;
            this.label = label;
            add(new DefaultMutableTreeNode(LOADING, false));
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("folders");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JScrollPane scroll = new JScrollPane(tree);
    private final Executor io;
    private Consumer<Path> onSelect = p -> { };
    private boolean quiet;
    private int generation;

    public FolderTree(Executor io) {
        super(new BorderLayout());
        this.io = io;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setToggleClickCount(2);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                if (e.getPath().getLastPathComponent() instanceof Node n) {
                    load(n);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (!quiet && tree.getLastSelectedPathComponent() instanceof Node n) {
                onSelect.accept(n.path);
            }
        });
        ShortcutKeys.release(tree);
        ShortcutKeys.release(scroll);
        add(scroll, BorderLayout.CENTER);
        restyle();
        PuristTheme.addListener(s -> restyle());
    }

    private List<Path> shownRoots;

    /** Called with the folder the user selected. */
    public void onSelect(Consumer<Path> handler) {
        onSelect = handler;
    }

    /**
     * Replaces the roots; the selected folder is kept selected if it is still a root. The same roots again change
     * nothing, so a library that keeps changing under a long scan does not fold the tree the user has opened.
     */
    public void setRoots(List<Path> roots) {
        if (roots.equals(shownRoots)) {
            return;
        }
        shownRoots = List.copyOf(roots);
        generation++;
        Path before = selectedFolder();
        quiet = true;
        try {
            root.removeAllChildren();
            Node keep = null;
            for (Path p : roots) {
                Node n = new Node(p, p.toString());
                root.add(n);
                if (p.equals(before)) {
                    keep = n;
                }
            }
            model.nodeStructureChanged(root);
            if (keep != null) {
                tree.setSelectionPath(new TreePath(keep.getPath()));
            }
        } finally {
            quiet = false;
        }
    }

    /** The selected folder; null for none. */
    public Path selectedFolder() {
        return tree.getLastSelectedPathComponent() instanceof Node n ? n.path : null;
    }

    /** Test access. */
    JTree tree() {
        return tree;
    }

    private void load(Node n) {
        if (n.state != State.NOT_LOADED) {
            return;
        }
        n.state = State.LOADING;
        int gen = generation;
        io.execute(() -> {
            List<Path> dirs = subfolders(n.path);
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return; // the roots were replaced meanwhile
                }
                n.removeAllChildren();
                for (Path d : dirs) {
                    Path name = d.getFileName();
                    n.add(new Node(d, name == null ? d.toString() : name.toString()));
                }
                n.state = State.LOADED;
                model.nodeStructureChanged(n);
            });
        });
    }

    /**
     * The directories directly inside {@code folder}, sorted ignoring case; empty if it cannot be read. The
     * recycle bin and {@code System Volume Information} of a drive root are left out.
     */
    static List<Path> subfolders(Path folder) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path e : entries) {
                Path name = e.getFileName();
                if (name == null || isSystemFolder(name.toString())) {
                    continue;
                }
                if (Files.isDirectory(e)) {
                    out.add(e);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        out.sort((a, b) -> a.getFileName().toString().toLowerCase(Locale.ROOT)
                .compareTo(b.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean isSystemFolder(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("$recycle.bin") || n.equals("system volume information");
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        setBackground(s.chassis);
        tree.setBackground(s.list);
        scroll.getViewport().setBackground(s.list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge));
        repaint();
    }

    private static final class Renderer extends DefaultTreeCellRenderer {
        Renderer() {
            PlainText.of(this); // folder names are file text
            setLeafIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
            setBorder(new EmptyBorder(0, 4, 0, 4));
        }

        @Override
        public Component getTreeCellRendererComponent(javax.swing.JTree t, Object value, boolean selected,
                boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, false);
            Skin s = PuristTheme.skin();
            String text = String.valueOf(value);
            setFont(Fonts.forText(text, Fonts.mono(13f)));
            setOpaque(true);
            setBackground(selected ? s.highlight : s.list);
            setForeground(selected ? s.highlightText : text.equals(LOADING) ? s.listDim : s.listText);
            return this;
        }
    }
}
