package purist.tags;

import java.nio.charset.StandardCharsets;

/**
 * Reads a Vorbis comment block, the tag format of FLAC (RFC 9639 section 8.6, the VORBIS_COMMENT metadata block).
 *
 * <p>The block is a little-endian {@code u32} vendor-string length and the vendor string, a little-endian
 * {@code u32} comment count, then that many comments, each a little-endian {@code u32} length and
 * {@code KEY=value} text. The key is ASCII; the value is UTF-8, or windows-1252 when it is not valid UTF-8
 * ({@link TextDecoding#decode}), and ends at its first NUL. A key that occurs more than once keeps every value in
 * order. Keys fold the way {@link TagMap} folds them.
 *
 * <p><b>Damaged blocks never throw.</b> The comment count is only an upper bound on the comments read: a block
 * that is cut short, or whose lengths run past its end, keeps the complete comments before the damage. A comment
 * with no {@code =}, an empty key or an empty value is skipped.
 */
public final class VorbisComments {

    private VorbisComments() {}

    /**
     * @param block the payload of the VORBIS_COMMENT block, without its metadata block header; may be null
     * @return the tags; never null, and empty if the block holds none or is not readable
     */
    public static TagMap parse(byte[] block) {
        TagMap tags = new TagMap();
        if (block == null || block.length < 8) {
            return tags;
        }
        long vendorLength = u32le(block, 0);
        long pos = 4 + vendorLength;
        if (pos + 4 > block.length) {
            return tags;
        }
        long count = u32le(block, (int) pos);
        pos += 4;
        for (long i = 0; i < count && pos + 4 <= block.length; i++) {
            long length = u32le(block, (int) pos);
            pos += 4;
            if (length > block.length - pos) {
                break; // this comment runs past the end of the block, and so does everything after it
            }
            int start = (int) pos;
            int end = start + (int) length;
            pos = end;
            int equals = start;
            while (equals < end && block[equals] != '=') {
                equals++;
            }
            if (equals == end) {
                continue; // no "="
            }
            String key = new String(block, start, equals - start, StandardCharsets.ISO_8859_1);
            tags.add(key, TextDecoding.decode(block, equals + 1, end - equals - 1));
        }
        return tags;
    }

    private static long u32le(byte[] b, int off) {
        return (b[off] & 0xFFL) | (b[off + 1] & 0xFFL) << 8 | (b[off + 2] & 0xFFL) << 16 | (b[off + 3] & 0xFFL) << 24;
    }
}
