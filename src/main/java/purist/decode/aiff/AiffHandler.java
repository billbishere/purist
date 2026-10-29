package purist.decode.aiff;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.decode.io.FileSource;
import purist.tags.Id3Result;
import purist.tags.Id3v2;
import purist.tags.TagMap;
import purist.tags.TextDecoding;

/**
 * AIFF and AIFF-C (spec section 7). Integer PCM of 1 to 32 bits is decoded exactly, big-endian for plain AIFF and
 * for the AIFF-C types {@code NONE} and {@code twos}, little-endian for {@code sowt}. Every other AIFF-C
 * compression type is refused with a stated reason rather than converted. Both flavours are badged {@code AIFF}.
 *
 * <p>The chunk walk ignores the {@code FORM} size field (writers get it wrong, and tags are often appended after
 * it) and looks at every chunk up to the end of the file, in whatever order they come. The first {@code COMM},
 * {@code SSND}, {@code NAME}, {@code AUTH} and ID3 chunk win. Chunks are padded to an even length.
 *
 * <p>Tags come from {@code NAME} (TITLE), {@code AUTH} (ARTIST) and an {@code ID3 } chunk. Where both have a key,
 * the ID3 one wins and replaces the {@code NAME}/{@code AUTH} value of that key; keys only {@code NAME}/{@code AUTH}
 * has are kept. The front cover, if any, is the ID3 picture ({@link #embeddedArt}).
 */
public final class AiffHandler implements FormatHandler {

    static final String CODEC = "AIFF";

    /** A {@code NAME} or {@code AUTH} chunk larger than this is not read; real ones are a few dozen bytes. */
    private static final long MAX_TEXT_BYTES = 1024 * 1024;
    private static final int PLAIN_COMM_BYTES = 18;
    private static final int AIFC_COMM_BYTES = 22;

    /**
     * The result of parsing an AIFF header, shared by {@link #probe}, {@link #open} and {@link #embeddedArt}.
     *
     * @param format         valid bits, rate and channels; {@code lengthFrames} counts the whole frames present
     * @param containerBytes bytes per sample in the file ({@code ceil(bits / 8)})
     * @param blockAlign     bytes per frame
     * @param littleEndian   true for AIFF-C {@code sowt}
     * @param dataOffset     file offset of the first sample, past the {@code SSND} header and its offset field
     * @param dataLength     bytes of audio, a whole number of frames, clamped to what the file really holds
     * @param tags           from {@code NAME} and {@code AUTH} only (the ID3 chunk is read by {@link #probe});
     *                       never null
     * @param id3Offset      file offset of the payload of the first {@code ID3 } or {@code id3 } chunk (where the
     *                       ID3v2 tag starts), or -1 if there is none
     * @param id3Length      length of that payload in bytes, clamped to the file; 0 if there is none
     */
    record AiffFile(StreamFormat format, int containerBytes, int blockAlign, boolean littleEndian, long dataOffset,
            long dataLength, TagMap tags, long id3Offset, long id3Length) {}

    @Override
    public List<String> extensions() {
        return List.of("aif", "aiff", "aifc");
    }

    @Override
    public List<TrackInfo> probe(Path p) throws IOException {
        try (FileSource src = FileSource.open(p)) {
            AiffFile a = parse(src);
            TagMap tags = new TagMap();
            tags.putAll(a.tags());
            readId3(src, a).ifPresent(id3 -> tags.putAll(id3.tags())); // ID3 wins, key by key
            return List.of(new TrackInfo(p, 0, CODEC, a.format(), tags, true, null));
        } catch (UnsupportedFormatException e) {
            return List.of(TrackInfo.unsupported(p, CODEC, e.reason()));
        }
    }

