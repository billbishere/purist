package purist.decode.flac;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.decode.io.FileSource;

/**
 * Native FLAC (spec section 7): a file of 1 to 8 channels and 4 to 32 bits is decoded exactly by
 * {@link FlacDecoder}; whether the output device takes that many channels is the player's business, not the
 * decoder's. Ogg-encapsulated FLAC is refused with a reason.
 *
 * <p>Tags come from the Vorbis comments and the front cover from the PICTURE block ({@link FlacMetadata}).
 */
public final class FlacHandler implements FormatHandler {

    static final String CODEC = "FLAC";

    @Override
    public List<String> extensions() {
        return List.of("flac");
    }

    @Override
    public List<TrackInfo> probe(Path p) throws IOException {
        try (FileSource src = FileSource.open(p)) {
            FlacMetadata m = FlacMetadata.read(src);
            return List.of(new TrackInfo(p, 0, CODEC, m.format(), m.tags(), true, null));
        } catch (UnsupportedFormatException e) {
            return List.of(TrackInfo.unsupported(p, CODEC, e.reason()));
        }
    }

    @Override
    public Decoder open(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            throw new DecodeException("a FLAC file has one track; subsong " + subsong + " does not exist");
        }
        return FlacDecoder.open(p);
    }

    /**
     * The front cover in the file's PICTURE blocks. Empty if there is none, if the file is Ogg FLAC, or if
     * {@code subsong} is not 0 (there is no such track).
     *
     * @throws DecodeException if the file is not a well-formed FLAC file
     */
    @Override
    public Optional<byte[]> embeddedArt(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            return Optional.empty();
        }
        try (FileSource src = FileSource.open(p)) {
            return FlacMetadata.read(src).frontCover();
        } catch (UnsupportedFormatException e) {
            return Optional.empty();
        }
    }
}
