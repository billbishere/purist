package purist.tags;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import purist.decode.io.FileSource;

/**
 * Reads ID3v2.3 and ID3v2.4 tags, the metadata WAV ({@code id3 }/{@code ID3 } chunk), AIFF ({@code ID3 } chunk),
 * DSF (metadata pointer) and DFF ({@code ID3 } chunk) carry. Version 2.2, and any version this reader does not
 * know, gives an empty result.
 *
 * <h2>What is read</h2>
 * <table>
 *   <caption>Frame mapping</caption>
 *   <tr><td>{@code TIT2}</td><td>TITLE</td></tr>
 *   <tr><td>{@code TPE1}</td><td>ARTIST</td></tr>
 *   <tr><td>{@code TPE2}</td><td>ALBUMARTIST</td></tr>
 *   <tr><td>{@code TALB}</td><td>ALBUM</td></tr>
 *   <tr><td>{@code TYER}, {@code TDRC}</td><td>DATE (whichever of the two comes first; the other is ignored)</td></tr>
 *   <tr><td>{@code TRCK}</td><td>TRACKNUMBER, raw text such as {@code "3/12"}</td></tr>
 *   <tr><td>{@code TPOS}</td><td>DISCNUMBER, raw text</td></tr>
 *   <tr><td>{@code TCON}</td><td>GENRE, with numeric references removed (see below)</td></tr>
 *   <tr><td>{@code TCOM}</td><td>COMPOSER</td></tr>
 *   <tr><td>{@code TXXX} with description {@code d}</td><td>the key {@code d}, upper-cased by {@link TagMap}</td></tr>
 *   <tr><td>{@code APIC}</td><td>{@link Id3Result#frontCover()}: type 3 (front cover) if there is one, else the
 *       first picture</td></tr>
 * </table>
 * Every other frame is ignored.
 *
 * <p><b>Text.</b> A text frame is split into values on the terminator of its encoding first (one zero byte for
 * encodings 0 and 3, an aligned pair of zero bytes for 1 and 2), then each value is decoded: encoding 0 with
 * {@link TextDecoding#decode} (UTF-8 if it is valid, else windows-1252, because some taggers write UTF-8 into
 * "ISO-8859-1" frames), encoding 1 as UTF-16 with a byte order mark (little-endian if there is none, the usual
 * Windows case), encoding 2 as UTF-16BE, encoding 3 as UTF-8. Each value becomes its own entry in the
 * {@link TagMap}, so ID3v2.4 multi-value frames give several. Empty and blank values are dropped, and only the
 * first 64 pieces of a frame are read, so the cost of a frame is proportional to its size. A frame whose
 * encoding byte is not 0 to 3 is skipped.
 *
 * <p><b>Genres.</b> Leading numeric references are removed from {@code TCON}: {@code "(17)Rock"} gives
 * {@code "Rock"}, and a value that is only a reference ({@code "(17)"}, or the ID3v2.4 form {@code "17"}, or
 * {@code "RX"}/{@code "CR"}) gives nothing, because Purist carries no table of the ID3v1 genres. {@code "(("}
 * at the start is the escape for a literal parenthesis.
 *
 * <p><b>Unsynchronisation.</b> In 2.3 the tag header flag 0x80 applies it to the whole tag, and the frame sizes
 * are those of the decoded frames. In 2.4 the frame flag 0x02 applies it to one frame, and the header flag 0x80
 * means (as the specification says) that every frame has it. The extended header, frame grouping bytes and the
 * 2.4 data length indicator are skipped; compressed and encrypted frames are skipped.
 *
 * <p><b>Damaged tags never throw.</b> A bad frame is skipped. A frame that claims more bytes than the tag has
 * left, a frame id that is not four capital letters or digits, and the first zero byte (padding) end the frame
 * walk, keeping what was read before. A bad tag header gives an empty result. ID3v2.4 tags written by some
 * taggers (iTunes) use plain instead of syncsafe frame sizes; a size with a high bit set is therefore read as
 * a plain 32-bit number.
 */
public final class Id3v2 {

