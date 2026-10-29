package purist.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import purist.library.LibraryFeed;
import purist.library.ScanProgress;
import purist.playlist.FoobarImport.ImportedPlaylist;
import purist.ui.drives.DriveListModel;
import purist.ui.kit.PlainText;
import purist.ui.kit.ShortcutKeys;
import purist.ui.kit.TextButton;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The LIBRARY section of Settings (spec §8, §9): the watched folders with {@code ADD FOLDER…} and {@code REMOVE},
 * {@code RESCAN}, {@code IMPORT FROM FOOBAR2000} (which says what it found and asks before it imports), the state of
 * the scan, and the last lines of {@code scan-errors.log}. Everything that touches the disk or the database runs
 * off the UI thread. EDT only, except those.
 */
public final class LibrarySettings extends JPanel {

    /** What foobar2000's profile holds that Purist can take over. */
    public record Findings(List<Path> folders, List<ImportedPlaylist> playlists) {
        public Findings {
            folders = List.copyOf(folders);
            playlists = List.copyOf(playlists);
        }

        public boolean isEmpty() {
            return folders.isEmpty() && playlists.isEmpty();
        }
    }

    /** What the section asks of the app. Called off the UI thread. */
    public interface Backend {
        List<Path> folders();

        void addFolder(Path folder);

        void removeFolder(Path folder);

        void rescan();

        /** Reads foobar2000's profile; read-only. */
        Findings foobar();

        /** Adds the folders and playlists of {@code f}. */
        void importFoobar(Findings f);

        /** The last {@code n} lines of the scan error log. */
        List<String> errorTail(int n);
    }

    static final int ERROR_LINES = 20;

    private static final java.util.logging.Logger LOG = purist.app.AppLog.logger(LibrarySettings.class);

