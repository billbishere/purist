package purist.decode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Reads one container format: finds its tracks and tags, and opens a bit-exact {@link Decoder} for one of them. */
public interface FormatHandler {

    /** Lower-case file extensions without the dot, for example {@code "wav"}. */
    List<String> extensions();

    /**
     * Describes every track in the file. A well-formed file that cannot be played bit-perfectly yields
     * {@link TrackInfo#unsupported} with the reason rather than an exception; a damaged file throws
     * {@link DecodeException}.
     */
    List<TrackInfo> probe(Path p) throws IOException;

    /**
     * Opens one track ({@code subsong} is 0 for every format except SACD ISO and CUE sheets).
     *
     * @throws UnsupportedFormatException if the track cannot be played bit-perfectly
     * @throws DecodeException if the file is damaged
     */
    Decoder open(Path p, int subsong) throws IOException;

    /** The embedded front cover, if the file carries one. */
    default Optional<byte[]> embeddedArt(Path p, int subsong) throws IOException {
        return Optional.empty();
    }
}