    private static final int HEADER_BYTES = 10;
    /** The most of one tag that {@link #readAt} loads, however big the tag claims to be (the format allows 256 MiB). */
    private static final int MAX_TAG_BYTES = 64 * 1024 * 1024;

    private static final int FLAG_UNSYNCHRONISATION = 0x80;
    private static final int FLAG_EXTENDED_HEADER = 0x40;

    private static final int ENC_LATIN1 = 0;
    private static final int ENC_UTF16_BOM = 1;
    private static final int ENC_UTF16BE = 2;
    private static final int ENC_UTF8 = 3;

    private static final int PICTURE_FRONT_COVER = 3;

    /**
     * The most values read from one text frame; later ones are ignored. Real frames hold a handful. Empty pieces
     * are skipped before this count, so a damaged frame of nothing but terminators costs a scan, not a value
     * per terminator.
     */
    private static final int MAX_VALUES_PER_FRAME = 64;

    private Id3v2() {}

    /**
     * Parses a whole tag.
     *
     * @param tag the bytes of the tag, starting with the ten-byte {@code "ID3"} header; bytes after the size
     *            the header declares are ignored, and a tag cut short is read as far as it goes. May be null.
     * @return what was found; never null, and empty if {@code tag} is not a readable ID3v2.3/2.4 tag
     */
    public static Id3Result parse(byte[] tag) {
        if (tag == null || tag.length < HEADER_BYTES || !hasMagic(tag)) {
            return Id3Result.empty();
        }
        int major = tag[3] & 0xFF;
        if (major != 3 && major != 4) {
            return Id3Result.empty();
        }
        int flags = tag[5] & 0xFF;
        int size = syncsafe(tag, 6);
        if (size < 0) {
            return Id3Result.empty();
        }
        int end = (int) Math.min(tag.length, HEADER_BYTES + (long) size);

        byte[] body = tag;
        int pos = HEADER_BYTES;
        int limit = end;
        boolean tagUnsynchronised = (flags & FLAG_UNSYNCHRONISATION) != 0;
        if (major == 3 && tagUnsynchronised) {
            body = deunsynchronise(tag, pos, end - pos);
            pos = 0;
            limit = body.length;
        }
        if ((flags & FLAG_EXTENDED_HEADER) != 0) {
            long skip = extendedHeaderBytes(body, pos, limit, major);
            if (skip < 0) {
                return Id3Result.empty();
            }
            pos += (int) skip;
        }
        return readFrames(body, pos, limit, major, major == 4 && tagUnsynchronised);
    }

    /**
     * Reads the tag that starts at {@code offset} of {@code src} and runs to the end of the file. Equivalent to
     * {@link #readAt(FileSource, long, long)} with no length bound.
     */
    public static Optional<Id3Result> readAt(FileSource src, long offset) throws IOException {
        return readAt(src, offset, Long.MAX_VALUE);
    }

    /**
     * Reads the tag that starts at {@code offset} of {@code src}, looking at no more than {@code maxLength}
     * bytes (the length of the container chunk, say) and no more than the file holds. At most 64 MiB are loaded.
     * The position of {@code src} is left unspecified.
     *
     * @return empty if there is no {@code "ID3"} header at {@code offset}; a result, possibly an empty one, if
     *         there is a header (a tag that cannot be read is an empty result, not an error)
     * @throws IOException if the file cannot be read
     */
    public static Optional<Id3Result> readAt(FileSource src, long offset, long maxLength) throws IOException {
        if (offset < 0) {
            return Optional.empty();
        }
        long available = Math.min(maxLength, src.size() - offset);
        if (available < HEADER_BYTES) {
            return Optional.empty();
        }
        try {
            src.seek(offset);
            byte[] header = src.bytes(HEADER_BYTES);
            if (!hasMagic(header)) {
                return Optional.empty();
            }
            int size = syncsafe(header, 6);
            if (size < 0) {
                return Optional.of(Id3Result.empty());
            }
            int n = (int) Math.min(Math.min(available, HEADER_BYTES + (long) size), MAX_TAG_BYTES);
            src.seek(offset);
            return Optional.of(parse(src.bytes(n)));
        } catch (EOFException e) {
            return Optional.empty(); // the file got shorter while we were reading it
        }
    }

