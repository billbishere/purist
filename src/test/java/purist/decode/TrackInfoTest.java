package purist.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.tags.TagMap;

class TrackInfoTest {

    private static final Path PATH = Path.of("music", "Some Album", "01 Song.flac");

    private static TrackInfo info(TagMap tags) {
        return new TrackInfo(PATH, 0, "FLAC", StreamFormat.pcm(96000, 24, 2, 100), tags, true, null);
    }

    @Test
    void taggedFieldsWin() {
        TagMap t = new TagMap();
        t.add("TITLE", "Real Title");
        t.add("ARTIST", "Real Artist");
        t.add("ALBUM", "Real Album");
        t.add("ALBUMARTIST", "Album Artist");
        TrackInfo i = info(t);
        assertEquals("Real Title", i.displayTitle());
        assertEquals("Real Artist", i.displayArtist());
        assertEquals("Real Album", i.displayAlbum());
        assertEquals("Album Artist", i.albumArtist());
    }

    @Test
    void untaggedFallsBackToFilenameAndFolder() {
        TrackInfo i = info(new TagMap());
        assertEquals("01 Song", i.displayTitle());
        assertEquals("", i.displayArtist());
        assertEquals("Some Album", i.displayAlbum());
        assertEquals("Unknown artist", i.albumArtist());
    }

    @Test
    void filenameWithoutExtensionKeepsInnerDots() {
        TrackInfo i = new TrackInfo(Path.of("a", "b", "Mr. Brightside (v1.2).wav"), 0, "WAV", null,
                new TagMap(), true, null);
        assertEquals("Mr. Brightside (v1.2)", i.displayTitle());
        TrackInfo noExt = new TrackInfo(Path.of("a", "b", "README"), 0, "WAV", null, new TagMap(), true, null);
        assertEquals("README", noExt.displayTitle());
        TrackInfo dotfile = new TrackInfo(Path.of("a", "b", ".hidden"), 0, "WAV", null, new TagMap(), true, null);
        assertEquals(".hidden", dotfile.displayTitle());
    }

    @Test
    void artistFallsBackToAlbumArtistThenEmpty() {
        TagMap onlyAlbumArtist = new TagMap();
        onlyAlbumArtist.add("ALBUMARTIST", "AA");
        assertEquals("AA", info(onlyAlbumArtist).displayArtist());
        assertEquals("AA", info(onlyAlbumArtist).albumArtist());

        TagMap onlyArtist = new TagMap();
        onlyArtist.add("ARTIST", "Solo");
        assertEquals("Solo", info(onlyArtist).displayArtist());
        assertEquals("Solo", info(onlyArtist).albumArtist());
    }

    @Test
    void albumFallbackWithNoParentFolderIsEmptyNotNull() {
        TrackInfo i = new TrackInfo(Path.of("lonely.wav"), 0, "WAV", null, new TagMap(), true, null);
        assertEquals("", i.displayAlbum());
        assertEquals("lonely", i.displayTitle());
    }

    @Test
    void nullTagsBehaveAsEmpty() {
        TrackInfo i = new TrackInfo(PATH, 0, "FLAC", null, null, true, null);
        assertNotNull(i.tags());
        assertEquals("01 Song", i.displayTitle());
    }

    @Test
    void pcmBadgeIsCodecAndLabel() {
        assertEquals("FLAC 24/96", info(new TagMap()).badge());
        TrackInfo wav = new TrackInfo(PATH, 0, "WAV", StreamFormat.pcm(44100, 16, 2, 0), new TagMap(), true, null);
        assertEquals("WAV 16/44.1", wav.badge());
    }

    @Test
    void dsdBadgeIsJustTheLabel() {
        TrackInfo i = new TrackInfo(PATH, 0, "DSF", StreamFormat.dsd(5644800, 2, 0, BitOrder.LSB_FIRST),
                new TagMap(), true, null);
        assertEquals("DSD128", i.badge());
    }

    @Test
    void unsupportedCarriesNoFormatAndAReason() {
        Path mp3 = Path.of("m", "x.mp3");
        TrackInfo i = TrackInfo.unsupported(mp3, "MP3", "MP3 is not supported in v1");
        assertFalse(i.supported());
        assertEquals("MP3 is not supported in v1", i.reason());
        assertNull(i.format());
        assertEquals(mp3, i.path());
        assertEquals(0, i.subsong());
        assertEquals("MP3", i.codec());
        assertEquals("MP3", i.badge());
        assertEquals("x", i.displayTitle());
        assertEquals("m", i.displayAlbum());
        assertEquals("", i.displayArtist());
        assertEquals("Unknown artist", i.albumArtist());
        assertTrue(i.tags().isEmpty());
    }

    @Test
    void cloudOnlyIsPlayableLaterSoItIsSupportedButHasNoFormat() {
        Path p = Path.of("c", "y.flac");
        TrackInfo i = TrackInfo.cloudOnly(p, "FLAC");
        assertTrue(i.supported());
        assertNull(i.format());
        assertNull(i.reason());
        assertEquals("FLAC", i.badge());
        assertEquals("y", i.displayTitle());
        assertTrue(i.tags().isEmpty());
    }
}
