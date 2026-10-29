package purist.decode.sacd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import purist.decode.DecodeException;
import purist.decode.UnsupportedFormatException;
import purist.decode.io.FileSource;
import purist.tags.TagMap;

/**
 * An SACD disc image (an {@code .iso}): its master table of contents, master text and the stereo area with its
 * tracks, and sector-level access to the audio. Every offset comes from the spec's "SACD ISO layout" subsection
 * (verified on a real disc); all numbers are big-endian.
 *
 * <p>Sectors are 2048 bytes. A raw image with 2064-byte sectors (a 12-byte header before the data) is detected by
 * the {@code SACDMTOC} marker in sector 510 at 2064-byte spacing, and {@link #readSector} then skips the header.
 *
 * <p>Only the stereo area is read. A multichannel area is ignored, and an area that is DST-compressed is parsed
 * and flagged ({@link SacdArea#isDst()}), not decoded. The text is read as ISO-8859-1.
 *
 * <p>Not thread-safe.
 */
public final class SacdImage implements AutoCloseable {

    /** Payload bytes in a sector. */
    public static final int SECTOR_BYTES = 2048;

    private static final int RAW_SECTOR_BYTES = 2064;
    private static final int RAW_HEADER_BYTES = 12;
    private static final int MASTER_TOC_SECTOR = 510;
    private static final int MASTER_TEXT_SECTOR = 511;
    private static final int MAX_TRACKS = 255;
    /** The largest area-TOC size accepted (real discs use 8 sectors). */
    private static final int MAX_AREA_TOC_SECTORS = 64;

    private final FileSource src;
    private final int sectorSize;
    private final String albumTitle;
    private final String albumArtist;
    private final Optional<LocalDate> date;
    private final Optional<SacdArea> stereoArea;

    private SacdImage(FileSource src, int sectorSize) throws IOException {
        this.src = src;
        this.sectorSize = sectorSize;

        byte[] master = readSector(MASTER_TOC_SECTOR);
        long stereoToc = u32(master, 0x40);

        int year = u16(master, 0x78);
        int month = master[0x7A] & 0xFF;
        int day = master[0x7B] & 0xFF;
        Optional<LocalDate> d = Optional.empty();
        if (year != 0) {
            try {
                d = Optional.of(LocalDate.of(year, month, day));
            } catch (DateTimeException e) {
                // a date that does not exist is no date
            }
        }
        this.date = d;

        String title = "";
        String artist = "";
        if (MASTER_TEXT_SECTOR * (long) sectorSize < src.size()) {
            byte[] text = readSector(MASTER_TEXT_SECTOR);
            if (hasId(text, 0, "SACDText")) {
                title = cString(text, u16(text, 0x10));
                artist = cString(text, u16(text, 0x12));
            }
        }
        this.albumTitle = title;
        this.albumArtist = artist;

        this.stereoArea = stereoToc == 0 ? Optional.empty() : Optional.of(parseArea(stereoToc));
    }

