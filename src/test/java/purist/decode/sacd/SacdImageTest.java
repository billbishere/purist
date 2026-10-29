package purist.decode.sacd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.DecodeException;
import purist.decode.UnsupportedFormatException;
import purist.testutil.SacdImageBuilder;

class SacdImageTest {

    @TempDir
    Path dir;

    private SacdImageBuilder threeTracks() {
        return new SacdImageBuilder()
                .albumTitle("Stranglehold Live").albumArtist("Ted Nugent").date(2014, 6, 10)
                .track("Stranglehold", "Ted Nugent", 100)
                .track("Stormtroopin'", "Ted Nugent", 61)
                .track("Hey Baby", "Ted Nugent", 47);
    }

    // ---- master TOC and text ----------------------------------------------------------------------------------

    @Test
    void parsesMasterTocAndText() throws IOException {
        Path p = threeTracks().write(dir, "a.iso");
        try (SacdImage img = SacdImage.open(p)) {
            assertEquals(2048, img.sectorSize());
            assertEquals("Stranglehold Live", img.albumTitle());
            assertEquals("Ted Nugent", img.albumArtist());
            assertEquals(LocalDate.of(2014, 6, 10), img.date().orElseThrow());
        }
    }

    @Test
    void missingTextAndDateAreEmpty() throws IOException {
        Path p = new SacdImageBuilder().albumTitle(null).albumArtist(null).date(0, 0, 0)
                .track("x", "y", 10).write(dir, "a.iso");
        try (SacdImage img = SacdImage.open(p)) {
            assertEquals("", img.albumTitle());
            assertEquals("", img.albumArtist());
            assertTrue(img.date().isEmpty());
        }
    }

    @Test
    void impossibleDateIsEmpty() throws IOException {
        Path p = new SacdImageBuilder().date(2014, 13, 40).track("x", "y", 10).write(dir, "a.iso");
        try (SacdImage img = SacdImage.open(p)) {
            assertTrue(img.date().isEmpty());
        }
    }

    // ---- stereo area and tracks -------------------------------------------------------------------------------

    @Test
    void parsesStereoAreaAndTracks() throws IOException {
        SacdImageBuilder b = threeTracks();
        Path p = b.write(dir, "a.iso");
        try (SacdImage img = SacdImage.open(p)) {
            SacdArea a = img.stereoArea().orElseThrow();
            assertEquals(SacdImageBuilder.AREA_TOC, a.tocSector());
            assertEquals(4, a.fsCode());
            assertEquals(2, a.frameFormat());
            assertEquals(2, a.channels());
            assertEquals(2_822_400, a.dsdRate());
            assertFalse(a.isDst());
            assertEquals(SacdImageBuilder.AREA_START, a.trackAreaStart());
            assertEquals(b.areaEnd(), a.trackAreaEnd());
            assertEquals(3, a.tracks().size());
            for (int i = 0; i < 3; i++) {
                SacdTrack t = a.tracks().get(i);
                assertEquals(i + 1, t.number());
                assertEquals(b.startLsn(i), t.startLsn(), "start lsn " + i);
                assertEquals(b.lengthSectors(i), t.lengthSectors(), "length sectors " + i);
                assertEquals(b.startFrame(i), t.startTimeFrames(), "start time " + i);
                assertEquals(b.lengthFrames(i), t.lengthFrames(), "length frames " + i);
            }
            assertEquals(150, a.tracks().get(0).startTimeFrames());
            assertEquals(150 + 100, a.tracks().get(1).startTimeFrames());
        }
    }

    @Test
    void timeCodeIsMinutesSecondsFrames() throws IOException {
        // a start of 1 min 12 s 3 frames = 5403, a length of 1 min 10 s 12 frames = 5262
        Path p = new SacdImageBuilder().noAudio().leadInFrames((1 * 60 + 12) * 75 + 3)
                .track("t", "a", (1 * 60 + 10) * 75 + 12).write(dir, "a.iso");
        try (SacdImage img = SacdImage.open(p)) {
            SacdTrack t = img.stereoArea().orElseThrow().tracks().get(0);
            assertEquals(5403, t.startTimeFrames());
            assertEquals(5262, t.lengthFrames());
        }
    }

