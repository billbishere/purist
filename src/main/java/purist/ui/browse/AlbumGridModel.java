package purist.ui.browse;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import purist.library.AlbumSummary;

/**
 * The albums in the grid, and the arithmetic of laying them out. The list is sorted by album artist, then album,
 * ignoring case (spec §9); an optional artist filter narrows it to one album artist. The grid is a fixed
 * {@link #CELL_W} by {@link #CELL_H} cell with a {@link #COVER} px square cover, as many columns as the width
 * allows; every position is computed from the index, so the view paints only the cells it can see.
 *
 * <p>EDT only.
 */
public final class AlbumGridModel {

    /** The cover's edge in px. */
    public static final int COVER = 120;
    /** Space between the cell's edge and its cover, on the left, top and right. */
    static final int PAD = 12;
    /** Space around the whole grid. */
    public static final int MARGIN = 8;
    /** A cell: the cover with its padding, then two lines of caption. */
    public static final int CELL_W = COVER + 2 * PAD;
    public static final int CELL_H = PAD + COVER + 6 + 15 + 15 + 8;

    private static final Comparator<AlbumSummary> ORDER = Comparator
            .comparing((AlbumSummary a) -> a.albumArtist().toLowerCase(Locale.ROOT))
            .thenComparing(a -> a.album().toLowerCase(Locale.ROOT))
            .thenComparing(AlbumSummary::albumArtist)
            .thenComparing(AlbumSummary::album);

    private List<AlbumSummary> all = List.of();
    private List<AlbumSummary> shown = List.of();
    private String artistFilter;
    private Set<String> keyFilter;

    /** Replaces every album; they are sorted here, whatever order they come in. */
    public void setAlbums(List<AlbumSummary> albums) {
        List<AlbumSummary> sorted = new ArrayList<>(albums);
        sorted.sort(ORDER);
        all = sorted;
        refilter();
    }

    /** Shows only the albums of {@code artist} (ignoring case); null shows them all. */
    public void setArtistFilter(String artist) {
        artistFilter = artist;
        refilter();
    }

    public String artistFilter() {
        return artistFilter;
    }

    /**
     * Shows only the albums whose {@link #keyOf key} is in {@code keys} (the albums that hold a search match); null
     * shows them all. Combines with the artist filter.
     */
    public void setKeyFilter(Set<String> keys) {
        keyFilter = keys == null ? null : Set.copyOf(keys);
        refilter();
    }

    /** What two spellings of the same album share: artist and album, ignoring case. */
    public static String keyOf(String albumArtist, String album) {
        return albumArtist.toLowerCase(Locale.ROOT) + '\u001F' + album.toLowerCase(Locale.ROOT);
    }

    private void refilter() {
        if (artistFilter == null && keyFilter == null) {
            shown = all;
            return;
        }
        String want = artistFilter == null ? null : artistFilter.toLowerCase(Locale.ROOT);
        List<AlbumSummary> out = new ArrayList<>();
        for (AlbumSummary a : all) {
            if (want != null && !a.albumArtist().toLowerCase(Locale.ROOT).equals(want)) {
                continue;
            }
            if (keyFilter != null && !keyFilter.contains(keyOf(a.albumArtist(), a.album()))) {
                continue;
            }
            out.add(a);
        }
        shown = out;
    }

    /** How many albums are shown. */
    public int size() {
        return shown.size();
    }

    public AlbumSummary get(int index) {
        return shown.get(index);
    }

    /** Where {@code album} sits in the shown list; -1 if it is not shown (or null). */
    public int indexOf(AlbumSummary album) {
        return album == null ? -1 : shown.indexOf(album);
    }

    // ---- layout ----

    /** How many columns fit in {@code width} px; never fewer than one. */
    public static int columns(int width) {
        return Math.max(1, (width - 2 * MARGIN) / CELL_W);
    }

    /** The height the grid needs at {@code width}; 0 for no albums. */
    public int heightFor(int width) {
        if (shown.isEmpty()) {
            return 0;
        }
        int cols = columns(width);
        int rows = (shown.size() + cols - 1) / cols;
        return 2 * MARGIN + rows * CELL_H;
    }

    /** The cell of album {@code index}; also valid for the empty slot just past the last album. */
    public Rectangle cellBounds(int index, int width) {
        int cols = columns(width);
        return new Rectangle(MARGIN + index % cols * CELL_W, MARGIN + index / cols * CELL_H, CELL_W, CELL_H);
    }

    /** The cover's square inside its cell. */
    public Rectangle coverBounds(int index, int width) {
        Rectangle c = cellBounds(index, width);
        return new Rectangle(c.x + PAD, c.y + PAD, COVER, COVER);
    }

    /** The album at a point, or -1 for a margin, a gap past the last column or an empty slot. */
    public int indexAt(int x, int y, int width) {
        int cols = columns(width);
        int cx = x - MARGIN;
        int cy = y - MARGIN;
        if (cx < 0 || cy < 0 || cx >= cols * CELL_W) {
            return -1;
        }
        int i = cy / CELL_H * cols + cx / CELL_W;
        return i < shown.size() ? i : -1;
    }

    /** The albums whose rows touch the vertical span {@code [top, bottom)}, as {@code {first, endExclusive}}. */
    public int[] visibleRange(int top, int bottom, int width) {
        int cols = columns(width);
        int firstRow = Math.max(0, (top - MARGIN) / CELL_H);
        int lastRow = Math.max(0, (bottom - 1 - MARGIN) / CELL_H);
        int first = Math.min(shown.size(), firstRow * cols);
        int end = Math.min(shown.size(), (lastRow + 1) * cols);
        return new int[] {first, Math.max(first, end)};
    }
}
