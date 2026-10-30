package purist.decode.cue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;

class CueHandlerTest {

    @TempDir
    Path dir;

    private final DecoderRegistry registry = DecoderRegistry.standard();

    /** 44.1 kHz: one CD frame is 588 samples. */
    private static final int CD = 588;

    private static final String SHEET = """
            REM GENRE "Jazz"
            REM DATE 1959
            PERFORMER "The Quartet"
            TITLE "Blue Album"
            FILE "album.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Opening"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Second"
                PERFORMER "Guest Singer"
                INDEX 00 00:00:10
                INDEX 01 00:00:20
              TRACK 03 AUDIO
                TITLE "Closing"
                FLAGS DCP
                INDEX 01 00:00:50
            """;

    // ---- the sheet ----

    @Test
    void parsesAlbumAndTrackFields() throws Exception {
        CueSheet s = CueSheet.parse(SHEET, dir);
        assertEquals("Blue Album", s.album().first("ALBUM").orElseThrow());
        assertEquals("The Quartet", s.album().first("ALBUMARTIST").orElseThrow());
        assertEquals("1959", s.album().first("DATE").orElseThrow());
        assertEquals("Jazz", s.album().first("GENRE").orElseThrow());
        assertEquals(3, s.tracks().size());
        CueSheet.Track two = s.tracks().get(1);
        assertEquals(2, two.number());
        assertEquals(dir.resolve("album.wav"), two.file());
        assertEquals(20, two.start(), "INDEX 01, not INDEX 00");
        assertEquals("Guest Singer", two.tags().first("ARTIST").orElseThrow());
        assertFalse(s.perTrackFiles());
    }

    @Test
    void minutesBeyondNinetyNineAndUnquotedNames() throws Exception {
        CueSheet s = CueSheet.parse("FILE long.flac WAVE\nTRACK 1 AUDIO\nINDEX 01 120:30:74\n", dir);
        assertEquals(dir.resolve("long.flac"), s.tracks().get(0).file());
        assertEquals((120 * 60 + 30) * 75L + 74, s.tracks().get(0).start());
    }

    @Test
    void dataTracksAreLeftOut() throws Exception {
        CueSheet s = CueSheet.parse("""
                FILE "a.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                TRACK 02 MODE1/2352
                INDEX 01 01:00:00
                """, dir);
        assertEquals(1, s.tracks().size());
    }

    @Test
    void aSheetOverSeparateTrackFilesIsRecognised() throws Exception {
        CueSheet s = CueSheet.parse("""
                FILE "01.flac" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                FILE "02.flac" WAVE
                TRACK 02 AUDIO
                INDEX 00 00:00:00
                INDEX 01 00:01:50
                """, dir);
        assertTrue(s.perTrackFiles(), "a pre-gap kept at the start of a track file does not make it an image");
    }

    @Test
    void malformedSheetsAreDecodeErrors() {
        assertThrows(DecodeException.class, () -> CueSheet.parse("TITLE \"x\"\n", dir));
        assertThrows(DecodeException.class, () -> CueSheet.parse("TRACK 01 AUDIO\nINDEX 01 00:00:00\n", dir));
        assertThrows(DecodeException.class, () -> CueSheet.parse("FILE a.wav WAVE\nTRACK 01 AUDIO\n", dir));
        assertThrows(DecodeException.class,
                () -> CueSheet.parse("FILE a.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 00:61:00\n", dir));
    }

