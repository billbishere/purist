package purist.decode.wav;

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
import purist.tags.RiffInfo;
import purist.tags.TagMap;

/**
 * WAV and RF64 (spec section 7). Integer PCM of 8 to 32 bits is decoded exactly; IEEE float and every
 * compressed format is refused with a stated reason rather than converted.
 *
 * <p>The chunk walk ignores the RIFF size field (writers get it wrong, and tags are often appended after it) and
 * looks at every chunk up to the end of the file. The first {@code fmt }, {@code data} and ID3 chunk win.
 *
 * <p>Tags come from {@code LIST/INFO} and from an {@code id3 }/{@code ID3 } chunk. Where both have a key, the ID3
 * one wins and replaces all the INFO values of that key; keys only INFO has are kept. The front cover, if any,
 * is the ID3 picture ({@link #embeddedArt}).
 */
public final class WavHandler implements FormatHandler {

    static final String CODEC = "WAV";
    static final String FLOAT_REFUSAL = "32-bit float WAV is not bit-perfect to an integer DAC";

    private static final long UNSIGNED_32_MAX = 0xFFFFFFFFL;
    /** A LIST/INFO chunk larger than this is not read; real ones are a few hundred bytes. */
    private static final long MAX_LIST_BYTES = 4L * 1024 * 1024;
    private static final int WAVE_FORMAT_PCM = 1;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

    /**
     * The result of parsing a WAV header, shared by {@link #probe}, {@link #open} and {@link #embeddedArt}.
     *
     * @param format         valid bits, rate and channels; {@code lengthFrames} counts the whole frames present
     * @param containerBytes bytes per sample in the file ({@code blockAlign / channels})
     * @param blockAlign     bytes per frame
     * @param dataOffset     file offset of the first sample
     * @param dataLength     bytes of audio, a whole number of frames, clamped to what the file really holds
     * @param tags           from {@code LIST/INFO} only (the ID3 chunk is read by {@link #probe}); never null
     * @param id3Offset      file offset of the payload of the first {@code id3 } or {@code ID3 } chunk (where the
     *                       ID3v2 tag starts), or -1 if there is none
     * @param id3Length      length of that payload in bytes, clamped to the file; 0 if there is none
     */
    record WavFile(StreamFormat format, int containerBytes, int blockAlign, long dataOffset, long dataLength,
            TagMap tags, long id3Offset, long id3Length) {}

    @Override
    public List<String> extensions() {
        return List.of("wav");
    }

    @Override
    public List<TrackInfo> probe(Path p) throws IOException {
        try (FileSource src = FileSource.open(p)) {
            WavFile w = parse(src);
            TagMap tags = new TagMap();
            tags.putAll(w.tags());
            readId3(src, w).ifPresent(id3 -> tags.putAll(id3.tags())); // ID3 wins, key by key
            return List.of(new TrackInfo(p, 0, CODEC, w.format(), tags, true, null));
        } catch (UnsupportedFormatException e) {
            return List.of(TrackInfo.unsupported(p, CODEC, e.reason()));
        }
    }

    /**
     * The front cover in the file's ID3 tag. Empty if there is no picture, if the file is a well-formed WAV that
     * cannot be played bit-perfectly, or if {@code subsong} is not 0 (there is no such track).
     *
     * @throws DecodeException if the file is not a well-formed WAV
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
    private static Optional<Id3Result> readId3(FileSource src, WavFile w) throws IOException {
        if (w.id3Offset() < 0) {
            return Optional.empty();
        }
        return Id3v2.readAt(src, w.id3Offset(), w.id3Length());
    }

    @Override
    public Decoder open(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            throw new DecodeException("a WAV file has one track; subsong " + subsong + " does not exist");
        }
        FileSource src = FileSource.open(p);
        try {
            return new WavDecoder(src, parse(src));
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
     * @throws UnsupportedFormatException for float and compressed WAV (the exception carries the reason)
     * @throws DecodeException for a file that is not a well-formed WAV
     */
    static WavFile parse(FileSource src) throws IOException {
        try {
            return parseChunks(src);
        } catch (EOFException e) {
            throw new DecodeException("truncated WAV header", e);
        }
    }

