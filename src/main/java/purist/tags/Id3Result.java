package purist.tags;

import java.util.Optional;

/**
 * What {@link Id3v2} found in one tag.
 *
 * @param tags       the text frames, mapped to Purist's keys; never null, and empty for a tag that has none or is
 *                   damaged beyond use
 * @param frontCover the image bytes of the front-cover picture (type 3), else of the first picture; empty if the
 *                   tag has no usable picture. The bytes are the file's own (JPEG or PNG), not decoded.
 */
public record Id3Result(TagMap tags, Optional<byte[]> frontCover) {

    public Id3Result {
        if (tags == null) {
            tags = new TagMap();
        }
        if (frontCover == null) {
            frontCover = Optional.empty();
        }
    }

    /** A result with no tags and no picture. Each call returns a new object because {@link TagMap} is mutable. */
    public static Id3Result empty() {
        return new Id3Result(new TagMap(), Optional.empty());
    }
}
