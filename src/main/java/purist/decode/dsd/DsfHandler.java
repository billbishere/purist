package purist.decode.dsd;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import purist.audio.BitOrder;
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

/**
 * DSF, the Sony DSD stream file (spec section 7). Little-endian {@code "DSD "}, {@code "fmt "} and {@code "data"}
 * chunks; the audio is in blocks of {@code blockSizePerChannel} bytes (4096), channel 0's block, then channel 1's,
 * and so on, with the last group zero-padded. Bits per sample says the bit order inside each byte: 1 is LSB-first,
 * 8 is MSB-first. The decoder hands the bytes on exactly as stored, one byte per channel per frame, and says which
 * order they are in; any bit reversal is the packer's business.
 *
 * <p>Stereo and mono only: a DSF with more than two channels is refused ("multichannel DSF"), as is any format id
 * other than 0 (raw DSD). The ID3v2 tag, if any, is at the metadata pointer of the {@code "DSD "} chunk.
 *
 * <p>A file whose data chunk is shorter than its sample count says is clamped to the whole groups that are
 * present, the way a truncated WAV is.
 */
public final class DsfHandler implements FormatHandler {

    static final String CODEC = "DSF";

    /** The largest block size accepted; real files use 4096. */
    private static final int MAX_BLOCK_SIZE = 1 << 20;
    private static final int HEADER_BYTES = 12;
    private static final long DSD_CHUNK_BYTES = 28;
    private static final long FMT_CHUNK_BYTES = 52;

    /**
     * The result of parsing a DSF header.
     *
     * @param format     DSD, with {@code lengthFrames} = bytes per channel actually present (at most the declared)
     * @param blockSize  bytes per channel in one block
     * @param dataOffset file offset of the first block
     * @param id3Offset  file offset of the ID3v2 tag, or -1 if there is none
     * @param id3Length  bytes from there to the end of the file; 0 if there is none
     */
    record DsfFile(StreamFormat format, int blockSize, long dataOffset, long id3Offset, long id3Length) {}

    @Override
    public List<String> extensions() {
        return List.of("dsf");
    }

    @Override
    public List<TrackInfo> probe(Path p) throws IOException {
        try (FileSource src = FileSource.open(p)) {
            DsfFile f = parse(src);
            TagMap tags = new TagMap();
            readId3(src, f).ifPresent(id3 -> tags.putAll(id3.tags()));
            return List.of(new TrackInfo(p, 0, CODEC, f.format(), tags, true, null));
        } catch (UnsupportedFormatException e) {
            return List.of(TrackInfo.unsupported(p, CODEC, e.reason()));
        }
    }

    /** The front cover of the file's ID3 tag; empty if none, or if {@code subsong} is not 0. */
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

    private static Optional<Id3Result> readId3(FileSource src, DsfFile f) throws IOException {
        if (f.id3Offset() < 0) {
            return Optional.empty();
        }
        return Id3v2.readAt(src, f.id3Offset(), f.id3Length());
    }

