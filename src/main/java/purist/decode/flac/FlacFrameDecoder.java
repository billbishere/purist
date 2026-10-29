package purist.decode.flac;

import java.nio.BufferUnderflowException;
import java.util.Arrays;
import purist.decode.DecodeException;
import purist.decode.flac.FlacDamageException.Kind;

/**
 * Decodes FLAC frames (RFC 9639 §9), one per call, into interleaved samples that are right-aligned and
 * sign-extended in {@code int}s, the project's PCM convention.
 *
 * <p>A frame is: a header with a CRC-8, one subframe per channel (CONSTANT, VERBATIM, FIXED order 0 to 4 or LPC
 * order 1 to 32, each with optional wasted bits), zero padding to a byte, and a CRC-16 over all of it. Stereo
 * frames may code a side channel, which has one bit more than the stream (33 bits for a 32-bit stream), so
 * subframes are decoded, predicted and decorrelated in {@code long}. Residuals are decoded in 64 bits as well;
 * RFC 9639 §9.2.7 limits them to 32 bits, a limit this decoder does not need and does not enforce.
 *
 * <p>Errors: a frame that runs past the bytes given throws {@link IncompleteFrameException}; anything else that
 * is wrong with it (no sync code, a reserved or invalid field, a CRC that does not match, a frame that differs
 * from STREAMINFO in channels or bit depth) throws a plain {@link DecodeException}. So does a decoded sample
 * outside the stream's bit depth: the RFC does not rule on it, but such a sample cannot be played bit-perfectly.
 * CRCs are checked before any sample reaches {@code out}.
 *
 * <p>The decoder keeps its work arrays between calls, growing them for the largest block seen, so decoding
 * allocates nothing but the {@link #lastHeader()} record. It is not thread-safe.
 */
public final class FlacFrameDecoder {

    /**
     * The frame continues past the bytes given to {@link #decode}. This is "need more bytes", not damage: a
     * stream decoder reads further and tries again, and only a frame cut off by the end of the file is a
     * truncation. Every other {@link DecodeException} from {@code decode} is final: more bytes would not help.
     * Damage can still look like this (a corrupted Rice code that runs on), so a stream decoder should bound how
     * far it reads for one frame.
     */
    public static final class IncompleteFrameException extends DecodeException {

        private static final long serialVersionUID = 1L;

        public IncompleteFrameException() {
            super("incomplete frame");
        }
    }

    private final FlacStreamInfo info;
    private final BitReader in = new BitReader(new byte[0], 0, 0);
    private final int[] coefs = new int[32];
    private final long[][] work; // one row per channel, as long as the largest block so far
    private FlacFrameHeader last;

    /** A decoder for the frames of the stream that {@code info} describes (1 to 8 channels, 4 to 32 bits). */
    public FlacFrameDecoder(FlacStreamInfo info) {
        if (info.channels() < 1 || info.channels() > 8) {
            throw new IllegalArgumentException("FLAC streams have 1 to 8 channels, not " + info.channels());
        }
        if (info.bitsPerSample() < 4 || info.bitsPerSample() > 32) {
            throw new IllegalArgumentException("FLAC streams have 4 to 32 bits, not " + info.bitsPerSample());
        }
        this.info = info;
        this.work = new long[info.channels()][0];
    }

