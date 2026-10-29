package purist.decode.flac;

import java.io.EOFException;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.UnsupportedFormatException;
import purist.decode.flac.FlacDamageException.Kind;
import purist.decode.io.FileSource;
import purist.tags.TagMap;
import purist.tags.VorbisComments;

/**
 * Everything in front of the audio of a FLAC file (RFC 9639 section 8): STREAMINFO, the seek table, the Vorbis
 * comments and the front cover. Other blocks (padding, application data, cue sheets, block types the RFC
 * reserves) are skipped.
 *
 * <p>A file may start with one or more ID3v2 tags before the {@code fLaC} marker, which many taggers write even
 * though FLAC does not use them; they are stepped over and their content is ignored. Only the first
 * VORBIS_COMMENT and the first SEEKTABLE block count. The front cover is the first picture of type 3, else the
 * first picture; a picture that is a link ({@code -->}), has no data or is cut short is not a cover.
 *
 * @param info             STREAMINFO, checked to have a sample rate and a bit depth the decoder can use
 * @param firstFrameOffset file offset of the first audio frame
 * @param seekPoints       the usable seek points in increasing order: placeholders, points out of order and points
 *                         that lie beyond the audio are dropped. Their offsets are relative to
 *                         {@code firstFrameOffset}.
 * @param tags             from the Vorbis comments; never null
 * @param frontCover       the image bytes, as stored
 */
