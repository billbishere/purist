package purist.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VorbisCommentsTest {

    // ---- helpers ---------------------------------------------------------------------------------

    private static byte[] le32(long v) {
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A comment block: vendor, then the given comments as raw bytes, with the declared count. */
    private static byte[] block(String vendor, long declaredCount, byte[]... comments) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] v = utf8(vendor);
        b.writeBytes(le32(v.length));
        b.writeBytes(v);
        b.writeBytes(le32(declaredCount));
        for (byte[] c : comments) {
            b.writeBytes(le32(c.length));
            b.writeBytes(c);
        }
        return b.toByteArray();
    }

    private static byte[] block(String... comments) {
        byte[][] raw = new byte[comments.length][];
        for (int i = 0; i < comments.length; i++) {
            raw[i] = utf8(comments[i]);
        }
        return block("reference libFLAC 1.5.0", comments.length, raw);
    }

    // ---- tests -----------------------------------------------------------------------------------

    @Test
    void readsKeyValuePairs() {
        TagMap t = VorbisComments.parse(block("TITLE=Echoes", "ARTIST=Pink Floyd", "TRACKNUMBER=1"));
        assertEquals(Optional.of("Echoes"), t.first("TITLE"));
        assertEquals(Optional.of("Pink Floyd"), t.first("ARTIST"));
        assertEquals(Optional.of("1"), t.first("TRACKNUMBER"));
        assertEquals(3, t.asMap().size());
    }

    @Test
    void repeatedKeysKeepEveryValueInOrder() {
        TagMap t = VorbisComments.parse(block("ARTIST=Simon", "ARTIST=Garfunkel", "ARTIST=Third"));
        assertEquals(List.of("Simon", "Garfunkel", "Third"), t.all("ARTIST"));
    }

    @Test
    void keysAreCaseInsensitiveAndFoldLikeTagMap() {
        TagMap t = VorbisComments.parse(block("artist=Low", "Album Artist=Various", "year=1999"));
        assertEquals(Optional.of("Low"), t.first("ARTIST"));
        assertEquals(Optional.of("Various"), t.first("ALBUMARTIST"));
        assertEquals(Optional.of("1999"), t.first("DATE"));
    }

    @Test
    void valueMayContainEqualsSigns() {
        TagMap t = VorbisComments.parse(block("COMMENT=a=b=c"));
        assertEquals(Optional.of("a=b=c"), t.first("COMMENT"));
    }

    @Test
    void valuesAreUtf8() {
        TagMap t = VorbisComments.parse(block("ARTIST=Björk", "TITLE=日本語 🎵"));
        assertEquals(Optional.of("Björk"), t.first("ARTIST"));
        assertEquals(Optional.of("日本語 🎵"), t.first("TITLE"));
    }

    @Test
    void malformedUtf8FallsBackToWindows1252() {
        byte[] bad = {'A', 'R', 'T', 'I', 'S', 'T', '=', 'B', 'j', (byte) 0xF6, 'r', 'k'};
        TagMap t = VorbisComments.parse(block("reference", 1, bad));
        assertEquals(Optional.of("Björk"), t.first("ARTIST"));
    }

    @Test
    void entriesWithoutAnEqualsSignAreSkipped() {
        TagMap t = VorbisComments.parse(block("JUSTAWORD", "TITLE=Kept", "", "=novalue-key"));
        assertEquals(Optional.of("Kept"), t.first("TITLE"));
        assertEquals(1, t.asMap().size());
    }

    @Test
    void emptyValuesAreDropped() {
        TagMap t = VorbisComments.parse(block("TITLE=", "ALBUM=Real"));
        assertFalse(t.first("TITLE").isPresent());
        assertEquals(Optional.of("Real"), t.first("ALBUM"));
    }

    @Test
    void vendorOnlyBlockHasNoTags() {
        assertTrue(VorbisComments.parse(block()).isEmpty());
    }

    @Test
    void nullEmptyAndShortBlocksGiveNoTags() {
        assertTrue(VorbisComments.parse(null).isEmpty());
        assertTrue(VorbisComments.parse(new byte[0]).isEmpty());
        assertTrue(VorbisComments.parse(new byte[] {1, 2, 3}).isEmpty());
        assertTrue(VorbisComments.parse(le32(100)).isEmpty()); // a vendor length with no vendor
    }

    @Test
    void aBlockCutShortKeepsTheCompleteComments() {
        byte[] whole = block("TITLE=One", "ARTIST=Two", "ALBUM=Three");
        for (int cut = 0; cut < whole.length; cut++) {
            VorbisComments.parse(Arrays.copyOf(whole, cut)); // never throws
        }
        // cut inside the third comment: the first two survive
        TagMap t = VorbisComments.parse(Arrays.copyOf(whole, whole.length - 3));
        assertEquals(Optional.of("One"), t.first("TITLE"));
        assertEquals(Optional.of("Two"), t.first("ARTIST"));
        assertFalse(t.first("ALBUM").isPresent());
    }

    @Test
    void aDeclaredCountLargerThanTheBlockReadsWhatIsThere() {
        byte[] b = block("v", 0xFFFFFFFFL, utf8("TITLE=Only"));
        TagMap t = VorbisComments.parse(b);
        assertEquals(Optional.of("Only"), t.first("TITLE"));
    }

    @Test
    void aDeclaredCountSmallerThanTheBlockIgnoresTheRest() {
        byte[] b = block("v", 1, utf8("TITLE=First"), utf8("ARTIST=Extra"));
        TagMap t = VorbisComments.parse(b);
        assertEquals(Optional.of("First"), t.first("TITLE"));
        assertFalse(t.first("ARTIST").isPresent());
    }

    @Test
    void lengthsAboveTwoToTheThirtyOneDoNotOverflow() {
        // vendor length 0xFFFFFFFF, then comment length 0xFFFFFFF0: both must be treated as too long, not negative
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(le32(0xFFFFFFFFL));
        b.writeBytes(new byte[8]);
        assertTrue(VorbisComments.parse(b.toByteArray()).isEmpty());

        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.writeBytes(le32(0));
        c.writeBytes(le32(2));
        c.writeBytes(le32(0xFFFFFFF0L));
        c.writeBytes(utf8("TITLE=x"));
        assertTrue(VorbisComments.parse(c.toByteArray()).isEmpty());
    }

    @Test
    void aValueEndsAtItsFirstNul() {
        byte[] withNul = {'T', 'I', 'T', 'L', 'E', '=', 'a', 'b', 0, 'c', 'd'};
        TagMap t = VorbisComments.parse(block("v", 1, withNul));
        assertEquals(Optional.of("ab"), t.first("TITLE"));
    }
}
