package purist.decode.flac;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.flac.FlacDamageException.Kind;
import purist.decode.flac.FlacFrameDecoder.IncompleteFrameException;
import purist.decode.io.FileSource;

/**
 * A seekable {@link Decoder} for a FLAC file, built on {@link FlacFrameDecoder}. It decodes whole frames into a
 * buffer and hands them out in whatever sizes {@link #read} is asked for, as interleaved little-endian
 * {@code int32}, right-aligned and sign-extended, which is what the frame decoder produces.
 *
 * <p><b>The start.</b> The audio starts where the metadata ends. Some taggers leave stray bytes there (another
 * padding block header and its padding, in the files this was built against); frames are looked for in the next
 * megabyte and decoding starts at the first one, provided it is frame 0, so that no audio is skipped.
 *
 * <p><b>The end.</b> When STREAMINFO knows the number of samples, that is where the stream ends: bytes after the
 * last frame (an ID3v1 or APE tag, say) are not looked at, and a file that ends before that point is truncated.
 * When it does not ({@code format().lengthFrames()} is negative), the stream ends at the end of the file.
 *
 * <p><b>Damage.</b> A frame that cannot be decoded (bad CRC, a field the RFC forbids, a different sample rate
 * from the stream's, a file cut short) stops the stream at that frame, and nothing from it is ever delivered.
 * The frames before it are: a {@link #read} that meets damage after producing at least one frame returns those
 * frames, which can be fewer than asked for without being the end, and the next {@code read} throws a
 * {@link DecodeException} (and keeps throwing it). Only a result of 0 is the end of the stream. {@link #seek}
 * clears the error. A seek stops at damage that lies between the last frame the search stood on and the target,
 * and the error comes with the next {@code read}; a seek point, or a frame found by the search, that lies beyond
 * the damage is stood on, and decoding resumes after it without meeting the damage.
 *
 * <p>When the bytes prove the damage, the exception is a {@link FlacDamageException} whose
 * {@link FlacDamageException#kind() kind} says which; any other {@code DecodeException} makes no such claim.
 *
 * <p><b>Reading.</b> Frames are found by reading ahead into a window that always holds at least one largest
 * possible frame (twice STREAMINFO's maximum frame size when it is given, else the most the RFC allows), so a
 * frame that does not decode within the window is damaged, and the decision never needs more of the file. A frame
 * that runs into the end of the file is truncated.
 *
 * <p><b>Seeking.</b> A seek point from the SEEKTABLE is used when the frame it names decodes and starts where the
 * table says; otherwise, and for the stretch beyond the nearest point, the file is searched by byte offset
 * (interpolating, then bisecting) for frames that decode, until the target lies within a short run of bytes after
 * a known frame, and the frames from there are decoded up to the one that holds it. The search needs the
 * first sample of a frame: that is the coded number of a variable-blocksize frame, and the frame number times the
 * block size for a fixed one, which needs STREAMINFO's block sizes to agree. When they do not, the search finds
 * nothing to stand on and decoding starts from the first frame, which is exact but slow.
 *
 * <p>Not thread-safe. {@link #read} allocates nothing but the frame header record per frame.
 */
public final class FlacDecoder implements Decoder {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** The largest block size a frame can have, and so the most samples per channel one frame can hold. */
    private static final int MAX_BLOCK = 65535;
    /** A frame's cap is never below this, whatever STREAMINFO says, so a nonsense maximum cannot make frames fail. */
    private static final int MIN_FRAME_CAP = 64 * 1024;
    /** Bytes the window holds beyond the frame cap, so the file is read in large steps. */
    private static final int READ_AHEAD = 256 * 1024;
    /** A seek stops searching once the frames it is looking for are within this many bytes. */
    private static final int SCAN_BYTES = 256 * 1024;
    private static final int MAX_PROBES = 64;
    /** How far past the metadata the first frame is looked for when it is not where the metadata ends. */
    private static final int MAX_LEAD_IN = 1024 * 1024;

    private final FileSource src;
    private final FlacStreamInfo info;
    private final StreamFormat format;
    private final List<SeekPoint> seekPoints;
    private final int channels;
    private final long total;     // 0 when STREAMINFO does not know
    private final long fileSize;
    private final long firstFrame;
    private final int fixedBlock; // samples per frame in a fixed-blocksize stream; 0 when it cannot be known
    private final int frameCap;
    private final FlacFrameDecoder frames;
    private final int[] pcm;      // one decoded frame: blockSize * channels samples, interleaved
    private final byte[] buf;

