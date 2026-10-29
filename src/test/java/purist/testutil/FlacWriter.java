package purist.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A FLAC encoder for tests, written from RFC 9639 and sharing no code with the decoder under test: it has its
 * own bit writer ({@link BitWriter}), its own bit-at-a-time CRC-8/CRC-16, and computes residuals from the
 * chosen predictor (fixed residuals as repeated finite differences, LPC residuals from the given quantized
 * coefficients). It never calls production decoding code.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #frame(FrameSpec)}: one frame with every coding choice under the test's control (subframe kind
 *       per channel, Rice parameter, partition order, escape partition, wasted bits, header encodings).
 *   <li>{@link #writeFile}: a whole {@code .flac} file of fixed-blocksize frames with STREAMINFO (correct
 *       MD5, frame sizes, total samples), optional SEEKTABLE, VORBIS_COMMENT and PICTURE, and a final PADDING
 *       block.
 * </ul>
 *
 * <p>Samples are signed, right-aligned {@code int}s of the given bit depth. Frames are written exactly as
 * specified; the writer refuses (with {@link IllegalArgumentException}) anything it cannot encode losslessly,
 * such as {@code CONSTANT} for a channel that varies or a Rice parameter that collides with the escape code.
 */
public final class FlacWriter {

    /** How the channels of a stereo frame are coded (RFC 9639 channel assignments 8, 9 and 10). */
    public enum ChannelMode {
        /** Each channel coded on its own (assignment {@code channels - 1}). */
        INDEPENDENT,
        /** Left, then side = left - right (assignment 8). */
        LEFT_SIDE,
        /** Side = left - right, then right (assignment 9). */
        SIDE_RIGHT,
        /** Mid = (left + right) >> 1, then side = left - right (assignment 10). */
        MID_SIDE
    }

    /** The subframe type to write. */
    public sealed interface SubframeKind permits Constant, Verbatim, Fixed, Lpc {

        SubframeKind CONSTANT = new Constant();
        SubframeKind VERBATIM = new Verbatim();

        /** A fixed predictor of order 0 to 4. */
        static SubframeKind FIXED(int order) {
            return new Fixed(order);
        }

        /**
         * A linear predictor. {@code precision} is the coefficient width in bits (1 to 15; 16 writes the
         * forbidden code 0b1111, for refusal tests) and {@code shift} the right shift (a negative value is
         * forbidden by the RFC and written only for refusal tests; the residual then uses a left shift).
         */
        static SubframeKind LPC(int order, int[] qcoefs, int precision, int shift) {
            return new Lpc(order, qcoefs.clone(), precision, shift);
        }

        /** The predictor order: the number of warm-up samples. */
        int order();
    }

    public record Constant() implements SubframeKind {
        @Override
        public int order() {
            return 0;
        }
    }

    public record Verbatim() implements SubframeKind {
        @Override
        public int order() {
            return 0;
        }
    }

    public record Fixed(int order) implements SubframeKind {
        public Fixed {
            if (order < 0 || order > 4) {
                throw new IllegalArgumentException("fixed order " + order);
            }
        }
    }

    public record Lpc(int order, int[] qcoefs, int precision, int shift) implements SubframeKind {
        public Lpc {
            if (order < 1 || order > 32 || qcoefs.length != order) {
                throw new IllegalArgumentException("LPC order " + order + " with " + qcoefs.length + " coefficients");
            }
            if (precision < 1 || precision > 16) {
                throw new IllegalArgumentException("precision " + precision);
            }
            if (shift < -16 || shift > 15) {
                throw new IllegalArgumentException("shift " + shift);
            }
        }
    }

    /** Everything that decides how one frame is written; built with {@link #of} and the fluent setters. */
    public static final class FrameSpec {
        private final int bps;
        private final int[][] channels;
        private int sampleRate = 44100;
        private ChannelMode mode = ChannelMode.INDEPENDENT;
        private SubframeKind[] kinds;
        private int riceParameter = -1;
        private int partitionOrder;
        private int escapePartition = -1;
        private int escapeWidth = -1;
        private Boolean fiveBitParameters;
        private int wastedBits;
        private long codedNumber;
        private boolean variableBlocksize;
        private int blockSizeCode = -1;
        private int rateCode = -1;
        private boolean bpsFromStreamInfo;
        private boolean unchecked;

