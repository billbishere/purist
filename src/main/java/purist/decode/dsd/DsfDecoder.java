package purist.decode.dsd;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.io.FileSource;

/**
 * Reads a DSF file's audio as byte-interleaved DSD: one byte per channel per frame, exactly the bytes stored, in
 * the file's own bit order. The file keeps each channel's bytes in blocks of {@code blockSize}; this decoder loads
 * one whole group of blocks (one block per channel) at a time into a buffer made at construction and interleaves
 * from it, so {@link #read} allocates nothing.
 *
 * <p>If the file turns out shorter than its header said (or cannot be read), {@link #read} returns the frames that
 * are good and then throws on every call until a {@link #seek}.
 */
final class DsfDecoder implements Decoder {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private final FileSource src;
    private final StreamFormat format;
    private final int channels;
    private final int blockSize;
    private final int groupBytes;
    private final long dataOffset;
    private final long totalFrames;
    private final byte[] group;

    /** Index of the group in {@link #group}, or -1 if none is loaded. */
    private long loadedGroup = -1;
    private long position;
    private IOException failure;
    private boolean closed;

    DsfDecoder(FileSource src, DsfHandler.DsfFile file) {
        this.src = src;
        this.format = file.format();
        this.channels = format.channels();
        this.blockSize = file.blockSize();
        this.groupBytes = blockSize * channels;
        this.dataOffset = file.dataOffset();
        this.totalFrames = format.lengthFrames();
        this.group = new byte[groupBytes];
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
            long frame = position + done;
            long g = frame / blockSize;
            int offset = (int) (frame % blockSize);
            if (g != loadedGroup && !load(g)) {
                break;
            }
            int n = Math.min(want - done, blockSize - offset);
            interleave(dst, (long) done * channels, offset, n);
            done += n;
        }
        position += done;
        if (done == 0 && failure != null) {
            throw failure;
        }
        return done;
    }

    /** Fills {@link #group} with group {@code g}; false (with {@link #failure} set) if it cannot. */
    private boolean load(long g) {
        loadedGroup = -1;
        try {
            src.seek(dataOffset + g * groupBytes);
            if (src.read(group, 0, groupBytes) != groupBytes) {
                failure = new DecodeException(
                        "DSF data ends early: group " + g + " is incomplete (frame " + g * blockSize + ")");
                return false;
            }
        } catch (IOException e) {
            failure = e;
            return false;
        }
        loadedGroup = g;
        return true;
    }

    private void interleave(MemorySegment dst, long out, int offset, int n) {
        if (channels == 1) {
            MemorySegment.copy(group, offset, dst, BYTE, out, n);
            return;
        }
        for (int c = 0; c < channels; c++) {
            int from = c * blockSize + offset;
            long to = out + c;
            for (int i = 0; i < n; i++, to += channels) {
                dst.set(BYTE, to, group[from + i]);
            }
        }
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
        loadedGroup = -1;
        position = Math.min(frame, totalFrames);
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