    @Test
    void textIsUtf8WhenValidOtherwiseTheAnsiCodePage() {
        String title = "Caf\u00e9";
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] utf8 = title.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[3 + utf8.length];
        System.arraycopy(bom, 0, withBom, 0, 3);
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);
        assertEquals(title, CueSheet.text(utf8));
        assertEquals(title, CueSheet.text(withBom));
        Charset ansi = Charset.forName(System.getProperty("native.encoding", "windows-1252"));
        if (ansi.newEncoder().canEncode(title) && !ansi.equals(StandardCharsets.UTF_8)) {
            assertEquals(title, CueSheet.text(title.getBytes(ansi)));
        }
    }

    // ---- the handler, over a real WAV whose sample n holds the value n ----

    @Test
    void probeListsEachTrackWithItsExactLengthAndTags() throws Exception {
        Path cue = album(70 * CD);
        List<TrackInfo> tracks = registry.probe(cue);
        assertEquals(3, tracks.size());
        assertEquals(List.of(20L * CD, 30L * CD, 20L * CD),
                tracks.stream().map(t -> t.format().lengthFrames()).toList());
        TrackInfo two = tracks.get(1);
        assertEquals(cue, two.path());
        assertEquals(2, two.subsong());
        assertEquals("WAV 16/44.1", two.badge());
        assertEquals("Second", two.tags().first("TITLE").orElseThrow());
        assertEquals("Guest Singer", two.tags().first("ARTIST").orElseThrow());
        assertEquals("The Quartet", tracks.get(0).tags().first("ARTIST").orElseThrow());
        assertEquals("Blue Album", two.tags().first("ALBUM").orElseThrow());
        assertEquals("2", two.tags().first("TRACKNUMBER").orElseThrow());
        assertEquals("3", two.tags().first("TOTALTRACKS").orElseThrow());
    }

    @Test
    void theTracksTogetherAreTheWholeFileSampleForSample() throws Exception {
        Path cue = album(70 * CD + 123); // the last track ends with the file, off a CD-frame boundary
        int next = 0;
        for (int n = 1; n <= 3; n++) {
            try (Decoder d = registry.open(new TrackRef(cue, n)); Arena a = Arena.ofConfined()) {
                assertEquals(0, d.position());
                MemorySegment buf = a.allocate(4096L * d.format().bytesPerDecodedFrame());
                int got;
                while ((got = d.read(buf, 4096)) > 0) {
                    for (int i = 0; i < got; i++) {
                        assertEquals(value(next), buf.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2L * i),
                                "track " + n + " sample " + i);
                        next++;
                    }
                }
                assertEquals(d.format().lengthFrames(), d.position());
            }
        }
        assertEquals(70 * CD + 123, next, "no sample lost or repeated");
    }

    @Test
    void seekIsRelativeToTheTrackAndStopsAtItsEnd() throws Exception {
        Path cue = album(70 * CD);
        try (Decoder d = registry.open(new TrackRef(cue, 2)); Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(16L * d.format().bytesPerDecodedFrame());
            d.seek(100);
            assertEquals(100, d.position());
            assertEquals(1, d.read(buf, 1));
            assertEquals(value(20 * CD + 100), buf.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0));
            d.seek(Long.MAX_VALUE / 2);
            assertEquals(0, d.read(buf, 16));
            d.seek(30L * CD - 3);
            assertEquals(3, d.read(buf, 16), "the read stops at the next track");
        }
    }

    @Test
    void aRenamedAudioFileIsFoundByItsOtherExtension() throws Exception {
        Path cue = album(70 * CD);
        Files.writeString(cue, SHEET.replace("album.wav", "album.flac")); // the sheet still names the FLAC
        assertEquals(3, registry.probe(cue).size());
        assertEquals(List.of(dir.resolve("album.wav")), new CueHandler(registry).audioFiles(cue));
    }

    @Test
    void aSheetLeftBehindAfterItsAlbumFileWasSplitAddsNothing() throws Exception {
        Path cue = dir.resolve("album.cue");
        Files.writeString(cue, SHEET);
        assertTrue(registry.probe(cue).isEmpty());
        assertTrue(new CueHandler(registry).audioFiles(cue).isEmpty());
        assertThrows(DecodeException.class, () -> registry.open(new TrackRef(cue, 1)));
    }

    @Test
    void aCloudOnlyAlbumFileIsReadFromTheSheetAlone() throws Exception {
        Path cue = album(70 * CD);
        List<TrackInfo> sheetOnly = new CueHandler(registry).probe(cue, false);
        assertEquals(3, sheetOnly.size());
        assertNull(sheetOnly.get(0).format());
        assertTrue(sheetOnly.get(0).supported());
    }

    @Test
    void anUnsupportedAudioFileGreysOutTheSheet() throws Exception {
        Path cue = dir.resolve("album.cue");
        Files.writeString(cue, SHEET.replace("album.wav", "album.ape"));
        Files.write(dir.resolve("album.ape"), new byte[64]);
        List<TrackInfo> rows = registry.probe(cue);
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).supported());
        assertTrue(rows.get(0).reason().contains("APE"), rows.get(0).reason());
    }

    @Test
    void aSheetOverSeparateFilesHasNoTracksOfItsOwn() throws Exception {
        Path cue = dir.resolve("rip.cue");
        Files.writeString(cue, "FILE \"01.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n");
        assertTrue(registry.probe(cue).isEmpty());
        assertTrue(new CueHandler(registry).audioFiles(cue).isEmpty());
    }

    @Test
    void trackRefsKeepTheSheetsTrackNumber() {
        TrackRef ref = new TrackRef(Path.of("C:\\Music\\A #1\\album.cue"), 7);
        assertEquals("file://C:\\Music\\A #1\\album.cue#7", ref.toUri());
        assertEquals(ref, TrackRef.parse(ref.toUri()));
    }

    // ---- fixtures ----

    /** album.wav (16-bit stereo, 44.1 kHz, {@code frames} long, sample n = {@link #value}) and album.cue. */
    private Path album(int frames) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 4)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(44100).putInt(44100 * 4).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 4);
        for (int i = 0; i < frames; i++) {
            short v = (short) value(i);
            b.putShort(v).putShort(v);
        }
        Files.write(dir.resolve("album.wav"), b.array());
        Path cue = dir.resolve("album.cue");
        Files.writeString(cue, SHEET);
        return cue;
    }

    /** The 16-bit value of sample {@code n}, as the decoder hands it out (sign-extended). */
    private static int value(int n) {
        return (short) (n * 7);
    }
}