    @Override
    public Decoder open(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            throw new DecodeException("a DSF file has one track; subsong " + subsong + " does not exist");
        }
        FileSource src = FileSource.open(p);
        try {
            return new DsfDecoder(src, parse(src));
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
     * @throws UnsupportedFormatException for a multichannel DSF, an unknown format id or version
     * @throws DecodeException for a file that is not a well-formed DSF
     */
    static DsfFile parse(FileSource src) throws IOException {
        try {
            return parseChunks(src);
        } catch (EOFException e) {
            throw new DecodeException("truncated DSF header", e);
        }
    }

    private static DsfFile parseChunks(FileSource src) throws IOException {
        long size = src.size();
        src.seek(0);
        if (!src.fourcc().equals("DSD ")) {
            throw new DecodeException("not a DSF file (no \"DSD \" chunk)");
        }
        long dsdChunkSize = src.u64le();
        src.u64le(); // total file size: writers get it wrong, and the real size is known
        long metadataPointer = src.u64le();
        if (dsdChunkSize != DSD_CHUNK_BYTES) {
            throw new DecodeException("DSF \"DSD \" chunk has size " + dsdChunkSize + ", not 28");
        }

        StreamFormat format = null;
        int blockSize = 0;
        int channels = 0;
        long declaredFrames = 0;
        long pos = DSD_CHUNK_BYTES;
        long dataOffset = -1;
        long dataBytes = 0;
        while (pos + HEADER_BYTES <= size) {
            src.seek(pos);
            String id = src.fourcc();
            long chunkSize = src.u64le(); // includes the 12-byte chunk header
            if (chunkSize < HEADER_BYTES) {
                throw new DecodeException("DSF chunk \"" + id + "\" has an impossible size " + chunkSize);
            }
            if (id.equals("fmt ")) {
                if (format != null) {
                    throw new DecodeException("DSF has two fmt chunks");
                }
                if (chunkSize < FMT_CHUNK_BYTES) {
                    throw new DecodeException("DSF fmt chunk is too small (" + chunkSize + " bytes)");
                }
                long version = src.u32le();
                long formatId = src.u32le();
                src.u32le(); // channel type: the channel count says all we need
                long channelNum = src.u32le();
                long rate = src.u32le();
                long bitsPerSample = src.u32le();
                long sampleCount = src.u64le();
                long block = src.u32le();

                if (version != 1) {
                    throw new UnsupportedFormatException("unknown DSF version " + version);
                }
                if (formatId != 0) {
                    throw new UnsupportedFormatException("unknown DSF format id " + formatId);
                }
                if (channelNum > 2) {
                    throw new UnsupportedFormatException(String.format("multichannel DSF (%d ch)", channelNum));
                }
                if (channelNum < 1) {
                    throw new DecodeException("DSF declares no channels");
                }
                if (rate < 1 || rate > Integer.MAX_VALUE) {
                    throw new DecodeException("DSF sampling frequency " + rate + " Hz is out of range");
                }
                if (bitsPerSample != 1 && bitsPerSample != 8) {
                    throw new DecodeException("DSF bits per sample is " + bitsPerSample + ", not 1 or 8");
                }
                if (block < 1 || block > MAX_BLOCK_SIZE) {
                    throw new DecodeException("DSF block size " + block + " is out of range");
                }
                if (sampleCount < 0) {
                    throw new DecodeException("DSF sample count is out of range");
                }
                channels = (int) channelNum;
                blockSize = (int) block;
                declaredFrames = sampleCount / 8 + (sampleCount % 8 == 0 ? 0 : 1);
                format = StreamFormat.dsd((int) rate, channels, declaredFrames,
                        bitsPerSample == 1 ? BitOrder.LSB_FIRST : BitOrder.MSB_FIRST);
            } else if (id.equals("data")) {
                if (format == null) {
                    throw new DecodeException("DSF data chunk comes before the fmt chunk");
                }
                dataOffset = pos + HEADER_BYTES;
                dataBytes = Math.min(chunkSize - HEADER_BYTES, Math.max(size - dataOffset, 0));
                break;
            }
            pos += chunkSize;
            if (pos < 0) {
                throw new DecodeException("DSF chunk \"" + id + "\" runs past the end of any file");
            }
        }
        if (format == null) {
            throw new DecodeException("DSF file has no fmt chunk");
        }
        if (dataOffset < 0) {
            throw new DecodeException("DSF file has no data chunk");
        }

        long groupBytes = (long) blockSize * channels;
        long groupsNeeded = (declaredFrames + blockSize - 1) / blockSize;
        long frames = declaredFrames;
        if (dataBytes < groupsNeeded * groupBytes) {
            frames = Math.min(declaredFrames, dataBytes / groupBytes * blockSize);
        }
        StreamFormat actual = StreamFormat.dsd(format.sampleRate(), channels, frames, format.dsdOrder());

        long id3Offset = -1;
        long id3Length = 0;
        if (metadataPointer > 0 && metadataPointer < size) {
            id3Offset = metadataPointer;
            id3Length = size - metadataPointer;
        }
        return new DsfFile(actual, blockSize, dataOffset, id3Offset, id3Length);
    }
}
