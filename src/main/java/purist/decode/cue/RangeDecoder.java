package purist.decode.cue;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import purist.audio.StreamFormat;
import purist.decode.Decoder;

/**
 * Frames {@code [start, end)} of another decoder, as a track of their own: position 0 is {@code start}, and the
 * stream ends at {@code end}. The samples are the inner decoder's, untouched. Closing it closes the inner decoder.
 */
final class RangeDecoder implements Decoder {

    private final Decoder inner;
    private final long start;
    private final long length;
    private final StreamFormat format;

    /** Takes ownership of {@code inner} and seeks it to {@code start}; closes it if that fails. */
    RangeDecoder(Decoder inner, long start, long end) throws IOException {
        if (start < 0 || end < start) {
            inner.close();
            throw new IllegalArgumentException("bad range " + start + ".." + end);
        }
        this.inner = inner;
        this.start = start;
        this.length = end - start;
        StreamFormat f = inner.format();
        this.format = new StreamFormat(f.kind(), f.sampleRate(), f.bits(), f.channels(), length, f.dsdOrder());
        try {
            inner.seek(start);
        } catch (IOException | RuntimeException e) {
            inner.close();
            throw e;
        }
    }

    @Override
    public StreamFormat format() {
        return format;
    }

    @Override
    public int read(MemorySegment dst, int maxFrames) throws IOException {
        if (maxFrames < 0) {
            throw new IllegalArgumentException("maxFrames must not be negative: " + maxFrames);
        }
        long left = length - position();
        if (left <= 0) {
            return 0;
        }
        return inner.read(dst, (int) Math.min(maxFrames, left));
    }

    @Override
    public void seek(long frame) throws IOException {
        if (frame < 0) {
            throw new IllegalArgumentException("frame must not be negative: " + frame);
        }
        inner.seek(start + Math.min(frame, length));
    }

    @Override
    public long position() {
        return inner.position() - start;
    }

    @Override
    public void close() throws IOException {
        inner.close();
    }
}