    /**
     * The front cover in the file's ID3 tag. Empty if there is no picture, if the file is a well-formed AIFF that
     * cannot be played bit-perfectly, or if {@code subsong} is not 0 (there is no such track).
     *
     * @throws DecodeException if the file is not a well-formed AIFF
     */
    @Override
    public Optional<byte[]> embeddedArt(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            return Optional.empty();
        }
        try (FileSource src = FileSource.open(p)) {
            return readId3(src, parse(src)).flatMap(Id3Result::frontCover);
        } catch (UnsupportedFormatException e) {
            return Optional.empty();
        }
    }

    /** The ID3 tag of the file's ID3 chunk, bounded by that chunk; empty if there is no such chunk or tag. */
    private static Optional<Id3Result> readId3(FileSource src, AiffFile a) throws IOException {
        if (a.id3Offset() < 0) {
            return Optional.empty();
        }
        return Id3v2.readAt(src, a.id3Offset(), a.id3Length());
    }

    @Override
    public Decoder open(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            throw new DecodeException("an AIFF file has one track; subsong " + subsong + " does not exist");
        }
        FileSource src = FileSource.open(p);
        try {
            return new AiffDecoder(src, parse(src));
        } catch (IOException | RuntimeException e) {
            try {
                src.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Parses the header and chunk list of the file behind {@code src}.
     *
     * @throws UnsupportedFormatException for compressed AIFF-C and samples wider than 32 bits (the exception
     *         carries the reason)
     * @throws DecodeException for a file that is not a well-formed AIFF
     */
    static AiffFile parse(FileSource src) throws IOException {
        try {
            return parseChunks(src);
        } catch (EOFException e) {
            throw new DecodeException("truncated AIFF header", e);
        }
    }

    private static AiffFile parseChunks(FileSource src) throws IOException {
        long size = src.size();
        if (size < 12) {
            throw new DecodeException("not an AIFF file (only " + size + " bytes)");
        }
        src.seek(0);
        if (!src.fourcc().equals("FORM")) {
            throw new DecodeException("not an AIFF file (no FORM header)");
        }
        src.u32be(); // the FORM size is not trusted: see the class comment
        String type = src.fourcc();
        boolean aifc;
        if (type.equals("AIFF")) {
            aifc = false;
        } else if (type.equals("AIFC")) {
            aifc = true;
        } else {
            throw new DecodeException("not an AIFF file (FORM type is not AIFF or AIFC)");
        }

        Comm comm = null;
        boolean haveSsnd = false;
        long dataOffset = 0;
        long dataBytes = 0;
        long id3Offset = -1;
        long id3Length = 0;
        TagMap tags = new TagMap();

        long pos = 12;
        while (pos + 8 <= size) {
            src.seek(pos);
            String id = src.fourcc();
            long sz = src.u32be();
            long payload = pos + 8;
            long avail = size - payload;
            switch (id) {
                case "COMM" -> {
                    if (comm == null) {
                        comm = readComm(src, sz, avail, aifc);
                    }
                }
                case "SSND" -> {
                    if (!haveSsnd) {
                        haveSsnd = true;
                        if (sz < 8 || avail < 8) {
                            throw new DecodeException("AIFF SSND chunk is truncated");
                        }
                        long offset = src.u32be();
                        src.u32be(); // block size: only a hint for aligned reads, the samples do not move
                        long held = Math.min(sz, avail) - 8;
                        if (offset > held) {
                            throw new DecodeException("AIFF SSND offset " + offset + " lies beyond its chunk");
                        }
                        dataOffset = payload + 8 + offset;
                        dataBytes = held - offset;
                    }
                }
                case "NAME" -> addText(src, tags, "TITLE", sz, avail);
                case "AUTH" -> addText(src, tags, "ARTIST", sz, avail);
                case "ID3 ", "id3 " -> {
                    if (id3Offset < 0) {
                        id3Offset = payload;
                        id3Length = Math.min(sz, avail);
                    }
                }
                default -> { }
            }
            if (sz > avail) {
                break; // this chunk runs past the end of the file, so nothing follows it
            }
            pos = payload + sz + (sz & 1); // chunks are padded to an even length
        }

        if (comm == null) {
            throw new DecodeException("AIFF file has no COMM chunk");
        }
        if (!haveSsnd && comm.frames > 0) {
            throw new DecodeException("AIFF file has no SSND chunk");
        }
        // The frame count in COMM is an upper bound; the sound data may hold fewer (a cut file) or more.
        long frames = Math.min(comm.frames, dataBytes / comm.blockAlign);
        StreamFormat format = StreamFormat.pcm(comm.rate, comm.bits, comm.channels, frames);
        return new AiffFile(format, comm.containerBytes, comm.blockAlign, comm.littleEndian, dataOffset,
                frames * comm.blockAlign, tags, id3Offset, id3Length);
    }

    /** The fields of a validated, integer-PCM {@code COMM} chunk. */
    private record Comm(int rate, int channels, int bits, int containerBytes, int blockAlign, long frames,
            boolean littleEndian) {}

    private static Comm readComm(FileSource src, long sz, long avail, boolean aifc) throws IOException {
        int needed = aifc ? AIFC_COMM_BYTES : PLAIN_COMM_BYTES;
        if (sz < needed || avail < needed) {
            throw new DecodeException(aifc ? "AIFF-C COMM chunk is truncated" : "AIFF COMM chunk is truncated");
        }
        int channels = (short) src.u16be(); // a signed 16-bit field
        long frames = src.u32be();
        int bits = (short) src.u16be();
        byte[] extended = src.bytes(10);
        boolean littleEndian = false;
        if (aifc) {
            String compression = src.fourcc();
            switch (compression) {
                case "NONE", "twos" -> littleEndian = false;
                case "sowt" -> littleEndian = true;
                default -> throw new UnsupportedFormatException(
                        String.format("compressed AIFF-C (%s)", compression));
            }
        }

        if (channels < 1) {
            throw new DecodeException("AIFF declares no channels");
        }
        double rate = Extended80.toDouble(extended);
        if (!(rate >= 1 && rate <= Integer.MAX_VALUE) || rate != Math.rint(rate)) {
            throw new DecodeException("AIFF sample rate " + rate + " Hz is not a whole number in range");
        }
        if (bits < 1) {
            throw new DecodeException("AIFF declares " + bits + " bits per sample");
        }
        if (bits > 32) {
            throw new UnsupportedFormatException("AIFF with " + bits + "-bit samples is not supported");
        }
        int container = (bits + 7) / 8;
        return new Comm((int) rate, channels, bits, container, channels * container, frames, littleEndian);
    }

    /** Adds the text of a {@code NAME}/{@code AUTH} chunk under {@code key}, unless the key already has one. */
    private static void addText(FileSource src, TagMap tags, String key, long sz, long avail) throws IOException {
        if (tags.first(key).isPresent() || sz > avail || sz > MAX_TEXT_BYTES) {
            return;
        }
        byte[] b = src.bytes((int) sz);
        tags.add(key, TextDecoding.decode(b, 0, b.length));
    }
}
