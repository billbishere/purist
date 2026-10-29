package purist.ui.drives;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import purist.library.LibraryFeed;
import purist.library.ScanProgress;
import purist.ui.drives.DriveListModel.Drive;
import purist.ui.drives.DriveListModel.Row;
import purist.ui.kit.PlainText;
import purist.ui.kit.SectionLabel;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The drives view (spec §8, §9): a check box for every fixed NTFS drive. Ticking one adds it as a watched root and
 * the library service scans it, one drive at a time; the row shows {@code SCANNING 12,345 / 40,000}, then the
 * result. Unticking asks first, because the drive's tracks leave the library (the files are not touched). A line
 * says whether Everything is running, because that decides how fast a drive is scanned, and the notice for a drive
 * Everything does not index is shown here too. Listing the drives and asking Everything run off the UI thread.
 * EDT only, except those.
 */
public final class DrivesView extends JPanel {

    /** What the view asks of the app. Called off the UI thread. */
    public interface Backend {
        List<Drive> drives();

        /** Whether Everything (voidtools) is running with its index loaded. */
        boolean everythingRunning();

        /** The library's watched roots. */
        List<Path> watched();

        /** Adds {@code drive} to the library and scans it. */
        void add(Path drive);

        /** Takes {@code drive} out of the library. */
        void remove(Path drive);
    }

    private static final java.util.logging.Logger LOG = purist.app.AppLog.logger(DrivesView.class);

    static final String HINT = "Tick the drives that hold your music.";

    private final Backend backend;
    private final DriveListModel model = new DriveListModel();
    private final JPanel list = new JPanel();
    private final JScrollPane scroll = new JScrollPane(list);
    private final JLabel hint = PlainText.of(new JLabel(HINT));
    private final JLabel everything = PlainText.of(new JLabel(" "));
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "purist-drives");
        t.setDaemon(true);
        return t;
    });
    private List<Row> shown = List.of();
    private int generation;

    public DrivesView(Backend backend) {
        this(backend, true);
    }

    /** @param firstRun whether the library was empty at start-up: only then is the hint to tick drives shown */
    public DrivesView(Backend backend, boolean firstRun) {
        super(new BorderLayout(0, 10));
        this.backend = backend;
        hint.setVisible(firstRun);
        setBorder(new EmptyBorder(0, 12, 12, 12));
        setOpaque(false);
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        SectionLabel title = new SectionLabel("Drives");
        for (JLabel l : new JLabel[] {title, hint, everything}) {
            l.setAlignmentX(LEFT_ALIGNMENT);
        }
        hint.setFont(Fonts.mono(13f));
        everything.setFont(Fonts.mono(12f));
        head.add(title);
        head.add(Box.createVerticalStrut(6));
        head.add(hint);
        head.add(Box.createVerticalStrut(4));
        head.add(everything);
        add(head, BorderLayout.NORTH);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        add(scroll, BorderLayout.CENTER);
        restyle();
        PuristTheme.addListener(s -> restyle());
    }

    /** Lists the drives again, asks whether Everything runs, and shows both. The view is being opened. */
    public void refresh() {
        int gen = ++generation;
        background("list the drives", () -> {
            List<Drive> drives = backend.drives();
            List<Path> watched = backend.watched();
            boolean running = backend.everythingRunning();
            SwingUtilities.invokeLater(() -> {
                if (gen != generation) {
                    return;
                }
                model.setDrives(drives);
                model.setWatched(watched);
                everything.setText(running
                        ? "EVERYTHING IS RUNNING: DRIVES ARE LISTED FROM ITS INDEX."
                        : "EVERYTHING IS NOT RUNNING: DRIVES ARE SCANNED FOLDER BY FOLDER, WHICH TAKES LONGER.");
                render();
            });
        });
    }

    /** Runs {@code work} on the background thread; a failure is logged, not left to kill the thread silently. */
    private void background(String what, Runnable work) {
        try {
            io.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOG.log(java.util.logging.Level.WARNING, "Drives view: could not " + what, e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closing
        }
    }

    /** Stops the background thread; the window is closing. */
    public void close() {
        io.shutdownNow();
    }

    /** A scan reported progress. */
    public void scanProgress(ScanProgress p) {
        if (p != null) {
            model.progress(p);
            render();
        }
    }

    /** A scan ended. */
    public void scanFinished(LibraryFeed.Finished f) {
        model.finished(f.folder(), f.result());
        render();
    }

    /** The rows as shown, for tests. */
    List<Row> rows() {
        return model.rows();
    }

    private void render() {
        List<Row> rows = model.rows();
        if (rows.equals(shown)) {
            return;
        }
        shown = rows;
        list.removeAll();
        for (Row r : rows) {
            list.add(rowPanel(r));
        }
        list.add(Box.createVerticalGlue());
        list.revalidate();
        list.repaint();
    }

    private JPanel rowPanel(Row r) {
        Skin s = PuristTheme.skin();
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(true);
        p.setBackground(s.list);
        p.setBorder(new EmptyBorder(4, 10, 4, 10));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, r.notice().isEmpty() ? 34 : 54));
        JCheckBox box = PlainText.of(new JCheckBox(DriveListModel.title(r.drive())));
        box.setOpaque(false);
        box.setFocusable(false);
        box.setFocusPainted(false);
        box.setFont(Fonts.forText(box.getText(), Fonts.mono(13f)));
        box.setForeground(s.listText);
        box.setSelected(r.ticked());
        box.addActionListener(e -> toggled(r.drive(), box));
        JLabel status = PlainText.of(new JLabel(r.status()));
        status.setFont(Fonts.mono(12f));
        status.setForeground(s.listDim);
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        box.setAlignmentX(LEFT_ALIGNMENT);
        left.add(box);
        if (!r.notice().isEmpty()) {
            JLabel note = PlainText.of(new JLabel(r.notice()));
            note.setFont(Fonts.forText(r.notice(), Fonts.mono(12f)));
            note.setForeground(s.listDim);
            note.setAlignmentX(LEFT_ALIGNMENT);
            note.setBorder(new EmptyBorder(0, 24, 0, 0));
            left.add(note);
        }
        p.add(left, BorderLayout.WEST);
        p.add(status, BorderLayout.EAST);
        return p;
    }

    private void toggled(Drive d, JCheckBox box) {
        Path root = d.root();
        if (box.isSelected()) {
            model.tick(root);
            background("add " + root, () -> backend.add(root));
            render();
            return;
        }
        int yes = JOptionPane.showConfirmDialog(this, "Take " + root + " out of the library?\n"
                + "Its tracks leave the library. Your files are not touched.", "Remove drive",
                JOptionPane.OK_CANCEL_OPTION);
        if (yes == JOptionPane.OK_OPTION) {
            model.untick(root);
            background("remove " + root, () -> backend.remove(root));
        } else {
            box.setSelected(true);
        }
        render();
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        setBackground(s.chassis);
        hint.setForeground(s.listText);
        everything.setForeground(s.listDim);
        list.setBackground(s.list);
        scroll.getViewport().setBackground(s.list);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge));
        shown = List.of(); // rows hold skin colours: draw them again
        if (isShowing() || !model.rows().isEmpty()) {
            render();
        }
    }

    /** Whether the first-run hint is showing, for tests. */
    boolean hintShown() {
        return hint.isVisible();
    }

    /** The Everything status line, for tests. */
    String everythingText() {
        return everything.getText();
    }

    /** The check box of row {@code i}, for tests. */
    JCheckBox box(int i) {
        JPanel left = (JPanel) ((JPanel) list.getComponent(i)).getComponent(0);
        return (JCheckBox) left.getComponent(0);
    }
}
