package purist.decode.sacd;

import java.util.List;

/**
 * One audio area of an SACD (the stereo one is the only one Purist plays).
 *
 * @param tocSector       the sector holding the area TOC ({@code TWOCHTOC})
 * @param fsCode          the sampling frequency code; 4 is DSD64
 * @param frameFormat     0 = DST-compressed, 2 = plain DSD "3 frames in 14 sectors", 3 = plain DSD "3 in 16"
 * @param channels        channels in the area
 * @param trackAreaStart  the first sector of the audio
 * @param trackAreaEnd    the last sector of the audio (inclusive)
 */
public record SacdArea(long tocSector, int fsCode, int frameFormat, int channels, long trackAreaStart,
        long trackAreaEnd, List<SacdTrack> tracks) {

    /** Sampling frequency code of DSD64, the only rate on an SACD. */
    static final int FS_DSD64 = 4;

    /** Bytes per channel in one frame (1/75 s of DSD64). */
    static final int FRAME_BYTES_PER_CHANNEL = 4704;

    public SacdArea {
        tracks = List.copyOf(tracks);
    }

    /** True if the audio is DST-compressed (frame format 0), which Purist does not decode. */
    public boolean isDst() {
        return frameFormat == 0;
    }

    /** The DSD bit rate per channel: 2822400 for fs code 4. */
    public int dsdRate() {
        if (fsCode != FS_DSD64) {
            throw new IllegalStateException("SACD sampling frequency code " + fsCode);
        }
        return 2_822_400;
    }
}