public record FlacMetadata(FlacStreamInfo info, long firstFrameOffset, List<SeekPoint> seekPoints, TagMap tags,
        Optional<byte[]> frontCover) {

    private static final int TYPE_STREAMINFO = 0;
    private static final int TYPE_SEEKTABLE = 3;
    private static final int TYPE_VORBIS_COMMENT = 4;
    private static final int TYPE_PICTURE = 6;
    private static final int TYPE_FORBIDDEN = 127;
    private static final int STREAMINFO_BYTES = 34;
    private static final int SEEK_POINT_BYTES = 18;
    private static final int ID3_HEADER_BYTES = 10;
    /** More ID3 tags than this in front of the marker is not a FLAC file anyone made on purpose. */
    private static final int MAX_ID3_TAGS = 8;
    private static final int PICTURE_FRONT_COVER = 3;

    public FlacMetadata {
        Objects.requireNonNull(info, "info");
        seekPoints = List.copyOf(seekPoints);
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(frontCover, "frontCover");
    }

    /** What a decoder of this file produces; the length is negative when STREAMINFO does not know it. */
    public StreamFormat format() {
        return StreamFormat.pcm(info.sampleRate(), info.bitsPerSample(), info.channels(),
                info.totalSamples() > 0 ? info.totalSamples() : -1);
    }

    /**
     * Reads the metadata of the file behind {@code src}, from its start. The position of {@code src} afterwards
     * is unspecified.
     *
     * @throws UnsupportedFormatException for Ogg-encapsulated FLAC, which is well formed but not read here
     * @throws DecodeException if the file is not a FLAC file, or its metadata is damaged or cut short
     */
    public static FlacMetadata read(FileSource src) throws IOException {
        try {
            return parse(src);
        } catch (EOFException e) {
            throw new FlacDamageException(Kind.TRUNCATED, "truncated FLAC metadata", e);
        }
    }

    private static FlacMetadata parse(FileSource src) throws IOException {
        long size = src.size();
        long markerAt = skipId3(src, size);
        if (size - markerAt < 4) {
            throw new FlacDamageException(Kind.NOT_FLAC, "not a FLAC file (only " + size + " bytes)");
        }
        src.seek(markerAt);
        String marker = src.fourcc();
        if (marker.equals("OggS")) {
            throw new UnsupportedFormatException("Ogg FLAC is not supported");
        }
        if (!marker.equals("fLaC")) {
            throw new FlacDamageException(Kind.NOT_FLAC, "not a FLAC file (no fLaC marker)");
        }

        FlacStreamInfo info = null;
        byte[] seekTable = null;
        TagMap tags = null;
        byte[] cover = null;
        boolean coverIsFront = false;
        boolean last = false;
        for (int index = 0; !last; index++) {
            long headerAt = src.position();
            int head = src.u8();
            last = (head & 0x80) != 0;
            int type = head & 0x7F;
            int length = src.u16be() << 8 | src.u8();
            if (type == TYPE_FORBIDDEN) {
                throw new DecodeException("invalid FLAC metadata block type 127 at byte " + headerAt);
            }
            if (index == 0 && (type != TYPE_STREAMINFO || length != STREAMINFO_BYTES)) {
                throw new DecodeException("FLAC file does not start with a 34-byte STREAMINFO block");
            }
            long payloadAt = src.position();
            if (length > size - payloadAt) {
                throw new FlacDamageException(Kind.TRUNCATED, "truncated FLAC metadata: the block at byte " + headerAt
                        + " needs " + length + " bytes and the file ends first");
            }
            switch (type) {
                case TYPE_STREAMINFO -> {
                    if (index == 0) {
                        info = streamInfo(src.bytes(length));
                    }
                }
                case TYPE_SEEKTABLE -> {
                    if (seekTable == null) {
                        seekTable = src.bytes(length);
                    }
                }
                case TYPE_VORBIS_COMMENT -> {
                    if (tags == null) {
                        tags = VorbisComments.parse(src.bytes(length));
                    }
                }
                case TYPE_PICTURE -> {
                    Picture p = picture(src.bytes(length));
                    if (p != null && (cover == null || !coverIsFront && p.type() == PICTURE_FRONT_COVER)) {
                        cover = p.data();
                        coverIsFront = p.type() == PICTURE_FRONT_COVER;
                    }
                }
                default -> { }
            }
            src.seek(payloadAt + length);
        }
        long firstFrame = src.position();
        return new FlacMetadata(info, firstFrame, seekPoints(seekTable, info, firstFrame, size),
                tags == null ? new TagMap() : tags, Optional.ofNullable(cover));
    }

    /** The offset just past any ID3v2 tags at the start of the file (0 if there are none). */
    private static long skipId3(FileSource src, long size) throws IOException {
        long pos = 0;
        for (int i = 0; i < MAX_ID3_TAGS && size - pos >= ID3_HEADER_BYTES; i++) {
            src.seek(pos);
            byte[] h = src.bytes(ID3_HEADER_BYTES);
            boolean tag = h[0] == 'I' && h[1] == 'D' && h[2] == '3' && (h[6] & 0x80) == 0 && (h[7] & 0x80) == 0
                    && (h[8] & 0x80) == 0 && (h[9] & 0x80) == 0;
            if (!tag) {
                break;
            }
            long body = (long) h[6] << 21 | (long) h[7] << 14 | (long) h[8] << 7 | h[9];
            boolean footer = h[3] == 4 && (h[5] & 0x10) != 0; // only ID3v2.4 can close a tag with a 10-byte footer
            pos += ID3_HEADER_BYTES + body + (footer ? ID3_HEADER_BYTES : 0);
        }
        return pos;
    }

    private static FlacStreamInfo streamInfo(byte[] b) throws DecodeException {
        BitReader in = new BitReader(b, 0, b.length);
        int minBlock = in.readUInt(16);
        int maxBlock = in.readUInt(16);
        int minFrame = in.readUInt(24);
        int maxFrame = in.readUInt(24);
        int rate = in.readUInt(20);
        int channels = in.readUInt(3) + 1;
        int bits = in.readUInt(5) + 1;
        long total = in.readULong(36);
        if (rate == 0) {
            throw new DecodeException("FLAC STREAMINFO declares a sample rate of 0 Hz");
        }
        if (bits < 4) {
            throw new DecodeException("FLAC STREAMINFO declares " + bits + "-bit samples; FLAC has 4 to 32");
        }
        return new FlacStreamInfo(minBlock, maxBlock, minFrame, maxFrame, rate, channels, bits, total,
                Arrays.copyOfRange(b, 18, 34));
    }

    private static List<SeekPoint> seekPoints(byte[] table, FlacStreamInfo info, long firstFrame, long fileSize) {
        List<SeekPoint> points = new ArrayList<>();
        if (table == null) {
            return points;
        }
        long lastSample = -1;
        long lastOffset = -1;
        for (int i = 0; i + SEEK_POINT_BYTES <= table.length; i += SEEK_POINT_BYTES) {
            long sample = u64be(table, i);
            long offset = u64be(table, i + 8);
            int samples = (table[i + 16] & 0xFF) << 8 | table[i + 17] & 0xFF;
            // A placeholder has sample 0xFFFFFFFFFFFFFFFF; a number with the top bit set is not a position either.
            if (sample < 0 || offset < 0 || sample <= lastSample || offset <= lastOffset) {
                continue;
            }
            if (info.totalSamples() > 0 && sample >= info.totalSamples() || firstFrame + offset >= fileSize) {
                continue; // beyond the audio
            }
            points.add(new SeekPoint(sample, offset, samples));
            lastSample = sample;
            lastOffset = offset;
        }
        return points;
    }

    private record Picture(int type, byte[] data) {}

    /** The picture in a PICTURE block (RFC 9639 section 8.8, big-endian), or null if it is not a usable image. */
    private static Picture picture(byte[] b) {
        ByteBuffer in = ByteBuffer.wrap(b);
        try {
            int type = in.getInt();
            int mimeLength = length(in, in.getInt());
            String mime = new String(b, in.position(), mimeLength, StandardCharsets.US_ASCII);
            in.position(in.position() + mimeLength);
            int descriptionLength = length(in, in.getInt());
            in.position(in.position() + descriptionLength);
            in.position(in.position() + 16); // width, height, colour depth, number of colours
            int dataLength = length(in, in.getInt());
            if (dataLength == 0 || mime.equals("-->")) {
                return null; // no data, or a link to an image elsewhere
            }
            return new Picture(type, Arrays.copyOfRange(b, in.position(), in.position() + dataLength));
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            return null; // cut short, or a length that runs past the block
        }
    }

    /** {@code declared} if that many bytes are left (a length of 2^31 or more reads as negative and never is). */
    private static int length(ByteBuffer in, int declared) {
        if (declared < 0 || declared > in.remaining()) {
            throw new BufferUnderflowException();
        }
        return declared;
    }

    private static long u64be(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = v << 8 | (b[off + i] & 0xFFL);
        }
        return v;
    }
}