    private final Backend backend;
    private final DefaultListModel<Path> folders = new DefaultListModel<>();
    private final JList<Path> list = new JList<>(folders);
    private final JLabel status = PlainText.of(new JLabel(" "));
    private final JTextArea errors = new JTextArea(5, 40);
    private final TextButton add = new TextButton("Add folder…", false);
    private final TextButton remove = new TextButton("Remove", false);
    private final TextButton rescan = new TextButton("Rescan", false);
    private final TextButton importFoobar = new TextButton("Import from foobar2000", false);
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "purist-library-settings");
        t.setDaemon(true);
        return t;
    });
    private final java.util.function.Supplier<Path> folderPicker;

    /** @param folderPicker asks the user for a folder (a chooser); null if they cancel. Runs on the UI thread. */
    public LibrarySettings(Backend backend, java.util.function.Supplier<Path> folderPicker) {
        super(new BorderLayout(0, 8));
        this.backend = backend;
        this.folderPicker = folderPicker;
        setOpaque(false);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(4);
        list.setFont(Fonts.mono(12f));
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            {
                PlainText.of(this);
            }

            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel,
                    boolean focus) {
                super.getListCellRendererComponent(l, v, i, sel, false);
                Skin s = PuristTheme.skin();
                setFont(Fonts.forText(String.valueOf(v), Fonts.mono(12f)));
                setBackground(sel ? s.highlight : s.list);
                setForeground(sel ? s.highlightText : s.listText);
                setBorder(new EmptyBorder(2, 8, 2, 8));
                return this;
            }
        });
        ShortcutKeys.release(list);
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(300, 86));
        ShortcutKeys.release(listScroll);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(add);
        buttons.add(remove);
        buttons.add(rescan);
        buttons.add(importFoobar);

        status.setFont(Fonts.mono(12f));
        errors.setEditable(false);
        errors.setFocusable(false);
        errors.setLineWrap(false);
        errors.setFont(Fonts.mono(11f));
        errors.setBorder(new EmptyBorder(4, 8, 4, 8));
        JScrollPane errorScroll = new JScrollPane(errors);
        errorScroll.setPreferredSize(new Dimension(300, 88));
        ShortcutKeys.release(errorScroll);
        JLabel errorsTitle = PlainText.of(new JLabel("Last scan errors"));
        errorsTitle.setFont(Fonts.mono(11f));
        errorsTitle.setName("errorsTitle");

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(listScroll, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.CENTER);
        top.add(status, BorderLayout.SOUTH);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setOpaque(false);
        bottom.add(errorsTitle, BorderLayout.NORTH);
        bottom.add(errorScroll, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(bottom, BorderLayout.CENTER);

        add.addActionListener(e -> {
            Path p = folderPicker.get();
            if (p != null) {
                background("ADD " + p, () -> backend.addFolder(p));
                if (!folders.contains(p)) {
                    folders.addElement(p);
                }
            }
        });
        remove.addActionListener(e -> removeSelected());
        rescan.addActionListener(e -> rescanNow());
        importFoobar.addActionListener(e -> startImport());
        restyle(errorsTitle);
        PuristTheme.addListener(s -> restyle(errorsTitle));
    }

    /**
     * Runs {@code work} on the background thread. If it fails, the status line says what and why instead of staying
     * on "RESCANNING" or "IMPORTING" for ever, and the failure is logged.
     */
    private void background(String what, Runnable work) {
        try {
            io.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    fail(what, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closing: the window is going away
        }
    }

    private void fail(String what, RuntimeException e) {
        LOG.log(Level.WARNING, what + " failed", e);
        String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        SwingUtilities.invokeLater(() -> {
            status.setText(("FAILED: " + what + ": " + why).toUpperCase(java.util.Locale.ROOT));
            status.setFont(Fonts.forText(status.getText(), Fonts.mono(12f)));
        });
    }

    /** Stops the background thread; the window is closing. */
    public void close() {
        io.shutdownNow();
    }

    /** RESCAN: scans every folder again. */
    void rescanNow() {
        status.setText("RESCANNING");
        background("RESCAN", backend::rescan);
    }

    /** Reads the folder list and the error log again; the section is being shown. */
    public void refresh() {
        background("READ THE LIBRARY FOLDERS", () -> {
            List<Path> now = backend.folders();
            List<String> tail = backend.errorTail(ERROR_LINES);
            SwingUtilities.invokeLater(() -> {
                folders.clear();
                now.forEach(folders::addElement);
                errors.setText(tail.isEmpty() ? "No errors." : String.join("\n", tail));
                errors.setCaretPosition(errors.getDocument().getLength());
            });
        });
    }

    /** A scan reported progress. */
    public void scanProgress(ScanProgress p) {
        if (p != null) {
            status.setText(DriveListModel.progressText(p) + "  " + p.current().getRoot());
        }
    }

    /** A scan ended: the line shows how, and the error log is read again. */
    public void scanFinished(LibraryFeed.Finished f) {
        status.setText("LAST SCAN " + f.folder() + "  " + DriveListModel.resultText(f.result()));
        refresh();
    }

    private void removeSelected() {
        Path p = list.getSelectedValue();
        if (p == null) {
            return;
        }
        int yes = JOptionPane.showConfirmDialog(this, "Take " + p + " out of the library?\n"
                + "Its tracks leave the library. Your files are not touched.", "Remove folder",
                JOptionPane.OK_CANCEL_OPTION);
        if (yes == JOptionPane.OK_OPTION) {
            folders.removeElement(p);
            background("REMOVE " + p, () -> backend.removeFolder(p));
        }
    }

    /** IMPORT FROM FOOBAR2000, for tests. */
    void importNow() {
        startImport();
    }

    private void startImport() {
        status.setText("READING THE FOOBAR2000 PROFILE…");
        background("READ THE FOOBAR2000 PROFILE", () -> {
            Findings f = backend.foobar();
            SwingUtilities.invokeLater(() -> confirmImport(f));
        });
    }

    private void confirmImport(Findings f) {
        if (f.isEmpty()) {
            status.setText("NOTHING FOUND IN THE FOOBAR2000 PROFILE");
            JOptionPane.showMessageDialog(this, "No folders or playlists were found in the foobar2000 profile.",
                    "Import from foobar2000", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int yes = JOptionPane.showConfirmDialog(this, message(f), "Import from foobar2000",
                JOptionPane.OK_CANCEL_OPTION);
        if (yes != JOptionPane.OK_OPTION) {
            status.setText(" ");
            return;
        }
        status.setText("IMPORTING…");
        background("IMPORT FROM FOOBAR2000", () -> {
            backend.importFoobar(f);
            SwingUtilities.invokeLater(() -> status.setText("IMPORTED " + f.folders().size() + " FOLDERS AND "
                    + f.playlists().size() + " PLAYLISTS"));
            refresh();
        });
    }

    /** What the confirmation says: the folders and the playlists, and that foobar2000's files are not touched. */
    static String message(Findings f) {
        StringBuilder sb = new StringBuilder("Import these from foobar2000?\n\nFolders:\n");
        if (f.folders().isEmpty()) {
            sb.append("  (none)\n");
        }
        for (Path p : f.folders()) {
            sb.append("  ").append(p).append('\n');
        }
        sb.append("\nPlaylists:\n");
        if (f.playlists().isEmpty()) {
            sb.append("  (none)\n");
        }
        for (ImportedPlaylist p : f.playlists()) {
            sb.append("  ").append(p.name()).append(" (").append(p.items().size()).append(" tracks)\n");
        }
        sb.append("\nfoobar2000's own files are only read, never changed.");
        return sb.toString();
    }

    /** The folders shown, for tests. */
    List<Path> shownFolders() {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < folders.size(); i++) {
            out.add(folders.get(i));
        }
        return out;
    }

    /** The error log text shown, for tests. */
    String errorText() {
        return errors.getText();
    }

    /** The status line, for tests. */
    String statusText() {
        return status.getText();
    }

    private void restyle(JLabel errorsTitle) {
        Skin s = PuristTheme.skin();
        status.setForeground(s.listDim);
        errorsTitle.setForeground(s.listDim);
        list.setBackground(s.list);
        errors.setBackground(s.list);
        errors.setForeground(s.listDim);
        for (JScrollPane sp : new JScrollPane[] {(JScrollPane) list.getParent().getParent(),
                (JScrollPane) errors.getParent().getParent()}) {
            sp.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge));
            sp.getViewport().setBackground(s.list);
        }
        repaint();
    }
}
