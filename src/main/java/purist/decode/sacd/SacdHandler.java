package purist.decode.sacd;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.tags.TagMap;

/**
 * SACD disc images ({@code .iso}, spec section 7). Each track of the stereo area is a subsong, numbered from 1 as on
 * the disc ({@code file.iso#3} is track 3). Only plain-DSD stereo audio is played: a DST-compressed area makes every
 * track unsupported ("DST-compressed SACD is not supported yet") and a disc without a stereo area is one
 * unsupported entry ("multichannel-only SACD is not supported in v1"). An {@code .iso} that is not an SACD image
 * is likewise listed as unsupported rather than as an error.
 *
 * <p>The tags of a track are its own text (TITLE, ARTIST, ...) plus the disc's ALBUM, ALBUMARTIST and DATE and
 * the TRACKNUMBER.
 */
public final class SacdHandler implements FormatHandler {

    static final String CODEC = "SACD";
    static final String DST_REASON = "DST-compressed SACD is not supported yet";
    static final String MULTICHANNEL_REASON = "multichannel-only SACD is not supported in v1";

    @Override
    public List<String> extensions() {
        return List.of("iso");
    }

    @Override
    public List<TrackInfo> probe(Path p) throws IOException {
        try (SacdImage img = SacdImage.open(p)) {
            Optional<SacdArea> found = img.stereoArea();
            if (found.isEmpty()) {
                return List.of(TrackInfo.unsupported(p, CODEC, MULTICHANNEL_REASON));
            }
            SacdArea area = found.get();
            TagMap album = new TagMap();
            album.add("ALBUM", img.albumTitle());
            album.add("ALBUMARTIST", img.albumArtist());
            img.date().ifPresent(d -> album.add("DATE", d.toString()));

            List<TrackInfo> infos = new ArrayList<>(area.tracks().size());
            for (SacdTrack t : area.tracks()) {
                TagMap tags = new TagMap();
                tags.putAll(t.tags());
                tags.putAllAbsent(album);
                tags.add("TRACKNUMBER", Integer.toString(t.number()));
                if (area.isDst()) {
                    infos.add(new TrackInfo(p, t.number(), CODEC, null, tags, false, DST_REASON));
                } else {
                    StreamFormat format = StreamFormat.dsd(area.dsdRate(), area.channels(),
                            t.lengthFrames() * SacdArea.FRAME_BYTES_PER_CHANNEL, BitOrder.MSB_FIRST);
                    infos.add(new TrackInfo(p, t.number(), CODEC, format, tags, true, null));
                }
            }
            return infos;
        } catch (UnsupportedFormatException e) {
            return List.of(TrackInfo.unsupported(p, CODEC, e.reason()));
        }
    }

    /**
     * @param subsong the track number, from 1
     * @throws UnsupportedFormatException for a DST area, a disc without a stereo area, or a file that is not an
     *         SACD image
     * @throws DecodeException if the track does not exist or the image is damaged
     */
    @Override
    public Decoder open(Path p, int subsong) throws IOException {
        SacdImage img = SacdImage.open(p);
        try {
            SacdArea area = img.stereoArea().orElseThrow(() -> new UnsupportedFormatException(MULTICHANNEL_REASON));
            if (area.isDst()) {
                throw new UnsupportedFormatException(DST_REASON);
            }
            SacdTrack track = area.tracks().stream().filter(t -> t.number() == subsong).findFirst()
                    .orElseThrow(() -> new DecodeException("this SACD has no track " + subsong + " (it has tracks 1 to "
                            + area.tracks().size() + ")"));
            return new SacdDecoder(img, area, track);
        } catch (IOException | RuntimeException e) {
            try {
                img.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