    // ---- frames ------------------------------------------------------------------------------------

    private static Id3Result readFrames(byte[] b, int start, int limit, int major, boolean allFramesUnsynchronised) {
        TagMap tags = new TagMap();
        byte[] front = null;
        byte[] firstPicture = null;
        String dateFrame = null;

        int pos = start;
        while (limit - pos >= HEADER_BYTES) {
            if (b[pos] == 0 || !isFrameId(b, pos)) {
                break; // padding, or something that is not a frame: nothing reliable follows
            }
            String id = new String(b, pos, 4, StandardCharsets.ISO_8859_1);
            long size = major == 4 ? frameSize24(b, pos + 4) : u32be(b, pos + 4);
            int frameFlags = b[pos + 9] & 0xFF;
            int dataStart = pos + HEADER_BYTES;
            if (size > limit - dataStart) {
                break;
            }
            pos = dataStart + (int) size;

            Span span = payload(b, dataStart, pos, major, frameFlags, allFramesUnsynchronised);
            if (span == null || span.off >= span.end) {
                continue;
            }
            switch (id) {
                case "APIC" -> {
                    Picture pic = readPicture(span);
                    if (pic != null && pic.type == PICTURE_FRONT_COVER) {
                        front = front != null ? front : pic.copy();
                    } else if (pic != null && firstPicture == null) {
                        firstPicture = pic.copy();
                    }
                }
                case "TXXX" -> readUserText(span, tags);
                default -> {
                    String key = keyOf(id);
                    if (key == null || (key.equals("DATE") && dateFrame != null && !dateFrame.equals(id))) {
                        continue;
                    }
                    readText(span, key, tags);
                    if (key.equals("DATE") && dateFrame == null && tags.first("DATE").isPresent()) {
                        dateFrame = id;
                    }
                }
            }
        }
        return new Id3Result(tags, Optional.ofNullable(front != null ? front : firstPicture));
    }

    /** A range of bytes: the data of one frame, with its flags-driven prefixes removed. */
    private record Span(byte[] b, int off, int end) {}

    /**
     * The data of a frame, or null if it cannot be read (compressed or encrypted). Handles unsynchronisation,
     * the grouping byte and the 2.4 data length indicator, in the order the specification gives them.
     */
    private static Span payload(byte[] b, int start, int stop, int major, int flags, boolean allUnsynchronised) {
        byte[] data = b;
        int off = start;
        int end = stop;
        if (major == 3) {
            if ((flags & 0xC0) != 0) {
                return null; // 0x80 compressed, 0x40 encrypted
            }
            if ((flags & 0x20) != 0) {
                off += 1; // grouping identity
            }
        } else {
            if ((flags & 0x0C) != 0) {
                return null; // 0x08 compressed, 0x04 encrypted
            }
            if (allUnsynchronised || (flags & 0x02) != 0) {
                data = deunsynchronise(b, off, end - off);
                off = 0;
                end = data.length;
            }
            if ((flags & 0x40) != 0) {
                off += 1; // grouping identity
            }
            if ((flags & 0x01) != 0) {
                off += 4; // data length indicator
            }
        }
        return off <= end ? new Span(data, off, end) : null;
    }

    private static String keyOf(String id) {
        return switch (id) {
            case "TIT2" -> "TITLE";
            case "TPE1" -> "ARTIST";
            case "TPE2" -> "ALBUMARTIST";
            case "TALB" -> "ALBUM";
            case "TYER", "TDRC" -> "DATE";
            case "TRCK" -> "TRACKNUMBER";
            case "TPOS" -> "DISCNUMBER";
            case "TCON" -> "GENRE";
            case "TCOM" -> "COMPOSER";
            default -> null;
        };
    }

    private static void readText(Span s, String key, TagMap tags) {
        int encoding = s.b[s.off] & 0xFF;
        if (encoding > ENC_UTF8) {
            return;
        }
        for (String v : values(encoding, s.b, s.off + 1, s.end)) {
            tags.add(key, key.equals("GENRE") ? genre(v) : v);
        }
    }