    /** {@code buf[bufOff..bufEnd)} holds the file from offset {@code bufBase + bufOff}. */
    private long bufBase;
    private int bufOff;
    private int bufEnd;
    private boolean eof;

    private int pcmPos;           // frames of pcm already delivered
    private int pcmLen;           // frames in pcm
    private long nextStart;       // the first sample of the frame that follows pcm in the file
    private long probedStart;     // the first sample of the frame the last successful probe found; -1 if unknowable
    private long probedNumber;    // the coded number in that frame's header
    private DecodeException pending;
    private boolean closed;

    private FlacDecoder(FileSource src, FlacMetadata meta) throws IOException {
        this.src = src;
        this.info = meta.info();
        this.format = meta.format();
        this.seekPoints = meta.seekPoints();
        this.channels = info.channels();
        this.total = info.totalSamples();
        this.fileSize = src.size();
        this.fixedBlock = info.minBlock() == info.maxBlock() ? info.maxBlock() : 0;
        this.frameCap = frameCap(info);
        this.frames = new FlacFrameDecoder(info);
        this.pcm = new int[MAX_BLOCK * channels];
        this.buf = new byte[frameCap + READ_AHEAD];
        this.firstFrame = findFirstFrame(meta.firstFrameOffset());
        reposition(firstFrame);
    }

