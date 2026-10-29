package purist.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RiffInfoTest {

    private static final Charset CP1252 = Charset.forName("windows-1252");

    /** A sub-chunk: id, little-endian size, payload, pad byte when odd. */
    private static byte[] sub(String id, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        int n = payload.length;
        o.write(n);
        o.write(n >> 8);
        o.write(n >> 16);
        o.write(n >> 24);
        o.writeBytes(payload);
        if ((n & 1) != 0) {
            o.write(0);
        }
        return o.toByteArray();
    }

    /** A NUL-terminated string sub-chunk, the way writers store INFO text. */
    private static byte[] text(String id, String value, Charset cs) {
        byte[] t = value.getBytes(cs);
        byte[] z = new byte[t.length + 1];
        System.arraycopy(t, 0, z, 0, t.length);
        return sub(id, z);
    }

    private static byte[] text(String id, String value) {
        return text(id, value, StandardCharsets.UTF_8);
    }

    private static byte[] payload(boolean withFormType, byte[]... chunks) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (withFormType) {
            o.writeBytes("INFO".getBytes(StandardCharsets.ISO_8859_1));
        }
        for (byte[] c : chunks) {
            o.writeBytes(c);
        }
        return o.toByteArray();
    }

    @Test
    void mapsEveryDocumentedId() {
        TagMap t = RiffInfo.parse(payload(true,
                text("INAM", "A Song"), text("IART", "An Artist"), text("IPRD", "An Album"),
                text("ICRD", "2018-05-04"), text("IGNR", "Trance"), text("ITRK", "7"),
                text("ICMT", "a comment")));
        assertEquals(Optional.of("A Song"), t.first("TITLE"));
        assertEquals(Optional.of("An Artist"), t.first("ARTIST"));
        assertEquals(Optional.of("An Album"), t.first("ALBUM"));
        assertEquals(Optional.of("2018-05-04"), t.first("DATE"));
        assertEquals(Optional.of("Trance"), t.first("GENRE"));
        assertEquals(Optional.of("7"), t.first("TRACKNUMBER"));
        assertEquals(Optional.of("a comment"), t.first("COMMENT"));
        assertEquals(7, t.asMap().size());
    }

    @Test
    void iprtIsAnAlternativeTrackNumber() {
        TagMap t = RiffInfo.parse(payload(true, text("IPRT", "3")));
        assertEquals(List.of("3"), t.all("TRACKNUMBER"));
    }

    @Test
    void trackNumberComesFromTheFirstOfItrkAndIprtOnly() {
        TagMap t = RiffInfo.parse(payload(true, text("ITRK", "3"), text("IPRT", "3/12")));
        assertEquals(List.of("3"), t.all("TRACKNUMBER"));
        TagMap u = RiffInfo.parse(payload(true, text("IPRT", "3/12"), text("ITRK", "3")));
        assertEquals(List.of("3/12"), u.all("TRACKNUMBER"));
    }

    @Test
    void theInfoFormTypeIsOptional() {
        byte[] body = payload(false, text("INAM", "No prefix"));
        assertEquals(Optional.of("No prefix"), RiffInfo.parse(body).first("TITLE"));
        assertEquals(Optional.of("No prefix"), RiffInfo.parse(payload(true, text("INAM", "No prefix"))).first("TITLE"));
    }

    @Test
    void utf8TextIsDecodedAsUtf8() {
        TagMap t = RiffInfo.parse(payload(true, text("INAM", "Motörhead 日本", StandardCharsets.UTF_8)));
        assertEquals(Optional.of("Motörhead 日本"), t.first("TITLE"));
    }

    @Test
    void textThatIsNotUtf8FallsBackToWindows1252() {
        TagMap t = RiffInfo.parse(payload(true, text("INAM", "Motörhead €5", CP1252)));
        assertEquals(Optional.of("Motörhead €5"), t.first("TITLE"));
    }

    @Test
    void textStopsAtTheFirstNulAndTrimsPadding() {
        byte[] raw = {'A', 'b', 'c', 0, 'J', 'U', 'N', 'K', 0};
        TagMap t = RiffInfo.parse(payload(true, sub("INAM", raw), sub("IART", "  Spaced \u0000".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Optional.of("Abc"), t.first("TITLE"));
        assertEquals(Optional.of("Spaced"), t.first("ARTIST"));
    }

    @Test
    void textWithoutATerminatorIsStillRead() {
        TagMap t = RiffInfo.parse(payload(true, sub("INAM", "Bare".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Optional.of("Bare"), t.first("TITLE"));
    }

    @Test
    void oddLengthChunksArePaddedToEven() {
        // "ab" + NUL is 3 bytes, so a pad byte follows; the next chunk must still be found.
        TagMap t = RiffInfo.parse(payload(true, text("INAM", "ab"), text("IART", "cd")));
        assertEquals(Optional.of("ab"), t.first("TITLE"));
        assertEquals(Optional.of("cd"), t.first("ARTIST"));
    }

    @Test
    void unknownIdsAreIgnored() {
        TagMap t = RiffInfo.parse(payload(true, text("ISFT", "Lavf60"), text("IENG", "someone"),
                text("INAM", "Kept")));
        assertEquals(Optional.of("Kept"), t.first("TITLE"));
        assertEquals(1, t.asMap().size());
    }

    @Test
    void blankValuesAreDropped() {
        TagMap t = RiffInfo.parse(payload(true, text("INAM", ""), text("IART", "   "), text("IPRD", "Real")));
        assertEquals(Optional.empty(), t.first("TITLE"));
        assertEquals(Optional.empty(), t.first("ARTIST"));
        assertEquals(Optional.of("Real"), t.first("ALBUM"));
    }

    @Test
    void repeatedIdsGiveMultipleValues() {
        TagMap t = RiffInfo.parse(payload(true, text("IART", "One"), text("IART", "Two")));
        assertEquals(List.of("One", "Two"), t.all("ARTIST"));
    }

    @Test
    void aTruncatedLastChunkIsIgnoredAndEarlierOnesSurvive() {
        byte[] good = text("INAM", "Good");
        byte[] cut = text("IART", "Cut off here");
        byte[] all = payload(true, good, cut);
        byte[] truncated = java.util.Arrays.copyOf(all, all.length - 5);
        TagMap t = RiffInfo.parse(truncated);
        assertEquals(Optional.of("Good"), t.first("TITLE"));
        assertEquals(Optional.empty(), t.first("ARTIST"));
    }

    @Test
    void aChunkClaimingMoreThanTheBufferHoldsDoesNotThrow() {
        byte[] huge = {'I', 'N', 'A', 'M', (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 'x'};
        assertTrue(RiffInfo.parse(payload(true, huge)).isEmpty());
    }

    @Test
    void tinyOrEmptyPayloadsGiveAnEmptyMap() {
        assertTrue(RiffInfo.parse(new byte[0]).isEmpty());
        assertTrue(RiffInfo.parse(new byte[] {'I', 'N', 'F', 'O'}).isEmpty());
        assertTrue(RiffInfo.parse(new byte[] {'I', 'N', 'F', 'O', 'I', 'N'}).isEmpty());
        assertTrue(RiffInfo.parse(new byte[] {1, 2, 3}).isEmpty());
    }
}
