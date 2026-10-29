package purist.tags;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static purist.testutil.Id3Writer.LATIN1;
import static purist.testutil.Id3Writer.UTF16;
import static purist.testutil.Id3Writer.UTF16BE;
import static purist.testutil.Id3Writer.UTF8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.io.FileSource;
import purist.testutil.Id3Writer;

class Id3v2Test {

    @TempDir
    Path dir;

    /** Starts like a JPEG and holds every byte pattern that unsynchronisation has to escape. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            'J', 'F', 'I', 'F', 0x00, 0x01, (byte) 0xFF, 0x00, 0x7F, (byte) 0xFF, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    private static Id3Result parse(Id3Writer w) {
        return Id3v2.parse(w.build());
    }

    private static List<String> all(Id3Result r, String key) {
        return r.tags().all(key);
    }

    /** The frames of a tag without its ten header bytes. */
    private static byte[] frames(Id3Writer w) {
        byte[] tag = w.build();
        return Arrays.copyOfRange(tag, 10, tag.length);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    // ---- text encodings --------------------------------------------------------------------------

    @Test
    void v23Latin1Title() {
        Id3Result r = parse(Id3Writer.v23().text("TIT2", LATIN1, "Café"));
        assertEquals(Optional.of("Café"), r.tags().first("TITLE"));
        assertTrue(r.frontCover().isEmpty());
    }

    @Test
    void utf8BytesInALatin1FrameAreStillRead() {
        // Some taggers write UTF-8 into a frame that says ISO-8859-1.
        byte[] payload = concat(new byte[] {0}, "Motörhead".getBytes(StandardCharsets.UTF_8));
        Id3Result r = parse(Id3Writer.v23().frame("TPE1", 0, payload));
        assertEquals(List.of("Motörhead"), all(r, "ARTIST"));
    }

    @Test
    void v24Utf8MultiValueArtists() {
        Id3Result r = parse(Id3Writer.v24().text("TPE1", UTF8, "Alice Ström", "Bob"));
        assertEquals(List.of("Alice Ström", "Bob"), all(r, "ARTIST"));
    }

    @Test
    void utf16BomTitle() {
        Id3Result r = parse(Id3Writer.v23().text("TIT2", UTF16, "Motörhead"));
        assertEquals(Optional.of("Motörhead"), r.tags().first("TITLE"));
    }

    @Test
    void utf16BigEndianBomIsHonoured() {
        byte[] payload = concat(new byte[] {1}, Id3Writer.utf16("Motörhead ♪", false, true));
        Id3Result r = parse(Id3Writer.v23().frame("TIT2", 0, payload));
        assertEquals(Optional.of("Motörhead ♪"), r.tags().first("TITLE"));
    }

    @Test
    void utf16WithoutBomIsAssumedLittleEndian() {
        byte[] payload = concat(new byte[] {1}, Id3Writer.utf16("Motörhead", true, false));
        Id3Result r = parse(Id3Writer.v23().frame("TIT2", 0, payload));
        assertEquals(Optional.of("Motörhead"), r.tags().first("TITLE"));
    }

    @Test
    void utf16BeWithoutBom() {
        Id3Result r = parse(Id3Writer.v24().text("TIT2", UTF16BE, "Motörhead 日本"));
        assertEquals(Optional.of("Motörhead 日本"), r.tags().first("TITLE"));
    }

    @Test
    void utf16SurrogatePairsSurvive() {
        Id3Result r = parse(Id3Writer.v24().text("TIT2", UTF16, "Note 𝄞"));
        assertEquals(Optional.of("Note 𝄞"), r.tags().first("TITLE"));
    }

    @Test
    void utf16MultiValueTerminatorMustBeAligned() {
        // "A" then U+4100 is 41 00 00 41 in little-endian: the 00 00 straddling the two characters is not a
        // terminator, the 00 00 after the second one is.
        Id3Result r = parse(Id3Writer.v24().text("TPE1", UTF16, "A䄀", "B"));
        assertEquals(List.of("A䄀", "B"), all(r, "ARTIST"));
    }

    @Test
    void utf8ByteOrderMarkIsDropped() {
        byte[] bom = {3, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] payload = concat(bom, "Title".getBytes(StandardCharsets.UTF_8));
        Id3Result r = parse(Id3Writer.v24().frame("TIT2", 0, payload));
        assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
    }

    @Test
    void trailingTerminatorAndBlankValuesAreDropped() {
        Id3Result r = parse(Id3Writer.v24().text("TIT2", LATIN1, "Title", "").text("TPE1", UTF16, "", "Artist", ""));
        assertEquals(List.of("Title"), all(r, "TITLE"));
        assertEquals(List.of("Artist"), all(r, "ARTIST"));
    }

    @Test
    void anOddTrailingByteInUtf16IsIgnored() {
        byte[] payload = concat(new byte[] {1}, Id3Writer.utf16("Hi", true, true), new byte[] {0x41});
        Id3Result r = parse(Id3Writer.v23().frame("TIT2", 0, payload));
        assertEquals(Optional.of("Hi"), r.tags().first("TITLE"));
    }

    // ---- frame sizes, padding, extended header ---------------------------------------------------

    @Test
    void frameSizesAreSyncsafeInV24AndPlainInV23() {
        String longTitle = "x".repeat(300); // 301 bytes: 0x012D plain, but 0x02 0x2D syncsafe
        String longer = "y".repeat(200); // 201 bytes = 0xC9: not a legal syncsafe byte, fine as a plain one
        Id3Result v23 = parse(Id3Writer.v23().text("TIT2", UTF8, longTitle).text("TALB", UTF8, longer)
                .text("TPE1", UTF8, "After"));
        assertEquals(Optional.of(longTitle), v23.tags().first("TITLE"));
        assertEquals(Optional.of(longer), v23.tags().first("ALBUM"));
        assertEquals(Optional.of("After"), v23.tags().first("ARTIST"));
        Id3Result v24 = parse(Id3Writer.v24().text("TIT2", UTF8, longTitle).text("TALB", UTF8, longer)
                .text("TPE1", UTF8, "After"));
        assertEquals(Optional.of(longTitle), v24.tags().first("TITLE"));
        assertEquals(Optional.of(longer), v24.tags().first("ALBUM"));
        assertEquals(Optional.of("After"), v24.tags().first("ARTIST"));
    }

    @Test
    void aV24TagWithPlainFrameSizesStillReads() {
        // iTunes wrote ordinary big-endian frame sizes into 2.4 tags. 200 bytes of text: 0x00 0x00 0x00 0xC9.
        String album = "z".repeat(200);
        byte[] payload = concat(new byte[] {3}, album.getBytes(StandardCharsets.UTF_8));
        byte[] frames = concat("TALB".getBytes(StandardCharsets.ISO_8859_1), new byte[] {0, 0, 0, (byte) 201, 0, 0},
                payload, "TIT2".getBytes(StandardCharsets.ISO_8859_1), new byte[] {0, 0, 0, 3, 0, 0},
                new byte[] {3, 'O', 'K'});
        byte[] tag = concat(new byte[] {'I', 'D', '3', 4, 0, 0}, Id3Writer.syncsafe(frames.length), frames);
        Id3Result r = Id3v2.parse(tag);
        assertEquals(Optional.of(album), r.tags().first("ALBUM"));
        assertEquals(Optional.of("OK"), r.tags().first("TITLE"));
    }

    @Test
    void paddingEndsTheFrameWalk() {
        // A frame-shaped thing after the padding, still inside the declared tag size, must not be read.
        byte[] stray = frames(Id3Writer.v23().text("TPE1", UTF8, "Stray"));
        Id3Result r = parse(Id3Writer.v23().text("TIT2", UTF8, "Title").padding(64).trailer(stray));
        assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
        assertTrue(r.tags().first("ARTIST").isEmpty());
    }

    @Test
    void extendedHeaderIsSkippedInV23AndV24() {
        for (Id3Writer w : List.of(Id3Writer.v23(), Id3Writer.v24())) {
            Id3Result r = parse(w.extendedHeader().text("TIT2", UTF8, "Title").text("TPE1", UTF8, "Artist"));
            assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
            assertEquals(Optional.of("Artist"), r.tags().first("ARTIST"));
        }
    }

    // ---- unsynchronisation -----------------------------------------------------------------------

    @Test
    void unsynchronisedTagDecoded() {
        Id3Result r = parse(Id3Writer.v23().unsynchronised()
                .text("TIT2", LATIN1, "ÿÿ") // FF FF: the first FF is followed by 0xFF, the last is at the end
                .text("TPE1", LATIN1, "Zoë")
                .apic("image/jpeg", 3, "cover", LATIN1, JPEG));
        assertEquals(Optional.of("ÿÿ"), r.tags().first("TITLE"));
        assertEquals(Optional.of("Zoë"), r.tags().first("ARTIST"));
        assertArrayEquals(JPEG, r.frontCover().orElseThrow());
    }

    @Test
    void unsynchronisationActuallyChangesTheBytes() {
        // Guards the test above: if the writer did not escape anything, that test would prove nothing.
        byte[] plain = Id3Writer.v23().apic("image/jpeg", 3, "", LATIN1, JPEG).build();
        byte[] unsync = Id3Writer.v23().unsynchronised().apic("image/jpeg", 3, "", LATIN1, JPEG).build();
        assertTrue(unsync.length > plain.length);
        assertEquals(0x80, unsync[5] & 0xFF);
    }

    @Test
    void unsynchronisedFramesInV24() {
        Id3Result r = parse(Id3Writer.v24().unsynchronised()
                .text("TIT2", LATIN1, "ÿÿ")
                .apic("image/jpeg", 3, "", LATIN1, JPEG)
                .text("TPE1", UTF8, "After"));
        assertEquals(Optional.of("ÿÿ"), r.tags().first("TITLE"));
        assertArrayEquals(JPEG, r.frontCover().orElseThrow());
        assertEquals(Optional.of("After"), r.tags().first("ARTIST"));
    }

    @Test
    void aV24TagLevelUnsynchronisationFlagAppliesToEveryFrame() {
        // The 2.4 header flag means "every frame is unsynchronised", whether or not the frame flags say so.
        byte[] stored = Id3Writer.unsynchronise(concat(new byte[] {0}, "ÿÿ".getBytes(StandardCharsets.ISO_8859_1)));
        byte[] frame = concat("TIT2".getBytes(StandardCharsets.ISO_8859_1), Id3Writer.syncsafe(stored.length),
                new byte[] {0, 0}, stored);
        byte[] tag = concat(new byte[] {'I', 'D', '3', 4, 0, (byte) 0x80}, Id3Writer.syncsafe(frame.length), frame);
        assertEquals(Optional.of("ÿÿ"), Id3v2.parse(tag).tags().first("TITLE"));
    }

    // ---- frame mapping ---------------------------------------------------------------------------

    @Test
    void everyMappedFrame() {
        Id3Result r = parse(Id3Writer.v23()
                .text("TIT2", UTF8, "Title").text("TPE1", UTF8, "Artist").text("TPE2", UTF8, "Album Artist")
                .text("TALB", UTF8, "Album").text("TYER", UTF8, "1999").text("TRCK", UTF8, "3/12")
                .text("TPOS", UTF8, "1/2").text("TCON", UTF8, "Rock").text("TCOM", UTF8, "Composer"));
        assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
        assertEquals(Optional.of("Artist"), r.tags().first("ARTIST"));
        assertEquals(Optional.of("Album Artist"), r.tags().first("ALBUMARTIST"));
        assertEquals(Optional.of("Album"), r.tags().first("ALBUM"));
        assertEquals(Optional.of("1999"), r.tags().first("DATE"));
        assertEquals(Optional.of("3/12"), r.tags().first("TRACKNUMBER"));
        assertEquals(Optional.of("1/2"), r.tags().first("DISCNUMBER"));
        assertEquals(Optional.of("Rock"), r.tags().first("GENRE"));
        assertEquals(Optional.of("Composer"), r.tags().first("COMPOSER"));
        assertEquals(9, r.tags().asMap().size());
    }

    @Test
    void tdrcMapsToDate() {
        Id3Result r = parse(Id3Writer.v24().text("TDRC", UTF8, "2018-05-01"));
        assertEquals(Optional.of("2018-05-01"), r.tags().first("DATE"));
    }

    @Test
    void whenTyerAndTdrcBothAppearTheFirstOneWins() {
        Id3Result r = parse(Id3Writer.v24().text("TDRC", UTF8, "2018-05-01").text("TYER", UTF8, "2018"));
        assertEquals(List.of("2018-05-01"), all(r, "DATE"));
        Id3Result r2 = parse(Id3Writer.v24().text("TYER", UTF8, "2018").text("TDRC", UTF8, "2018-05-01"));
        assertEquals(List.of("2018"), all(r2, "DATE"));
    }

    @Test
    void trackAndDiscNumbersKeepTheirRawText() {
        Id3Result r = parse(Id3Writer.v24().text("TRCK", UTF16, "03/12").text("TPOS", LATIN1, "2"));
        assertEquals(Optional.of("03/12"), r.tags().first("TRACKNUMBER"));
        assertEquals(Optional.of("2"), r.tags().first("DISCNUMBER"));
    }

    @Test
    void genreReferencesAreStripped() {
        assertEquals(List.of("Rock"), all(parse(Id3Writer.v23().text("TCON", LATIN1, "(17)Rock")), "GENRE"));
        assertEquals(List.of("Noise"), all(parse(Id3Writer.v23().text("TCON", LATIN1, "(51)(39)Noise")), "GENRE"));
        assertEquals(List.of("Remix"), all(parse(Id3Writer.v23().text("TCON", LATIN1, "(RX)Remix")), "GENRE"));
        assertEquals(List.of("Jazz"), all(parse(Id3Writer.v23().text("TCON", LATIN1, "Jazz")), "GENRE"));
        assertEquals(List.of("Dream Pop"), all(parse(Id3Writer.v23().text("TCON", LATIN1, "(17) Dream Pop")), "GENRE"));
    }

    @Test
    void aBareGenreReferenceMapsToNothing() {
        assertTrue(parse(Id3Writer.v23().text("TCON", LATIN1, "(17)")).tags().isEmpty());
        assertTrue(parse(Id3Writer.v23().text("TCON", LATIN1, "(17)(18)")).tags().isEmpty());
        assertTrue(parse(Id3Writer.v24().text("TCON", UTF8, "17")).tags().isEmpty());
        assertTrue(parse(Id3Writer.v24().text("TCON", UTF8, "RX")).tags().isEmpty());
    }

    @Test
    void genreWithAnEscapedOpeningParenthesisIsKept() {
        assertEquals(List.of("(Live)"), all(parse(Id3Writer.v23().text("TCON", LATIN1, "((Live)")), "GENRE"));
    }

    @Test
    void multipleGenresAreMultipleValues() {
        Id3Result r = parse(Id3Writer.v24().text("TCON", UTF8, "Rock", "17", "Blues"));
        assertEquals(List.of("Rock", "Blues"), all(r, "GENRE"));
    }

    @Test
    void txxxBecomesKey() {
        Id3Result r = parse(Id3Writer.v24()
                .txxx("replaygain_track_gain", LATIN1, "-6.5 dB")
                .txxx("Mood", UTF8, "calm", "late")
                .txxx("LABEL", UTF16, "Blue Note"));
        assertEquals(Optional.of("-6.5 dB"), r.tags().first("REPLAYGAIN_TRACK_GAIN"));
        assertEquals(List.of("calm", "late"), all(r, "MOOD"));
        assertEquals(Optional.of("Blue Note"), r.tags().first("LABEL"));
    }

    @Test
    void txxxWithAnEmptyDescriptionOrNoValueIsIgnored() {
        Id3Result r = parse(Id3Writer.v24().txxx("", UTF8, "orphan").txxx("EMPTY", UTF8).txxx("EMPTY2", UTF8, ""));
        assertTrue(r.tags().isEmpty());
    }

    @Test
    void txxxCanFoldOntoAnAliasKey() {
        Id3Result r = parse(Id3Writer.v24().txxx("ALBUM ARTIST", UTF8, "The Band"));
        assertEquals(Optional.of("The Band"), r.tags().first("ALBUMARTIST"));
    }

    @Test
    void unmappedFramesAreIgnored() {
        Id3Result r = parse(Id3Writer.v24().text("TSSE", UTF8, "Lavf").text("TPUB", UTF8, "Label")
                .frame("COMM", 0, new byte[] {3, 'e', 'n', 'g', 0, 'h', 'i'}).frame("PRIV", 0, new byte[] {1, 2, 0, 3})
                .text("TIT2", UTF8, "Kept"));
        assertEquals(1, r.tags().asMap().size());
        assertEquals(Optional.of("Kept"), r.tags().first("TITLE"));
    }

    // ---- pictures --------------------------------------------------------------------------------

    @Test
    void apicFrontCoverPreferred() {
        byte[] other = {1, 1, 1, 1};
        Id3Result r = parse(Id3Writer.v23()
                .apic("image/png", 0, "other", LATIN1, other)
                .apic("image/jpeg", 3, "front", LATIN1, JPEG)
                .apic("image/png", 4, "back", LATIN1, PNG));
        assertArrayEquals(JPEG, r.frontCover().orElseThrow());
    }

    @Test
    void theFirstFrontCoverWins() {
        Id3Result r = parse(Id3Writer.v24()
                .apic("image/jpeg", 3, "", UTF8, JPEG).apic("image/png", 3, "", UTF8, PNG));
        assertArrayEquals(JPEG, r.frontCover().orElseThrow());
    }

    @Test
    void withoutAFrontCoverTheFirstPictureIsUsed() {
        Id3Result r = parse(Id3Writer.v24()
                .apic("image/png", 4, "back", UTF8, PNG).apic("image/jpeg", 0, "other", UTF8, JPEG));
        assertArrayEquals(PNG, r.frontCover().orElseThrow());
    }

    @Test
    void apicDescriptionIsSkippedInEveryEncoding() {
        // Each image starts with 00 00 so that a misplaced terminator would show up in the result.
        byte[] image = {0, 0, 1, 2, 3, 0};
        for (int enc : new int[] {LATIN1, UTF16, UTF16BE, UTF8}) {
            String description = enc == LATIN1 ? "Cover Ärt" : "Cover Ärt ♪";
            Id3Result r = parse(Id3Writer.v24().apic("image/png", 3, description, enc, image));
            assertArrayEquals(image, r.frontCover().orElseThrow(), "encoding " + enc);
        }
    }

    @Test
    void apicWithAnEmptyDescriptionAndEmptyMime() {
        Id3Result r = parse(Id3Writer.v23().apic("", 3, "", UTF16, PNG));
        assertArrayEquals(PNG, r.frontCover().orElseThrow());
    }

    @Test
    void apicWithNoImageBytesIsNoPicture() {
        assertTrue(parse(Id3Writer.v24().apic("image/png", 3, "", UTF8, new byte[0])).frontCover().isEmpty());
    }

    @Test
    void aLinkedPictureIsNotAnImage() {
        byte[] url = "http://x/y.jpg".getBytes(StandardCharsets.ISO_8859_1);
        assertTrue(parse(Id3Writer.v24().apic("-->", 3, "", LATIN1, url)).frontCover().isEmpty());
    }

    @Test
    void aBrokenPictureFrameIsSkippedAndTheNextOneUsed() {
        Id3Result r = parse(Id3Writer.v24()
                .frame("APIC", 0, new byte[] {0, 'i', 'm', 'a', 'g', 'e'}) // no MIME terminator
                .frame("APIC", 0, new byte[] {0, 'i', 0, 3}) // ends before the description terminator
                .frame("APIC", 0, new byte[] {9, 'i', 0, 3, 0, 1, 2}) // unknown encoding
                .apic("image/png", 0, "", LATIN1, PNG));
        assertArrayEquals(PNG, r.frontCover().orElseThrow());
    }

    @Test
    void noPictureGivesAnEmptyCover() {
        assertTrue(parse(Id3Writer.v24().text("TIT2", UTF8, "T")).frontCover().isEmpty());
    }

    @Test
    void theCoverIsACopyNotAViewOfTheTag() {
        byte[] tag = Id3Writer.v24().apic("image/png", 3, "", LATIN1, PNG).build();
        byte[] cover = Id3v2.parse(tag).frontCover().orElseThrow();
        Arrays.fill(tag, (byte) 0);
        assertArrayEquals(PNG, cover);
    }

    // ---- frame flags -----------------------------------------------------------------------------

    @Test
    void compressedAndEncryptedFramesAreSkipped() {
        byte[] ok = Id3Writer.textPayload(UTF8, "garbled");
        Id3Result v23 = parse(Id3Writer.v23()
                .frame("TIT2", 0x80, ok).frame("TPE1", 0x40, ok).text("TALB", UTF8, "Kept"));
        assertEquals(1, v23.tags().asMap().size());
        assertEquals(Optional.of("Kept"), v23.tags().first("ALBUM"));
        Id3Result v24 = parse(Id3Writer.v24()
                .frame("TIT2", 0x08, ok).frame("TPE1", 0x04, ok).text("TALB", UTF8, "Kept"));
        assertEquals(1, v24.tags().asMap().size());
        assertEquals(Optional.of("Kept"), v24.tags().first("ALBUM"));
    }

    @Test
    void groupingByteAndDataLengthIndicatorAreSkipped() {
        byte[] text = Id3Writer.textPayload(UTF8, "Title");
        Id3Result v23 = parse(Id3Writer.v23().frame("TIT2", 0x20, concat(new byte[] {7}, text)));
        assertEquals(Optional.of("Title"), v23.tags().first("TITLE"));
        Id3Result v24 = parse(Id3Writer.v24().frame("TIT2", 0x40, concat(new byte[] {7}, text)));
        assertEquals(Optional.of("Title"), v24.tags().first("TITLE"));
        byte[] dli = Id3Writer.syncsafe(text.length);
        Id3Result v24dli = parse(Id3Writer.v24().frame("TIT2", 0x01, concat(dli, text)));
        assertEquals(Optional.of("Title"), v24dli.tags().first("TITLE"));
        Id3Result both = parse(Id3Writer.v24().frame("TIT2", 0x41, concat(new byte[] {7}, dli, text)));
        assertEquals(Optional.of("Title"), both.tags().first("TITLE"));
    }

    @Test
    void framesWithAnUnknownEncodingOrNoPayloadAreSkipped() {
        Id3Result r = parse(Id3Writer.v24()
                .frame("TIT2", 0, new byte[] {7, 'x'})
                .frame("TPE1", 0, new byte[0])
                .frame("TALB", 0, new byte[] {3})
                .text("TCOM", UTF8, "Kept"));
        assertEquals(1, r.tags().asMap().size());
        assertEquals(Optional.of("Kept"), r.tags().first("COMPOSER"));
    }

    // ---- bounded work on text frames ---------------------------------------------------------------

    /** A text frame of this many zero bytes is one value per terminator (per pair, in UTF-16) if split naively. */
    private static final int ZERO_FRAME_BYTES = 8 * 1024 * 1024;
    /** Generous: a bounded parse of that tag allocates a few kilobytes, a value-per-terminator parse tens of MiB. */
    private static final long ALLOCATION_LIMIT = 2L * 1024 * 1024;
    private static final int MAX_VALUES_PER_FRAME = 64;

    /** Bytes this thread allocated while running {@code r}, so a test can tell bounded work from unbounded. */
    private static long allocatedBy(Runnable r) {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id);
        r.run();
        return mx.getThreadAllocatedBytes(id) - before;
    }