    @Test
    void readSectorGivesThe2048BytePayload() throws IOException {
        byte[] image = threeTracks().build();
        Path p = dir.resolve("a.iso");
        Files.write(p, image);
        try (SacdImage img = SacdImage.open(p)) {
            byte[] s = img.readSector(SacdImageBuilder.AREA_START);
            assertEquals(2048, s.length);
            assertArrayEquals(Arrays.copyOfRange(image, 624 * 2048, 625 * 2048), s);
            assertEquals("SACDMTOC", new String(img.readSector(510), 0, 8));
        }
    }

    @Test
    void readingBeyondTheImageIsDamage() throws IOException {
        Path p = threeTracks().write(dir, "a.iso");
        try (SacdImage img = SacdImage.open(p)) {
            assertThrows(DecodeException.class, () -> img.readSector(10_000_000L));
            assertThrows(DecodeException.class, () -> img.readSector(-1));
        }
    }

    // ---- track text -------------------------------------------------------------------------------------------

    @Test
    void trackTextTitleAndPerformerWithPadding() throws IOException {
        // string lengths 1..8 cover every padding remainder of the 4-byte alignment
        SacdImageBuilder b = new SacdImageBuilder().noAudio();
        String[] titles = {"A", "AB", "ABC", "ABCD", "ABCDE", "ABCDEF", "ABCDEFG", "ABCDEFGH"};
        for (int i = 0; i < titles.length; i++) {
            b.track(titles[i], titles[titles.length - 1 - i], 5);
        }
        try (SacdImage img = SacdImage.open(b.write(dir, "a.iso"))) {
            List<SacdTrack> tracks = img.stereoArea().orElseThrow().tracks();
            assertEquals(8, tracks.size());
            for (int i = 0; i < titles.length; i++) {
                assertEquals(titles[i], tracks.get(i).tags().first("TITLE").orElseThrow(), "title " + i);
                assertEquals(titles[titles.length - 1 - i], tracks.get(i).tags().first("ARTIST").orElseThrow(),
                        "performer " + i);
            }
        }
    }

    @Test
    void trackTextReachesIntoTheFollowingSectorsAndKeepsLatin1() throws IOException {
        SacdImageBuilder b = new SacdImageBuilder().noAudio();
        for (int i = 0; i < 20; i++) {
            b.track("Track number " + (i + 1) + " café", "Performer " + (i + 1), 3);
        }
        try (SacdImage img = SacdImage.open(b.write(dir, "a.iso"))) {
            List<SacdTrack> tracks = img.stereoArea().orElseThrow().tracks();
            assertEquals(20, tracks.size());
            assertEquals("Track number 20 café", tracks.get(19).tags().first("TITLE").orElseThrow());
            assertEquals("Performer 20", tracks.get(19).tags().first("ARTIST").orElseThrow());
        }
    }

    @Test
    void otherTextItemsAreMappedAndTracksWithoutTextHaveNoTags() throws IOException {
        SacdImageBuilder b = new SacdImageBuilder().noAudio()
                .track("One", "Artist One", 5).textItem(3, "Writer").textItem(4, "Composer")
                .textItem(5, "Arranger").textItem(6, "A message")
                .track(null, null, 5)
                .track("Three", null, 5);
        try (SacdImage img = SacdImage.open(b.write(dir, "a.iso"))) {
            List<SacdTrack> tracks = img.stereoArea().orElseThrow().tracks();
            SacdTrack one = tracks.get(0);
            assertEquals("Writer", one.tags().first("LYRICIST").orElseThrow());
            assertEquals("Composer", one.tags().first("COMPOSER").orElseThrow());
            assertEquals("Arranger", one.tags().first("ARRANGER").orElseThrow());
            assertEquals("A message", one.tags().first("COMMENT").orElseThrow());
            assertTrue(tracks.get(1).tags().isEmpty());
            assertEquals("Three", tracks.get(2).tags().first("TITLE").orElseThrow());
            assertTrue(tracks.get(2).tags().first("ARTIST").isEmpty());
        }
    }

    // ---- 2064-byte sectors ------------------------------------------------------------------------------------

