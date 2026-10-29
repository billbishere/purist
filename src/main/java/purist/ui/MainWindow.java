package purist.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;
import purist.app.AppLibrary;
import purist.app.AppLog;
import purist.app.BackgroundSaver;
import purist.app.Profile;
import purist.app.Settings;
import purist.asio.AsioDriverInfo;
import purist.asio.AsioDriverList;
import purist.asio.AsioSink;
import purist.audio.AudioDeviceException;
import purist.audio.AudioSink;
import purist.audio.NotBitPerfectException;
import purist.audio.SinkStream;
import purist.audio.StreamFormat;
import purist.decode.DecoderRegistry;
import purist.library.EverythingSearch;
import purist.library.LibraryFeed;
import purist.library.ScanProgress;
import purist.player.Player;
import purist.player.PlayerState;
import purist.player.PlayerStatus;
import purist.player.RepeatMode;
import purist.playlist.FoobarImport;
import purist.playlist.Playlist;
import purist.playlist.PlaylistStore;
import purist.playlist.TrackRef;
import purist.ui.browse.BrowseView;
import purist.ui.drives.Drives;
import purist.ui.kit.ShortcutKeys;
import purist.ui.drives.DrivesView;
import purist.ui.drives.DriveListModel.Drive;
import purist.ui.queue.QueueReorder;
import purist.ui.queue.QueueView;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;
import purist.wasapi.WasapiDevice;
import purist.wasapi.WasapiDeviceList;
import purist.wasapi.WasapiSink;

/**
 * The Purist window (spec §9): the deck on top, and below it the left index and one content area showing the
 * library (albums, artists, folders), the queue or settings. It owns the Player and the output sink: an ASIO driver
 * or a WASAPI device in exclusive mode. EDT only.
 */
public final class MainWindow {

    /**
     * The entries of the left index, in order. {@code digit} is the Ctrl+digit that opens one; a divider is drawn
     * above the entries that have {@code dividerBefore}.
     */
    public enum View {
        ALBUMS("Albums", 1, false),
        ARTISTS("Artists", 2, false),
        FOLDERS("Folders", 3, false),
        DRIVES("Drives", 4, false),
        QUEUE("Queue", 5, true),
        SETTINGS("Settings", 6, false);

        private final String label;
        private final int digit;
        private final boolean dividerBefore;

        View(String label, int digit, boolean dividerBefore) {
            this.label = label;
            this.digit = digit;
            this.dividerBefore = dividerBefore;
        }

        public String label() {
            return label;
        }

        public int digit() {
            return digit;
        }

        public boolean dividerBefore() {
            return dividerBefore;
        }

        /** The view named by a settings value; null when it is missing or unknown. */
        static View fromSetting(String value) {
            if (value != null) {
                for (View v : values()) {
                    if (v.name().equalsIgnoreCase(value.trim())) {
                        return v;
                    }
                }
            }
            return null;
        }
    }

    /** The card holding the library views; the three library entries of the index share it. */
    private static final String BROWSE_CARD = "BROWSE";
    private static final String VIEW = "ui.view";
    private static final String LAST_DIR = "ui.lastDir";
    private static final String LAST_PLAYLIST = "queue.playlist";
    private static final int DEFAULT_W = 980;
    private static final int DEFAULT_H = 680;
    private static final String BOUNDS = "window.bounds";
    private static final String MAXIMIZED = "window.maximized";
    private static final String CRASH_TOAST = "Something went wrong. Details are in purist.log.";
    private static final Logger LOG = AppLog.logger(MainWindow.class);