    /**
     * Opens {@code p}.
     *
     * @throws purist.decode.UnsupportedFormatException for Ogg FLAC
     * @throws DecodeException if the file is not a FLAC file or its metadata is damaged
     */
    public static FlacDecoder open(Path p) throws IOException {
        FileSource src = FileSource.open(p);
        try {
            return new FlacDecoder(src, FlacMetadata.read(src));
        } catch (IOException | RuntimeException e) {
            try {
                src.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** STREAMINFO, including the MD5 of the audio. */
    public FlacStreamInfo streamInfo() {
        return info;
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
        long needed = (long) maxFrames * channels * 4;
        if (dst.byteSize() < needed) {
            throw new IllegalArgumentException(
                    "destination holds " + dst.byteSize() + " bytes but " + maxFrames + " frames need " + needed);
        }
        int done = 0;
        while (done < maxFrames) {
            if (pcmPos == pcmLen) {
                try {
                    if (!nextFrame()) {
                        break;
                    }
                } catch (DecodeException e) {
                    pending = e;
                    if (done > 0) {
                        break; // deliver what is good now; the next call reports the damage
                    }
                    throw e;
                }
            }
            int n = Math.min(maxFrames - done, pcmLen - pcmPos);
            int from = pcmPos * channels;
            int count = n * channels;
            long out = (long) done * channels * 4;
            for (int i = 0; i < count; i++, out += 4) {
                dst.set(LE_INT, out, pcm[from + i]);
            }
            pcmPos += n;
            done += n;
        }
        return done;
    }

    @Override
    public long position() {
        return nextStart - (pcmLen - pcmPos);
    }

    @Override
    public void seek(long frame) throws IOException {
        if (closed) {
            throw new IOException("decoder is closed");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("negative frame: " + frame);
        }
        long target = total > 0 ? Math.min(frame, total) : frame;
        pending = null;
        pcmPos = 0;
        pcmLen = 0;
        if (target == 0) {
            reposition(firstFrame);
            nextStart = 0;
        } else if (total > 0 && target >= total) {
            reposition(fileSize);
            nextStart = total;
        } else {
            locate(target);
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            src.close();
        }
    }

    // ---- frames ---------------------------------------------------------------------------------------

    /**
     * Decodes the next frame of the file into {@code pcm}, ready to be handed out.
     *
     * @return false at the end of the stream
     * @throws DecodeException if the frame is damaged or the file ends too soon; also, again, after an earlier
     *         failure
     */
    private boolean nextFrame() throws IOException {
        if (pending != null) {
            throw pending;
        }
        pcmPos = 0;
        pcmLen = 0;
        if (total > 0 && nextStart >= total) {
            return false;
        }
        fill();
        long at = bufBase + bufOff;
        if (bufOff == bufEnd) { // the file has ended
            if (total > 0) {
                throw new FlacDamageException(Kind.TRUNCATED, "truncated stream at byte " + at + ": the file ends "
                        + (total - nextStart) + " samples before the end STREAMINFO promises");
            }
            return false;
        }
        int used = decodeHere(at);
        int blockSize = frames.lastHeader().blockSize();
        pcmLen = total > 0 ? (int) Math.min(blockSize, total - nextStart) : blockSize;
        bufOff += used;
        nextStart += pcmLen;
        return true;
    }

    /**
     * Decodes the frame at {@code buf[bufOff]} into {@code pcm}, whose contents are not used unless this returns.
     *
     * @return the number of bytes the frame occupies
     */
    private int decodeHere(long at) throws DecodeException {
        int avail = bufEnd - bufOff;
        int used;
        try {
            used = frames.decode(buf, bufOff, avail, pcm);
        } catch (IncompleteFrameException e) {
            // fill() keeps frameCap bytes in the window unless the file ends first, so running out is decisive
            if (avail < frameCap) {
                throw new FlacDamageException(Kind.TRUNCATED, "truncated frame at byte " + at, e);
            }
            throw new FlacDamageException(Kind.OVERSIZED_FRAME, "damaged frame at byte " + at
                    + " (it is longer than any frame of this stream should be)", e);
        } catch (DecodeException e) {
            String message = e.getMessage() + " (at byte " + at + ")";
            // the position is added to the message; what the frame decoder proved about the damage is kept
            throw e instanceof FlacDamageException d ? new FlacDamageException(d.kind(), message, e)
                    : new DecodeException(message, e);
        }
        FlacFrameHeader h = frames.lastHeader();
        if (h.sampleRate() != info.sampleRate()) {
            throw new DecodeException("the frame at byte " + at + " has a sample rate of " + h.sampleRate()
                    + " Hz but the stream is " + info.sampleRate() + " Hz");
        }
        return used;
    }

    /** The most bytes one frame of this stream can take, with margin; the window never holds less (but at EOF). */
    private static int frameCap(FlacStreamInfo info) {
        // RFC 9639: a frame has at most 16 header bytes and 2 CRC bytes, and each channel a subframe of at most a few
        // bytes of overhead and (bits + 1) bits for each of at most 65535 samples (the side channel has one more bit
        // than the stream). Real encoders never exceed that; a quarter more is allowed to be safe.
        long perChannel = 6 + ((long) MAX_BLOCK * (info.bitsPerSample() + 1) + 7) / 8;
        long rfc = (18 + info.channels() * perChannel) * 5 / 4;
        long cap = info.maxFrame() > 0 ? Math.max(2L * info.maxFrame(), MIN_FRAME_CAP) : rfc;
        return (int) Math.min(cap, rfc);
    }

    // ---- the byte window ------------------------------------------------------------------------------

    /** Starts reading at file offset {@code off}, with an empty window. */
    private void reposition(long off) {
        src.seek(off);
        bufBase = off;
        bufOff = 0;
        bufEnd = 0;
        eof = false;
    }

    /** Tops the window up so it holds {@code frameCap} bytes from {@code bufOff}, or everything up to the end. */
    private void fill() throws IOException {
        int avail = bufEnd - bufOff;
        if (eof || avail >= frameCap) {
            return;
        }
        if (bufOff > 0) {
            System.arraycopy(buf, bufOff, buf, 0, avail);
            bufBase += bufOff;
            bufOff = 0;
            bufEnd = avail;
        }
        int want = buf.length - bufEnd;
        int n = src.read(buf, bufEnd, want); // fewer than asked for only at the end of the file
        if (n > 0) {
            bufEnd += n;
        }
        if (n < want) {
            eof = true;
        }
    }

    /**
     * The offset of the first frame. That is where the metadata ends, unless something else sits there: some taggers
     * leave a stray block header and padding after the block marked last. Frames are then searched for in the next
     * {@code MAX_LEAD_IN} bytes, and the first one found is used only if it is frame 0 of the stream, so that
     * nothing audible is skipped. Otherwise the metadata's answer stands and reading reports what is wrong.
     */
    private long findFirstFrame(long expected) throws IOException {
        long found = probe(expected, Math.min(fileSize, expected + MAX_LEAD_IN));
        return found >= 0 && probedNumber == 0 ? found : expected;
    }

    // ---- seeking --------------------------------------------------------------------------------------

    /**
     * Positions the decoder so the next {@link #read} returns sample {@code target}, which is above 0 and, when the
     * length is known, below it.
     */
    private void locate(long target) throws IOException {
        long loOff = firstFrame; // a frame is known to start here, at sample loStart <= target
        long loStart = 0;
        long hiOff = fileSize;   // no frame that starts here or later is needed
        long hiStart = total > 0 ? total : -1; // the first sample at hiOff, if known

        int best = bestSeekPoint(target);
        if (best >= 0) {
            SeekPoint p = seekPoints.get(best);
            long off = firstFrame + p.offset();
            reposition(off);
            if (tryFrame()) {
                long start = startOf(frames.lastHeader());
                if (start < 0 || start == p.sample()) { // a table that names the wrong frame is not used at all
                    loOff = off;
                    loStart = p.sample();
                    if (best + 1 < seekPoints.size()) {
                        SeekPoint next = seekPoints.get(best + 1);
                        hiOff = firstFrame + next.offset();
                        hiStart = next.sample();
                    }
                }
            }
        }

        for (int i = 0; i < MAX_PROBES && hiOff - loOff > SCAN_BYTES; i++) {
            long guess;
            if (hiStart > loStart && i % 2 == 0) { // interpolate, then bisect, then interpolate again
                guess = loOff + (long) ((double) (target - loStart) / (hiStart - loStart) * (hiOff - loOff));
            } else {
                guess = loOff + (hiOff - loOff) / 2;
            }
            guess = Math.max(loOff + 1, Math.min(guess, hiOff - 1));
            long found = probe(guess, hiOff);
            if (found < 0 || probedStart < 0) { // nothing to stand on: narrow the search from above
                hiOff = guess;
            } else if (probedStart <= target) {
                loOff = found;
                loStart = probedStart;
            } else {
                hiOff = found;
                hiStart = probedStart;
            }
        }

        reposition(loOff);
        nextStart = loStart;
        try {
            while (nextFrame()) {
                if (target < nextStart) {
                    pcmPos = (int) (target - (nextStart - pcmLen));
                    return;
                }
            }
        } catch (DecodeException e) {
            pending = e; // position() is the start of the frame that failed
        }
    }

    /** The index of the last seek point at or before {@code target}, or -1. */
    private int bestSeekPoint(long target) {
        int lo = 0;
        int hi = seekPoints.size() - 1;
        int best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (seekPoints.get(mid).sample() <= target) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /**
     * Looks for the first frame that starts at or after file offset {@code from} and before {@code limit}: a frame
     * sync code with a good CRC-8, whose frame then decodes completely with a good CRC-16 (a sync code can occur
     * inside audio data by chance; a frame that decodes cannot). Leaves the first sample of the frame in
     * {@code probedStart} (-1 if it cannot be known) and the number in its header in {@code probedNumber}.
     *
     * @return the file offset of that frame, or -1 if there is none
     */
    private long probe(long from, long limit) throws IOException {
        reposition(from);
        while (true) {
            fill();
            long at = bufBase + bufOff;
            if (at >= limit) {
                return -1;
            }
            // sync codes can start at bufOff..stop-1: before the limit, and not on the window's last byte
            int stop = bufOff + (int) Math.max(0, Math.min(bufEnd - 1 - bufOff, limit - at));
            int c = bufOff;
            while (c < stop && !(buf[c] == (byte) 0xFF && (buf[c + 1] & 0xFE) == 0xF8)) {
                c++;
            }
            if (c == stop) {
                if (eof || at + (stop - bufOff) >= limit) {
                    return -1; // nothing more to look at
                }
                bufOff = stop; // keep the last byte: it may be the first half of a sync code
                continue;
            }
            bufOff = c;
            if (tryFrame()) {
                probedStart = startOf(frames.lastHeader());
                probedNumber = frames.lastHeader().codedNumber();
                return bufBase + bufOff;
            }
            bufOff++;
        }
    }

    /** Whether a whole frame of this stream decodes at {@code buf[bufOff]}; its header is then {@code lastHeader}. */
    private boolean tryFrame() throws IOException {
        fill();
        try {
            frames.decode(buf, bufOff, bufEnd - bufOff, pcm);
        } catch (DecodeException e) {
            return false;
        }
        return frames.lastHeader().sampleRate() == info.sampleRate();
    }

    /** The first sample of the frame with this header, or -1 if it cannot be told. */
    private long startOf(FlacFrameHeader h) {
        if (h.variableBlocksize()) {
            return h.codedNumber();
        }
        return fixedBlock > 0 ? h.codedNumber() * fixedBlock : -1;
    }
}