    @Test
    void aZeroFilledTextFrameCostsNothingAndYieldsNothing() {
        for (int enc : new int[] {LATIN1, UTF16, UTF16BE, UTF8}) {
            byte[] payload = new byte[1 + ZERO_FRAME_BYTES];
            payload[0] = (byte) enc;
            byte[] tag = Id3Writer.v24().frame("TIT2", 0, payload).text("TPE1", UTF8, "After").build();
            Id3Result[] result = new Id3Result[1];
            long allocated = allocatedBy(() -> result[0] = Id3v2.parse(tag));
            assertTrue(result[0].tags().first("TITLE").isEmpty(), "encoding " + enc);
            assertEquals(Optional.of("After"), result[0].tags().first("ARTIST"), "encoding " + enc);
            assertTrue(allocated < ALLOCATION_LIMIT, "encoding " + enc + " allocated " + allocated + " bytes");
        }
    }

    @Test
    void aZeroFilledUserTextFrameCostsNothingAndYieldsNothing() {
        for (int enc : new int[] {LATIN1, UTF16, UTF16BE, UTF8}) {
            byte[] description = Id3Writer.encode("MOOD", enc);
            byte[] terminator = Id3Writer.terminator(enc);
            byte[] payload = concat(new byte[] {(byte) enc}, description, terminator, new byte[ZERO_FRAME_BYTES]);
            byte[] tag = Id3Writer.v24().frame("TXXX", 0, payload).text("TPE1", UTF8, "After").build();
            Id3Result[] result = new Id3Result[1];
            long allocated = allocatedBy(() -> result[0] = Id3v2.parse(tag));
            assertTrue(result[0].tags().first("MOOD").isEmpty(), "encoding " + enc);
            assertEquals(Optional.of("After"), result[0].tags().first("ARTIST"), "encoding " + enc);
            assertTrue(allocated < ALLOCATION_LIMIT, "encoding " + enc + " allocated " + allocated + " bytes");
        }
    }

