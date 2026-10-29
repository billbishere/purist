package purist.ui.browse;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Graphics;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import purist.app.AppLog;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.library.AlbumSummary;
import purist.library.LibraryDb;
import purist.library.ScanProgress;
import purist.library.TrackRow;
import purist.library.art.ThumbnailCache;
import purist.library.query.QueryException;
import purist.library.query.QueryParser;
import purist.library.query.SqlBuilder;
import purist.library.query.SqlBuilder.Sql;
import purist.playlist.TrackRef;
import purist.ui.kit.PlainText;
import purist.ui.kit.ShortcutKeys;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * The library browser (spec §9): a search field with a count beside it, and under them one of
 * <ul>
 *   <li><b>Albums</b>: the cover grid;</li>
 *   <li><b>Artists</b>: the album artists on the left and the grid of the chosen one;</li>
 *   <li><b>Folders</b>: the watched folders as a tree and the tracks of the chosen folder;</li>
 *   <li>while the search box holds a query, the matching tracks, whichever of the three is open.</li>
 * </ul>
 * Every read of the database, the disk or the cover files happens on a background thread and comes back through
 * {@code invokeLater}. EDT only, except those reads.
 */
public final class BrowseView extends JPanel {

    /** What the index entry chose. */
    public enum Mode { ALBUMS, ARTISTS, FOLDERS }

    private static final Logger LOG = AppLog.logger(BrowseView.class);
    private static final String CARD_ALBUMS = "albums";
    private static final String CARD_FOLDERS = "folders";
    private static final String CARD_SEARCH = "search";
    /** Search results beyond this many are not listed. */
    static final int RESULT_CAP = 5000;
    private static final String SEARCH_ORDER = FolderQuery.ORDER + " LIMIT " + (RESULT_CAP + 1);
    private static final int COVER_PX = 192;
    private static final NumberFormat COUNT = NumberFormat.getIntegerInstance(Locale.US);

