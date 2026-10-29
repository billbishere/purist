package purist.tags;

import java.nio.charset.StandardCharsets;

/**
 * Reads the text tags of a RIFF {@code LIST/INFO} chunk, the metadata most WAV writers use.
 *
 * <p>Mapping: {@code INAM} TITLE, {@code IART} ARTIST, {@code IPRD} ALBUM, {@code ICRD} DATE, {@code IGNR} GENRE,
 * {@code ITRK} or {@code IPRT} TRACKNUMBER (only the first of the two that appears), {@code ICMT} COMMENT. Other
 * ids are ignored. Text is decoded by {@link TextDecoding#decode}: UTF-8 when it decodes cleanly and windows-1252
 * otherwise, ending at the first NUL and trimmed.
 */
public final class RiffInfo {

    private RiffInfo() {}

    /**
     * Parses the payload of a {@code LIST} chunk of type {@code INFO}. The leading {@code "INFO"} form type is
     * skipped if present, so the payload may be given with or without it. Damaged input never throws: a
     * truncated chunk ends the parse and whatever was read before it is kept.
     *
     * @return the tags found; empty if there are none
     */
    public static TagMap parse(byte[] infoListPayload) {
        TagMap tags = new TagMap();
        if (infoListPayload == null) {
            return tags;
        }
        byte[] b = infoListPayload;
        int pos = b.length >= 4 && b[0] == 'I' && b[1] == 'N' && b[2] == 'F' && b[3] == 'O' ? 4 : 0;
        boolean haveTrackNumber = false;
        while (b.length - pos >= 8) {
            String id = new String(b, pos, 4, StandardCharsets.ISO_8859_1);
            long size = (b[pos + 4] & 0xFFL) | (b[pos + 5] & 0xFFL) << 8 | (b[pos + 6] & 0xFFL) << 16
                    | (b[pos + 7] & 0xFFL) << 24;
            int start = pos + 8;
            if (size > b.length - start) {
                break; // the last chunk is cut off: ignore it
            }
            int len = (int) size;
            String key = switch (id) {
                case "INAM" -> "TITLE";
                case "IART" -> "ARTIST";
                case "IPRD" -> "ALBUM";
                case "ICRD" -> "DATE";
                case "IGNR" -> "GENRE";
                case "ITRK", "IPRT" -> "TRACKNUMBER";
                case "ICMT" -> "COMMENT";
                default -> null;
            };
            if (key != null) {
                String value = TextDecoding.decode(b, start, len);
                if (!value.isEmpty() && !(haveTrackNumber && key.equals("TRACKNUMBER"))) {
                    tags.add(key, value);
                    haveTrackNumber |= key.equals("TRACKNUMBER");
                }
            }
            pos = start + len + (len & 1); // chunks are padded to an even length
        }
        return tags;
    }
}