    @Test
    void aFrameWithMoreThanTheCapOfValuesKeepsTheFirst64() {
        String[] many = IntStream.range(0, 100).mapToObj(i -> "v" + i).toArray(String[]::new);
        List<String> first64 = List.of(many).subList(0, MAX_VALUES_PER_FRAME);
        for (int enc : new int[] {LATIN1, UTF16, UTF16BE, UTF8}) {
            assertEquals(first64, all(parse(Id3Writer.v24().text("TPE1", enc, many)), "ARTIST"), "encoding " + enc);
            assertEquals(first64, all(parse(Id3Writer.v24().txxx("MOOD", enc, many)), "MOOD"), "encoding " + enc);
        }
        // exactly the cap is not cut
        String[] exactly = Arrays.copyOf(many, MAX_VALUES_PER_FRAME);
        assertEquals(first64, all(parse(Id3Writer.v24().text("TPE1", UTF8, exactly)), "ARTIST"));
    }

    @Test
    void emptyValuesDoNotUseUpTheCap() {
        String[] values = new String[250];
        Arrays.fill(values, "");
        values[0] = "first";
        values[249] = "last";
        assertEquals(List.of("first", "last"), all(parse(Id3Writer.v24().text("TPE1", UTF8, values)), "ARTIST"));
        // UTF-16BE has no byte order mark, so an empty value is two terminator bytes and nothing else; with a mark
        // an empty value would still be a piece of two bytes, and pieces that have bytes count towards the cap.
        assertEquals(List.of("first", "last"), all(parse(Id3Writer.v24().text("TPE1", UTF16BE, values)), "ARTIST"));
        String[] user = Arrays.copyOf(values, 250);
        assertEquals(List.of("first", "last"), all(parse(Id3Writer.v24().txxx("MOOD", UTF8, user)), "MOOD"));
    }