    private final Settings settings;
    private final AppLibrary library;
    private final JFrame frame = new JFrame("Purist");
    private final DecoderRegistry registry = DecoderRegistry.standard();
    private volatile List<AsioDriverInfo> drivers = AsioDriverList.installed();
    private final List<WasapiDevice> endpoints = activeEndpoints();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final Banner banner = new Banner();
    private final Toasts toasts;
    private final Deck deck;
    private final PlaylistView playlist;
    private final PlaylistStore store;
    private final QueueView queueView;
    /** The playlist whose tracks the Player holds as its queue; null if it was deleted or none was started. */
    private UUID playingId;
    private final BrowseView browse;
    private final IndexRail rail;
    private final SettingsView settingsView;
    private final DrivesView drivesView;
    private final LibrarySettings librarySettings;
    private final Player player;
    private final UiPlayerBridge bridge;
    private OutputChoice output;
    private AudioSink sink;
    /** The queue index to resume from: the one playing or last playing, else the one the queue was started at. */
    private int lastIndex = -1;
    /** Reads folders and probes ISOs, one request at a time and in order. */
    private final ExecutorService disk = daemonThread("purist-open");
    /** Lists the ASIO drivers (registry) and WASAPI endpoints (COM): slow, so never on the EDT. */
    private final ExecutorService outputProbe = daemonThread("purist-outputs");
    /** The window's ticks; stopped at shutdown so nothing polls a library that is closing. */
    private final Timer ticker = new Timer(100, e -> refresh());
    /** Saves the view, playlist and window bounds shortly after the last change, so a crash loses little. */
    private final Timer saveSoon = new Timer(1500, e -> {
        saveBounds();
        save();
    });
    /** Closes replaced sinks: closing an ASIO sink waits for its control thread, which the EDT must not do. */
    private final ExecutorService closer = daemonThread("purist-sink-close");
    private final List<CompletableFuture<Void>> closing = new CopyOnWriteArrayList<>();
    private final AtomicBoolean panelOpen = new AtomicBoolean();
    /** Writes {@code settings.properties} off the UI thread. */
    private final BackgroundSaver saver;

