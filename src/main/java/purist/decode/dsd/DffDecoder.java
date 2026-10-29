package purist.decode.dsd;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.io.FileSource;

/**
 * Reads a DFF file's sound data, which is already byte-interleaved DSD in MSB-first order, so the bytes are copied
 * through untouched. The read goes through one buffer made at construction, so {@link #read} allocates nothing.
 *
 * <p>If the file turns out shorter than its header said (or cannot be read), {@link #read} returns the frames that
 * are good and then throws on every call until a {@link #seek}.
 */
final class DffDecoder implements Decoder {

    private static final int CHUNK_BYTES = 64 * 1024;

    private final FileSource src;
    private final StreamFormat format;
    private final int channels;
    private final boolean swap;
    private final long soundOffset;
    private final long totalFrames;
    private final int chunkFrames;
    private final byte[] chunk;
    private final MemorySegment chunkSegment;

    private long position;
    private IOException failure;
    private boolean closed;

    DffDecoder(FileSource src, DffHandler.DffFile file) {
        this.src = src;
        this.format = file.format();
        this.channels = format.channels();
        this.swap = file.swapChannels();
        this.soundOffset = file.soundOffset();
        this.totalFrames = format.lengthFrames();
        this.chunkFrames = Math.max(1, CHUNK_BYTES / channels);
        this.chunk = new byte[chunkFrames * channels];
        this.chunkSegment = MemorySegment.ofArray(chunk);
        src.seek(soundOffset);
    }

    @Override
    public StreamFormat format() {
        return format;
    }

    @Override
    public int read(MemorySegment dst, int maxFrames) throws IOException {
        if (closed) {
            throw new IOException("decoder is closed");
        }
        if (maxFrames < 0) {
            throw new IllegalArgumentException("negative frame count: " + maxFrames);
        }
        long needed = (long) maxFrames * channels;
        if (dst.byteSize() < needed) {
            throw new IllegalArgumentException(
                    "destination holds " + dst.byteSize() + " bytes but " + maxFrames + " frames need " + needed);
        }
        if (failure != null) {
            throw failure;
        }
        int want = (int) Math.min(maxFrames, totalFrames - position);
        int done = 0;
        while (done < want) {
            int n = Math.min(want - done, chunkFrames);
            int got;
            try {
                got = src.read(chunk, 0, n * channels);
            } catch (IOException e) {
                failure = e;
                break;
            }
            int frames = got <= 0 ? 0 : got / channels;
            if (swap) { // right was filed before left: put them in left, right order (a reorder; no byte changes)
                for (int i = 0; i < frames * 2; i += 2) {
                    byte first = chunk[i];
                    chunk[i] = chunk[i + 1];
                    chunk[i + 1] = first;
                }
            }
            if (frames > 0) {
                MemorySegment.copy(chunkSegment, 0, dst, (long) done * channels, (long) frames * channels);
                done += frames;
            }
            if (frames < n) {
                failure = new DecodeException(
                        "DFF sound data ends early at frame " + (position + done) + " of " + totalFrames);
                break;
            }
        }
        position += done;
        if (done == 0 && failure != null) {
            throw failure;
        }
        return done;
    }

    @Override
    public void seek(long frame) throws IOException {
        if (closed) {
            throw new IOException("decoder is closed");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("negative frame: " + frame);
        }
        failure = null;
        position = Math.min(frame, totalFrames);
        src.seek(soundOffset + position * channels);
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            src.close();
        }
    }
}