    // ---- damaged and foreign input ---------------------------------------------------------------

    @Test
    void version22GivesAnEmptyResult() {
        Id3Result r = parse(Id3Writer.v22().frame("TT2", 0, Id3Writer.textPayload(LATIN1, "Title")));
        assertTrue(r.tags().isEmpty());
        assertTrue(r.frontCover().isEmpty());
    }

    @Test
    void notATagGivesAnEmptyResult() {
        for (byte[] b : new byte[][] {null, new byte[0], new byte[5], "RIFF....WAVEfmt ".getBytes(),
                "ID3".getBytes(), new byte[64]}) {
            Id3Result r = Id3v2.parse(b);
            assertNotNull(r);
            assertTrue(r.tags().isEmpty());
            assertTrue(r.frontCover().isEmpty());
        }
    }

    @Test
    void anUnknownMajorVersionOrABadSizeGivesAnEmptyResult() {
        byte[] good = Id3Writer.v24().text("TIT2", UTF8, "Title").build();
        byte[] v5 = good.clone();
        v5[3] = 5;
        assertTrue(Id3v2.parse(v5).tags().isEmpty());
        byte[] v9 = good.clone();
        v9[3] = 9;
        assertTrue(Id3v2.parse(v9).tags().isEmpty());
        byte[] badSize = good.clone();
        badSize[6] = (byte) 0x80; // not syncsafe
        assertTrue(Id3v2.parse(badSize).tags().isEmpty());
    }

