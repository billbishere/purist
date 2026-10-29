package purist.decode.io;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Buffered random-access reader over a {@link FileChannel}, for the header parsing and sample reads of the
 * decoders. It opens through NIO, so paths over 260 characters and non-ASCII names work.
 *
 * <p>Multi-byte readers come in little- and big-endian forms and return unsigned values ({@code u64} is the raw
 * 64-bit pattern). Every read that cannot be satisfied because the file ends throws {@link EOFException}, except
 * {@link #read}, which reports the end with -1. Reads share one 64 KiB buffer allocated at open; nothing is
 * allocated per read except by {@link #fourcc} and {@link #bytes}, which return new objects.
 *
 * <p>Not thread-safe.
 */
public final class FileSource implements AutoCloseable {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final FileChannel channel;
    private final long size;
    private final byte[] buf = new byte[BUFFER_SIZE];
    private final ByteBuffer window = ByteBuffer.wrap(buf);

    /** File offset of {@code buf[0]}. */
    private long bufStart;
    /** Index in {@code buf} of the next byte to hand out. */
    private int bufPos;
    /** Number of valid bytes in {@code buf}. */
    private int bufLimit;
    private boolean closed;

    private FileSource(FileChannel channel, long size) {
        this.channel = channel;
        this.size = size;
    }

    /** Opens {@code p} for reading, positioned at 0. */
    public static FileSource open(Path p) throws IOException {
        FileChannel ch = FileChannel.open(p, StandardOpenOption.READ);
        try {
            return new FileSource(ch, ch.size());
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /** The file size in bytes when it was opened. */
    public long size() {
        return size;
    }

    /** The offset of the next byte to be read. */
    public long position() {
        return bufStart + bufPos;
    }

    /** Moves to {@code pos}. Positions past the end are allowed; reading there reports the end of file. */
    public void seek(long pos) {
        if (pos < 0) {
            throw new IllegalArgumentException("negative position: " + pos);
        }
        if (pos >= bufStart && pos <= bufStart + bufLimit) {
            bufPos = (int) (pos - bufStart);
        } else {
            bufStart = pos;
            bufPos = 0;
            bufLimit = 0;
        }
    }

    /** Moves by {@code n} bytes (negative moves back); the result must not be negative. */
    public void skip(long n) {
        seek(position() + n);
    }

    public int u8() throws IOException {
        if (bufPos == bufLimit) {
            ensure(1);
        }
        return buf[bufPos++] & 0xFF;
    }

    public int u16le() throws IOException {
        ensure(2);
        int v = (buf[bufPos] & 0xFF) | (buf[bufPos + 1] & 0xFF) << 8;
        bufPos += 2;
        return v;
    }

    public int u16be() throws IOException {
        ensure(2);
        int v = (buf[bufPos] & 0xFF) << 8 | (buf[bufPos + 1] & 0xFF);
        bufPos += 2;
        return v;
    }

    public long u32le() throws IOException {
        ensure(4);
        long v = (buf[bufPos] & 0xFFL) | (buf[bufPos + 1] & 0xFFL) << 8 | (buf[bufPos + 2] & 0xFFL) << 16
                | (buf[bufPos + 3] & 0xFFL) << 24;
        bufPos += 4;
        return v;
    }

    public long u32be() throws IOException {
        ensure(4);
        long v = (buf[bufPos] & 0xFFL) << 24 | (buf[bufPos + 1] & 0xFFL) << 16 | (buf[bufPos + 2] & 0xFFL) << 8
                | (buf[bufPos + 3] & 0xFFL);
        bufPos += 4;
        return v;
    }

    public long u64le() throws IOException {
        ensure(8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = v << 8 | (buf[bufPos + i] & 0xFFL);
        }
        bufPos += 8;
        return v;
    }

    public long u64be() throws IOException {
        ensure(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = v << 8 | (buf[bufPos + i] & 0xFFL);
        }
        bufPos += 8;
        return v;
    }

    /** Four bytes as a string, one char per byte (ISO-8859-1), such as {@code "RIFF"}. */
    public String fourcc() throws IOException {
        ensure(4);
        String s = new String(buf, bufPos, 4, StandardCharsets.ISO_8859_1);
        bufPos += 4;
        return s;
    }

    /** Exactly {@code n} bytes, or {@link EOFException} (before allocating) if the file has fewer. */
    public byte[] bytes(int n) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("negative length: " + n);
        }
        if (n > size - position()) {
            throw new EOFException("need " + n + " bytes at offset " + position() + " but the file ends at " + size);
        }
        byte[] b = new byte[n];
        if (n > 0 && read(b, 0, n) != n) {
            throw new EOFException("file ended while reading " + n + " bytes");
        }
        return b;
    }

    /**
     * Reads up to {@code len} bytes into {@code b[off..]}. It returns fewer than {@code len} only when the file
     * ends first, and -1 when it was already at the end (0 when {@code len} is 0).
     */
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        int total = 0;
        while (total < len) {
            int have = bufLimit - bufPos;
            if (have == 0) {
                if (!refill()) {
                    break;
                }
                continue;
            }
            int n = Math.min(have, len - total);
            System.arraycopy(buf, bufPos, b, off + total, n);
            bufPos += n;
            total += n;
        }
        return total == 0 ? -1 : total;
    }

    /** Closes the file. Idempotent; later reads throw {@link ClosedChannelException}. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        bufStart = position();
        bufPos = 0;
        bufLimit = 0;
        channel.close();
    }

    /**
     * Makes at least {@code n} (at most 8) bytes available at {@code bufPos}, sliding the unread tail to the
     * front of the buffer so a value that straddles the end of a read stays contiguous.
     */
    private void ensure(int n) throws IOException {
        int have = bufLimit - bufPos;
        if (have >= n) {
            return;
        }
        if (closed) {
            throw new ClosedChannelException();
        }
        bufStart += bufPos;
        System.arraycopy(buf, bufPos, buf, 0, have);
        bufPos = 0;
        bufLimit = have;
        while (bufLimit < n) {
            window.limit(BUFFER_SIZE).position(bufLimit);
            int r = channel.read(window, bufStart + bufLimit);
            if (r < 0) {
                throw new EOFException("unexpected end of file at offset " + (bufStart + bufLimit) + " (size " + size + ")");
            }
            bufLimit += r;
        }
    }

    /** Replaces the exhausted buffer with the next block of the file; false at the end of the file. */
    private boolean refill() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
        bufStart += bufLimit;
        bufPos = 0;
        bufLimit = 0;
        window.clear();
        int r = channel.read(window, bufStart);
        if (r <= 0) {
            return false;
        }
        bufLimit = r;
        return true;
    }
}