    private MainWindow(Settings settings, AppLibrary library, Profile profile) {
        this.settings = settings;
        this.saver = new BackgroundSaver(settings::save);
        this.library = library;
        toasts = new Toasts(frame.getRootPane());
        saveSoon.setRepeats(false);
        OutputChoices.migrate(settings);
        OutputChoices.Resolved outputs = OutputChoices.resolve(settings, OutputChoices.list(drivers, endpoints));
        output = outputs.selected();
        sink = createSink(output);
        player = new Player(sink, registry, SwingUtilities::invokeLater);
        bridge = new UiPlayerBridge(toasts, banner, this::reopenSink);
        player.addListener(bridge);

        deck = new Deck(new Deck.Actions() {
            @Override public void prev() { player.previous(); }
            @Override public void play() { playOrResume(); }
            @Override public void pause() { player.togglePause(); }
            @Override public void stop() {
                bridge.cancelRetry(); // STOP means stop: a pending auto-retry must not start playback again
                player.stop();
            }
            @Override public void next() { player.next(); }
            @Override public void open() { openFiles(false); }
            @Override public void seek(double s) { player.seek(s); }
            @Override public void shuffle(boolean on) { player.setShuffle(on); }
            @Override public void repeat(boolean on) { player.setRepeat(on ? RepeatMode.ALL : RepeatMode.OFF); }
        });
        playlist = new PlaylistView(registry, this::playRow, () -> openFiles(false), () -> openFiles(true));
        store = new PlaylistStore(profile.playlists());
        queueView = new QueueView(store, playlist, savedPlaylist(), new QueueView.Listener() {
            @Override
            public void selected(Playlist p) {
                viewPlaylist(p);
            }

            @Override
            public void deleted(Playlist p) {
                if (p.id().equals(playingId)) {
                    playingId = null; // the Player goes on with its own copy of the queue
                }
            }
        });
        playlist.setEditListener(new PlaylistView.EditListener() {
            @Override
            public void moved(int[] rows, int to) {
                editViewed(size -> {
                    queueView.current().move(rows, to);
                    return QueueReorder.oldToNewAfterMove(size, rows, to);
                });
            }

            @Override
            public void removed(int[] rows) {
                editViewed(size -> {
                    queueView.current().remove(rows);
                    return QueueReorder.oldToNewAfterRemove(size, rows);
                });
            }
        });
        settingsView = new SettingsView(outputs.choices(), output, this::selectOutput, this::openDriverPanel,
                this::selectSkin, c -> OutputChoices.dopEnabled(settings, c.key()), this::setDop);
        settingsView.setStatus(output == null ? null : "Output: " + output.label());
        librarySettings = new LibrarySettings(libraryBackend(), this::pickFolder);
        settingsView.setLibrary(librarySettings);
        drivesView = new DrivesView(drivesBackend(), library.firstRun());
        browse = new BrowseView(library.db(), library.thumbs(), registry, this::playRefs, this::addRefs);
        rail = new IndexRail(this::show);

        content.add(browse, BROWSE_CARD);
        content.add(queueView, View.QUEUE.name());
        content.add(drivesView, View.DRIVES.name());
        JScrollPane settingsScroll = new JScrollPane(settingsView);
        settingsScroll.setBorder(null);
        settingsScroll.setOpaque(false);
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        settingsScroll.getViewport().setOpaque(false);
        settingsScroll.setName(View.SETTINGS.name());
        ShortcutKeys.release(settingsScroll); // its arrows scroll; here they seek
        PuristTheme.addListener(sk -> settingsScroll.getViewport().setBackground(sk.chassis));
        settingsScroll.getViewport().setBackground(PuristTheme.skin().chassis);
        settingsScroll.getViewport().setOpaque(true);
        content.add(settingsScroll, View.SETTINGS.name());
        JPanel railBox = new JPanel(new BorderLayout());
        railBox.setBorder(new EmptyBorder(0, 12, 12, 0));
        railBox.setOpaque(false);
        railBox.add(rail, BorderLayout.CENTER);
        JPanel lower = new JPanel(new BorderLayout());
        lower.setOpaque(false);
        lower.add(railBox, BorderLayout.WEST);
        lower.add(content, BorderLayout.CENTER);
        JPanel main = new JPanel(new BorderLayout());
        main.add(deck, BorderLayout.NORTH);
        main.add(lower, BorderLayout.CENTER);
        JPanel root = new JPanel(new BorderLayout());
        root.add(banner, BorderLayout.NORTH);
        root.add(main, BorderLayout.CENTER);
        frame.setContentPane(root);
        // The search box is the first thing Swing would focus, and while a text box has the focus Space and the
        // arrows type instead of pausing and seeking: the window itself takes the focus when it opens.
        root.setFocusable(true);
        frame.setFocusTraversalPolicy(new javax.swing.LayoutFocusTraversalPolicy() {
            @Override
            public java.awt.Component getInitialComponent(java.awt.Window w) {
                return root;
            }
        });
        bindKeys(root);
        restyle(main);
        PuristTheme.addListener(s -> restyle(main));

        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        frame.setMinimumSize(new Dimension(720, 520));
        frame.setBounds(restoredBounds());
        if ("true".equals(settings.get(MAXIMIZED, null))) {
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
        viewPlaylist(queueView.current());
        View saved = View.fromSetting(settings.get(VIEW, null));
        // With no folder in the library there is nothing to browse: start where the drives are ticked.
        show(library.firstRun() ? View.DRIVES : saved != null ? saved : View.ALBUMS);
        browse.reload();

        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            AppLog.LOGGING_HANDLER.uncaughtException(t, ex);
            SwingUtilities.invokeLater(() -> toasts.show(CRASH_TOAST));
        });
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                saveSoon.restart();
            }

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                saveSoon.restart();
            }
        });
        ticker.start();
        for (String problem : store.loadProblems()) {
            SwingUtilities.invokeLater(() -> toasts.show(problem));
        }
    }

    /**
     * Builds and shows the window, then queues and plays {@code files} (files or folders given on the command line,
     * as "Open with" does). Call on the EDT after the theme is set up.
     */
    public static MainWindow open(Settings settings, Profile profile, AppLibrary library, List<Path> files) {
        MainWindow w = new MainWindow(settings, library, profile);
        w.frame.setVisible(true);
        LOG.info("Window open, profile " + profile.dir() + ", output " + w.sink.name());
        if (!files.isEmpty()) {
            w.expandAndEnqueue(files);
        }
        return w;
    }

    public void show(View v) {
        if (v == View.SETTINGS && settingsView != null) {
            refreshOutputs();
        }
        switch (v) {
            case ALBUMS -> {
                browse.showMode(BrowseView.Mode.ALBUMS);
                cards.show(content, BROWSE_CARD);
            }
            case ARTISTS -> {
                browse.showMode(BrowseView.Mode.ARTISTS);
                cards.show(content, BROWSE_CARD);
            }
            case FOLDERS -> {
                browse.showMode(BrowseView.Mode.FOLDERS);
                cards.show(content, BROWSE_CARD);
            }
            case DRIVES -> {
                drivesView.refresh();
                cards.show(content, v.name());
            }
            case SETTINGS -> {
                librarySettings.refresh();
                cards.show(content, v.name());
            }
            case QUEUE -> cards.show(content, v.name());
        }
        rail.select(v);
        settings.put(VIEW, v.name());
        saveSoon.restart();
    }

    /** Ctrl+F: the search box lives in the library views, so from the queue or settings go to the albums first. */
    private void focusSearch() {
        if (rail.selected() == View.QUEUE || rail.selected() == View.SETTINGS || rail.selected() == View.DRIVES) {
            show(View.ALBUMS);
        }
        browse.focusSearch();
    }

    private void refresh() {
        PlayerStatus s = player.status();
        deck.update(s);
        boolean stopped = s.state() == PlayerState.STOPPED;
        boolean viewedPlays = queueView.current().id().equals(playingId);
        playlist.setPlaying(stopped || !viewedPlays ? -1 : s.index());
        browse.setPlaying(stopped || s.track() == null ? null : new TrackRef(s.track().path(), s.track().subsong()));
        if (s.index() >= 0) {
            lastIndex = s.index();
        }
        pollLibrary();
    }

    /** What the library reported since the last tick: scan progress, a changed library, notices. */
    private void pollLibrary() {
        LibraryFeed feed = library.feed();
        ScanProgress p = feed.progress();
        browse.setScan(p);
        drivesView.scanProgress(p);
        librarySettings.scanProgress(p);
        if (feed.takeChanged()) {
            browse.reload();
        }
        for (String notice : feed.takeNotices()) {
            toasts.show(notice);
        }
        for (LibraryFeed.Finished f : feed.takeFinished()) {
            drivesView.scanFinished(f);
            librarySettings.scanFinished(f);
        }
    }

    /** The folder chooser of ADD FOLDER; null if cancelled. */
    private Path pickFolder() {
        File start = new File(settings.get(LAST_DIR, System.getProperty("user.home")));
        List<Path> picked = OpenFilesAction.pick(frame, true, start);
        return picked.isEmpty() ? null : picked.getFirst();
    }

    /** What the LIBRARY section of Settings does. Its methods run off the UI thread. */
    private LibrarySettings.Backend libraryBackend() {
        return new LibrarySettings.Backend() {
            @Override
            public List<Path> folders() {
                return library.db().folders();
            }

            @Override
            public void addFolder(Path folder) {
                library.service().addFolder(folder);
            }

            @Override
            public void removeFolder(Path folder) {
                library.service().removeFolder(folder);
            }

            @Override
            public void rescan() {
                library.service().rescanAll();
            }

            /**
             * The tracks the library has stored for an image, or -1 if it does not know: not scanned yet, or stored
             * as one unsupported / damaged / cloud-only row (subsong 0), which says nothing about its tracks.
             */
            private int imageTracks(Path image) {
                int most = -1;
                for (var row : library.db().tracks("t.path = ? COLLATE NOCASE", List.of(image.toString()), null)) {
                    most = Math.max(most, row.ref().subsong());
                }
                return most < 1 ? -1 : most;
            }

            @Override
            public LibrarySettings.Findings foobar() {
                Path profile = FoobarImport.defaultProfile();
                return new LibrarySettings.Findings(FoobarImport.folders(profile), FoobarImport.playlists(profile));
            }

            @Override
            public void importFoobar(LibrarySettings.Findings f) {
                List<Path> have = library.db().folders();
                for (Path folder : f.folders()) {
                    if (have.stream().noneMatch(h -> h.equals(folder.toAbsolutePath().normalize()))) {
                        library.service().addFolder(folder);
                    }
                }
                List<String> names = new ArrayList<>();
                for (Playlist p : store.all()) {
                    names.add(p.name().toLowerCase(java.util.Locale.ROOT));
                }
                for (FoobarImport.ImportedPlaylist ip : f.playlists()) {
                    if (!names.contains(ip.name().toLowerCase(java.util.Locale.ROOT))) {
                        store.create(ip.name()).add(FoobarImport.dropMissingTracks(ip.items(), this::imageTracks));
                    }
                }
                SwingUtilities.invokeLater(queueView::refresh);
            }

            @Override
            public List<String> errorTail(int n) {
                return library.errors().tail(n);
            }
        };
    }

    /** What the DRIVES view does. Its methods run off the UI thread. */
    private DrivesView.Backend drivesBackend() {
        return new DrivesView.Backend() {
            @Override
            public List<Drive> drives() {
                return Drives.fixedNtfs();
            }

            @Override
            public boolean everythingRunning() {
                return EverythingSearch.available();
            }

            @Override
            public List<Path> watched() {
                return library.db().folders();
            }

            @Override
            public void add(Path drive) {
                library.service().addFolder(drive);
            }

            @Override
            public void remove(Path drive) {
                library.service().removeFolder(drive);
            }
        };
    }

    private void playOrResume() {
        PlayerStatus s = player.status();
        switch (s.state()) {
            case PAUSED -> player.resume();
            case PLAYING -> { }
            case STOPPED -> {
                int at = resumeIndex(lastIndex, player.queue().size(), player.queueRanOut());
                if (at >= 0) {
                    player.play(at);
                } else if (!queueView.current().items().isEmpty()) {
                    playRow(0);
                } else {
                    openFiles(false);
                }
            }
        }
    }

    /** The playlist the user last had open, if it still exists. */
    private Playlist savedPlaylist() {
        String id = settings.get(LAST_PLAYLIST, null);
        if (id != null) {
            for (Playlist p : store.all()) {
                if (p.id().toString().equals(id)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * A tab was picked (or the window started): show that playlist. When nothing is playing, the Player takes it
     * as its queue too, so the PLAY button plays what is on screen.
     */
    private void viewPlaylist(Playlist p) {
        playlist.setQueue(p.items());
        settings.put(LAST_PLAYLIST, p.id().toString());
        saveSoon.restart();
        if (player.status().state() == PlayerState.STOPPED) {
            lastIndex = -1;
            player.setQueue(p.items(), -1);
            playingId = p.id();
        }
    }

    /** A row of the open playlist was double-clicked: play it, making that playlist the Player's queue. */
    private void playRow(int row) {
        Playlist cur = queueView.current();
        lastIndex = row;
        if (cur.id().equals(playingId)) {
            player.play(row);
        } else {
            player.setQueue(cur.items(), row);
            playingId = cur.id();
        }
    }

    /**
     * The open playlist was reordered or had rows taken out on screen. {@code edit} applies the same to the stored
     * playlist and returns the old-to-new index map; the Player, if it holds this playlist, follows without a
     * break in the sound.
     */
    private void editViewed(java.util.function.IntFunction<int[]> edit) {
        Playlist cur = queueView.current();
        int[] map = edit.apply(cur.items().size());
        if (cur.id().equals(playingId)) {
            player.editQueue(cur.items(), map);
        }
        if (lastIndex >= 0 && lastIndex < map.length) {
            lastIndex = map[lastIndex] >= 0 ? map[lastIndex] : Math.min(lastIndex, Math.max(0, cur.items().size() - 1));
        }
    }

    /**
     * Where to play from after a stop or a device loss: the last index, or the first track if nothing had started
     * (the first open failed, so the Player never reported an index); -1 for an empty queue.
     */
    static int resumeIndex(int lastIndex, int queueSize) {
        return queueSize == 0 ? -1 : Math.max(0, Math.min(lastIndex, queueSize - 1));
    }

    /**
     * As above, but when the queue played out to its end ({@code ranOut}) and tracks were added after it, PLAY goes
     * on with the first added track instead of replaying the old last one.
     */
    static int resumeIndex(int lastIndex, int queueSize, boolean ranOut) {
        if (ranOut && lastIndex >= 0 && lastIndex + 1 < queueSize) {
            return lastIndex + 1;
        }
        return resumeIndex(lastIndex, queueSize);
    }

    private void openFiles(boolean folders) {
        File start = new File(settings.get(LAST_DIR, System.getProperty("user.home")));
        List<Path> picked = OpenFilesAction.pick(frame, folders, start); // the chooser itself is the only EDT part
        if (picked.isEmpty()) {
            return;
        }
        Path parent = picked.getFirst().getParent(); // the folder it is in (for a folder: the one beside it)
        if (parent != null) {
            settings.put(LAST_DIR, parent.toString());
        }
        expandAndEnqueue(picked);
    }

    /** Walks folders and probes ISOs on the {@code disk} thread, then queues the result on the EDT. */
    private void expandAndEnqueue(List<Path> selection) {
        disk.execute(() -> {
            try {
                List<TrackRef> refs = OpenFilesAction.expand(selection, registry);
                SwingUtilities.invokeLater(() -> enqueue(refs));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot read " + selection, e);
                SwingUtilities.invokeLater(() -> toasts.show("Could not read the files: " + e));
            }
        });
    }

    private void enqueue(List<TrackRef> refs) {
        if (refs.isEmpty()) {
            return;
        }
        playRefs(refs, 0);
        show(View.QUEUE);
    }

    /** PLAY: replaces the open playlist with {@code refs} and plays it from {@code start}. */
    private void playRefs(List<TrackRef> refs, int start) {
        if (refs.isEmpty()) {
            return;
        }
        Playlist cur = queueView.current();
        cur.replace(refs);
        lastIndex = start; // so a first open that fails (DAC off) can still be retried from here
        player.setQueue(cur.items(), start);
        playingId = cur.id();
        playlist.setQueue(cur.items());
    }

    /** + QUEUE: appends {@code refs} to the open playlist; what is playing is not interrupted. */
    private void addRefs(List<TrackRef> refs) {
        if (refs.isEmpty()) {
            return;
        }
        Playlist cur = queueView.current();
        int before = cur.items().size();
        cur.add(refs);
        playlist.append(refs);
        if (cur.id().equals(playingId)) {
            int[] same = new int[before];
            for (int i = 0; i < before; i++) {
                same[i] = i;
            }
            player.editQueue(cur.items(), same);
        } else if (player.status().state() == PlayerState.STOPPED) {
            player.setQueue(cur.items(), -1);
            playingId = cur.id();
        }
        toasts.show("Added " + refs.size() + (refs.size() == 1 ? " track" : " tracks") + " to " + cur.name());
    }

    /**
     * Lists the outputs again, so a DAC switched on or plugged in after start-up appears. If the remembered device
     * was missing and is now back, Purist switches to it.
     */
    private void refreshOutputs() {
        outputProbe.execute(() -> {
            List<AsioDriverInfo> asio = AsioDriverList.installed();
            List<WasapiDevice> wasapi = activeEndpoints();
            SwingUtilities.invokeLater(() -> applyOutputs(asio, wasapi));
        });
    }

    /** On the EDT, with the lists the {@code outputProbe} thread made. */
    private void applyOutputs(List<AsioDriverInfo> asio, List<WasapiDevice> wasapi) {
        drivers = asio; // a driver installed since start-up can now be opened
        OutputChoices.Resolved now = OutputChoices.resolve(settings, OutputChoices.list(asio, wasapi));
        OutputChoice back = now.selected();
        if (output != null && back != null && !output.present() && back.present() && back.type() == output.type()
                && back.name().equals(output.name())) {
            output = back;
            OutputChoices.remember(settings, back);
            swapSink(createSink(back));
            settingsView.setStatus("Output: " + back.label());
        }
        settingsView.setChoices(now.choices(), output);
    }

    /** The active WASAPI endpoints; none if Windows could not list them (ASIO still works then). */
    private static List<WasapiDevice> activeEndpoints() {
        try {
            return WasapiDeviceList.active();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot list the WASAPI devices", e);
            return List.of();
        }
    }

    /** The sink for {@code c}: an ASIO driver, a WASAPI endpoint, or, with nothing to play on, a refusing one. */
    private AudioSink createSink(OutputChoice c) {
        if (c == null) {
            return new NoDriverSink();
        }
        if (c.type() == OutputChoice.Type.WASAPI) {
            // DoP is opt-in per device; the sink asks at each open, so the Settings checkbox needs no rebuild.
            return WasapiSink.forDevice(new WasapiDevice(c.key(), c.name()),
                    () -> OutputChoices.dopEnabled(settings, c.key()));
        }
        for (AsioDriverInfo d : drivers) {
            if (d.name().equals(c.key())) {
                return AsioSink.forDriver(d);
            }
        }
        return new NoDriverSink();
    }

    private void selectOutput(OutputChoice c) {
        if (c.equals(output)) {
            return;
        }
        bridge.cancelRetry();
        output = c;
        OutputChoices.remember(settings, c);
        save();
        swapSink(createSink(c));
        settingsView.setStatus("Output: " + c.label());
    }

    /** The "supports DoP" box: remembered per endpoint; the running WASAPI sink reads it at its next open. */
    private void setDop(OutputChoice c, boolean on) {
        OutputChoices.setDopEnabled(settings, c.key(), on);
        save();
        settingsView.setStatus(on ? "DoP on for " + c.name() + ": DSD is sent as DoP"
                : "DoP off for " + c.name() + ": DSD tracks are skipped");
    }

    /** Re-creates the sink for the current output and resumes where playback stopped (RETRY and auto-retry). */
    private void reopenSink() {
        if (output == null) {
            return;
        }
        swapSink(createSink(output));
        int at = resumeIndex(lastIndex, player.queue().size());
        if (at >= 0) {
            player.play(at);
        }
    }

    /**
     * The Player switches sinks on its own thread, between commands, and can be inside a slow file or device open
     * until then. The old sink is closed only once the Player reports the switch done (so nothing is using it
     * any more), and on a background thread: closing a sink waits for its control thread.
     */
    private void swapSink(AudioSink next) {
        AudioSink old = sink;
        CompletableFuture<AudioSink> switched = player.setSink(next);
        sink = next;
        CompletableFuture<Void> closed = switched.handleAsync((previous, error) -> {
            try {
                old.close();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot close the old output " + old.name(), e);
            }
            return null;
        }, closer);
        closing.add(closed);
        closed.whenComplete((v, e) -> closing.remove(closed));
    }

    /** The driver's own panel can stay open for as long as the user likes, so it never runs on the EDT. */
    private void openDriverPanel() {
        if (!(sink instanceof AsioSink a) || !panelOpen.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                a.openControlPanel();
            } catch (AudioDeviceException e) {
                SwingUtilities.invokeLater(() -> settingsView.setStatus(e.getMessage()));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Driver panel failed", e);
                SwingUtilities.invokeLater(() -> toasts.show(CRASH_TOAST));
            } finally {
                panelOpen.set(false);
            }
        }, "purist-driver-panel");
        t.setDaemon(true);
        t.start();
    }

    private static ExecutorService daemonThread(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private void selectSkin(Skin s) {
        PuristTheme.apply(s);
        settings.put(Skin.SETTING, s.name());
        save();
    }

    private void bindKeys(JComponent root) {
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "open", () -> openFiles(false));
        typingSafeKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "pause", player::togglePause);
        typingSafeKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "back", () -> seekBy(-5));
        typingSafeKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "fwd", () -> seekBy(5));
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK), "prev", player::previous);
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK), "next", player::next);
        for (View v : View.values()) {
            key(root, KeyStroke.getKeyStroke(KeyEvent.VK_0 + v.digit(), InputEvent.CTRL_DOWN_MASK),
                    "view-" + v.name(), () -> show(v));
        }
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "search", this::focusSearch);
    }

    private void seekBy(double delta) {
        PlayerStatus s = player.status();
        if (s.durationSeconds() > 0) {
            player.seek(Math.clamp(s.positionSeconds() + delta, 0, s.durationSeconds()));
        }
    }

    private static void key(JComponent root, KeyStroke k, String name, Runnable r) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    /**
     * Like {@link #key}, for a key that a text box also uses (Space types a space, the arrows move the caret): while
     * a text box has the focus the action is disabled, which lets the key through to the box instead.
     */
    private static void typingSafeKey(JComponent root, KeyStroke k, String name, Runnable r) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public boolean isEnabled() {
                return !typing();
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    /** True while the keyboard focus is in a text box. */
    static boolean typing() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent;
    }

    /**
     * The saved bounds put back on a screen that exists now (a monitor may have gone), or a centred default. The
     * older {@code window.x/y/w/h} keys are still read.
     */
    private Rectangle restoredBounds() {
        Rectangle r = WindowState.parse(settings.get(BOUNDS, null)).orElseGet(() -> legacyBounds());
        if (r == null) {
            frame.setSize(DEFAULT_W, DEFAULT_H);
            frame.setLocationRelativeTo(null);
            return frame.getBounds();
        }
        return WindowState.clamp(r, usableScreens());
    }

    private Rectangle legacyBounds() {
        int x = settings.getInt("window.x", Integer.MIN_VALUE);
        int y = settings.getInt("window.y", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE) {
            return null;
        }
        return new Rectangle(x, y, settings.getInt("window.w", DEFAULT_W), settings.getInt("window.h", DEFAULT_H));
    }

    /** Each screen without its taskbar. */
    private static List<Rectangle> usableScreens() {
        List<Rectangle> out = new ArrayList<>();
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle b = d.getDefaultConfiguration().getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(d.getDefaultConfiguration());
            out.add(new Rectangle(b.x + in.left, b.y + in.top, b.width - in.left - in.right,
                    b.height - in.top - in.bottom));
        }
        return out;
    }

    /** Saves where the window is, unless it is maximised (then the size it had before stays the one to come back to). */
    private void saveBounds() {
        boolean maximised = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        settings.put(MAXIMIZED, maximised ? "true" : null);
        if (!maximised) {
            settings.put(BOUNDS, WindowState.format(frame.getBounds()));
        }
        for (String old : new String[] {"window.x", "window.y", "window.w", "window.h"}) {
            settings.put(old, null);
        }
    }

    private void shutdown() {
        saveSoon.stop();
        saveBounds();
        saveNow();
        ticker.stop();
        frame.dispose(); // the window goes at once; the rest can take a moment
        // Whatever fails, the process must end.
        try {
            player.close();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot stop the player", e);
        } finally {
            try {
                sink.close();
                waitForOldSinks();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot close the output", e);
            } finally {
                try {
                    // Everything that reads the library stops first, then the library, its thumbnails and its DB.
                    // Each step runs whatever the others do: a failing worker must not skip the playlist flush or
                    // the DB close.
                    runAll(new CloseStep("stop the workers", this::stopWorkers),
                            new CloseStep("close the playlists", store::close),
                            new CloseStep("close the library", library::close));
                } finally {
                    System.exit(0);
                }
            }
        }
    }

    /** One thing shutdown does; named for the log line if it fails. */
    record CloseStep(String what, Runnable run) {
    }

    /** Runs every step in order, logging and swallowing a step's RuntimeException; returns how many failed. */
    static int runAll(CloseStep... steps) {
        int failed = 0;
        for (CloseStep step : steps) {
            try {
                step.run().run();
            } catch (RuntimeException e) {
                failed++;
                LOG.log(Level.WARNING, "Cannot " + step.what(), e);
            }
        }
        return failed;
    }

    /** Stops the threads that read the library, the disk or the audio devices for the UI. */
    private void stopWorkers() {
        runAll(new CloseStep("close the browse view", browse::close),
                new CloseStep("close the library settings", librarySettings::close),
                new CloseStep("close the drives view", drivesView::close),
                new CloseStep("stop the disk worker", disk::shutdownNow),
                new CloseStep("stop the output probe", outputProbe::shutdownNow),
                new CloseStep("stop the sink closer", closer::shutdown));
    }

    /** Gives sinks that are being closed in the background a moment to release their drivers. */
    private void waitForOldSinks() {
        try {
            CompletableFuture.allOf(closing.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, "An old output did not close in time", e);
        }
    }

    /** Writes the settings on the saver's thread: the UI thread never waits for the disk. */
    private void save() {
        saver.saveLater();
    }

    /** Writes the settings now, for the last save before the process ends. */
    private void saveNow() {
        saver.close(); // lets a save in flight finish first
        try {
            settings.save();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot save settings", e);
        }
    }

    private static void restyle(JPanel main) {
        Skin s = PuristTheme.skin();
        main.setBackground(s.chassis);
        for (var c : main.getComponents()) {
            c.setBackground(s.chassis);
        }
    }

    /** The sink when there is no output at all: every track is refused with a reason that says so. */
    private static final class NoDriverSink implements AudioSink {
        @Override
        public String name() {
            return "No output device";
        }

        @Override
        public SinkStream open(StreamFormat f) throws NotBitPerfectException {
            throw new NotBitPerfectException("no output device is available");
        }

        @Override
        public void addDeviceListener(Consumer<String> onDeviceLost) {
        }

        @Override
        public void close() {
        }
    }
}
