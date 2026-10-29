package purist.decode.sacd;

import purist.tags.TagMap;

/**
 * One track of an SACD area, from the area's {@code SACDTRL1}, {@code SACDTRL2} and {@code SACDTTxt} sectors.
 *
 * @param number          1-based track number
 * @param startLsn        the sector in which the track's first frame begins (absolute; sectors are shared with the
 *                        neighbouring tracks, so this is where a frame begins, not where a frame ends)
 * @param lengthSectors   sectors from {@code startLsn} up to and including the one holding the track's last byte
 * @param startTimeFrames the time code of the first frame, in 1/75 s frames from the start of the track area
 *                        (track 1 normally starts at 150, after a 2 s lead-in)
 * @param lengthFrames    the track's length in frames
 * @param tags            the track text (TITLE, ARTIST and so on); empty if the track has none
 */
public record SacdTrack(int number, long startLsn, long lengthSectors, long startTimeFrames, long lengthFrames,
        TagMap tags) {
}