        private FrameSpec(int bps, int[][] channels) {
            this.bps = bps;
            this.channels = channels;
        }

        /** A frame of these per-channel samples (all the same length: the block size), all VERBATIM. */
        public static FrameSpec of(int bps, int[]... channels) {
            if (bps < 4 || bps > 32) {
                throw new IllegalArgumentException("bps " + bps);
            }
            if (channels.length < 1 || channels.length > 8) {
                throw new IllegalArgumentException(channels.length + " channels");
            }
            int n = channels[0].length;
            int[][] copy = new int[channels.length][];
            for (int c = 0; c < channels.length; c++) {
                if (channels[c].length != n) {
                    throw new IllegalArgumentException("channel lengths differ");
                }
                copy[c] = channels[c].clone();
            }
            if (n < 1 || n > 65535) {
                throw new IllegalArgumentException("block size " + n);
            }
            return new FrameSpec(bps, copy);
        }

        /** The sample rate written into the header (auto-coded unless {@link #rateCode} is given). */
        public FrameSpec rate(int hz) {
            sampleRate = hz;
            return this;
        }

        public FrameSpec mode(ChannelMode m) {
            mode = m;
            return this;
        }

        /** The same subframe kind for every coded channel. */
        public FrameSpec kind(SubframeKind k) {
            kinds = new SubframeKind[] {k};
            return this;
        }

        /** One subframe kind per coded channel, in coded order (so the side channel's slot in stereo modes). */
        public FrameSpec kinds(SubframeKind... k) {
            kinds = k.clone();
            return this;
        }

        /** The Rice parameter of every non-escaped partition; by default the cheapest per partition. */
        public FrameSpec rice(int k) {
            riceParameter = k;
            return this;
        }

        /** Residual partition order (0 to 15). */
        public FrameSpec partitionOrder(int order) {
            partitionOrder = order;
            return this;
        }

        /** Writes partition {@code index} of every residual as an escape partition of raw values. */
        public FrameSpec escape(int index) {
            escapePartition = index;
            return this;
        }

        /** Like {@link #escape(int)} with this raw width instead of the smallest that fits. */
        public FrameSpec escape(int index, int width) {
            escapePartition = index;
            escapeWidth = width;
            return this;
        }

        /** Residual coding method 1 (5-bit Rice parameters, escape 31) or method 0 (4-bit, escape 15). */
        public FrameSpec fiveBitParameters(boolean on) {
            fiveBitParameters = on;
            return this;
        }

        /**
         * Wasted bits for every subframe; the coded samples' low bits must be zero (mid = (L + R) >> 1 keeps one
         * zero bit fewer than L and R). Up to the whole subframe depth, which the RFC forbids, for refusal tests.
         */
        public FrameSpec wasted(int bits) {
            wastedBits = bits;
            return this;
        }

        /** A fixed-blocksize frame with this frame number. */
        public FrameSpec number(long frameNumber) {
            codedNumber = frameNumber;
            variableBlocksize = false;
            return this;
        }

        /** A variable-blocksize frame whose first sample has this number. */
        public FrameSpec variable(long sampleNumber) {
            codedNumber = sampleNumber;
            variableBlocksize = true;
            return this;
        }

        /** Forces the 4-bit block size code (6 and 7 are the uncommon 8- and 16-bit forms). */
        public FrameSpec blockSizeCode(int code) {
            blockSizeCode = code;
            return this;
        }

        /** Forces the 4-bit sample rate code (0 = from STREAMINFO; 12, 13, 14 = uncommon forms). */
        public FrameSpec rateCode(int code) {
            rateCode = code;
            return this;
        }

        /** Writes bit depth code 0 ("get from STREAMINFO") instead of the explicit code. */
        public FrameSpec bpsFromStreamInfo() {
            bpsFromStreamInfo = true;
            return this;
        }

        /** Skips the check that source samples fit the bit depth (for decoder range-check tests). */
        public FrameSpec unchecked() {
            unchecked = true;
            return this;
        }
    }