    private static WavFile parseChunks(FileSource src) throws IOException {
        long size = src.size();
        if (size < 12) {
            throw new DecodeException("not a WAV file (only " + size + " bytes)");
        }
        src.seek(0);
        String magic = src.fourcc();
        boolean rf64;
        if (magic.equals("RIFF")) {
            rf64 = false;
        } else if (magic.equals("RF64")) {
            rf64 = true;
        } else {
            throw new DecodeException("not a WAV file (no RIFF header)");
        }
        src.u32le(); // the RIFF size is not trusted: see the class comment
        if (!src.fourcc().equals("WAVE")) {
            throw new DecodeException("not a WAV file (RIFF type is not WAVE)");
        }

        Fmt fmt = null;
        boolean haveData = false;
        long dataOffset = 0;
        long dataSize = 0;
        long ds64DataSize = -1;
        long id3Offset = -1;
        long id3Length = 0;
        TagMap tags = new TagMap();

        long pos = 12;
        while (pos + 8 <= size) {
            src.seek(pos);
            String id = src.fourcc();
            long sz = src.u32le();
            long payload = pos + 8;
            long avail = size - payload;
            switch (id) {
                case "ds64" -> {
                    if (rf64 && ds64DataSize < 0 && sz >= 16 && avail >= 16) {
                        src.u64le(); // RIFF size
                        long declared = src.u64le();
                        if (declared >= 0) { // a size of 2^63 or more is garbage: ignore it
                            ds64DataSize = declared;
                        }
                    }
                }
                case "fmt " -> {
                    if (fmt == null) {
                        fmt = readFmt(src, sz, avail);
                    }
                }
                case "data" -> {
                    if (!haveData) {
                        haveData = true;
                        dataOffset = payload;
                        dataSize = rf64 && sz == UNSIGNED_32_MAX && ds64DataSize >= 0 ? ds64DataSize : sz;
                        sz = dataSize; // where the next chunk starts
                    }
                }
                case "LIST" -> {
                    if (sz >= 4 && sz <= avail && sz <= MAX_LIST_BYTES) {
                        byte[] body = src.bytes((int) sz);
                        if (body[0] == 'I' && body[1] == 'N' && body[2] == 'F' && body[3] == 'O') {
                            tags.putAllAbsent(RiffInfo.parse(body));
                        }
                    }
                }
                case "id3 ", "ID3 " -> {
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

        if (fmt == null) {
            throw new DecodeException("WAV file has no fmt chunk");
        }
        if (!haveData) {
            throw new DecodeException("WAV file has no data chunk");
        }
        long present = size - dataOffset;
        long bytes = Math.min(dataSize, present);
        bytes -= bytes % fmt.blockAlign;
        long frames = bytes / fmt.blockAlign;
        StreamFormat format = StreamFormat.pcm(fmt.rate, fmt.validBits, fmt.channels, frames);
        return new WavFile(format, fmt.containerBytes, fmt.blockAlign, dataOffset, bytes, tags, id3Offset,
                id3Length);
    }

    /** The fields of a validated integer-PCM {@code fmt } chunk. */
    private record Fmt(int rate, int channels, int validBits, int containerBytes, int blockAlign) {}

    private static Fmt readFmt(FileSource src, long sz, long avail) throws IOException {
        if (sz < 16 || avail < 16) {
            throw new DecodeException("WAV fmt chunk is truncated");
        }
        int tag = src.u16le();
        int channels = src.u16le();
        long rate = src.u32le();
        src.u32le(); // average bytes per second: redundant
        int blockAlign = src.u16le();
        int bitsPerSample = src.u16le();
        int validBits = bitsPerSample;
        int format = tag;
        if (tag == WAVE_FORMAT_EXTENSIBLE) {
            if (sz < 40 || avail < 40) {
                throw new DecodeException("WAV extensible fmt chunk is truncated");
            }
            src.u16le(); // size of the extension
            int declaredValid = src.u16le();
            if (declaredValid != 0) {
                validBits = declaredValid;
            }
            src.u32le(); // channel mask: the order of channels is not our concern
            format = subFormat(src.bytes(16));
        }

        if (format == WAVE_FORMAT_IEEE_FLOAT) {
            throw new UnsupportedFormatException(FLOAT_REFUSAL);
        }
        if (format != WAVE_FORMAT_PCM) {
            throw new UnsupportedFormatException(String.format("compressed WAV (format 0x%04X)", format));
        }
        if (channels < 1) {
            throw new DecodeException("WAV declares no channels");
        }
        if (rate < 1 || rate > Integer.MAX_VALUE) {
            throw new DecodeException("WAV sample rate " + rate + " Hz is out of range");
        }
        if (blockAlign < 1 || blockAlign % channels != 0) {
            throw new DecodeException(
                    "WAV block alignment " + blockAlign + " does not fit " + channels + " channels");
        }
        int container = blockAlign / channels;
        if (container > 4) {
            throw new UnsupportedFormatException("WAV with " + container * 8 + "-bit samples is not supported");
        }
        if (validBits < 1 || validBits > container * 8) {
            throw new DecodeException(
                    "WAV declares " + validBits + " valid bits in a " + container * 8 + "-bit container");
        }
        return new Fmt((int) rate, channels, validBits, container, blockAlign);
    }

    /**
     * The format code of a WAVE_FORMAT_EXTENSIBLE sub-format GUID. A GUID built on the standard base
     * ({@code xxxxxxxx-0000-0010-8000-00AA00389B71}) carries the format code in its first two bytes; any other
     * GUID is reported as the extensible code itself.
     */
    private static int subFormat(byte[] guid) {
        byte[] tail = {0x00, 0x00, 0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38,
                (byte) 0x9B, 0x71};
        for (int i = 0; i < tail.length; i++) {
            if (guid[2 + i] != tail[i]) {
                return WAVE_FORMAT_EXTENSIBLE;
            }
        }
        return (guid[0] & 0xFF) | (guid[1] & 0xFF) << 8;
    }
}
