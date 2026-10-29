package purist.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.tags.TagMap;
import purist.ui.kit.TrackTableModel.Column;

class TrackTableModelTest {

    private static TagMap tags(String... kv) {
        TagMap t = new TagMap();
        for (int i = 0; i < kv.length; i += 2) {
            t.add(kv[i], kv[i + 1]);
        }
        return t;
    }

    private static TrackInfo flac() {
        // 4:20 at 96 kHz
        return new TrackInfo(Path.of("E:\\Music\\Thriller\\04 Thriller.flac"), 0, "FLAC",
                StreamFormat.pcm(96000, 24, 2, 260L * 96000),
                tags("TITLE", "Thriller", "ARTIST", "Michael Jackson", "ALBUM", "Thriller"), true, null);
    }

    private static TrackInfo mp3() {
        return TrackInfo.unsupported(Path.of("E:\\Music\\x\\song.mp3"), "MP3", "MP3 is not bit-perfect");
    }

    @Test
    void valuesAndFormatting() {
        TrackTableModel m = new TrackTableModel(List.of(Column.NUMBER, Column.TITLE, Column.ARTIST, Column.ALBUM,
                Column.LENGTH, Column.FORMAT));
        m.setRows(List.of(flac(), mp3()));

        assertEquals(2, m.getRowCount());
        assertEquals(6, m.getColumnCount());
        assertEquals("1.", m.getValueAt(0, 0));
        assertEquals("Thriller", m.getValueAt(0, 1));
        assertEquals("Michael Jackson", m.getValueAt(0, 2));
        assertEquals("Thriller", m.getValueAt(0, 3));
        assertEquals("4:20", m.getValueAt(0, 4));
        assertEquals("FLAC 24/96", m.getValueAt(0, 5));
        assertFalse(m.isUnsupported(0));
        assertTrue(m.isUnsupported(1), "MP3 rows are shown greyed out");
        assertEquals("MP3", m.getValueAt(1, 5));
        assertEquals("song", m.getValueAt(1, 1), "no title: the file name without its extension");
        assertEquals("", m.getValueAt(1, 4), "no format, no length");
    }

    @Test
    void lengthsFromAnHourOnShowHours() {
        TrackInfo long1 = new TrackInfo(Path.of("a.wav"), 0, "WAV", StreamFormat.pcm(44100, 16, 2, 3725L * 44100),
                new TagMap(), true, null);
        TrackTableModel m = new TrackTableModel(List.of(Column.LENGTH));
        m.setRows(List.of(long1));
        assertEquals("1:02:05", m.getValueAt(0, 0));
    }

    @Test
    void theRowIsTheTrackInfoWeSetAndTheColumnIsTheOneAsked() {
        TrackInfo t = flac();
        TrackTableModel m = new TrackTableModel(List.of(Column.PLAYING, Column.TITLE));
        m.setRows(List.of(t));
        assertSame(t, m.row(0));
        assertEquals(Column.TITLE, m.column(1));
        assertEquals("", m.getColumnName(0));
    }

    @Test
    void thePlayingRowIsFoundByFileAndSubsong() {
        TrackInfo a = flac();
        TrackInfo iso1 = new TrackInfo(Path.of("E:\\sacd\\x.iso"), 1, "ISO", StreamFormat.pcm(44100, 16, 2, 100),
                new TagMap(), true, null);
        TrackInfo iso2 = new TrackInfo(Path.of("E:\\sacd\\x.iso"), 2, "ISO", StreamFormat.pcm(44100, 16, 2, 100),
                new TagMap(), true, null);
        TrackTableModel m = new TrackTableModel(List.of(Column.PLAYING, Column.TITLE));
        m.setRows(List.of(a, iso1, iso2));

        assertFalse(m.isPlaying(0));
        m.setPlaying(new TrackRef(Path.of("E:\\sacd\\x.iso"), 2));
        assertFalse(m.isPlaying(1), "another subsong of the same ISO is another track");
        assertTrue(m.isPlaying(2));
        assertEquals("\u25B6", m.getValueAt(2, 0));
        assertEquals("", m.getValueAt(1, 0));
        m.setPlaying(null);
        assertFalse(m.isPlaying(2));
        assertNull(m.playing());
    }

    @Test
    void settingRowsAgainKeepsThePlayingMark() {
        TrackInfo a = flac();
        TrackTableModel m = new TrackTableModel(List.of(Column.PLAYING));
        m.setPlaying(new TrackRef(a.path(), 0));
        m.setRows(List.of(a));
        assertTrue(m.isPlaying(0));
    }
}