    @Test
    void aFrameLargerThanTheRestOfTheTagEndsTheWalk() {
        Id3Result r = parse(Id3Writer.v24().text("TIT2", UTF8, "Title")
                .frameWithDeclaredSize("TPE1", 5000, Id3Writer.textPayload(UTF8, "Artist"))
                .text("TALB", UTF8, "Never read"));
        assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
        assertTrue(r.tags().first("ARTIST").isEmpty());
        assertTrue(r.tags().first("ALBUM").isEmpty());
    }

    @Test
    void aHugeFrameSizeDoesNotOverflow() {
        for (Id3Writer w : List.of(Id3Writer.v23(), Id3Writer.v24())) {
            byte[] tag = w.text("TIT2", UTF8, "Title").build();
            byte[] huge = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
            byte[] frame = concat("TPE1".getBytes(StandardCharsets.ISO_8859_1), huge, new byte[] {0, 0, 3, 'x'});
            byte[] bad = concat(tag, frame);
            // grow the declared tag size to cover the bogus frame
            byte[] size = Id3Writer.syncsafe(bad.length - 10);
            System.arraycopy(size, 0, bad, 6, 4);
            Id3Result r = Id3v2.parse(bad);
            assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
            assertTrue(r.tags().first("ARTIST").isEmpty());
        }
    }