    private static final int[] TABLE_RATES = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100,
        48000, 96000};

    private FlacWriter() {}

    // ---- frames ---------------------------------------------------------------------------------------

    /** One complete frame: header with CRC-8, subframes, zero padding to a byte, CRC-16. */
    public static byte[] frame(FrameSpec f) {
        int n = f.channels[0].length;
        int nch = f.channels.length;
        if (!f.unchecked) {
            for (int[] ch : f.channels) {
                for (int v : ch) {
                    if (!BitWriter.fitsSigned(v, f.bps)) {
                        throw new IllegalArgumentException(v + " does not fit " + f.bps + " bits");
                    }
                }
            }
        }

        long[][] coded = new long[nch][n];
        int[] codedBps = new int[nch];
        int assignment;
        if (f.mode == ChannelMode.INDEPENDENT) {
            assignment = nch - 1;
            for (int c = 0; c < nch; c++) {
                for (int i = 0; i < n; i++) {
                    coded[c][i] = f.channels[c][i];
                }
                codedBps[c] = f.bps;
            }
        } else {
            if (nch != 2) {
                throw new IllegalArgumentException(f.mode + " needs two channels");
            }
            long[] side = new long[n];
            long[] mid = new long[n];
            for (int i = 0; i < n; i++) {
                long l = f.channels[0][i];
                long r = f.channels[1][i];
                side[i] = l - r;
                mid[i] = Math.floorDiv(l + r, 2L);
            }
            switch (f.mode) {
                case LEFT_SIDE -> {
                    assignment = 8;
                    coded[0] = toLong(f.channels[0]);
                    coded[1] = side;
                    codedBps[0] = f.bps;
                    codedBps[1] = f.bps + 1;
                }
                case SIDE_RIGHT -> {
                    assignment = 9;
                    coded[0] = side;
                    coded[1] = toLong(f.channels[1]);
                    codedBps[0] = f.bps + 1;
                    codedBps[1] = f.bps;
                }
                case MID_SIDE -> {
                    assignment = 10;
                    coded[0] = mid;
                    coded[1] = side;
                    codedBps[0] = f.bps;
                    codedBps[1] = f.bps + 1;
                }
                default -> throw new AssertionError(f.mode);
            }
        }

        BitWriter w = new BitWriter();
        header(w, f, n, assignment);
        for (int c = 0; c < nch; c++) {
            SubframeKind kind = f.kinds == null ? SubframeKind.VERBATIM
                    : f.kinds.length == 1 ? f.kinds[0] : f.kinds[c];
            subframe(w, coded[c], codedBps[c], kind, f);
        }
        w.align();
        byte[] body = w.toByteArray();
        int crc = crc16(body, 0, body.length);
        byte[] out = new byte[body.length + 2];
        System.arraycopy(body, 0, out, 0, body.length);
        out[body.length] = (byte) (crc >>> 8);
        out[body.length + 1] = (byte) crc;
        return out;
    }

    private static void header(BitWriter w, FrameSpec f, int n, int assignment) {
        int bsCode = f.blockSizeCode >= 0 ? f.blockSizeCode : autoBlockSizeCode(n);
        int rateCode = f.rateCode >= 0 ? f.rateCode : autoRateCode(f.sampleRate);
        int bpsCode = f.bpsFromStreamInfo ? 0 : bpsCode(f.bps);

        w.bits(0b111111111111100, 15).bits(f.variableBlocksize ? 1 : 0, 1);
        w.bits(bsCode, 4).bits(rateCode, 4);
        w.bits(assignment, 4).bits(bpsCode, 3).bits(0, 1);
        if (!f.variableBlocksize && f.codedNumber >= 1L << 31) {
            throw new IllegalArgumentException("frame number " + f.codedNumber + " exceeds 31 bits");
        }
        w.bytes(codedNumber(f.codedNumber));

        switch (bsCode) {
            case 0 -> throw new IllegalArgumentException("block size code 0 is reserved");
            case 6 -> w.unsigned(n - 1, 8);
            case 7 -> w.unsigned(n - 1, 16);
            default -> {
                if (commonBlockSize(bsCode) != n) {
                    throw new IllegalArgumentException("block size code " + bsCode + " is not " + n);
                }
            }
        }
        switch (rateCode) {
            case 0 -> { }
            case 12 -> {
                if (f.sampleRate % 1000 != 0) {
                    throw new IllegalArgumentException(f.sampleRate + " Hz is not whole kHz");
                }
                w.unsigned(f.sampleRate / 1000, 8);
            }
            case 13 -> w.unsigned(f.sampleRate, 16);
            case 14 -> {
                if (f.sampleRate % 10 != 0) {
                    throw new IllegalArgumentException(f.sampleRate + " Hz is not a multiple of 10");
                }
                w.unsigned(f.sampleRate / 10, 16);
            }
            default -> {
                if (rateCode > 14 || TABLE_RATES[rateCode] != f.sampleRate) {
                    throw new IllegalArgumentException("rate code " + rateCode + " is not " + f.sampleRate);
                }
            }
        }
        byte[] head = w.toByteArray();
        w.bits(crc8(head, 0, head.length), 8);
    }

    private static int autoBlockSizeCode(int n) {
        for (int code = 1; code <= 15; code++) {
            if (code != 6 && code != 7 && commonBlockSize(code) == n) {
                return code;
            }
        }
        return n <= 256 ? 6 : 7;
    }

    /** The block size of a common code, or -1 for 0, 6 and 7. */
    private static int commonBlockSize(int code) {
        if (code == 1) {
            return 192;
        }
        if (code >= 2 && code <= 5) {
            return 576 * (1 << (code - 2));
        }
        if (code >= 8) {
            return 256 * (1 << (code - 8));
        }
        return -1;
    }

    private static int autoRateCode(int rate) {
        for (int code = 1; code <= 11; code++) {
            if (TABLE_RATES[code] == rate) {
                return code;
            }
        }
        if (rate % 1000 == 0 && rate / 1000 <= 255) {
            return 12;
        }
        if (rate <= 65535) {
            return 13;
        }
        if (rate % 10 == 0 && rate / 10 <= 65535) {
            return 14;
        }
        return 0;
    }

    private static int bpsCode(int bps) {
        return switch (bps) {
            case 8 -> 1;
            case 12 -> 2;
            case 16 -> 4;
            case 20 -> 5;
            case 24 -> 6;
            case 32 -> 7;
            default -> 0;
        };
    }

    /** The UTF-8-like coded number of RFC 9639 §9.1.5: 1 to 7 bytes for up to 36 bits. */
    public static byte[] codedNumber(long v) {
        if (v < 0 || v >= 1L << 36) {
            throw new IllegalArgumentException("coded number " + v);
        }
        if (v < 0x80) {
            return new byte[] {(byte) v};
        }
        int len = 2;
        while (len < 7 && v >= 1L << (5 * len + 1)) { // 2 bytes hold 11 bits, 3 hold 16, ... 6 hold 31
            len++;
        }
        byte[] out = new byte[len];
        long rest = v;
        for (int i = len - 1; i >= 1; i--) {
            out[i] = (byte) (0x80 | (rest & 0x3F));
            rest >>>= 6;
        }
        out[0] = (byte) (((0xFF << (8 - len)) & 0xFF) | rest);
        return out;
    }

    // ---- subframes ------------------------------------------------------------------------------------

    private static void subframe(BitWriter w, long[] samples, int sbps, SubframeKind kind, FrameSpec f) {
        int n = samples.length;
        int wasted = f.wastedBits;
        if (wasted < 0 || wasted > sbps) {
            throw new IllegalArgumentException("wasted bits " + wasted);
        }
        long[] s = new long[n];
        for (int i = 0; i < n; i++) {
            if (wasted > 0 && (samples[i] & ((1L << wasted) - 1)) != 0) {
                throw new IllegalArgumentException("sample " + samples[i] + " has no " + wasted + " wasted bits");
            }
            s[i] = samples[i] >> wasted;
        }
        int eb = sbps - wasted;

        int type = switch (kind) {
            case Constant _ -> 0;
            case Verbatim _ -> 1;
            case Fixed x -> 8 + x.order();
            case Lpc l -> 31 + l.order();
        };
        w.bits(0, 1).bits(type, 6);
        if (wasted > 0) {
            w.bit(1).unary(wasted - 1);
        } else {
            w.bit(0);
        }

        switch (kind) {
            case Constant _ -> {
                for (long v : s) {
                    if (v != s[0]) {
                        throw new IllegalArgumentException("CONSTANT subframe for a channel that varies");
                    }
                }
                w.signed(s[0], eb);
            }
            case Verbatim _ -> {
                for (long x : s) {
                    w.signed(x, eb);
                }
            }
            case Fixed x -> {
                int order = x.order();
                requireOrder(order, n);
                for (int i = 0; i < order; i++) {
                    w.signed(s[i], eb);
                }
                // The order-k fixed residual is the k-th finite difference of the signal.
                long[] d = s.clone();
                for (int pass = 0; pass < order; pass++) {
                    for (int i = n - 1; i > pass; i--) {
                        d[i] -= d[i - 1];
                    }
                }
                residual(w, d, order, f);
            }
            case Lpc l -> {
                int order = l.order();
                requireOrder(order, n);
                for (int i = 0; i < order; i++) {
                    w.signed(s[i], eb);
                }
                w.bits(l.precision() - 1, 4);
                w.signed(l.shift(), 5);
                for (int q : l.qcoefs()) {
                    w.signed(q, l.precision());
                }
                long[] r = new long[n];
                for (int i = order; i < n; i++) {
                    long sum = 0;
                    for (int j = 0; j < order; j++) {
                        sum += (long) l.qcoefs()[j] * s[i - 1 - j];
                    }
                    long prediction = l.shift() >= 0 ? sum >> l.shift() : sum << -l.shift();
                    r[i] = s[i] - prediction;
                }
                residual(w, r, order, f);
            }
        }
    }

    private static void requireOrder(int order, int n) {
        if (order > n) {
            throw new IllegalArgumentException("order " + order + " exceeds block size " + n);
        }
    }

    /** Writes the coded residual of {@code r[order..n)}. */
    private static void residual(BitWriter w, long[] r, int order, FrameSpec f) {
        int n = r.length;
        int po = f.partitionOrder;
        int parts = 1 << po;
        if (po > 15 || n % parts != 0 || (n >> po) < order) {
            throw new IllegalArgumentException("partition order " + po + " for block size " + n + ", order " + order);
        }
        int size = n >> po;
        int[] ks = new int[parts];
        boolean needFive = false;
        for (int p = 0; p < parts; p++) {
            if (p == f.escapePartition) {
                continue;
            }
            int from = p == 0 ? order : p * size;
            int to = (p + 1) * size;
            ks[p] = f.riceParameter >= 0 ? f.riceParameter : bestRiceParameter(r, from, to);
            needFive |= ks[p] > 14;
        }
        boolean five = f.fiveBitParameters != null ? f.fiveBitParameters : needFive;
        int paramBits = five ? 5 : 4;
        int escape = five ? 31 : 15;

        w.bits(five ? 1 : 0, 2).bits(po, 4);
        for (int p = 0; p < parts; p++) {
            int from = p == 0 ? order : p * size;
            int to = (p + 1) * size;
            if (p == f.escapePartition) {
                int width = f.escapeWidth >= 0 ? f.escapeWidth : smallestWidth(r, from, to);
                if (width > 31) {
                    throw new IllegalArgumentException("escape width " + width);
                }
                w.bits(escape, paramBits).bits(width, 5);
                for (int i = from; i < to; i++) {
                    w.signed(r[i], width);
                }
            } else {
                if (ks[p] >= escape) {
                    throw new IllegalArgumentException("Rice parameter " + ks[p] + " with " + paramBits + "-bit codes");
                }
                w.bits(ks[p], paramBits);
                for (int i = from; i < to; i++) {
                    w.rice(r[i], ks[p]);
                }
            }
        }
    }

    private static int bestRiceParameter(long[] r, int from, int to) {
        int best = 0;
        long bestBits = Long.MAX_VALUE;
        for (int k = 0; k <= 30; k++) {
            long bits = 0;
            for (int i = from; i < to; i++) {
                long folded = r[i] >= 0 ? r[i] * 2 : -r[i] * 2 - 1;
                bits += (folded >>> k) + 1 + k;
            }
            if (bits < bestBits) {
                bestBits = bits;
                best = k;
            }
        }
        return best;
    }

    private static int smallestWidth(long[] r, int from, int to) {
        int width = 0;
        for (int i = from; i < to; i++) {
            while (!BitWriter.fitsSigned(r[i], width)) {
                width++;
            }
        }
        return width;
    }

    private static long[] toLong(int[] a) {
        long[] out = new long[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i];
        }
        return out;
    }

    // ---- CRCs (bit at a time, independent of the decoder's tables) --------------------------------------

    /** CRC-8, polynomial x^8 + x^2 + x + 1 (0x07), initial value 0, no reflection. */
    public static int crc8(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= b[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    /** CRC-16, polynomial x^16 + x^15 + x^2 + 1 (0x8005), initial value 0, no reflection. */
    public static int crc16(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }

    // ---- files ----------------------------------------------------------------------------------------

    /**
     * Writes {@code dir/name}, a complete FLAC file, and returns its path.
     *
     * <p>Frames are fixed-blocksize, {@code blockSize} samples each (the last one shorter), numbered from 0,
     * every subframe of the given kind, Rice parameters chosen per partition, partition order 2 when the block
     * allows it (else 1, else 0). A frame too short for the predictor order is written VERBATIM. Metadata, in
     * order: STREAMINFO (min/max block = {@code blockSize}, real min/max frame sizes, total samples, MD5 over the
     * interleaved little-endian samples of {@code ceil(bps/8)} bytes); SEEKTABLE if asked (a point for the frame
     * that starts each new second, then one placeholder point); VORBIS_COMMENT if {@code tags} is not null
     * (vendor {@code "Purist FlacWriter"}, one comment per entry in iteration order, and a value with NUL
     * characters becomes one comment per NUL-separated piece, so {@code "A\0B"} writes two comments); PICTURE if
     * {@code picture} is not null (type 3 front cover, MIME {@code image/png} for PNG data else
     * {@code image/jpeg}, empty description, zero width/height/depth/colours); and a 16-byte PADDING block, which
     * is last.
     *
     * @param samples interleaved signed samples; the length must be a multiple of {@code channels}
     */
    public static Path writeFile(Path dir, String name, int rate, int bps, int channels, int[] samples,
            int blockSize, SubframeKind kind, ChannelMode mode, boolean seekTable, Map<String, String> tags,
            byte[] picture) throws IOException {
        if (channels < 1 || channels > 8 || samples.length % channels != 0) {
            throw new IllegalArgumentException(channels + " channels, " + samples.length + " samples");
        }
        if (bps < 4 || bps > 32 || rate < 1 || rate >= 1 << 20 || blockSize < 1 || blockSize > 65535) {
            throw new IllegalArgumentException("bps " + bps + ", rate " + rate + ", block size " + blockSize);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mode, "mode");
        long total = samples.length / channels;

        List<byte[]> frames = new ArrayList<>();
        List<long[]> points = new ArrayList<>(); // {first sample, offset, samples}
        long offset = 0;
        long lastSecond = -1;
        int minFrame = Integer.MAX_VALUE;
        int maxFrame = 0;
        for (long start = 0, number = 0; start < total; start += blockSize, number++) {
            int n = (int) Math.min(blockSize, total - start);
            int[][] ch = new int[channels][n];
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < channels; c++) {
                    ch[c][i] = samples[(int) ((start + i) * channels + c)];
                }
            }
            int order = kind.order();
            int po = 2;
            while (po > 0 && (n % (1 << po) != 0 || (n >> po) <= order)) {
                po--;
            }
            SubframeKind k = n <= order ? SubframeKind.VERBATIM : kind;
            byte[] frame = frame(FrameSpec.of(bps, ch).rate(rate).mode(mode).kind(k).number(number)
                    .partitionOrder(po)); // CONSTANT and VERBATIM have no residual, so ignore it
            frames.add(frame);
            if (start / rate != lastSecond) {
                lastSecond = start / rate;
                points.add(new long[] {start, offset, n});
            }
            offset += frame.length;
            minFrame = Math.min(minFrame, frame.length);
            maxFrame = Math.max(maxFrame, frame.length);
        }
        if (frames.isEmpty()) {
            minFrame = 0;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'f', 'L', 'a', 'C'});
        metadataBlock(out, 0, false, streamInfo(blockSize, minFrame, maxFrame, rate, channels, bps, total,
                md5(samples, bps)));
        if (seekTable) {
            ByteArrayOutputStream st = new ByteArrayOutputStream();
            for (long[] p : points) {
                st.writeBytes(be(p[0], 8));
                st.writeBytes(be(p[1], 8));
                st.writeBytes(be(p[2], 2));
            }
            st.writeBytes(be(-1L, 8)); // placeholder point
            st.writeBytes(be(0, 8));
            st.writeBytes(be(0, 2));
            metadataBlock(out, 3, false, st.toByteArray());
        }
        if (tags != null) {
            metadataBlock(out, 4, false, vorbisComment(tags));
        }
        if (picture != null) {
            metadataBlock(out, 6, false, pictureBlock(picture));
        }
        metadataBlock(out, 1, true, new byte[16]);
        for (byte[] frame : frames) {
            out.writeBytes(frame);
        }
        Path path = dir.resolve(name);
        Files.write(path, out.toByteArray());
        return path;
    }

    /** The 34-byte STREAMINFO payload. */
    public static byte[] streamInfo(int blockSize, int minFrame, int maxFrame, int rate, int channels, int bps,
            long totalSamples, byte[] md5) {
        BitWriter w = new BitWriter();
        w.unsigned(blockSize, 16).unsigned(blockSize, 16);
        w.unsigned(minFrame, 24).unsigned(maxFrame, 24);
        w.unsigned(rate, 20).unsigned(channels - 1, 3).unsigned(bps - 1, 5).unsigned(totalSamples, 36);
        w.bytes(md5);
        return w.toByteArray();
    }

    /** MD5 of the interleaved samples as little-endian two's complement of {@code ceil(bps/8)} bytes each. */
    public static byte[] md5(int[] interleaved, int bps) {
        int width = (bps + 7) / 8;
        byte[] bytes = new byte[interleaved.length * width];
        int p = 0;
        for (int v : interleaved) {
            for (int b = 0; b < width; b++) {
                bytes[p++] = (byte) (v >> (8 * b));
            }
        }
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void metadataBlock(ByteArrayOutputStream out, int type, boolean last, byte[] payload) {
        out.write((last ? 0x80 : 0) | type);
        out.writeBytes(be(payload.length, 3));
        out.writeBytes(payload);
    }

    private static byte[] vorbisComment(Map<String, String> tags) {
        List<byte[]> comments = new ArrayList<>();
        for (Map.Entry<String, String> e : tags.entrySet()) {
            for (String value : e.getValue().split("\0", -1)) {
                comments.add((e.getKey() + "=" + value).getBytes(StandardCharsets.UTF_8));
            }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] vendor = "Purist FlacWriter".getBytes(StandardCharsets.UTF_8);
        b.writeBytes(le32(vendor.length));
        b.writeBytes(vendor);
        b.writeBytes(le32(comments.size()));
        for (byte[] c : comments) {
            b.writeBytes(le32(c.length));
            b.writeBytes(c);
        }
        return b.toByteArray();
    }

    private static byte[] pictureBlock(byte[] data) {
        boolean png = data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
                && data[3] == 'G';
        byte[] mime = (png ? "image/png" : "image/jpeg").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(be(3, 4)); // front cover
        b.writeBytes(be(mime.length, 4));
        b.writeBytes(mime);
        b.writeBytes(be(0, 4)); // description length
        b.writeBytes(be(0, 4)); // width
        b.writeBytes(be(0, 4)); // height
        b.writeBytes(be(0, 4)); // colour depth
        b.writeBytes(be(0, 4)); // indexed colours
        b.writeBytes(be(data.length, 4));
        b.writeBytes(data);
        return b.toByteArray();
    }

    private static byte[] be(long v, int bytes) {
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (v >>> (8 * (bytes - 1 - i)));
        }
        return out;
    }

    private static byte[] le32(int v) {
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }
}
