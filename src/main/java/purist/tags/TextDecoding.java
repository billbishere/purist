package purist.tags;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Turns the raw bytes of a text tag into a {@code String}, for formats that do not say which charset their text is
 * in (RIFF {@code LIST/INFO}, AIFF {@code NAME} and {@code AUTH}) and so have to be guessed.
 */
public final class TextDecoding {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private TextDecoding() {}

    /**
     * Decodes {@code len} bytes of {@code b} starting at {@code off}. The rules, in order:
     * <ol>
     *   <li>The text ends at the first NUL byte in the range, or at the end of the range if there is none; later
     *       bytes are ignored, so padding and junk after a terminator cannot affect the result.</li>
     *   <li>The text is UTF-8 if it decodes cleanly (a strict decode: any malformed sequence rejects it), and
     *       windows-1252 otherwise, which can decode any byte.</li>
     *   <li>The result is trimmed of leading and trailing whitespace ({@link String#strip()}).</li>
     * </ol>
     * It never throws for the content of the bytes, and an empty or all-NUL range gives the empty string.
     *
     * @throws IndexOutOfBoundsException if the range does not lie inside {@code b}
     */
    public static String decode(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, end - off))
                    .toString();
        } catch (CharacterCodingException e) {
            s = new String(b, off, end - off, WINDOWS_1252);
        }
        return s.strip();
    }
}