    @Test
    void aFrameWithAnInvalidIdEndsTheWalk() {
        Id3Result r = parse(Id3Writer.v23().text("TIT2", UTF8, "Title").text("tpe1", UTF8, "Lower")
                .text("TALB", UTF8, "Never read"));
        assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
        assertEquals(1, r.tags().asMap().size());
    }

    @Test
    void aCutOffTagKeepsTheFramesBeforeTheCut() {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").text("TPE1", UTF8, "Artist Name Here").build();
        Id3Result r = Id3v2.parse(Arrays.copyOf(tag, tag.length - 5));
        assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
        assertTrue(r.tags().first("ARTIST").isEmpty());
    }

    @Test
    void anExtendedHeaderLargerThanTheTagGivesAnEmptyResult() {
        byte[] tag = Id3Writer.v23().extendedHeader().text("TIT2", UTF8, "Title").build();
        tag[13] = 100; // the extended header claims 100 bytes
        assertTrue(Id3v2.parse(tag).tags().isEmpty());
        byte[] v24 = Id3Writer.v24().extendedHeader().text("TIT2", UTF8, "Title").build();
        v24[13] = 100;
        assertTrue(Id3v2.parse(v24).tags().isEmpty());
    }

    @Test
    void damagedTagsNeverThrow() {
        Random rnd = new Random(20261020);
        byte[][] seeds = {
                Id3Writer.v23().text("TIT2", UTF16, "Motörhead").text("TCON", LATIN1, "(17)Rock")
                        .apic("image/jpeg", 3, "front", UTF16, JPEG).txxx("MOOD", UTF8, "a", "b").build(),
                Id3Writer.v24().text("TPE1", UTF8, "A", "B").unsynchronised()
                        .apic("image/jpeg", 3, "", UTF16BE, JPEG).txxx("X", UTF16, "v").build(),
                Id3Writer.v23().unsynchronised().extendedHeader().text("TALB", LATIN1, "ÿÿ")
                        .apic("image/png", 0, "d", UTF8, PNG).build(),
                Id3Writer.v24().extendedHeader().frame("TIT2", 0x41, new byte[] {1, 0, 0, 0, 3, 3, 'h', 'i'}).build(),
        };
        for (int round = 0; round < 12000; round++) {
            byte[] b = seeds[round % seeds.length].clone();
            int flips = 1 + rnd.nextInt(6);
            for (int i = 0; i < flips; i++) {
                b[rnd.nextInt(b.length)] = (byte) rnd.nextInt(256);
            }
            if (rnd.nextInt(3) == 0) {
                b = Arrays.copyOf(b, rnd.nextInt(b.length + 1));
            }
            Id3Result r = Id3v2.parse(b);
            assertNotNull(r.tags());
            assertNotNull(r.frontCover());
        }
        for (int round = 0; round < 3000; round++) {
            byte[] b = new byte[10 + rnd.nextInt(200)];
            rnd.nextBytes(b);
            byte[] head = {'I', 'D', '3', (byte) (3 + rnd.nextInt(2)), 0, (byte) (rnd.nextInt(256) & 0xC0)};
            System.arraycopy(head, 0, b, 0, 6);
            for (int i = 6; i < 10; i++) {
                b[i] &= 0x7F;
            }
            assertNotNull(Id3v2.parse(b));
        }
    }

