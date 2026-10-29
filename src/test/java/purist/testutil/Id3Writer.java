package purist.testutil;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds ID3v2 tags for tests, with an encoder of its own that shares no code with the reader under test.
 *
 * <p>A tag is made of frames added in order; {@link #build} puts the header in front, applies unsynchronisation
 * and padding as asked, and returns the bytes. Text values are written separated by the encoding's terminator
 * (one zero byte for Latin-1 and UTF-8, two for UTF-16) and, like most writers, without a terminator after the
 * last value unless the last value is an empty string.
 *
 * <p>Version 2.3 puts unsynchronisation on the whole tag (header flag 0x80, frame sizes are those of the
 * decoded frames); version 2.4 puts it on individual frames (frame flag 0x02, frame sizes are those of the
 * stored bytes), as the two specifications say.
 */
public final class Id3Writer {

    public static final int LATIN1 = 0;
    /** UTF-16 with a little-endian byte order mark, what Windows taggers write. */
    public static final int UTF16 = 1;
    public static final int UTF16BE = 2;
    public static final int UTF8 = 3;

    private static final Charset LATIN1_CS = StandardCharsets.ISO_8859_1;

    private final int major;
    private final List<byte[]> frames = new ArrayList<>();
    private boolean unsynchronised;
    private boolean extendedHeader;
    private int padding;
    private byte[] trailer = new byte[0];

    private Id3Writer(int major) {
        this.major = major;
    }

    /** A version 2.3 tag. */
    public static Id3Writer v23() {
        return new Id3Writer(3);
    }

    /** A version 2.4 tag. */
    public static Id3Writer v24() {
        return new Id3Writer(4);
    }

    /** A version 2.2 tag (three-letter frame ids are not written: this is only for the refusal test). */
    public static Id3Writer v22() {
        return new Id3Writer(2);
    }

    /** Applies unsynchronisation: to the whole tag in 2.3, to every frame (flag 0x02) in 2.4. */
    public Id3Writer unsynchronised() {
        unsynchronised = true;
        return this;
    }

    /** Writes an extended header after the tag header (6 bytes in 2.3 plus its size field, 6 in 2.4). */
    public Id3Writer extendedHeader() {
        extendedHeader = true;
        return this;
    }

    /** Appends this many zero bytes after the last frame, inside the declared tag size. */
    public Id3Writer padding(int bytes) {
        padding = bytes;
        return this;
    }

    /** Appends these bytes after the padding, still inside the declared tag size. */
    public Id3Writer trailer(byte[] bytes) {
        trailer = bytes;
        return this;
    }

    /** A text frame holding these values in the given encoding. */
    public Id3Writer text(String id, int encoding, String... values) {
        return frame(id, 0, textPayload(encoding, values));
    }

    /** A {@code TXXX} frame: description, then the values. */
    public Id3Writer txxx(String description, int encoding, String... values) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(encoding);
        o.writeBytes(encode(description, encoding));
        o.writeBytes(terminator(encoding));
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                o.writeBytes(terminator(encoding));
            }
            o.writeBytes(encode(values[i], encoding));
        }
        return frame("TXXX", 0, o.toByteArray());
    }

    /** An {@code APIC} frame. */
    public Id3Writer apic(String mime, int pictureType, String description, int encoding, byte[] image) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(encoding);
        o.writeBytes(mime.getBytes(LATIN1_CS));
        o.write(0);
        o.write(pictureType);
        o.writeBytes(encode(description, encoding));
        o.writeBytes(terminator(encoding));
        o.writeBytes(image);
        return frame("APIC", 0, o.toByteArray());
    }

    /**
     * A frame with exactly this payload. {@code formatFlags} is the second flag byte; for version 2.4 the
     * unsynchronisation bit is added on top when {@link #unsynchronised} is set.
     */
    public Id3Writer frame(String id, int formatFlags, byte[] payload) {
        byte[] data = payload;
        int flags2 = formatFlags;
        if (major == 4 && unsynchronised) {
            data = unsynchronise(payload);
            flags2 |= 0x02;
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(id.getBytes(LATIN1_CS));
        o.writeBytes(major == 4 ? syncsafe(data.length) : plain32(data.length));
        o.write(0);
        o.write(flags2);
        o.writeBytes(data);
        frames.add(o.toByteArray());
        return this;
    }

    /** A frame whose header declares {@code declaredSize} bytes while only {@code payload} follows. */
    public Id3Writer frameWithDeclaredSize(String id, int declaredSize, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(id.getBytes(LATIN1_CS));
        o.writeBytes(major == 4 ? syncsafe(declaredSize) : plain32(declaredSize));
        o.write(0);
        o.write(0);
        o.writeBytes(payload);
        frames.add(o.toByteArray());
        return this;
    }

    /** The finished tag: ten header bytes, the optional extended header, the frames, the padding. */
    public byte[] build() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (extendedHeader) {
            if (major == 4) {
                body.writeBytes(syncsafe(6));
                body.write(1); // one flag byte
                body.write(0); // no flags set
            } else {
                body.writeBytes(plain32(6));
                body.writeBytes(new byte[] {0, 0}); // flags
                body.writeBytes(plain32(0)); // padding size
            }
        }
        for (byte[] f : frames) {
            body.writeBytes(f);
        }
        byte[] content = body.toByteArray();
        if (major == 3 && unsynchronised) {
            content = unsynchronise(content);
        }
        int flags = 0;
        if (major != 4 && unsynchronised) {
            flags |= 0x80;
        }
        if (extendedHeader) {
            flags |= 0x40;
        }
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.writeBytes(new byte[] {'I', 'D', '3', (byte) major, 0, (byte) flags});
        tag.writeBytes(syncsafe(content.length + padding + trailer.length));
        tag.writeBytes(content);
        tag.writeBytes(new byte[padding]);
        tag.writeBytes(trailer);
        return tag.toByteArray();
    }

    // ---- encoders ----------------------------------------------------------------------------------

    /** {@code s} in the given encoding, without a terminator. */
    public static byte[] encode(String s, int encoding) {
        return switch (encoding) {
            case LATIN1 -> s.getBytes(LATIN1_CS);
            case UTF16 -> utf16(s, true, true);
            case UTF16BE -> s.getBytes(StandardCharsets.UTF_16BE);
            case UTF8 -> s.getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException("encoding " + encoding);
        };
    }

    /** UTF-16 text in the given byte order, preceded by a byte order mark if {@code bom} is set. */
    public static byte[] utf16(String s, boolean littleEndian, boolean bom) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        if (bom) {
            o.writeBytes(littleEndian ? new byte[] {(byte) 0xFF, (byte) 0xFE} : new byte[] {(byte) 0xFE, (byte) 0xFF});
        }
        o.writeBytes(s.getBytes(littleEndian ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE));
        return o.toByteArray();
    }

    /** The terminator of a string in this encoding. */
    public static byte[] terminator(int encoding) {
        return encoding == UTF16 || encoding == UTF16BE ? new byte[2] : new byte[1];
    }

    /** The payload of a text frame: encoding byte, then the values separated by terminators. */
    public static byte[] textPayload(int encoding, String... values) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(encoding);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                o.writeBytes(terminator(encoding));
            }
            o.writeBytes(encode(values[i], encoding));
        }
        return o.toByteArray();
    }

    /**
     * Unsynchronisation: a zero byte goes after every 0xFF that is followed by a byte of 0xE0 or more, by 0x00,
     * or by the end of the data, so no false MPEG sync pattern can appear.
     */
    public static byte[] unsynchronise(byte[] in) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < in.length; i++) {
            o.write(in[i]);
            if ((in[i] & 0xFF) == 0xFF && (i == in.length - 1 || (in[i + 1] & 0xFF) >= 0xE0 || in[i + 1] == 0)) {
                o.write(0);
            }
        }
        return o.toByteArray();
    }

    /** Four bytes of 7 bits each, most significant first. */
    public static byte[] syncsafe(int n) {
        return new byte[] {(byte) (n >> 21 & 0x7F), (byte) (n >> 14 & 0x7F), (byte) (n >> 7 & 0x7F),
                (byte) (n & 0x7F)};
    }

    private static byte[] plain32(int n) {
        return new byte[] {(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }
}