    /**
     * Decodes the frame that starts at {@code buf[off]}, reading no further than {@code off + len}, and writes
     * its {@code blockSize * channels} samples, interleaved, to the start of {@code out}.
     *
     * @return the number of bytes the frame occupies
     * @throws IncompleteFrameException if the frame runs past {@code off + len}
     * @throws DecodeException if the frame is damaged or invalid, or holds more samples than {@code out}
     */
    public int decode(byte[] buf, int off, int len, int[] out) throws DecodeException {
        FlacFrameHeader h = FlacFrameHeader.parse(buf, off, len, info);
        long frame = h.codedNumber();
        int channels = h.channels();
        int bps = h.bitsPerSample();
        int n = h.blockSize();
        if (channels != info.channels()) {
            throw new DecodeException("FLAC frame " + frame + " has " + channels + " channels but the stream has "
                    + info.channels());
        }
        if (bps != info.bitsPerSample()) {
            throw new DecodeException("FLAC frame " + frame + " has " + bps + "-bit samples but the stream is "
                    + info.bitsPerSample() + "-bit");
        }
        if ((long) n * channels > out.length) {
            throw new DecodeException("FLAC frame " + frame + " holds " + n + " samples per channel, more than the "
                    + "output buffer takes");
        }
        if (work[0].length < n) {
            for (int c = 0; c < channels; c++) {
                work[c] = new long[n];
            }
        }

        int assignment = h.channelAssignment();
        int crcAt;
        try {
            in.reset(buf, off + h.headerLength(), len - h.headerLength());
            for (int c = 0; c < channels; c++) {
                boolean side = assignment == 8 && c == 1 || assignment == 9 && c == 0 || assignment == 10 && c == 1;
                subframe(work[c], n, side ? bps + 1 : bps, frame);
            }
            in.alignToByte();
            crcAt = in.bytePosition();
        } catch (BufferUnderflowException e) {
            throw new IncompleteFrameException();
        }
        if (off + len - crcAt < 2) {
            throw new IncompleteFrameException();
        }
        int crc = (buf[crcAt] & 0xFF) << 8 | buf[crcAt + 1] & 0xFF;
        if (Crc.crc16(buf, off, crcAt - off) != crc) {
            throw new FlacDamageException(Kind.CRC_MISMATCH, "CRC mismatch at frame " + frame);
        }
        interleave(out, n, channels, assignment, bps, frame);
        last = h;
        return crcAt + 2 - off;
    }

    /** The header of the last frame decoded successfully, or null before the first. */
    public FlacFrameHeader lastHeader() {
        return last;
    }

    // ---- subframes (RFC 9639 §9.2) --------------------------------------------------------------------

    /** Decodes one subframe of {@code sbps}-bit samples into {@code s[0..n)}. */
    private void subframe(long[] s, int n, int sbps, long frame) throws DecodeException {
        int head = in.readUInt(8); // zero padding bit, 6-bit type, wasted-bits flag
        if ((head & 0x80) != 0) {
            throw new DecodeException("FLAC frame " + frame + " has a subframe whose padding bit is set");
        }
        int type = head >>> 1;
        int wasted = 0;
        if ((head & 1) != 0) {
            int k = in.readUnary(); // k + 1 wasted bits
            if (k < 0 || k + 1 >= sbps) {
                throw new DecodeException("FLAC frame " + frame + " has a subframe with " + (k + 1L)
                        + " wasted bits, leaving none of its " + sbps + " bits");
            }
            wasted = k + 1;
        }
        int width = sbps - wasted;
        if (type == 0) {
            Arrays.fill(s, 0, n, in.readSLong(width));
        } else if (type == 1) {
            for (int i = 0; i < n; i++) {
                s[i] = in.readSLong(width);
            }
        } else if (type >= 8 && type <= 12) {
            fixed(s, n, width, type - 8, frame);
        } else if (type >= 32) {
            lpc(s, n, width, type - 31, frame);
        } else {
            throw new DecodeException("FLAC frame " + frame + " uses the reserved subframe type " + type);
        }
        if (wasted > 0) {
            for (int i = 0; i < n; i++) {
                s[i] <<= wasted;
            }
        }
    }

