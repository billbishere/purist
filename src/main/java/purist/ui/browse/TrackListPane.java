package purist.ui.browse;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.ui.kit.PlainText;
import purist.ui.kit.TextButton;
import purist.ui.kit.TrackTable;
import purist.ui.kit.TrackTableModel.Column;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;

/**
 * A track table with {@code PLAY} and {@code + QUEUE} above it, for what a folder holds and for search results.
 * PLAY replaces the playlist with every row and plays the first; + QUEUE appends every row. Double-click plays the
 * list from that row, as in the album panel. EDT only.
 */
public final class TrackListPane extends JPanel {

    private final TrackTable table = new TrackTable(
            List.of(Column.NUMBER, Column.TITLE, Column.ARTIST, Column.ALBUM, Column.LENGTH, Column.FORMAT), 13, 26);
    private final TextButton play = new TextButton("Play", true);
    private final TextButton queue = new TextButton("+ Queue", false);
    private final JLabel note = PlainText.of(new JLabel(" "));

    public TrackListPane(BiConsumer<List<TrackRef>, Integer> onPlay, Consumer<List<TrackRef>> onAdd) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        table.onPlay(onPlay);
        table.onAdd(onAdd);
        play.addActionListener(e -> {
            if (table.rowCount() > 0) {
                onPlay.accept(table.allRefs(), 0);
            }
        });
        queue.addActionListener(e -> {
            if (table.rowCount() > 0) {
                onAdd.accept(table.allRefs());
            }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.add(play);
        bar.add(queue);
        bar.add(note);
        bar.setBorder(new EmptyBorder(0, 0, 0, 0));
        note.setFont(Fonts.mono(12f));
        add(bar, BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        restyle();
        PuristTheme.addListener(s -> restyle());
        setRows(List.of(), "");
    }

    /** Shows {@code rows}; {@code message} is a dim line beside the buttons, such as "5,000+ tracks: showing the first". */
    public void setRows(List<TrackInfo> rows, String message) {
        table.setRows(rows);
        play.setEnabled(!rows.isEmpty());
        queue.setEnabled(!rows.isEmpty());
        note.setText(message == null || message.isEmpty() ? " " : message);
    }

    public void setPlaying(TrackRef ref) {
        table.setPlaying(ref);
    }

    private void restyle() {
        note.setForeground(PuristTheme.skin().listDim);
    }
}