    @Test
    void emptyResultsAreIndependent() {
        Id3Result a = Id3Result.empty();
        a.tags().add("TITLE", "x");
        assertTrue(Id3Result.empty().tags().isEmpty());
        assertEquals(Optional.empty(), new Id3Result(null, null).frontCover());
        assertTrue(new Id3Result(null, null).tags().isEmpty());
    }

    // ---- reading from a file ---------------------------------------------------------------------

    /** A file of 100 junk bytes, the tag, then 50 junk bytes. */
    private Path fileWith(byte[] tag) throws IOException {
        byte[] file = concat(new byte[100], tag, new byte[50]);
        Path p = dir.resolve("tagged.bin");
        Files.write(p, file);
        return p;
    }

    @Test
    void readAtOffset() throws IOException {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").apic("image/png", 3, "", UTF8, PNG).build();
        try (FileSource s = FileSource.open(fileWith(tag))) {
            Id3Result r = Id3v2.readAt(s, 100).orElseThrow();
            assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
            assertArrayEquals(PNG, r.frontCover().orElseThrow());
        }
    }

    @Test
    void readAtStopsAtTheTagEvenWhenTheFileGoesOn() throws IOException {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").build();
        byte[] stray = frames(Id3Writer.v24().text("TPE1", UTF8, "Stray"));
        Path p = dir.resolve("after.bin");
        Files.write(p, concat(new byte[100], tag, stray));
        try (FileSource s = FileSource.open(p)) {
            Id3Result r = Id3v2.readAt(s, 100).orElseThrow();
            assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
            assertTrue(r.tags().first("ARTIST").isEmpty());
        }
    }