    /**
     * Opens and parses the image.
     *
     * @throws UnsupportedFormatException {@code "not an SACD image"} if there is no {@code SACDMTOC} marker, or
     *         if the stereo area is of a kind Purist does not play (other sampling rate or frame format)
     * @throws DecodeException if the image is damaged
     */
    public static SacdImage open(Path iso) throws IOException {
        FileSource src = FileSource.open(iso);
        try {
            return new SacdImage(src, detectSectorSize(src));
        } catch (IOException | RuntimeException e) {
            try {
                src.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static int detectSectorSize(FileSource src) throws IOException {
        if (hasMarkerAt(src, (long) MASTER_TOC_SECTOR * SECTOR_BYTES)) {
            return SECTOR_BYTES;
        }
        if (hasMarkerAt(src, (long) MASTER_TOC_SECTOR * RAW_SECTOR_BYTES + RAW_HEADER_BYTES)) {
            return RAW_SECTOR_BYTES;
        }
        throw new UnsupportedFormatException("not an SACD image");
    }

    private static boolean hasMarkerAt(FileSource src, long offset) throws IOException {
        if (offset + 8 > src.size()) {
            return false;
        }
        src.seek(offset);
        return src.fourcc().equals("SACD") && src.fourcc().equals("MTOC");
    }

    /** The raw sector size: 2048, or 2064 for an image with a 12-byte header before each sector's data. */
    public int sectorSize() {
        return sectorSize;
    }

    /** The album title from the master text; empty if there is none. */
    public String albumTitle() {
        return albumTitle;
    }

    /** The album artist from the master text; empty if there is none. */
    public String albumArtist() {
        return albumArtist;
    }

    /** The disc date from the master TOC, if it holds a valid one. */
    public Optional<LocalDate> date() {
        return date;
    }

    /** The stereo area; empty if the disc has none (a multichannel-only disc). */
    public Optional<SacdArea> stereoArea() {
        return stereoArea;
    }

    /**
     * The 2048-byte payload of sector {@code lsn}.
     *
     * @throws DecodeException if the sector is not in the image
     */
    public byte[] readSector(long lsn) throws IOException {
        byte[] b = new byte[SECTOR_BYTES];
        readSector(lsn, b);
        return b;
    }

    /** Like {@link #readSector(long)} but into {@code dst}, which must hold 2048 bytes; allocates nothing. */
    public void readSector(long lsn, byte[] dst) throws IOException {
        if (dst.length < SECTOR_BYTES) {
            throw new IllegalArgumentException("a sector needs 2048 bytes, not " + dst.length);
        }
        long offset = lsn * sectorSize + (sectorSize == RAW_SECTOR_BYTES ? RAW_HEADER_BYTES : 0);
        if (lsn < 0 || offset + SECTOR_BYTES > src.size()) {
            throw new DecodeException("SACD sector " + lsn + " is beyond the end of the image");
        }
        src.seek(offset);
        if (src.read(dst, 0, SECTOR_BYTES) != SECTOR_BYTES) {
            throw new DecodeException("SACD image ends inside sector " + lsn);
        }
    }

    @Override
    public void close() throws IOException {
        src.close();
    }

    // ---- the stereo area --------------------------------------------------------------------------------------

    private SacdArea parseArea(long tocSector) throws IOException {
        byte[] toc = readSector(tocSector);
        if (!hasId(toc, 0, "TWOCHTOC")) {
            throw new DecodeException("SACD stereo area TOC (TWOCHTOC) is missing at sector " + tocSector);
        }
        int size = u16(toc, 0x0A);
        int fsCode = toc[0x14] & 0xFF;
        int frameFormat = toc[0x15] & 0x0F;
        int channels = toc[0x20] & 0xFF;
        int trackCount = toc[0x45] & 0xFF;
        long areaStart = u32(toc, 0x48);
        long areaEnd = u32(toc, 0x4C);

        if (size < 1 || size > MAX_AREA_TOC_SECTORS) {
            throw new DecodeException("SACD area TOC has an impossible size of " + size + " sectors");
        }
        if (fsCode != SacdArea.FS_DSD64) {
            throw new UnsupportedFormatException("unsupported SACD sampling frequency code " + fsCode);
        }
        if (frameFormat != 0 && frameFormat != 2 && frameFormat != 3) {
            throw new UnsupportedFormatException("unknown SACD frame format " + frameFormat);
        }
        if (channels < 1) {
            throw new DecodeException("SACD stereo area declares no channels");
        }
        if (areaEnd < areaStart) {
            throw new DecodeException("SACD track area ends (" + areaEnd + ") before it starts (" + areaStart + ")");
        }
        if (trackCount > MAX_TRACKS) {
            throw new DecodeException("SACD area declares " + trackCount + " tracks");
        }

        // the sub-sectors are found by their 8-byte IDs among the area's sectors
        byte[] trl1 = null;
        byte[] trl2 = null;
        long ttxtSector = -1;
        byte[] head = new byte[SECTOR_BYTES];
        for (int s = 1; s < size; s++) {
            readSector(tocSector + s, head);
            if (trl1 == null && hasId(head, 0, "SACDTRL1")) {
                trl1 = head.clone();
            } else if (trl2 == null && hasId(head, 0, "SACDTRL2")) {
                trl2 = head.clone();
            } else if (ttxtSector < 0 && hasId(head, 0, "SACDTTxt")) {
                ttxtSector = tocSector + s;
            }
        }

        List<SacdTrack> tracks = new ArrayList<>(trackCount);
        if (trackCount > 0) {
            if (trl1 == null) {
                throw new DecodeException("SACD area has no track list (SACDTRL1)");
            }
            if (trl2 == null) {
                throw new DecodeException("SACD area has no track times (SACDTRL2)");
            }
            // the text entries may lie in the sectors after SACDTTxt, up to the end of the area's sectors
            byte[] text = ttxtSector < 0 ? new byte[0] : readSectors(ttxtSector, tocSector + size - ttxtSector);
            for (int i = 0; i < trackCount; i++) {
                tracks.add(new SacdTrack(i + 1,
                        u32(trl1, 8 + 4 * i),
                        u32(trl1, 8 + 4 * MAX_TRACKS + 4 * i),
                        timeCode(trl2, 8 + 4 * i),
                        timeCode(trl2, 8 + 4 * MAX_TRACKS + 4 * i),
                        trackText(text, i)));
            }
        }
        return new SacdArea(tocSector, fsCode, frameFormat, channels, areaStart, areaEnd, tracks);
    }

    private byte[] readSectors(long first, long count) throws IOException {
        byte[] all = new byte[(int) count * SECTOR_BYTES];
        byte[] one = new byte[SECTOR_BYTES];
        for (int i = 0; i < count; i++) {
            readSector(first + i, one);
            System.arraycopy(one, 0, all, i * SECTOR_BYTES, SECTOR_BYTES);
        }
        return all;
    }

    /**
     * The text of track {@code index} (0-based) from the SACDTTxt data: a u16 offset per track at +8, each
     * pointing at {@code u8 item_count, 3 pad bytes}, then per item {@code u8 type, u8 0x20, NUL-terminated string},
     * every item padded to a 4-byte boundary. Text is metadata: an entry that does not parse gives fewer tags,
     * never an error.
     */
    private static TagMap trackText(byte[] text, int index) {
        TagMap tags = new TagMap();
        int tableAt = 8 + 2 * index;
        if (tableAt + 2 > text.length) {
            return tags;
        }
        int entry = u16(text, tableAt);
        if (entry == 0 || entry + 4 > text.length) {
            return tags;
        }
        int items = text[entry] & 0xFF;
        int p = entry + 4;
        for (int k = 0; k < items && p + 2 <= text.length; k++) {
            int type = text[p] & 0xFF;
            int start = p + 2;
            int nul = start;
            while (nul < text.length && text[nul] != 0) {
                nul++;
            }
            if (nul >= text.length) {
                break;
            }
            String key = switch (type) {
                case 1 -> "TITLE";
                case 2 -> "ARTIST";
                case 3 -> "LYRICIST";
                case 4 -> "COMPOSER";
                case 5 -> "ARRANGER";
                case 6 -> "COMMENT";
                default -> null;
            };
            if (key != null) {
                tags.add(key, new String(text, start, nul - start, StandardCharsets.ISO_8859_1).strip());
            }
            p = entry + ((nul + 1 - entry + 3) & ~3);
        }
        return tags;
    }

    // ---- byte helpers -----------------------------------------------------------------------------------------

    private static boolean hasId(byte[] b, int at, String id) {
        byte[] want = id.getBytes(StandardCharsets.US_ASCII);
        return Arrays.equals(b, at, at + want.length, want, 0, want.length);
    }

    private static int u16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) << 24 | (b[at + 1] & 0xFFL) << 16 | (b[at + 2] & 0xFFL) << 8 | (b[at + 3] & 0xFFL);
    }

    /** Minutes, seconds, frames as three bytes at {@code at}, in 1/75 s frames. */
    private static long timeCode(byte[] b, int at) {
        return ((b[at] & 0xFFL) * 60 + (b[at + 1] & 0xFF)) * 75 + (b[at + 2] & 0xFF);
    }

    /** The NUL-terminated string at {@code offset} in the sector; empty for offset 0 or one outside the sector. */
    private static String cString(byte[] b, int offset) {
        if (offset == 0 || offset >= b.length) {
            return "";
        }
        int end = offset;
        while (end < b.length && b[end] != 0) {
            end++;
        }
        return new String(b, offset, end - offset, StandardCharsets.ISO_8859_1).strip();
    }
}
