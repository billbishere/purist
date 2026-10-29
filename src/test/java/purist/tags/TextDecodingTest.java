package purist.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextDecodingTest {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static String decode(byte[] b) {
        return TextDecoding.decode(b, 0, b.length);
    }

    @Test
    void plainAsciiIsReturnedAsIs() {
        assertEquals("A Song", decode("A Song".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void cleanUtf8IsDecodedAsUtf8() {
        // 'ö' is C3 B6 in UTF-8; read as windows-1252 it would come out as "Ã¶".
        assertEquals("Motörhead", decode("Motörhead".getBytes(StandardCharsets.UTF_8)));
        assertEquals("日本語 €", decode("日本語 €".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bytesThatAreNotUtf8FallBackToWindows1252() {
        assertEquals("Motörhead", decode(bytes('M', 'o', 't', 0xF6, 'r', 'h', 'e', 'a', 'd')));
        // 0x80 is the euro sign in windows-1252 (a control character in ISO-8859-1), 0x92 a right quote.
        assertEquals("€5’s", decode(bytes(0x80, '5', 0x92, 's')));
    }

    @Test
    void aMultiByteSequenceCutOffAtTheEndFallsBackToo() {
        // C3 on its own is a UTF-8 lead byte with nothing after it: malformed, so windows-1252 'Ã'.
        assertEquals("cafÃ", decode(bytes('c', 'a', 'f', 0xC3)));
    }

    @Test
    void textEndsAtTheFirstNulAndTheRestIsIgnored() {
        assertEquals("Title", decode(bytes('T', 'i', 't', 'l', 'e', 0, 'j', 'u', 'n', 'k')));
        assertEquals("Title", decode(bytes('T', 'i', 't', 'l', 'e', 0, 0, 0)));
        // Junk after the NUL must not decide the charset: these bytes would force windows-1252 if they counted.
        assertEquals("é", decode(bytes(0xC3, 0xA9, 0, 0xF6, 0xF6)));
    }

    @Test
    void leadingAndTrailingWhitespaceIsStripped() {
        assertEquals("A Song", decode("  \tA Song \r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("A Song", decode(bytes(' ', 'A', ' ', 'S', 'o', 'n', 'g', ' ', ' ', 0, ' ')));
        assertEquals("two  words", decode("two  words".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void emptyBlankAndAllNulInputGiveTheEmptyString() {
        assertEquals("", decode(new byte[0]));
        assertEquals("", decode(new byte[] {0}));
        assertEquals("", decode(new byte[] {0, 0, 0}));
        assertEquals("", decode("   ".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void onlyTheRequestedWindowIsRead() {
        byte[] b = "xxA Songyy".getBytes(StandardCharsets.US_ASCII);
        assertEquals("A Song", TextDecoding.decode(b, 2, 6));
        assertEquals("", TextDecoding.decode(b, 2, 0));
        // A byte outside the window cannot spoil the charset choice.
        byte[] w = bytes(0xFF, 'o', 'k', 0xFF);
        assertEquals("ok", TextDecoding.decode(w, 1, 2));
        // The window ends the text even where no NUL does, and a NUL inside it ends the text early.
        byte[] n = bytes('a', 'b', 'c', 0, 'd');
        assertEquals("ab", TextDecoding.decode(n, 0, 2));
        assertEquals("abc", TextDecoding.decode(n, 0, 4));
        assertEquals("", TextDecoding.decode(n, 3, 2));
    }

    @Test
    void aWindowOutsideTheArrayIsRefused() {
        byte[] b = new byte[4];
        assertThrows(IndexOutOfBoundsException.class, () -> TextDecoding.decode(b, 2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> TextDecoding.decode(b, -1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> TextDecoding.decode(b, 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> TextDecoding.decode(b, 5, 0));
    }
}