    /** {@code TXXX}: encoding, description, then one or more values. */
    private static void readUserText(Span s, TagMap tags) {
        int encoding = s.b[s.off] & 0xFF;
        if (encoding > ENC_UTF8) {
            return;
        }
        int descriptionStart = s.off + 1;
        int descriptionEnd = terminator(encoding, s.b, descriptionStart, s.end);
        if (descriptionEnd <= descriptionStart) {
            return; // no terminator, so no value; or an empty description
        }
        String description = decode(encoding, s.b, descriptionStart, descriptionEnd);
        if (description.isBlank()) {
            return;
        }
        for (String v : values(encoding, s.b, descriptionEnd + terminatorLength(encoding), s.end)) {
            tags.add(description, v);
        }
    }

    /** A picture found in an {@code APIC} frame; the image is the range {@code data[off..end)}. */
    private record Picture(int type, byte[] data, int off, int end) {
        byte[] copy() {
            return Arrays.copyOfRange(data, off, end);
        }
    }

    /** {@code APIC}: encoding, MIME (Latin-1, terminated), picture type, description (terminated), image. */
    private static Picture readPicture(Span s) {
        int encoding = s.b[s.off] & 0xFF;
        if (encoding > ENC_UTF8) {
            return null;
        }
        int mimeStart = s.off + 1;
        int mimeEnd = terminator(ENC_LATIN1, s.b, mimeStart, s.end);
        if (mimeEnd < 0 || mimeEnd + 1 >= s.end) {
            return null;
        }
        String mime = new String(s.b, mimeStart, mimeEnd - mimeStart, StandardCharsets.ISO_8859_1);
        if (mime.equals("-->")) {
            return null; // the "image" is a URL
        }
        int type = s.b[mimeEnd + 1] & 0xFF;
        int descStart = mimeEnd + 2;
        int descEnd = terminator(encoding, s.b, descStart, s.end);
        if (descEnd < 0) {
            return null;
        }
        int imageStart = descEnd + terminatorLength(encoding);
        return imageStart < s.end ? new Picture(type, s.b, imageStart, s.end) : null;
    }

    // ---- text ------------------------------------------------------------------------------------

    /**
     * Splits {@code b[off..end)} on the terminator of {@code encoding} and decodes the pieces. Empty pieces (two
     * terminators in a row, or one at either end) are skipped without being decoded, and at most
     * {@value #MAX_VALUES_PER_FRAME} pieces are decoded; the rest of the range is ignored. The work and the
     * memory are therefore proportional to the size of the range however many terminators it holds.
     */
    private static List<String> values(int encoding, byte[] b, int off, int end) {
        int width = terminatorLength(encoding);
        List<String> out = new ArrayList<>(2);
        int from = off;
        while (from < end && out.size() < MAX_VALUES_PER_FRAME) {
            int stop = terminator(encoding, b, from, end); // aligned to from, which is aligned to off
            int to = stop < 0 ? end : stop;
            if (to > from) {
                out.add(decode(encoding, b, from, to));
            }
            if (stop < 0) {
                break;
            }
            from = stop + width;
        }
        return out;
    }

    private static String decode(int encoding, byte[] b, int from, int to) {
        return switch (encoding) {
            case ENC_LATIN1 -> TextDecoding.decode(b, from, to - from);
            case ENC_UTF8 -> clean(new String(b, from, to - from, StandardCharsets.UTF_8));
            case ENC_UTF16_BOM -> {
                boolean bigEndian = false;
                int start = from;
                if (to - from >= 2) {
                    int b0 = b[from] & 0xFF;
                    int b1 = b[from + 1] & 0xFF;
                    if (b0 == 0xFE && b1 == 0xFF) {
                        bigEndian = true;
                        start += 2;
                    } else if (b0 == 0xFF && b1 == 0xFE) {
                        start += 2;
                    }
                }
                yield utf16(b, start, to, bigEndian);
            }
            default -> utf16(b, from, to, true);
        };
    }

