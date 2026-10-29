package purist.decode;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import purist.audio.StreamFormat;

/**
 * Turns one track of a file into the exact samples it holds. A decoder never resamples, dithers, scales or
 * converts: PCM comes out as interleaved little-endian {@code int32}, each sample value right-aligned and
 * sign-extended to {@link StreamFormat#bits()} (8-bit unsigned sources are re-centred by subtracting 128); DSD
 * comes out as one byte per channel per frame, in {@link StreamFormat#dsdOrder()}.
 *
 * <p>A decoder is used by one thread at a time and is not thread-safe. {@link #read} runs in the player's hot
 * loop and must not allocate.
 */
public interface Decoder extends AutoCloseable {

    /** What {@link #read} produces. Constant for the life of the decoder. */
    StreamFormat format();

    /**
     * Decodes up to {@code maxFrames} frames into {@code dst} starting at offset 0, writing
     * {@code frames * format().bytesPerDecodedFrame()} bytes, and returns the number of frames decoded. The
     * result is less than {@code maxFrames} only at the end of the stream, or when the stream is damaged: then
     * {@code read} returns the good frames before the damage (possibly fewer than {@code maxFrames}, and not the end)
     * and the next call throws {@link IOException}, and keeps throwing it until {@link #seek}. 0 always means a clean
     * end of the stream (and stays 0).
     *
     * @throws IllegalArgumentException if {@code maxFrames} is negative or {@code dst} is too small for
     *         {@code maxFrames} frames (checked before anything is written)
     * @throws IOException if the file cannot be read or the decoder is closed
     */
    int read(MemorySegment dst, int maxFrames) throws IOException;

    /**
     * Moves to {@code frame} (a PCM frame, or a DSD byte-per-channel unit). A frame beyond the end is clamped
     * to the end, after which {@link #read} returns 0.
     *
     * @throws IllegalArgumentException if {@code frame} is negative
     * @throws IOException if the file cannot be read or the decoder is closed
     */
    void seek(long frame) throws IOException;

    /** The index of the next frame {@link #read} will return. */
    long position();

    /** Releases the file. Idempotent. */
    @Override
    void close() throws IOException;
}