    private final LibraryDb db;
    private final BiConsumer<List<TrackRef>, Integer> play;
    private final Consumer<List<TrackRef>> add;
    private final ExecutorService queries = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "purist-browse");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService folderIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "purist-folders");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;

    private final AlbumGridModel model = new AlbumGridModel();
    private final CoverLoader covers;
    private final AlbumGrid grid;
    private final JScrollPane gridScroll;
    private final ArtistList artists = new ArtistList();
    private final FolderTree folders;
    private final TrackListPane folderTracks;
    private final TrackListPane results;
    private final SearchField search;
    private final JLabel count = PlainText.of(new JLabel(" "));
    private final ProgressLine progress = new ProgressLine();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JPanel albumsCard = new JPanel(new BorderLayout(10, 0));
    /** Where {@link #albumsCard} sits when no search runs; during a search it is the top of {@link #searchSplit}. */
    private final JPanel albumsHolder = new JPanel(new BorderLayout());
    private final javax.swing.JSplitPane searchSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT);
    private final AlbumPanel albumPanel;
    private String libraryEmptyText = "";

    private Mode mode = Mode.ALBUMS;
    private boolean searching;
    private String queryError;
    private String resultNote = "";
    private int resultCount;
    private boolean resultsCapped;
    /** True from the moment the track list of a search is shown until its album narrowing arrives. */
    private boolean albumsPending;
    private int folderCount = -1;
    private volatile int libraryGen;
    private volatile int searchGen;
    private volatile int folderGen;
    private int albumTotal;

    public BrowseView(LibraryDb db, ThumbnailCache thumbs, DecoderRegistry registry,
            BiConsumer<List<TrackRef>, Integer> play, Consumer<List<TrackRef>> add) {
        super(new BorderLayout(0, 10));
        this.db = db;
        this.play = play;
        this.add = add;
        setOpaque(false);
        setBorder(new EmptyBorder(0, 10, 12, 12));

        covers = new CoverLoader(thumbs, registry, COVER_PX, this::coversArrived);
        grid = new AlbumGrid(model, covers);
        grid.onPlay(this::playAlbum);
        albumPanel = new AlbumPanel(covers, this::loadTracks, play, add);
        grid.onSelect(albumPanel::show);
        gridScroll = new JScrollPane(grid);
        gridScroll.getVerticalScrollBar().setUnitIncrement(AlbumGridModel.CELL_H / 3);
        ShortcutKeys.release(gridScroll);
        ShortcutKeys.release(grid);

        artists.onSelect(artist -> {
            if (mode == Mode.ARTISTS) {
                model.setArtistFilter(artist);
                grid.setSelected(null);
                albumPanel.show(null);
                grid.modelChanged();
                updateCount();
            }
        });
        artists.setVisible(false);

        albumsCard.setOpaque(false);
        albumsCard.add(artists, BorderLayout.WEST);
        albumsCard.add(gridScroll, BorderLayout.CENTER);
        albumsCard.add(albumPanel, BorderLayout.EAST);
        albumsHolder.setOpaque(false);
        albumsHolder.add(albumsCard, BorderLayout.CENTER);

        folders = new FolderTree(folderIo);
        folders.setPreferredSize(new Dimension(280, 100));
        folderTracks = new TrackListPane(play, add);
        folders.onSelect(this::listFolder);
        JPanel folderCard = new JPanel(new BorderLayout(10, 0));
        folderCard.setOpaque(false);
        folderCard.add(folders, BorderLayout.WEST);
        folderCard.add(folderTracks, BorderLayout.CENTER);

        results = new TrackListPane(play, add);
        searchSplit.setOpaque(false);
        searchSplit.setBorder(null);
        searchSplit.setResizeWeight(0.55);
        searchSplit.setDividerSize(8);
        searchSplit.setContinuousLayout(true);
        ShortcutKeys.release(searchSplit); // a split pane moves its divider with the arrows: they are seek keys here
        body.setFocusable(true); // somewhere for the keyboard focus to rest that is not a text box
        searchSplit.setBottomComponent(results);

        body.setOpaque(false);
        body.add(albumsHolder, CARD_ALBUMS);
        body.add(folderCard, CARD_FOLDERS);
        body.add(searchSplit, CARD_SEARCH);

        search = new SearchField(SearchField.DEBOUNCE_MILLIS, this::runQuery);
        search.onDone(this::releaseFocusFromSearch);
        count.setFont(Fonts.mono(12f));
        JPanel bar = new JPanel(new BorderLayout(14, 0));
        bar.setOpaque(false);
        bar.add(search, BorderLayout.CENTER);
        bar.add(count, BorderLayout.EAST);
        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(progress, BorderLayout.NORTH);
        north.add(bar, BorderLayout.CENTER);
        add(north, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);

        restyle();
        PuristTheme.addListener(s -> restyle());
        applyMode();
    }

    // ---- what the window drives ----

    /** Runs a database or disk read on the background thread, unless the view has been closed. */
    private void query(Runnable work) {
        if (closed) {
            return;
        }
        try {
            queries.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // closing
        }
    }

    /** Stops the background readers (queries, folder listing, covers); call before the database is closed. */
    public void close() {
        closed = true;
        queries.shutdownNow();
        folderIo.shutdownNow();
        covers.close();
    }

    /** Switches between the album grid, the artists and the folders; this ends any search. */
    public void showMode(Mode m) {
        mode = m;
        leaveSearch();
        queryError = null;
        search.clear();
        applyMode();
    }

    /** Reads the library again (a scan changed it) and keeps the selection, the search and the folder. */
    public void reload() {
        int gen = ++libraryGen;
        query(() -> {
            if (gen != libraryGen) {
                return; // a newer reload is queued behind this one
            }
            List<AlbumSummary> albums;
            List<Path> roots;
            try {
                albums = db.albums();
                roots = db.folders();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot read the library", e);
                return;
            }
            SwingUtilities.invokeLater(() -> applyLibrary(gen, albums, roots));
        });
    }

    public void focusSearch() {
        search.focusField();
    }

    /**
     * Enter or Escape in the search box: the keyboard focus leaves the box, so Space pauses and the arrows seek
     * again (while a text box has the focus they type and move the caret).
     */
    private void releaseFocusFromSearch() {
        (mode == Mode.FOLDERS && !searching ? body : grid).requestFocusInWindow();
    }

    /** Marks the track that is playing in every list that shows it. */
    public void setPlaying(TrackRef ref) {
        results.setPlaying(ref);
        folderTracks.setPlaying(ref);
        albumPanel.setPlaying(ref);
    }

    /** Shows the progress of the scan that is running as a thin line at the top; null when none is. */
    public void setScan(ScanProgress p) {
        progress.set(p);
    }

    // ---- loading ----

    private void applyLibrary(int gen, List<AlbumSummary> albums, List<Path> roots) {
        if (gen != libraryGen) {
            return;
        }
        AlbumSummary keep = grid.selected();
        model.setAlbums(albums);
        albumTotal = albums.size();
        artists.setArtists(ArtistList.entries(albums));
        if (mode == Mode.ARTISTS) {
            model.setArtistFilter(artists.selectedArtist());
        }
        libraryEmptyText = albums.isEmpty()
                ? "NO ALBUMS YET. TICK YOUR DRIVES IN DRIVES, OR ADD FOLDERS IN SETTINGS." : "";
        grid.setEmptyText(searching ? "NO ALBUM HOLDS A MATCH" : libraryEmptyText);
        if (keep != null && model.indexOf(keep) < 0) {
            grid.setSelected(null);
            albumPanel.show(null);
        } else if (keep != null) {
            albumPanel.show(keep); // its tracks may have changed
        }
        grid.modelChanged();
        Path folder = folders.selectedFolder();
        folders.setRoots(roots);
        if (folder != null) {
            listFolder(folder);
        }
        if (searching) {
            runQuery(search.text());
        }
        updateCount();
    }

    private void listFolder(Path folder) {
        int gen = ++folderGen;
        Sql sql = FolderQuery.directTracks(folder);
        query(() -> {
            if (gen != folderGen) {
                return;
            }
            List<TrackInfo> infos;
            try {
                infos = db.tracks(sql.where(), sql.params(), FolderQuery.ORDER).stream().map(TrackRow::toInfo).toList();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot list " + folder, e);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (gen == folderGen) {
                    folderTracks.setRows(infos, "");
                    folderCount = infos.size();
                    updateCount();
                }
            });
        });
    }

    private void runQuery(String text) {
        String q = text.strip();
        int gen = ++searchGen;
        if (q.isEmpty()) {
            leaveSearch();
            queryError = null;
            applyMode();
            return;
        }
        query(() -> {
            if (gen != searchGen) {
                return; // typed past: a newer query is queued behind this one
            }
            try {
                Sql sql = SqlBuilder.build(QueryParser.parse(q));
                List<TrackRow> rows = db.tracks(sql.where(), sql.params(), SEARCH_ORDER);
                if (gen != searchGen) {
                    return;
                }
                boolean capped = rows.size() > RESULT_CAP;
                List<TrackInfo> infos = rows.stream().limit(RESULT_CAP).map(TrackRow::toInfo).toList();
                // The track list goes to the screen first: the album narrowing (a DISTINCT over every match, however
                // many the cap cut off) is a second step on this thread, so a broad query on a big library does not
                // hold the results back.
                SwingUtilities.invokeLater(() -> {
                    if (gen != searchGen) {
                        return;
                    }
                    queryError = null;
                    resultCount = infos.size();
                    resultsCapped = capped;
                    resultNote = capped ? "FIRST " + COUNT.format(RESULT_CAP) + " SHOWN" : "";
                    albumsPending = true;
                    results.setRows(infos, resultNote);
                    if (!searching) {
                        enterSearch(Set.of(), true);
                    }
                    updateCount();
                });
                narrowAlbums(gen, sql);
            } catch (QueryException e) {
                SwingUtilities.invokeLater(() -> {
                    if (gen == searchGen) {
                        queryError = e.getMessage();
                        updateCount();
                    }
                });
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Search failed: " + q, e);
            }
        });
    }

    /** The grid shows every album a match touches, whatever the track list's cap cut off. */
    private void narrowAlbums(int gen, Sql sql) {
        Set<String> albumKeys = new HashSet<>();
        try {
            for (LibraryDb.AlbumName n : db.albumsMatching(sql.where(), sql.params())) {
                albumKeys.add(AlbumGridModel.keyOf(n.albumArtist(), n.album()));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Cannot find the albums of a search", e);
            SwingUtilities.invokeLater(() -> {
                if (gen == searchGen) {
                    albumsPending = false; // the track list stands; the grid keeps what it showed
                    updateCount();
                }
            });
            return;
        }
        if (gen != searchGen) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (gen == searchGen) {
                albumsPending = false;
                enterSearch(albumKeys, false);
                updateCount();
            }
        });
    }

    private void playAlbum(AlbumSummary a) {
        query(() -> {
            List<TrackRef> refs;
            try {
                refs = db.album(a.albumArtist(), a.album()).stream().map(TrackRow::ref).toList();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot read " + a.album(), e);
                return;
            }
            if (!refs.isEmpty()) {
                SwingUtilities.invokeLater(() -> play.accept(refs, 0));
            }
        });
    }

    /** The tracks of {@code a}, read off the UI thread. */
    private void loadTracks(AlbumSummary a, Consumer<List<TrackInfo>> done) {
        query(() -> {
            List<TrackInfo> infos;
            try {
                infos = db.album(a.albumArtist(), a.album()).stream().map(TrackRow::toInfo).toList();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Cannot read " + a.album(), e);
                return;
            }
            SwingUtilities.invokeLater(() -> done.accept(infos));
        });
    }

    /**
     * A query matched tracks: the track table below lists them (up to the cap), and the grid above narrows to the
     * albums, by {@link AlbumGridModel#keyOf key}, that hold a match.
     */
    private void enterSearch(Set<String> keys, boolean pending) {
        boolean first = !searching;
        searching = true;
        artists.setVisible(false);
        model.setArtistFilter(null);
        model.setKeyFilter(keys);
        AlbumSummary keep = grid.selected();
        if (keep == null || model.indexOf(keep) < 0) {
            grid.setSelected(null);
            albumPanel.show(null);
        }
        grid.setEmptyText(pending ? "FINDING ALBUMS…" : "NO ALBUM HOLDS A MATCH");
        searchSplit.setTopComponent(albumsCard);
        grid.modelChanged();
        cards.show(body, CARD_SEARCH);
        if (first) {
            SwingUtilities.invokeLater(() -> searchSplit.setDividerLocation(0.55));
        }
    }

    /** Ends a search: the grid shows every album again, in its own card. */
    private void leaveSearch() {
        if (!searching) {
            return;
        }
        searching = false;
        albumsPending = false;
        model.setKeyFilter(null);
        albumsHolder.add(albumsCard, BorderLayout.CENTER);
        grid.setEmptyText(libraryEmptyText);
        grid.modelChanged();
    }

    private void coversArrived() {
        grid.repaint();
        albumPanel.coverMayHaveArrived();
    }

    // ---- state ----

    private void applyMode() {
        switch (mode) {
            case ALBUMS -> {
                artists.setVisible(false);
                model.setArtistFilter(null);
                grid.modelChanged();
                cards.show(body, CARD_ALBUMS);
            }
            case ARTISTS -> {
                artists.setVisible(true);
                model.setArtistFilter(artists.selectedArtist());
                grid.modelChanged();
                cards.show(body, CARD_ALBUMS);
            }
            case FOLDERS -> cards.show(body, CARD_FOLDERS);
        }
        if (searching) {
            cards.show(body, CARD_SEARCH);
        }
        updateCount();
        revalidate();
    }

    private void updateCount() {
        String text;
        if (queryError != null) {
            text = "QUERY: " + queryError.toUpperCase(Locale.ROOT);
        } else if (searching && albumsPending) {
            text = tracks(resultCount, resultsCapped);
        } else if (searching) {
            int n = model.size();
            text = tracks(resultCount, resultsCapped) + " \u00B7 " + COUNT.format(n) + (n == 1 ? " ALBUM" : " ALBUMS");
        } else if (mode == Mode.FOLDERS) {
            text = folderCount < 0 ? "" : tracks(folderCount);
        } else {
            int n = model.size();
            text = COUNT.format(n) + (n == 1 ? " ALBUM" : " ALBUMS");
        }
        count.setText(text.isEmpty() ? " " : text);
        count.setFont(Fonts.forText(count.getText(), Fonts.mono(12f)));
    }

    private static String tracks(int n) {
        return tracks(n, false);
    }

    /** {@code 5,000+ TRACKS} when the list was cut off at {@code n}. */
    private static String tracks(int n, boolean more) {
        return COUNT.format(n) + (more ? "+" : "") + (n == 1 && !more ? " TRACK" : " TRACKS");
    }

    private void restyle() {
        Skin s = PuristTheme.skin();
        count.setForeground(s.listDim);
        gridScroll.getViewport().setBackground(s.list);
        gridScroll.setBorder(BorderFactory.createMatteBorder(1, 1, 1, 1, s.edge));
        repaint();
    }

    /** Test access. */
    AlbumGrid grid() {
        return grid;
    }

    /** Test access. */
    javax.swing.JSplitPane searchSplit() {
        return searchSplit;
    }

    /** Test access. */
    AlbumGridModel model() {
        return model;
    }

    /** Test access. */
    SearchField search() {
        return search;
    }

    /** Test access. */
    boolean searching() {
        return searching;
    }

    /** Test access. */
    String countText() {
        return count.getText();
    }

    /** A 2 px line across the top that fills as a scan goes; invisible when none runs. */
    private static final class ProgressLine extends JComponent {
        private ScanProgress current;

        ProgressLine() {
            setPreferredSize(new Dimension(10, 2));
        }

        void set(ScanProgress p) {
            if (p != current) {
                current = p;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            ScanProgress p = current;
            if (p == null) {
                return;
            }
            Skin s = PuristTheme.skin();
            g.setColor(s.edge);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (p.total() > 0) {
                g.setColor(s.dark ? s.segment : s.highlight);
                g.fillRect(0, 0, (int) ((long) getWidth() * Math.min(p.done(), p.total()) / p.total()), getHeight());
            }
        }
    }
}
