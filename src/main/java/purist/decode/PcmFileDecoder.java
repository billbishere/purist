package purist.decode;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import purist.audio.StreamFormat;
import purist.decode.io.FileSource;

/**
 * The shared half of a {@link Decoder} for uncompressed PCM that sits in one contiguous run of whole frames in a
 * file (WAV, AIFF). It owns everything that is the same for such formats: the closed-state checks, the check that
 * the destination is big enough, the loop that reads the file in chunks, the clamp to the end of the data, exact
 * {@code seek}, {@code position} and the idempotent {@code close}. A subclass supplies only {@link #convert},
 * which turns the file's sample bytes into the decoder's output.
 *
 * <p>{@link #read} reuses one byte buffer sized at construction and allocates nothing.
 */
public abstract class PcmFileDecoder implements Decoder {

    /** The layout of one output sample: little-endian {@code int32}, which {@link #convert} writes. */
    protected static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final int CHUNK_BYTES = 64 * 1024;

    private final FileSource src;
    private final StreamFormat format;
    private final int channels;
    private final int blockAlign;
    private final long dataOffset;
    private final long totalFrames;
    private final int chunkFrames;
    private final byte[] chunk;

    private long position;
    private boolean closed;

    /**
     * Positions {@code src} at the start of the audio.
     *
     * @param src        the open file; this decoder closes it
     * @param format     what {@link #read} produces; its {@code lengthFrames} is the number of whole frames present
     * @param blockAlign bytes per frame in the file, at least 1
     * @param dataOffset file offset of the first frame
     */
    protected PcmFileDecoder(FileSource src, StreamFormat format, int blockAlign, long dataOffset) {
        this.src = src;
        this.format = format;
        this.channels = format.channels();
        this.blockAlign = blockAlign;
        this.dataOffset = dataOffset;
        this.totalFrames = format.lengthFrames();
        this.chunkFrames = Math.max(1, CHUNK_BYTES / blockAlign);
        this.chunk = new byte[chunkFrames * blockAlign];
        src.seek(dataOffset);
    }

    /**
     * Converts {@code frames} whole frames to output samples. Called from {@link #read} for each chunk read from
     * the file; it must not allocate.
     *
     * @param chunk     the file bytes of the frames, from index 0 ({@code frames * blockAlign} bytes are valid)
     * @param frames    the number of frames in {@code chunk}
     * @param dst       the destination; write {@code frames * channels} samples with {@link #LE_INT}
     * @param dstOffset byte offset in {@code dst} of the first sample to write
     */
    protected abstract void convert(byte[] chunk, int frames, MemorySegment dst, long dstOffset);

    @Override
    public final StreamFormat format() {
        return format;
    }

    @Override
    public final int read(MemorySegment dst, int maxFrames) throws IOException {
        if (closed) {
            throw new IOException("decoder is closed");
        }
        if (maxFrames < 0) {
            throw new IllegalArgumentException("negative frame count: " + maxFrames);
        }
        long needed = (long) maxFrames * channels * 4;
        if (dst.byteSize() < needed) {
            throw new IllegalArgumentException(
                    "destination holds " + dst.byteSize() + " bytes but " + maxFrames + " frames need " + needed);
        }
        int want = (int) Math.min(maxFrames, totalFrames - position);
        int done = 0;
        while (done < want) {
            int n = Math.min(want - done, chunkFrames);
            int got = src.read(chunk, 0, n * blockAlign);
            int frames = got <= 0 ? 0 : got / blockAlign;
            if (frames > 0) {
                convert(chunk, frames, dst, (long) done * channels * 4);
                done += frames;
            }
            if (frames < n) {
                break; // the file is shorter than its header said; stop where the data stops
            }
        }
        position += done;
        return done;
    }

    @Override
    public final void seek(long frame) throws IOException {
        if (closed) {
            throw new IOException("decoder is closed");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("negative frame: " + frame);
        }
        long target = Math.min(frame, totalFrames);
        src.seek(dataOffset + target * blockAlign);
        position = target;
    }

    @Override
    public final long position() {
        return position;
    }

    @Override
    public final void close() throws IOException {
        if (!closed) {
            closed = true;
            src.close();
        }
    }
}