    private static String utf16(byte[] b, int from, int to, boolean bigEndian) {
        int length = (to - from) & ~1; // a stray last byte is not a character
        return clean(new String(b, from, length, bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE));
    }

    /** Drops a leading byte order mark character and surrounding white space. */
    private static String clean(String s) {
        return (s.startsWith("﻿") ? s.substring(1) : s).strip();
    }

    /** {@code TCON} without its numeric references; empty if nothing but references was there. */
    private static String genre(String value) {
        String s = value;
        while (s.startsWith("(") && !s.startsWith("((")) {
            int close = s.indexOf(')');
            if (close < 0 || !isGenreReference(s.substring(1, close))) {
                break;
            }
            s = s.substring(close + 1).stripLeading();
        }
        if (s.startsWith("((")) {
            s = s.substring(1); // "((" is an escaped "("
        }
        return isGenreReference(s) ? "" : s;
    }

    /** 1 to 3 digits (an ID3v1 genre number), or {@code RX} (remix), or {@code CR} (cover). */
    private static boolean isGenreReference(String s) {
        if (s.equals("RX") || s.equals("CR")) {
            return true;
        }
        if (s.isEmpty() || s.length() > 3) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Bytes in the terminator of a string in this encoding. */
    private static int terminatorLength(int encoding) {
        return encoding == ENC_UTF16_BOM || encoding == ENC_UTF16BE ? 2 : 1;
    }

    /** The index of the first terminator in {@code b[from..end)} (aligned to {@code from} for UTF-16), or -1. */
    private static int terminator(int encoding, byte[] b, int from, int end) {
        int width = terminatorLength(encoding);
        for (int i = from; i + width <= end; i += width) {
            if (b[i] == 0 && (width == 1 || b[i + 1] == 0)) {
                return i;
            }
        }
        return -1;
    }

    // ---- bytes -------------------------------------------------------------------------------------

    private static boolean hasMagic(byte[] b) {
        return b[0] == 'I' && b[1] == 'D' && b[2] == '3';
    }

    private static boolean isFrameId(byte[] b, int pos) {
        for (int i = pos; i < pos + 4; i++) {
            if (!(b[i] >= 'A' && b[i] <= 'Z') && !(b[i] >= '0' && b[i] <= '9')) {
                return false;
            }
        }
        return true;
    }

    /** Four bytes of 7 bits each, or -1 if any byte has its top bit set. */
    private static int syncsafe(byte[] b, int off) {
        int v = 0;
        for (int i = 0; i < 4; i++) {
            if ((b[off + i] & 0x80) != 0) {
                return -1;
            }
            v = v << 7 | b[off + i];
        }
        return v;
    }

    private static long u32be(byte[] b, int off) {
        return (b[off] & 0xFFL) << 24 | (b[off + 1] & 0xFFL) << 16 | (b[off + 2] & 0xFFL) << 8 | (b[off + 3] & 0xFFL);
    }

    /** A 2.4 frame size: syncsafe, or a plain number if the bytes cannot be syncsafe. */
    private static long frameSize24(byte[] b, int off) {
        int s = syncsafe(b, off);
        return s >= 0 ? s : u32be(b, off);
    }

    /** Bytes the extended header takes at {@code pos}, or -1 if it does not fit the tag. */
    private static long extendedHeaderBytes(byte[] b, int pos, int limit, int major) {
        if (limit - pos < 4) {
            return -1;
        }
        long n;
        if (major == 3) {
            n = 4 + u32be(b, pos); // the size does not count its own four bytes
        } else {
            int declared = syncsafe(b, pos); // here it does
            if (declared < 6) {
                return -1;
            }
            n = declared;
        }
        return n <= limit - pos ? n : -1;
    }

    /** Undoes unsynchronisation: every 0xFF 0x00 becomes 0xFF. */
    private static byte[] deunsynchronise(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        int n = 0;
        int end = off + len;
        for (int i = off; i < end; i++) {
            out[n++] = b[i];
            if (b[i] == (byte) 0xFF && i + 1 < end && b[i + 1] == 0) {
                i++;
            }
        }
        return n == len ? out : Arrays.copyOf(out, n);
    }
}