    private void fixed(long[] s, int n, int width, int order, long frame) throws DecodeException {
        warmUp(s, n, width, order, frame);
        residual(s, n, order, frame);
        switch (order) {
            case 1 -> {
                for (int i = 1; i < n; i++) {
                    s[i] += s[i - 1];
                }
            }
            case 2 -> {
                for (int i = 2; i < n; i++) {
                    s[i] += 2 * s[i - 1] - s[i - 2];
                }
            }
            case 3 -> {
                for (int i = 3; i < n; i++) {
                    s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3];
                }
            }
            case 4 -> {
                for (int i = 4; i < n; i++) {
                    s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4];
                }
            }
            default -> { } // order 0: the residual is the signal
        }
    }

    private void lpc(long[] s, int n, int width, int order, long frame) throws DecodeException {
        warmUp(s, n, width, order, frame);
        int precision = in.readUInt(4);
        if (precision == 15) {
            throw new DecodeException("FLAC frame " + frame + " uses the invalid LPC precision code 15");
        }
        precision++;
        int shift = in.readSInt(5);
        if (shift < 0) {
            throw new DecodeException("FLAC frame " + frame + " has a negative LPC shift (" + shift + ")");
        }
        int[] q = coefs;
        for (int j = 0; j < order; j++) {
            q[j] = in.readSInt(precision);
        }
        residual(s, n, order, frame);
        for (int i = order; i < n; i++) {
            long sum = 0;
            for (int j = 0; j < order; j++) {
                sum += q[j] * s[i - 1 - j]; // the first coefficient weighs the newest sample
            }
            s[i] += sum >> shift;
        }
    }

    /** Reads the {@code order} unencoded warm-up samples of a predicted subframe. */
    private void warmUp(long[] s, int n, int width, int order, long frame) throws DecodeException {
        if (order > n) {
            throw new DecodeException("FLAC frame " + frame + " has a predictor of order " + order + " in a block of "
                    + n + " samples");
        }
        for (int i = 0; i < order; i++) {
            s[i] = in.readSLong(width);
        }
    }

    /** Reads the Rice-coded residual (RFC 9639 §9.2.7) into {@code s[order..n)}. */
    private void residual(long[] s, int n, int order, long frame) throws DecodeException {
        int method = in.readUInt(2);
        if (method > 1) {
            throw new DecodeException("FLAC frame " + frame + " uses the reserved residual coding method " + method);
        }
        int parameterBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = in.readUInt(4);
        int size = n >> partitionOrder;
        if (size << partitionOrder != n || size < order) {
            throw new DecodeException("FLAC frame " + frame + " has a residual partition order of " + partitionOrder
                    + ", which does not fit a block of " + n + " samples with predictor order " + order);
        }
        // The first partition holds size - order samples. RFC 9639 asks encoders to keep that above zero; an empty
        // first partition still decodes unambiguously, so it is accepted.
        int i = order;
        for (int p = 1; p <= 1 << partitionOrder; p++) {
            int end = p * size;
            int k = in.readUInt(parameterBits);
            if (k == escape) {
                int raw = in.readUInt(5); // raw two's complement samples of this width; 0 means all zero
                for (; i < end; i++) {
                    s[i] = in.readSInt(raw);
                }
            } else {
                for (; i < end; i++) {
                    s[i] = in.readRice(k);
                }
            }
        }
    }

    // ---- channels -------------------------------------------------------------------------------------

    /** Undoes the stereo decorrelation and interleaves the channels into {@code out}, checking every sample. */
    private void interleave(int[] out, int n, int channels, int assignment, int bps, long frame)
            throws DecodeException {
        long min = -1L << (bps - 1);
        long max = ~min;
        long[] a = work[0];
        switch (assignment) {
            case 8 -> { // left, side = left - right
                long[] side = work[1];
                for (int i = 0, o = 0; i < n; i++, o += 2) {
                    long l = a[i];
                    long r = l - side[i];
                    if (l < min || l > max || r < min || r > max) {
                        throw outOfRange(frame, bps);
                    }
                    out[o] = (int) l;
                    out[o + 1] = (int) r;
                }
            }
            case 9 -> { // side = left - right, right
                long[] right = work[1];
                for (int i = 0, o = 0; i < n; i++, o += 2) {
                    long r = right[i];
                    long l = a[i] + r;
                    if (l < min || l > max || r < min || r > max) {
                        throw outOfRange(frame, bps);
                    }
                    out[o] = (int) l;
                    out[o + 1] = (int) r;
                }
            }
            case 10 -> { // mid = (left + right) >> 1, side = left - right
                long[] side = work[1];
                for (int i = 0, o = 0; i < n; i++, o += 2) {
                    long sd = side[i];
                    long mid = a[i] << 1 | sd & 1;
                    long l = (mid + sd) >> 1;
                    long r = (mid - sd) >> 1;
                    if (l < min || l > max || r < min || r > max) {
                        throw outOfRange(frame, bps);
                    }
                    out[o] = (int) l;
                    out[o + 1] = (int) r;
                }
            }
            default -> {
                for (int c = 0; c < channels; c++) {
                    long[] s = work[c];
                    for (int i = 0, o = c; i < n; i++, o += channels) {
                        long v = s[i];
                        if (v < min || v > max) {
                            throw outOfRange(frame, bps);
                        }
                        out[o] = (int) v;
                    }
                }
            }
        }
    }

    private static DecodeException outOfRange(long frame, int bps) {
        return new DecodeException("FLAC frame " + frame + " decodes to a sample outside the " + bps + "-bit range");
    }
}
