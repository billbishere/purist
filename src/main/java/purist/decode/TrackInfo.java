package purist.decode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import purist.audio.Kind;
import purist.audio.StreamFormat;
import purist.tags.TagMap;

/**
 * Everything the library knows about one playable unit of a file: a file for most formats, one track of an SACD
 * ISO ({@code subsong}). Unsupported and cloud-only files have no {@code format}: it is read from the file, and
 * the file either cannot be played ({@link #unsupported}) or is not on disk yet ({@link #cloudOnly}).
 *
 * @param codec     short format name shown in the badge, such as {@code WAV}, {@code FLAC} or {@code DSF}
 * @param format    what the decoder will produce; null when {@code supported} is false or the file is cloud-only
 * @param tags      never null
 * @param supported false for a file that is shown greyed out and never played
 * @param reason    why an unsupported file is skipped; null when supported
 */
public record TrackInfo(Path path, int subsong, String codec, StreamFormat format, TagMap tags,
        boolean supported, String reason) {

    public TrackInfo {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(codec, "codec");
        if (tags == null) {
            tags = new TagMap();
        }
    }

    /** A file that is recognised but will not be played, with the stated reason. It has no format or tags. */
    public static TrackInfo unsupported(Path p, String codec, String reason) {
        return new TrackInfo(p, 0, codec, null, new TagMap(), false, reason);
    }

    /**
     * A file whose content is still in the cloud (OneDrive and friends). It is playable once downloaded, so it
     * is supported, but nothing is read from it until then: no format and no tags.
     */
    public static TrackInfo cloudOnly(Path p, String codec) {
        return new TrackInfo(p, 0, codec, null, new TagMap(), true, null);
    }

    /** TITLE, else the file name without its extension. */
    public String displayTitle() {
        Optional<String> title = tags.first("TITLE");
        if (title.isPresent()) {
            return title.get();
        }
        Path name = path.getFileName();
        String s = name == null ? path.toString() : name.toString();
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    /** ARTIST, else ALBUMARTIST, else the empty string. */
    public String displayArtist() {
        return tags.first("ARTIST").orElseGet(() -> tags.first("ALBUMARTIST").orElse(""));
    }

    /** ALBUM, else the name of the folder holding the file (empty if it has none). */
    public String displayAlbum() {
        Optional<String> album = tags.first("ALBUM");
        if (album.isPresent()) {
            return album.get();
        }
        Path parent = path.getParent();
        Path folder = parent == null ? null : parent.getFileName();
        return folder == null ? "" : folder.toString();
    }

    /** ALBUMARTIST, else ARTIST, else {@code "Unknown artist"}; what the album browser groups by. */
    public String albumArtist() {
        return tags.first("ALBUMARTIST").orElseGet(() -> tags.first("ARTIST").orElse("Unknown artist"));
    }

    /** {@code "FLAC 24/96"} for PCM, {@code "DSD128"} for DSD, and just the codec when there is no format. */
    public String badge() {
        if (format == null) {
            return codec;
        }
        return format.kind() == Kind.PCM ? codec + " " + format.label() : format.label();
    }
}