    @Test
    void detects2064ByteSectors() throws IOException {
        SacdImageBuilder b = threeTracks().sectorSize(2064);
        byte[] raw = b.build();
        Path p = dir.resolve("raw.iso");
        Files.write(p, raw);
        try (SacdImage img = SacdImage.open(p)) {
            assertEquals(2064, img.sectorSize());
            assertEquals("Stranglehold Live", img.albumTitle());
            SacdArea a = img.stereoArea().orElseThrow();
            assertEquals(3, a.tracks().size());
            assertEquals("Stormtroopin'", a.tracks().get(1).tags().first("TITLE").orElseThrow());
            // the payload skips the 12-byte header
            byte[] s = img.readSector(SacdImageBuilder.AREA_START);
            assertArrayEquals(Arrays.copyOfRange(raw, 624 * 2064 + 12, 624 * 2064 + 12 + 2048), s);
        }
    }

    // ---- DST, other areas, not SACD ---------------------------------------------------------------------------

    @Test
    void dstAreaFlagged() throws IOException {
        Path p = threeTracks().frameFormat(0).write(dir, "dst.iso");
        try (SacdImage img = SacdImage.open(p)) {
            SacdArea a = img.stereoArea().orElseThrow();
            assertTrue(a.isDst());
            assertEquals(0, a.frameFormat());
            assertEquals(3, a.tracks().size());
        }
    }

    @Test
    void frameFormat3Accepted() throws IOException {
        try (SacdImage img = SacdImage.open(threeTracks().frameFormat(3).write(dir, "a.iso"))) {
            assertEquals(3, img.stereoArea().orElseThrow().frameFormat());
            assertFalse(img.stereoArea().orElseThrow().isDst());
        }
    }

    @Test
    void unknownFrameFormatIsUnsupported() throws IOException {
        Path p = threeTracks().frameFormat(7).write(dir, "a.iso");
        assertThrows(UnsupportedFormatException.class, () -> SacdImage.open(p));
    }

    @Test
    void otherSamplingFrequencyIsUnsupportedAtOpen() throws IOException {
        Path p = threeTracks().fsCode(5).write(dir, "a.iso");
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> SacdImage.open(p));
        assertTrue(e.reason().contains("5"), e.reason());
    }

    @Test
    void noStereoAreaIsEmpty() throws IOException {
        try (SacdImage img = SacdImage.open(threeTracks().noStereoArea().write(dir, "a.iso"))) {
            assertTrue(img.stereoArea().isEmpty());
            assertEquals("Stranglehold Live", img.albumTitle());
        }
    }

    @Test
    void areaWithoutTrackListIsDamaged() throws IOException {
        byte[] image = threeTracks().build();
        int at = (SacdImageBuilder.AREA_TOC + 1) * 2048;
        Arrays.fill(image, at, at + 8, (byte) 0); // blank the SACDTRL1 id
        Path p = dir.resolve("a.iso");
        Files.write(p, image);
        assertThrows(DecodeException.class, () -> SacdImage.open(p));
    }

    @Test
    void areaTocNotWhereTheMasterSaysIsDamaged() throws IOException {
        byte[] image = threeTracks().build();
        int at = SacdImageBuilder.AREA_TOC * 2048;
        Arrays.fill(image, at, at + 8, (byte) 0);
        Path p = dir.resolve("a.iso");
        Files.write(p, image);
        assertThrows(DecodeException.class, () -> SacdImage.open(p));
    }

    @Test
    void notSacdThrows() throws IOException {
        Path zeros = dir.resolve("zeros.iso");
        Files.write(zeros, new byte[3_000_000]);
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> SacdImage.open(zeros));
        assertEquals("not an SACD image", e.reason());

        Path tiny = dir.resolve("tiny.iso");
        Files.write(tiny, new byte[100]);
        assertEquals("not an SACD image",
                assertThrows(UnsupportedFormatException.class, () -> SacdImage.open(tiny)).reason());

        Path empty = dir.resolve("empty.iso");
        Files.write(empty, new byte[0]);
        assertThrows(UnsupportedFormatException.class, () -> SacdImage.open(empty));
    }
}
