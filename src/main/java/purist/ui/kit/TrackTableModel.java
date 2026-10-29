package purist.ui.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.table.AbstractTableModel;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.ui.Deck;

/**
 * The rows of a track table: one {@link TrackInfo} per row and a fixed choice of columns. Used by the search
 * results, a folder's files and an album's track list. A row for a file Purist cannot play is
 * {@linkplain #isUnsupported unsupported}, and the view draws it dim; the row whose file and subsong equal
 * {@link #setPlaying the playing track} is {@linkplain #isPlaying playing}, and the view draws it inverted.
 *
 * <p>Values are plain strings. EDT only.
 */
public final class TrackTableModel extends AbstractTableModel {

    /** What a column shows. */
    public enum Column {
        /** A marker on the playing row. */
        PLAYING(""),
        /** The row's position, such as {@code 3.}. */
        NUMBER("#"),
        TITLE("Title"),
        ARTIST("Artist"),
        ALBUM("Album"),
        /** {@code m:ss}, or {@code h:mm:ss} from an hour. */
        LENGTH("Length"),
        /** {@code FLAC 24/96} or {@code DSD128}; just the codec for a file with no format. */
        FORMAT("Format");

        private final String heading;

        Column(String heading) {
            this.heading = heading;
        }

        public String heading() {
            return heading;
        }
    }

    /** The marker shown in the {@link Column#PLAYING} column; the view falls back to a font that can draw it. */
    public static final String PLAYING_MARK = "▶";

    private final List<Column> columns;
    private List<TrackInfo> rows = List.of();
    private TrackRef playing;

    public TrackTableModel(List<Column> columns) {
        this.columns = List.copyOf(columns);
    }

    /** Replaces every row. */
    public void setRows(List<TrackInfo> rows) {
        this.rows = new ArrayList<>(rows);
        fireTableDataChanged();
    }

    public TrackInfo row(int row) {
        return rows.get(row);
    }

    public Column column(int column) {
        return columns.get(column);
    }

    /** Marks the track that is playing now; null for none. The mark survives {@link #setRows}. */
    public void setPlaying(TrackRef ref) {
        if (Objects.equals(ref, playing)) {
            return;
        }
        playing = ref;
        if (!rows.isEmpty()) {
            fireTableRowsUpdated(0, rows.size() - 1);
        }
    }

    public TrackRef playing() {
        return playing;
    }

    public boolean isPlaying(int row) {
        if (playing == null) {
            return false;
        }
        TrackInfo t = rows.get(row);
        return t.subsong() == playing.subsong() && t.path().equals(playing.path());
    }

    /** True for a file that will not be played (an MP3, a damaged file): shown dim. */
    public boolean isUnsupported(int row) {
        return !rows.get(row).supported();
    }

    /** The track the row stands for, as a queue entry. */
    public TrackRef ref(int row) {
        TrackInfo t = rows.get(row);
        return new TrackRef(t.path(), t.subsong());
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public String getColumnName(int column) {
        return columns.get(column).heading().isEmpty() ? "" : columns.get(column).heading();
    }

    @Override
    public Object getValueAt(int row, int column) {
        TrackInfo t = rows.get(row);
        return switch (columns.get(column)) {
            case PLAYING -> isPlaying(row) ? PLAYING_MARK : "";
            case NUMBER -> (row + 1) + ".";
            case TITLE -> t.displayTitle();
            case ARTIST -> t.displayArtist();
            case ALBUM -> t.displayAlbum();
            case LENGTH -> length(t);
            case FORMAT -> t.supported() ? t.badge() : t.codec();
        };
    }

    private static String length(TrackInfo t) {
        if (t.format() == null || t.format().lengthFrames() <= 0) {
            return "";
        }
        return Deck.time(t.format().seconds(t.format().lengthFrames()));
    }
}