    @Test
    void readAtWhereThereIsNoTagIsEmpty() throws IOException {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").build();
        try (FileSource s = FileSource.open(fileWith(tag))) {
            assertEquals(Optional.empty(), Id3v2.readAt(s, 0));
            assertEquals(Optional.empty(), Id3v2.readAt(s, 101));
            assertEquals(Optional.empty(), Id3v2.readAt(s, s.size()));
            assertEquals(Optional.empty(), Id3v2.readAt(s, s.size() + 1000));
            assertEquals(Optional.empty(), Id3v2.readAt(s, -1));
        }
    }

    @Test
    void readAtAFileThatEndsInsideTheHeaderIsEmpty() throws IOException {
        Path p = dir.resolve("short.bin");
        Files.write(p, new byte[] {'I', 'D', '3', 4, 0});
        try (FileSource s = FileSource.open(p)) {
            assertEquals(Optional.empty(), Id3v2.readAt(s, 0));
        }
    }

    @Test
    void readAtAForeignVersionIsAnEmptyResult() throws IOException {
        byte[] tag = Id3Writer.v22().frame("TT2", 0, Id3Writer.textPayload(LATIN1, "Title")).build();
        try (FileSource s = FileSource.open(fileWith(tag))) {
            Id3Result r = Id3v2.readAt(s, 100).orElseThrow();
            assertTrue(r.tags().isEmpty());
        }
    }

    @Test
    void readAtIsBoundedByTheLength() throws IOException {
        byte[] both = Id3Writer.v24().text("TIT2", UTF8, "Title").text("TPE1", UTF8, "Artist").build();
        try (FileSource s = FileSource.open(fileWith(both))) {
            // The first frame is 10 header + 6 payload bytes; allow the tag header plus that frame and no more.
            Id3Result r = Id3v2.readAt(s, 100, 10 + 16).orElseThrow();
            assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
            assertTrue(r.tags().first("ARTIST").isEmpty());
            assertEquals(Optional.of("Artist"), Id3v2.readAt(s, 100, both.length).orElseThrow().tags().first("ARTIST"));
            assertEquals(Optional.empty(), Id3v2.readAt(s, 100, 9));
            assertEquals(Optional.empty(), Id3v2.readAt(s, 100, 0));
        }
    }

    @Test
    void readAtATagLargerThanTheFileReadsWhatIsThere() throws IOException {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").text("TPE1", UTF8, "Artist").build();
        byte[] cut = Arrays.copyOf(tag, tag.length - 4);
        Path p = dir.resolve("cut.bin");
        Files.write(p, cut);
        try (FileSource s = FileSource.open(p)) {
            Id3Result r = Id3v2.readAt(s, 0).orElseThrow();
            assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
            assertTrue(r.tags().first("ARTIST").isEmpty());
        }
    }

    @Test
    void readAtDoesNotTrustAHugeDeclaredSize() throws IOException {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").build();
        System.arraycopy(new byte[] {0x7F, 0x7F, 0x7F, 0x7F}, 0, tag, 6, 4); // 256 MiB declared
        Path p = dir.resolve("huge.bin");
        Files.write(p, tag);
        try (FileSource s = FileSource.open(p)) {
            Id3Result r = Id3v2.readAt(s, 0).orElseThrow();
            assertEquals(Optional.of("Title"), r.tags().first("TITLE"));
        }
    }

    @Test
    void readAtWithABadSizeIsAnEmptyResult() throws IOException {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").build();
        tag[7] = (byte) 0x90; // not syncsafe
        try (FileSource s = FileSource.open(fileWith(tag))) {
            assertTrue(Id3v2.readAt(s, 100).orElseThrow().tags().isEmpty());
        }
    }

    @Test
    void readAtLeavesTheSourceUsable() throws IOException {
        byte[] tag = Id3Writer.v23().text("TIT2", UTF8, "Title").build();
        try (FileSource s = FileSource.open(fileWith(tag))) {
            Id3v2.readAt(s, 100);
            s.seek(0);
            assertEquals(0, s.u8());
        }
    }

    @Test
    void parseAcceptsATagFollowedByOtherBytes() {
        byte[] tag = Id3Writer.v24().text("TIT2", UTF8, "Title").build();
        Id3Result r = Id3v2.parse(concat(tag, new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00, 1, 2, 3}));
        assertEquals(List.of("Title"), all(r, "TITLE"));
        assertFalse(r.tags().isEmpty());
    }
}
