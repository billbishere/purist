package purist.library;

import purist.audio.StreamFormat;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.tags.TagMap;

/**
 * One row of the library: a playable unit of a file as the database holds it.
 *
 * @param id        database id; changes whenever the file is re-read, so never keep it across a rescan
 * @param ref       the file and subsong
 * @param codec     short format name, such as {@code FLAC}
 * @param format    null for a cloud-only file (probed at play time) and for an unsupported or damaged file. A DSD
 *                  format carries {@code MSB_FIRST} as its bit order because the database does not store one; only
 *                  the decoder, opened from the file, knows the real order, so playback must not use it
 * @param supported false for a file shown greyed out
 * @param error     why a file is greyed out (unsupported format or a parse failure); null otherwise
 * @param tags      never null
 */
public record TrackRow(long id, TrackRef ref, String codec, StreamFormat format, boolean supported, String error,
        TagMap tags) {

    /** The same data as the {@link TrackInfo} the decoders produce. */
    public TrackInfo toInfo() {
        return new TrackInfo(ref.path(), ref.subsong(), codec, format, tags, supported, error);
    }
}
