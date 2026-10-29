package purist.decode.flac;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static purist.testutil.FlacWriter.ChannelMode.INDEPENDENT;
import static purist.testutil.FlacWriter.ChannelMode.LEFT_SIDE;
import static purist.testutil.FlacWriter.ChannelMode.MID_SIDE;
import static purist.testutil.FlacWriter.ChannelMode.SIDE_RIGHT;
import static purist.testutil.FlacWriter.SubframeKind.CONSTANT;
import static purist.testutil.FlacWriter.SubframeKind.FIXED;
import static purist.testutil.FlacWriter.SubframeKind.LPC;
import static purist.testutil.FlacWriter.SubframeKind.VERBATIM;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.DecodeException;
import purist.decode.flac.FlacFrameDecoder.IncompleteFrameException;
import purist.testutil.FlacWriter;
import purist.testutil.FlacWriter.ChannelMode;
import purist.testutil.FlacWriter.FrameSpec;
import purist.testutil.FlacWriter.SubframeKind;

/**
 * Most frames here are built by the independent {@link FlacWriter} from source sample arrays, and the decoded
 * samples are compared with those source arrays. Because a writer and a decoder can share a misreading of the
 * RFC, the example files of RFC 9639 Appendix D are decoded too, against the values the RFC states.
 */
class FlacFrameDecoderTest {

    // ---- helpers --------------------------------------------------------------------------------------

    private static FlacStreamInfo info(int channels, int bps) {
        return info(44100, channels, bps);
    }

    private static FlacStreamInfo info(int rate, int channels, int bps) {
        return new FlacStreamInfo(16, 4096, 0, 0, rate, channels, bps, 0, new byte[16]);
    }

    private static int[] interleave(int[]... ch) {
        int n = ch[0].length;
        int[] out = new int[n * ch.length];
        for (int i = 0; i < n; i++) {
            for (int c = 0; c < ch.length; c++) {
                out[i * ch.length + c] = ch[c][i];
            }
        }
        return out;
    }

    /** Decodes {@code frame} (which must be consumed exactly) and returns the interleaved samples. */
    private static int[] decode(FlacStreamInfo info, byte[] frame) throws DecodeException {
        FlacFrameDecoder d = new FlacFrameDecoder(info);
        int[] out = new int[65535 * info.channels()];
        assertEquals(frame.length, d.decode(frame, 0, frame.length, out));
        return Arrays.copyOf(out, d.lastHeader().blockSize() * info.channels());
    }

    /** Writes the spec, decodes it, and checks the result against the source channels. */
    private static void roundTrip(FrameSpec spec, int bps, String what, int[]... source) throws DecodeException {
        byte[] frame = FlacWriter.frame(spec);
        assertArrayEquals(interleave(source), decode(info(source.length, bps), frame), what);
    }

    private static int min(int bps) {
        return (int) -(1L << (bps - 1));
    }

    private static int max(int bps) {
        return (int) ((1L << (bps - 1)) - 1);
    }

    /** Uniform random samples over the whole range of {@code bps}, with both extremes at the start. */
    private static int[] noise(int bps, int n, long seed) {
        Random rnd = new Random(seed);
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            s[i] = (int) (rnd.nextLong() >> (64 - bps));
        }
        if (n >= 2) {
            s[0] = max(bps);
            s[1] = min(bps);
        }
        return s;
    }

    /** A loud, smooth signal (two sines plus a little noise) that predictors model well. */
    private static int[] smooth(int bps, int n, long seed) {
        Random rnd = new Random(seed);
        double amp = Math.pow(2, bps - 1) * 0.45;
        double f1 = 0.003 + rnd.nextDouble() * 0.01;
        double f2 = 0.03 + rnd.nextDouble() * 0.05;
        int[] s = new int[n];
        for (int i = 0; i < n; i++) {
            double v = amp * Math.sin(i * f1) + amp * 0.5 * Math.sin(i * f2 + seed) + rnd.nextGaussian() * 3;
            s[i] = (int) Math.max(min(bps), Math.min(max(bps), Math.round(v)));
        }
        return s;
    }

    private static int[] filled(int n, int v) {
        int[] s = new int[n];
        Arrays.fill(s, v);
        return s;
    }

    private static int headerLength(byte[] frame) {
        int lead = frame[4] & 0xFF;
        int utf8 = lead < 0x80 ? 1 : Integer.numberOfLeadingZeros(~lead << 24);
        int bs = (frame[2] & 0xFF) >>> 4;
        int rate = frame[2] & 0x0F;
        int extra = (bs == 6 ? 1 : bs == 7 ? 2 : 0) + (rate == 12 ? 1 : rate == 13 || rate == 14 ? 2 : 0);
        return 4 + utf8 + extra + 1;
    }

    /** Recomputes the header CRC-8 after a test patched header bytes. */
    private static byte[] fixCrc8(byte[] frame, int headerLength) {
        frame[headerLength - 1] = (byte) FlacWriter.crc8(frame, 0, headerLength - 1);
        return fixCrc16(frame);
    }

    /** Recomputes the footer CRC-16 after a test patched frame bytes. */
    private static byte[] fixCrc16(byte[] frame) {
        int crc = FlacWriter.crc16(frame, 0, frame.length - 2);
        frame[frame.length - 2] = (byte) (crc >>> 8);
        frame[frame.length - 1] = (byte) crc;
        return frame;
    }

    private static DecodeException decodeFails(FlacStreamInfo info, byte[] frame) {
        DecodeException e = assertThrows(DecodeException.class,
                () -> new FlacFrameDecoder(info).decode(frame, 0, frame.length, new int[65536 * info.channels()]));
        assertFalse(e instanceof IncompleteFrameException, () -> "corruption reported as incomplete: " + e);
        return e;
    }

    private static void assertMessageContains(DecodeException e, String text) {
        assertTrue(e.getMessage().contains(text), () -> "message '" + e.getMessage() + "' lacks '" + text + "'");
    }

    // ---- RFC 9639 Appendix D: the example files, an oracle that shares nothing with FlacWriter ---------

    private static byte[] hex(String dump) {
        return HexFormat.of().parseHex(dump.replaceAll("\\s+", ""));
    }

    /**
     * The STREAMINFO an example states (with the MD5 taken from the file), after checking that
     * {@link FlacWriter#streamInfo} lays those values out exactly as the example file does.
     */
    private static FlacStreamInfo rfcStreamInfo(byte[] file, int block, int minFrame, int maxFrame, int rate,
            int channels, int bps, long total) {
        assertEquals("fLaC", new String(file, 0, 4, StandardCharsets.US_ASCII));
        byte[] md5 = Arrays.copyOfRange(file, 26, 42);
        assertArrayEquals(Arrays.copyOfRange(file, 8, 42),
                FlacWriter.streamInfo(block, minFrame, maxFrame, rate, channels, bps, total, md5));
        return new FlacStreamInfo(block, block, minFrame, maxFrame, rate, channels, bps, total, md5);
    }

    /** D.1: one stereo 16-bit sample in two VERBATIM subframes, with 2 and 4 wasted bits. */
    @Test
    void rfcExample1VerbatimWithWastedBits() throws DecodeException {
        byte[] file = hex("""
                66 4c 61 43 80 00 00 22 10 00 10 00 00 00 0f 00 00 0f 0a c4 42 f0 00 00 00 01 3e 84 b4 18
                07 dc 69 03 07 58 6a 3d ad 1a 2e 0f ff f8 69 18 00 00 bf 03 58 fd 03 12 8b aa 9a""");
        FlacStreamInfo info = rfcStreamInfo(file, 4096, 15, 15, 44100, 2, 16, 1);
        FlacFrameDecoder d = new FlacFrameDecoder(info);
        int[] out = new int[2];
        assertEquals(15, d.decode(file, 42, file.length - 42, out));
        assertEquals(new FlacFrameHeader(1, 44100, 1, 16, 0, false, 7), d.lastHeader());
        assertArrayEquals(new int[] {25588, 10416}, out);
        assertArrayEquals(info.md5(), FlacWriter.md5(out, 16), "the STREAMINFO MD5 of the decoded audio");
    }

    /** D.2: a 16-sample side/right frame of two FIXED order-1 subframes, then a 3-sample VERBATIM frame. */
    @Test
    void rfcExample2SideRightFixedThenVerbatim() throws DecodeException {
        byte[] file = hex("""
                66 4c 61 43 00 00 00 22 00 10 00 10 00 00 17 00 00 44 0a c4 42 f0 00 00
                00 13 d5 b0 56 49 75 e9 8b 8d 8b 93 04 22 75 7b 81 03 03 00 00 12 00 00
                00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 10 04 00 00 3a 20 00 00 00
                72 65 66 65 72 65 6e 63 65 20 6c 69 62 46 4c 41 43 20 31 2e 33 2e 33 20
                32 30 31 39 30 38 30 34 01 00 00 00 0e 00 00 00 54 49 54 4c 45 3d d7 a9
                d7 9c d7 95 d7 9d 81 00 00 06 00 00 00 00 00 00 ff f8 69 98 00 0f 99 12
                08 67 01 62 3d 14 42 99 8f 5d f7 0d 6f e0 0c 17 ca eb 21 00 0e e7 a7 7a
                24 a1 59 0c 12 17 b6 03 09 7b 78 4f aa 9a 33 d2 85 e0 70 ad 5b 1b 48 51
                b4 01 0d 99 d2 cd 1a 68 f1 e6 b8 10 ff f8 69 18 01 02 a4 02 c3 82 c4 0b
                c1 4a 03 ee 48 dd 03 b6 7c 13 30""");
        FlacStreamInfo info = rfcStreamInfo(file, 16, 23, 68, 44100, 2, 16, 19);
        FlacFrameDecoder d = new FlacFrameDecoder(info);
        int[] out = new int[32];
        int[] decoded = new int[38];
        assertEquals(68, d.decode(file, 0x88, file.length - 0x88, out));
        assertEquals(new FlacFrameHeader(16, 44100, 9, 16, 0, false, 7), d.lastHeader());
        System.arraycopy(out, 0, decoded, 0, 32);
        assertEquals(23, d.decode(file, 0xcc, file.length - 0xcc, out));
        assertEquals(new FlacFrameHeader(3, 44100, 1, 16, 1, false, 7), d.lastHeader());
        System.arraycopy(out, 0, decoded, 32, 6);
        assertEquals(0xcc + 23, file.length, "the second frame ends the file");

        int[] left = {10372, 18041, 14942, 17876, 15627, 17899, 16242, 18077, 16824, 18263, 17295, -14418, -15201,
            -14508, -15195, -14818, -15486, -15349, -16054};
        int[] right = {6070, 10545, 8743, 10449, 9143, 10463, 9502, 10569, 9840, 10680, 10113, -8428, -8895, -8476,
            -8896, -8653, -9072, -8958, -9410};
        assertArrayEquals(interleave(left, right), decoded);
        assertArrayEquals(info.md5(), FlacWriter.md5(decoded, 16), "the STREAMINFO MD5 of the decoded audio");
    }

    /** D.3: a 24-sample mono 8-bit LPC order-3 frame whose residual has four partitions, one escaped. */
    @Test
    void rfcExample3LpcWithEscapedPartition() throws DecodeException {
        byte[] file = hex("""
                66 4c 61 43 80 00 00 22 10 00 10 00 00 00 1f 00 00 1f 07 d0 00 70 00 00
                00 18 f8 f9 e3 96 f5 cb cf c6 dc 80 7f 99 77 90 6b 32 ff f8 68 02 00 17
                e9 44 00 4f 6f 31 3d 10 47 d2 27 cb 6d 09 08 31 45 2b dc 28 22 22 80 57
                a3""");
        FlacStreamInfo info = rfcStreamInfo(file, 4096, 31, 31, 32000, 1, 8, 24);
        FlacFrameDecoder d = new FlacFrameDecoder(info);
        int[] out = new int[24];
        assertEquals(31, d.decode(file, 0x2a, file.length - 0x2a, out));
        assertEquals(new FlacFrameHeader(24, 32000, 0, 8, 0, false, 7), d.lastHeader());
        assertArrayEquals(new int[] {0, 79, 111, 78, 8, -61, -90, -68, -13, 42, 67, 53, 13, -27, -46, -38, -12, 14,
            24, 19, 6, -4, -5, 0}, out);
        assertArrayEquals(info.md5(), FlacWriter.md5(out, 8), "the STREAMINFO MD5 of the decoded audio");
    }

    // ---- subframe types -------------------------------------------------------------------------------

    @Test
    void decodesConstant() throws DecodeException {
        roundTrip(FrameSpec.of(16, filled(4096, -12345)).kind(CONSTANT), 16, "mono 16", filled(4096, -12345));
        int[] l = filled(1152, max(24));
        int[] r = filled(1152, min(24));
        roundTrip(FrameSpec.of(24, l, r).kind(CONSTANT), 24, "stereo extremes 24", l, r);
        roundTrip(FrameSpec.of(32, filled(192, Integer.MIN_VALUE)).kind(CONSTANT), 32, "32-bit min",
                filled(192, Integer.MIN_VALUE));
        int[] a = filled(576, 1000);
        int[] b = filled(576, -1000);
        roundTrip(FrameSpec.of(16, a, b).kind(CONSTANT).mode(LEFT_SIDE), 16, "constant left/side", a, b);
        roundTrip(FrameSpec.of(8, filled(17, 0)).kind(CONSTANT), 8, "silence", filled(17, 0));
    }

    @Test
    void decodesVerbatim16() throws DecodeException {
        int[] l = noise(16, 1152, 1);
        int[] r = noise(16, 1152, 2);
        roundTrip(FrameSpec.of(16, l, r).kind(VERBATIM), 16, "stereo", l, r);
        int[] m = noise(16, 4096, 3);
        roundTrip(FrameSpec.of(16, m).kind(VERBATIM), 16, "mono", m);
        int[] one = {-32768};
        roundTrip(FrameSpec.of(16, one).kind(VERBATIM), 16, "single sample", one);
    }

    @Test
    void fixedOrders0to4() throws DecodeException {
        for (int order = 0; order <= 4; order++) {
            int[] l = smooth(16, 4608, order);
            int[] r = smooth(16, 4608, order + 10);
            roundTrip(FrameSpec.of(16, l, r).kind(FIXED(order)), 16, "16-bit smooth, order " + order, l, r);

            int[] m = smooth(24, 4096, order + 20);
            roundTrip(FrameSpec.of(24, m).kind(FIXED(order)).partitionOrder(2), 24, "24-bit, 4 partitions, order "
                    + order, m);

            // Full-scale noise: residuals grow past the sample width, Rice parameters past 14 (5-bit codes).
            int[] n = noise(16, 1024, order + 30);
            roundTrip(FrameSpec.of(16, n).kind(FIXED(order)).partitionOrder(1), 16, "noise, order " + order, n);

            // A fixed Rice parameter with 4-bit codes on a quiet signal.
            int[] q = smooth(8, 256, order + 40);
            roundTrip(FrameSpec.of(8, q).kind(FIXED(order)).rice(3).fiveBitParameters(false), 8,
                    "8-bit, k=3, order " + order, q);
        }
    }

    @Test
    void fixedOrderPerChannel() throws DecodeException {
        int[][] ch = new int[5][];
        for (int c = 0; c < 5; c++) {
            ch[c] = smooth(20, 2304, 50 + c);
        }
        roundTrip(FrameSpec.of(20, ch).kinds(FIXED(0), FIXED(1), FIXED(2), FIXED(3), FIXED(4)), 20, "5 channels",
                ch);
    }

    @Test
    void lpcOrder8() throws DecodeException {
        int[] coefs = {1200, -300, 150, -80, 40, -20, 10, -5};
        int[] l = smooth(24, 4096, 60);
        int[] r = smooth(24, 4096, 61);
        roundTrip(FrameSpec.of(24, l, r).kind(LPC(8, coefs, 12, 10)).partitionOrder(2), 24, "order 8", l, r);
        roundTrip(FrameSpec.of(24, l, r).kind(LPC(8, coefs, 12, 10)).mode(MID_SIDE), 24, "order 8 mid/side", l, r);

        int[] m = smooth(16, 2048, 62);
        roundTrip(FrameSpec.of(16, m).kind(LPC(1, new int[] {16000}, 15, 14)), 16, "order 1", m);
        roundTrip(FrameSpec.of(16, m).kind(LPC(2, new int[] {-1, 0}, 1, 0)), 16, "precision 1, shift 0", m);
        roundTrip(FrameSpec.of(16, m).kind(LPC(3, new int[] {16383, -16384, 7}, 15, 15)), 16, "precision 15", m);

        Random rnd = new Random(63);
        int[] big = new int[32];
        for (int j = 0; j < 32; j++) {
            big[j] = rnd.nextInt(1 << 15) - (1 << 14);
        }
        int[] n = noise(24, 1024, 64);
        roundTrip(FrameSpec.of(24, n).kind(LPC(32, big, 15, 15)), 24, "order 32, noise", n);
        roundTrip(FrameSpec.of(24, n).kind(LPC(32, big, 15, 0)), 24, "order 32, no shift", n);
    }

    @Test
    void riceEscapePartition() throws DecodeException {
        int[] m = smooth(16, 4096, 70);
        roundTrip(FrameSpec.of(16, m).kind(FIXED(2)).partitionOrder(2).escape(1), 16, "escape partition 1", m);
        roundTrip(FrameSpec.of(16, m).kind(FIXED(2)).partitionOrder(2).escape(0), 16, "escape partition 0", m);
        roundTrip(FrameSpec.of(16, m).kind(FIXED(2)).partitionOrder(2).escape(3, 31), 16, "31-bit raw values", m);
        roundTrip(FrameSpec.of(16, m).kind(FIXED(2)).partitionOrder(2).escape(2).fiveBitParameters(true), 16,
                "escape with 5-bit parameters", m);

        // A silent first partition coded as an escape of width 0 (no bits per sample).
        int[] s = smooth(16, 1024, 71);
        Arrays.fill(s, 0, 256, 0);
        roundTrip(FrameSpec.of(16, s).kind(FIXED(0)).partitionOrder(2).escape(0, 0), 16, "width 0", s);

        int[] n = noise(24, 512, 72);
        roundTrip(FrameSpec.of(24, n).kind(FIXED(1)).partitionOrder(1).escape(1), 24, "noise escape", n);
    }

    @Test
    void wastedBits() throws DecodeException {
        int[] l = smooth(24, 2048, 80);
        int[] r = smooth(24, 2048, 81);
        for (int i = 0; i < l.length; i++) {
            l[i] &= ~0xF;
            r[i] &= ~0xF;
        }
        for (SubframeKind k : List.of(VERBATIM, FIXED(2), LPC(2, new int[] {1800, -800}, 12, 10))) {
            roundTrip(FrameSpec.of(24, l, r).kind(k).wasted(4), 24, "4 wasted bits, " + k, l, r);
            roundTrip(FrameSpec.of(24, l, r).kind(k).wasted(4).mode(LEFT_SIDE), 24, "wasted left/side " + k, l, r);
            roundTrip(FrameSpec.of(24, l, r).kind(k).wasted(1).mode(MID_SIDE), 24, "1 wasted mid/side " + k, l, r);
        }
        int[] c = filled(4096, 0x7F0000);
        roundTrip(FrameSpec.of(24, c).kind(CONSTANT).wasted(16), 24, "constant, 16 wasted", c);
        int[] coarse = new int[1000];
        for (int i = 0; i < coarse.length; i++) {
            coarse[i] = ((i % 15) - 7) << 20;
        }
        roundTrip(FrameSpec.of(24, coarse).kind(VERBATIM).wasted(20), 24, "20 wasted, 4 bits left", coarse);
    }

    // ---- channels and sizes ---------------------------------------------------------------------------

    @Test
    void stereoModesExact() throws DecodeException {
        for (int bps : new int[] {16, 24}) {
            int n = 4096;
            int[] l = smooth(bps, n, bps);
            int[] r = smooth(bps, n, bps + 1);
            // Extremes: side = L - R needs bps + 1 bits; odd L + R exercises the mid/side low bit.
            l[0] = max(bps);
            r[0] = min(bps);
            l[1] = min(bps);
            r[1] = max(bps);
            l[2] = max(bps);
            r[2] = max(bps) - 1;
            l[3] = -1;
            r[3] = 0;
            for (ChannelMode mode : ChannelMode.values()) {
                for (SubframeKind k : List.of(VERBATIM, FIXED(2), LPC(4, new int[] {2900, -1800, 600, -100}, 13, 11))) {
                    roundTrip(FrameSpec.of(bps, l, r).mode(mode).kind(k), bps, bps + "-bit " + mode + " " + k, l, r);
                }
            }
        }
    }

    @Test
    void bps8_16_24Exact() throws DecodeException {
        for (int bps : new int[] {8, 12, 16, 20, 24}) {
            int[] l = noise(bps, 1152, bps);
            int[] r = smooth(bps, 1152, bps);
            roundTrip(FrameSpec.of(bps, l, r).kind(VERBATIM), bps, bps + "-bit verbatim", l, r);
            roundTrip(FrameSpec.of(bps, l, r).kind(FIXED(3)).mode(MID_SIDE), bps, bps + "-bit fixed m/s", l, r);
        }
        // Depths without a header code come from STREAMINFO.
        for (int bps : new int[] {4, 5, 17, 18, 23, 31}) {
            int[] l = noise(bps, 600, bps);
            int[] r = noise(bps, 600, bps + 100);
            roundTrip(FrameSpec.of(bps, l, r).kind(FIXED(1)).mode(SIDE_RIGHT), bps, bps + "-bit side/right", l, r);
            roundTrip(FrameSpec.of(bps, l).kind(VERBATIM), bps, bps + "-bit mono", l);
        }
        // Code 0 for a depth that has a code of its own.
        int[] m = noise(16, 256, 7);
        roundTrip(FrameSpec.of(16, m).bpsFromStreamInfo(), 16, "16-bit via STREAMINFO", m);
    }

    @Test
    void bps32SideChannel() throws DecodeException {
        int n = 1024;
        int[] l = noise(32, n, 90);
        int[] r = noise(32, n, 91);
        l[0] = Integer.MAX_VALUE;
        r[0] = Integer.MIN_VALUE; // side = 2^32 - 1, which needs 33 bits
        l[1] = Integer.MIN_VALUE;
        r[1] = Integer.MAX_VALUE; // side = -(2^32 - 1)
        l[2] = Integer.MAX_VALUE;
        r[2] = Integer.MAX_VALUE - 1;
        for (ChannelMode mode : ChannelMode.values()) {
            roundTrip(FrameSpec.of(32, l, r).mode(mode).kind(VERBATIM), 32, "32-bit noise " + mode, l, r);
            roundTrip(FrameSpec.of(32, l, r).mode(mode).kind(FIXED(1)), 32, "32-bit fixed " + mode, l, r);
            roundTrip(FrameSpec.of(32, l, r).mode(mode).kind(LPC(2, new int[] {3, -1}, 3, 1)), 32,
                    "32-bit lpc " + mode, l, r);
        }

        int[] sl = smooth(32, n, 92);
        int[] sr = smooth(32, n, 93);
        for (int i = 0; i < n; i++) {
            sr[i] = -sl[i] + (i % 7); // anti-phase: side is about twice full scale
        }
        sl[5] = Integer.MAX_VALUE;
        sr[5] = Integer.MIN_VALUE;
        for (ChannelMode mode : ChannelMode.values()) {
            for (SubframeKind k : List.of(FIXED(2), FIXED(4), LPC(8, new int[] {1200, -300, 150, -80, 40, -20, 10, -5},
                    12, 10))) {
                roundTrip(FrameSpec.of(32, sl, sr).mode(mode).kind(k).partitionOrder(2), 32,
                        "32-bit anti-phase " + mode + " " + k, sl, sr);
            }
        }

        // A constant side channel of 2^32 - 1, and a 33-bit side with wasted bits.
        int[] cl = filled(256, Integer.MAX_VALUE);
        int[] cr = filled(256, Integer.MIN_VALUE);
        roundTrip(FrameSpec.of(32, cl, cr).mode(LEFT_SIDE).kind(CONSTANT), 32, "constant 33-bit side", cl, cr);
        int[] wl = new int[256];
        int[] wr = new int[256];
        for (int i = 0; i < 256; i++) {
            wl[i] = (i % 2 == 0 ? Integer.MAX_VALUE : 0x40000000) & ~0xFF;
            wr[i] = Integer.MIN_VALUE + (i << 8);
        }
        roundTrip(FrameSpec.of(32, wl, wr).mode(SIDE_RIGHT).kind(VERBATIM).wasted(8), 32, "wasted 33-bit side", wl, wr);
        // Mid = (L + R) >> 1 keeps one zero bit fewer than L and R.
        roundTrip(FrameSpec.of(32, wl, wr).mode(MID_SIDE).kind(FIXED(1)).wasted(7), 32, "wasted mid/side", wl, wr);
    }

    @Test
    void variableBlocksizeCodedNumber() throws DecodeException {
        long[] numbers = {0, 1, 0x7F, 0x80, 0x7FF, 0x800, 0xFFFF, 0x10000, 0x1FFFFF, 0x200000, 0x3FFFFFF, 0x4000000,
            0x7FFFFFFFL, 0x80000000L, 0x9_8765_4321L, (1L << 36) - 1};
        int[] m = smooth(16, 300, 100);
        for (int i = 0; i < numbers.length; i++) {
            long v = numbers[i];
            byte[] frame = FlacWriter.frame(FrameSpec.of(16, m).kind(FIXED(2)).variable(v));
            FlacFrameDecoder d = new FlacFrameDecoder(info(1, 16));
            int[] out = new int[300];
            assertEquals(frame.length, d.decode(frame, 0, frame.length, out));
            assertArrayEquals(m, out, "sample number " + v);
            FlacFrameHeader h = d.lastHeader();
            assertTrue(h.variableBlocksize());
            assertEquals(v, h.codedNumber());
            assertEquals(300, h.blockSize());
            int utf8 = FlacWriter.codedNumber(v).length;
            assertEquals(4 + utf8 + 2 + 1, h.headerLength(), "16-bit uncommon block size after the number");
            assertEquals(headerLength(frame), h.headerLength());
        }
        for (long v : new long[] {0, 0x7F, 0x80, 0x10000, 0x7FFFFFFFL}) {
            byte[] frame = FlacWriter.frame(FrameSpec.of(16, m).kind(VERBATIM).number(v));
            FlacFrameDecoder d = new FlacFrameDecoder(info(1, 16));
            d.decode(frame, 0, frame.length, new int[300]);
            assertFalse(d.lastHeader().variableBlocksize());
            assertEquals(v, d.lastHeader().codedNumber());
        }
    }

    @Test
    void headerCodes() throws DecodeException {
        int[] sizes = {0, 192, 576, 1152, 2304, 4608, 0, 0, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768};
        for (int code = 1; code <= 15; code++) {
            int n = code == 6 ? 77 : code == 7 ? 40000 : sizes[code];
            int[] m = noise(8, n, code);
            byte[] frame = FlacWriter.frame(FrameSpec.of(8, m).kind(FIXED(1)).blockSizeCode(code));
            FlacFrameDecoder d = new FlacFrameDecoder(info(1, 8));
            int[] out = new int[n];
            assertEquals(frame.length, d.decode(frame, 0, frame.length, out));
            assertArrayEquals(m, out, "block size code " + code);
            assertEquals(n, d.lastHeader().blockSize());
        }
        // Uncommon 8- and 16-bit forms for sizes that also have a common code, and the largest allowed.
        for (int[] c : new int[][] {{6, 1}, {6, 256}, {7, 1}, {7, 4096}, {7, 65535}}) {
            int[] m = noise(8, c[1], c[1]);
            byte[] frame = FlacWriter.frame(FrameSpec.of(8, m).blockSizeCode(c[0]));
            assertArrayEquals(m, decode(info(1, 8), frame), "code " + c[0] + " size " + c[1]);
        }

        int[] rates = {-1, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000};
        int[] m = noise(16, 192, 5);
        for (int code = 0; code <= 14; code++) {
            int rate = switch (code) {
                case 0 -> 352800;
                case 12 -> 255000;
                case 13 -> 65535;
                case 14 -> 655350;
                default -> rates[code];
            };
            byte[] frame = FlacWriter.frame(FrameSpec.of(16, m).rate(rate).rateCode(code));
            FlacFrameDecoder d = new FlacFrameDecoder(info(code == 0 ? 352800 : 1, 1, 16));
            int[] out = new int[192];
            d.decode(frame, 0, frame.length, out);
            assertEquals(rate, d.lastHeader().sampleRate(), "rate code " + code);
            assertArrayEquals(m, out);
        }

        // Both uncommon fields at once: 16-bit block size, then 16-bit rate (50 kHz would also fit code 12).
        int[] s = noise(24, 1000, 6);
        byte[] frame = FlacWriter.frame(FrameSpec.of(24, s, s).rate(50000).rateCode(13).mode(LEFT_SIDE)
                .blockSizeCode(7));
        FlacFrameDecoder d = new FlacFrameDecoder(info(50000, 2, 24));
        int[] out = new int[2000];
        d.decode(frame, 0, frame.length, out);
        FlacFrameHeader h = d.lastHeader();
        assertEquals(new FlacFrameHeader(1000, 50000, 8, 24, 0, false, 4 + 1 + 2 + 2 + 1), h);
        assertEquals(2, h.channels());
        assertArrayEquals(interleave(s, s), out);
    }

    @Test
    void multichannelIndependent() throws DecodeException {
        for (int channels = 1; channels <= 8; channels++) {
            int[][] ch = new int[channels][];
            for (int c = 0; c < channels; c++) {
                ch[c] = c % 2 == 0 ? smooth(24, 1152, c) : noise(24, 1152, c);
            }
            byte[] frame = FlacWriter.frame(FrameSpec.of(24, ch).kind(FIXED(2)));
            assertArrayEquals(interleave(ch), decode(info(channels, 24), frame), channels + " channels");
            FlacFrameDecoder d = new FlacFrameDecoder(info(channels, 24));
            d.decode(frame, 0, frame.length, new int[1152 * channels]);
            assertEquals(channels - 1, d.lastHeader().channelAssignment());
            assertEquals(channels, d.lastHeader().channels());
        }
    }

    @Test
    void randomFramesRoundTrip() throws DecodeException {
        Random rnd = new Random(2026);
        int rounds = 1500;
        int decoded = 0;
        for (int round = 0; round < rounds; round++) {
            int bps = 4 + rnd.nextInt(29);
            boolean stereoMode = rnd.nextInt(3) > 0;
            int channels = stereoMode ? 2 : 1 + rnd.nextInt(8);
            ChannelMode mode = stereoMode ? ChannelMode.values()[rnd.nextInt(4)] : INDEPENDENT;
            int n = 1 + rnd.nextInt(rnd.nextBoolean() ? 64 : 3000);
            int wasted = rnd.nextInt(4) == 0 ? rnd.nextInt(Math.min(bps - 1, 6)) : 0;
            // Mid = (L + R) >> 1 keeps one zero bit fewer than L and R, so mid/side clears one bit more.
            int zeros = mode == MID_SIDE && wasted > 0 ? wasted + 1 : wasted;
            int[][] ch = new int[channels][];
            for (int c = 0; c < channels; c++) {
                ch[c] = rnd.nextBoolean() ? noise(bps, n, rnd.nextLong()) : smooth(bps, n, rnd.nextLong());
                for (int i = 0; i < n; i++) {
                    ch[c][i] = (ch[c][i] >> zeros) << zeros;
                }
            }
            SubframeKind kind = switch (rnd.nextInt(4)) {
                case 0 -> VERBATIM;
                case 1 -> FIXED(rnd.nextInt(Math.min(5, n + 1)));
                case 2 -> {
                    int order = 1 + rnd.nextInt(Math.min(32, n));
                    int precision = 1 + rnd.nextInt(15);
                    int shift = rnd.nextInt(16);
                    int[] q = new int[order];
                    for (int j = 0; j < order; j++) {
                        q[j] = rnd.nextInt(1 << precision) - (1 << (precision - 1));
                    }
                    // Keep the predictor's gain (sum of |q| / 2^shift) at most 4, so residuals stay near the
                    // signal's size; an unbounded gain makes Rice codes of millions of bits per sample.
                    while (Arrays.stream(q).mapToLong(Math::abs).sum() > 4L << shift) {
                        for (int j = 0; j < order; j++) {
                            q[j] /= 2;
                        }
                    }
                    yield LPC(order, q, precision, shift);
                }
                default -> VERBATIM;
            };
            int po = 0;
            for (int p = rnd.nextInt(5); p > 0; p--) {
                if (n % (1 << p) == 0 && (n >> p) >= kind.order()) {
                    po = p;
                    break;
                }
            }
            FrameSpec spec = FrameSpec.of(bps, ch).mode(mode).kind(kind).wasted(wasted).partitionOrder(po)
                    .variable(rnd.nextLong() >>> 28);
            if (rnd.nextInt(4) == 0) {
                spec.escape(rnd.nextInt(1 << po));
            }
            byte[] frame;
            try {
                frame = FlacWriter.frame(spec);
            } catch (IllegalArgumentException tooWide) {
                continue; // an escape partition whose raw values would need more than 31 bits
            }
            assertArrayEquals(interleave(ch), decode(info(channels, bps), frame), "round " + round);
            decoded++;
        }
        assertTrue(decoded > rounds * 95 / 100, "only " + decoded + " of " + rounds + " rounds could be written");
    }

    // ---- buffers ------------------------------------------------------------------------------------

    @Test
    void consumesExactlyOneFrameFromALongerBuffer() throws DecodeException {
        int[] a = smooth(16, 4096, 110);
        int[] b = smooth(16, 192, 111);
        int[] c = smooth(16, 8192, 112);
        byte[] fa = FlacWriter.frame(FrameSpec.of(16, a).kind(FIXED(2)).number(0));
        byte[] fb = FlacWriter.frame(FrameSpec.of(16, b).kind(LPC(1, new int[] {1}, 2, 0)).number(1));
        byte[] fc = FlacWriter.frame(FrameSpec.of(16, c).kind(VERBATIM).number(2));
        byte[] all = new byte[3 + fa.length + fb.length + fc.length + 5];
        System.arraycopy(fa, 0, all, 3, fa.length);
        System.arraycopy(fb, 0, all, 3 + fa.length, fb.length);
        System.arraycopy(fc, 0, all, 3 + fa.length + fb.length, fc.length);

        FlacFrameDecoder d = new FlacFrameDecoder(info(1, 16)); // STREAMINFO says 4096; the third frame is larger
        assertNull(d.lastHeader());
        int[] out = new int[8192];
        int pos = 3;
        int[][] expected = {a, b, c};
        for (int f = 0; f < 3; f++) {
            int used = d.decode(all, pos, all.length - pos, out);
            assertEquals(new int[] {fa.length, fb.length, fc.length}[f], used);
            assertEquals(f, d.lastHeader().codedNumber());
            assertArrayEquals(expected[f], Arrays.copyOf(out, expected[f].length), "frame " + f);
            pos += used;
        }
        // The decoder keeps its larger work arrays and still decodes a small frame.
        assertEquals(fb.length, d.decode(fb, 0, fb.length, out));
        assertArrayEquals(b, Arrays.copyOf(out, b.length));
    }

    @Test
    void incompleteFrameIsDistinguishable() throws DecodeException {
        int[] l = smooth(24, 1152, 120);
        int[] r = smooth(24, 1152, 121);
        for (byte[] frame : List.of(FlacWriter.frame(FrameSpec.of(24, l, r).kind(FIXED(2)).mode(MID_SIDE)),
                FlacWriter.frame(FrameSpec.of(24, l, r).kind(VERBATIM).variable(1L << 35)))) {
            FlacFrameDecoder d = new FlacFrameDecoder(info(2, 24));
            int[] out = new int[2304];
            for (int len = 0; len < frame.length; len++) {
                int cut = len;
                IncompleteFrameException e = assertThrows(IncompleteFrameException.class,
                        () -> d.decode(frame, 0, cut, out), "length " + len);
                assertEquals("incomplete frame", e.getMessage());
            }
            assertEquals(frame.length, d.decode(frame, 0, frame.length, out));
            assertArrayEquals(interleave(l, r), out);
        }
    }

    @Test
    void outputBufferMustHoldTheFrame() {
        int[] l = noise(16, 1024, 130);
        byte[] frame = FlacWriter.frame(FrameSpec.of(16, l, l));
        DecodeException e = assertThrows(DecodeException.class,
                () -> new FlacFrameDecoder(info(2, 16)).decode(frame, 0, frame.length, new int[2047]));
        assertFalse(e instanceof IncompleteFrameException);
    }

    @Test
    void decodingAllocatesOnlyTheHeaderRecord() throws DecodeException {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(threads.isThreadAllocatedMemoryEnabled(), "this JVM does not count allocated bytes");
        int[] l = smooth(24, 4096, 140);
        int[] r = smooth(24, 4096, 141);
        byte[] frame = FlacWriter.frame(FrameSpec.of(24, l, r).kind(LPC(8, new int[] {1200, -300, 150, -80, 40, -20,
            10, -5}, 12, 10)).mode(MID_SIDE).partitionOrder(2));
        FlacFrameDecoder d = new FlacFrameDecoder(info(2, 24));
        int[] out = new int[8192];
        for (int i = 0; i < 2000; i++) {
            d.decode(frame, 0, frame.length, out); // warm up the JIT
        }
        long fewest = Long.MAX_VALUE;
        for (int attempt = 0; attempt < 5; attempt++) {
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 200; i++) {
                d.decode(frame, 0, frame.length, out);
            }
            fewest = Math.min(fewest, threads.getCurrentThreadAllocatedBytes() - before);
        }
        assertTrue(fewest <= 200 * 64, "allocated " + fewest + " bytes over 200 frames");
        assertArrayEquals(interleave(l, r), out);
    }

    // ---- corruption -----------------------------------------------------------------------------------

    @Test
    void corruptedCrcThrows() throws DecodeException {
        int[] l = noise(16, 4096, 150);
        int[] r = noise(16, 4096, 151);
        byte[] good = FlacWriter.frame(FrameSpec.of(16, l, r).kind(VERBATIM).number(7));
        FlacStreamInfo info = info(2, 16);
        assertArrayEquals(interleave(l, r), decode(info, good));
        int hl = headerLength(good);

        byte[] header = good.clone();
        header[2] ^= 0x10; // block size code 12 (4096) becomes 13 (8192): same header length, wrong CRC-8
        assertEquals("CRC mismatch at frame 7", decodeFails(info, header).getMessage());

        byte[] crc8 = good.clone();
        crc8[hl - 1] ^= 0x01;
        assertEquals("CRC mismatch at frame 7", decodeFails(info, crc8).getMessage());

        byte[] body = good.clone();
        body[hl + 500] ^= 0x04; // inside a verbatim sample: the structure survives, the CRC-16 does not
        assertEquals("CRC mismatch at frame 7", decodeFails(info, body).getMessage());

        for (int i = 1; i <= 2; i++) {
            byte[] footer = good.clone();
            footer[footer.length - i] ^= (byte) 0x80;
            assertEquals("CRC mismatch at frame 7", decodeFails(info, footer).getMessage());
        }
    }

    @Test
    void everySingleBitFlipIsRejected() {
        int[] l = smooth(16, 576, 160);
        int[] r = smooth(16, 576, 161);
        byte[] good = FlacWriter.frame(FrameSpec.of(16, l, r).kind(FIXED(2)).mode(LEFT_SIDE).partitionOrder(1)
                .number(3));
        FlacStreamInfo info = info(2, 16);
        for (int bit = 0; bit < good.length * 8; bit++) {
            byte[] bad = good.clone();
            bad[bit >>> 3] ^= (byte) (0x80 >>> (bit & 7));
            byte[] padded = Arrays.copyOf(bad, bad.length + 64); // room for a parse that runs on
            int b = bit;
            assertThrows(DecodeException.class,
                    () -> new FlacFrameDecoder(info).decode(padded, 0, padded.length, new int[65536 * 2]),
                    () -> "bit " + b);
        }
    }

    @Test
    void damagedFramesOnlyEverThrowDecodeException() {
        Random rnd = new Random(170);
        List<byte[]> frames = new ArrayList<>();
        int[] s = smooth(24, 1024, 171);
        int[] t = noise(24, 1024, 172);
        frames.add(FlacWriter.frame(FrameSpec.of(24, s, t).kind(FIXED(3)).mode(MID_SIDE).partitionOrder(3)));
        frames.add(FlacWriter.frame(FrameSpec.of(24, s, t).kind(LPC(12, new int[] {9, -8, 7, -6, 5, -4, 3, -2, 1, 0,
            1, 2}, 5, 3)).mode(SIDE_RIGHT)));
        frames.add(FlacWriter.frame(FrameSpec.of(24, s, t).kind(FIXED(1)).partitionOrder(2).escape(1)));
        frames.add(FlacWriter.frame(FrameSpec.of(24, s, t).kind(VERBATIM)));
        FlacStreamInfo info = info(2, 24);
        FlacFrameDecoder d = new FlacFrameDecoder(info);
        int[] out = new int[65536 * 2];
        for (int round = 0; round < 20000; round++) {
            byte[] bad = frames.get(round % frames.size()).clone();
            int edits = 1 + rnd.nextInt(4);
            for (int e = 0; e < edits; e++) {
                bad[rnd.nextInt(bad.length)] = (byte) rnd.nextInt(256);
            }
            int len = rnd.nextInt(4) == 0 ? rnd.nextInt(bad.length + 1) : bad.length;
            if (rnd.nextBoolean()) { // re-sign so the damage reaches the subframe parser
                int hl = headerLength(bad);
                if (hl < bad.length) {
                    fixCrc8(bad, hl);
                }
            }
            try {
                d.decode(bad, 0, len, out);
            } catch (DecodeException expected) {
                // fine: damage is reported as a DecodeException
            } catch (RuntimeException e) {
                throw new AssertionError("round " + round + " threw " + e, e);
            }
        }
    }

    // ---- invalid headers ------------------------------------------------------------------------------

    @Test
    void reservedHeaderValuesAreRejected() {
        int[] m = noise(16, 4096, 180);
        byte[] good = FlacWriter.frame(FrameSpec.of(16, m).number(5));
        FlacStreamInfo info = info(1, 16);
        int hl = headerLength(good);

        byte[] f = good.clone();
        f[2] = (byte) (f[2] & 0x0F); // block size code 0
        assertMessageContains(decodeFails(info, fixCrc8(f, hl)), "block size");

        f = good.clone();
        f[2] = (byte) (f[2] | 0x0F); // sample rate code 15
        assertMessageContains(decodeFails(info, fixCrc8(f, hl)), "sample rate");

        f = good.clone();
        f[3] = (byte) ((f[3] & 0xF1) | (3 << 1)); // bit depth code 3
        assertMessageContains(decodeFails(info, fixCrc8(f, hl)), "bit depth");

        for (int ch = 11; ch <= 15; ch++) {
            f = good.clone();
            f[3] = (byte) ((f[3] & 0x0F) | (ch << 4));
            assertMessageContains(decodeFails(info, fixCrc8(f, hl)), "channel");
        }

        f = good.clone();
        f[3] |= 0x01; // the reserved bit
        assertMessageContains(decodeFails(info, fixCrc8(f, hl)), "reserved");

        f = good.clone();
        f[0] = (byte) 0xFE;
        assertEquals("no frame sync", decodeFails(info, f).getMessage());
        f = good.clone();
        f[1] = (byte) 0xFA;
        assertEquals("no frame sync", decodeFails(info, f).getMessage());
    }

    @Test
    void blockSizeOf65536IsRejected() {
        int[] m = noise(8, 300, 190);
        byte[] f = FlacWriter.frame(FrameSpec.of(8, m).blockSizeCode(7));
        int hl = headerLength(f);
        f[5] = (byte) 0xFF;
        f[6] = (byte) 0xFF;
        assertMessageContains(decodeFails(info(1, 8), fixCrc8(f, hl)), "block size");
    }

    @Test
    void badCodedNumbersAreRejected() {
        int[] m = noise(16, 256, 200);
        FlacStreamInfo info = info(1, 16);

        byte[] f = FlacWriter.frame(FrameSpec.of(16, m).number(0x800)); // 3-byte number
        f[5] = (byte) 0xC5; // a continuation byte must be 10xxxxxx
        assertMessageContains(decodeFails(info, fixCrc8(f, headerLength(f))), "coded number");

        f = FlacWriter.frame(FrameSpec.of(16, m).number(0));
        f[4] = (byte) 0xBF; // a continuation byte cannot lead
        assertMessageContains(decodeFails(info, f), "coded number");
        f[4] = (byte) 0xFF;
        assertMessageContains(decodeFails(info, f), "coded number");

        f = FlacWriter.frame(FrameSpec.of(16, m).variable(1L << 33)); // 7-byte sample number
        f[1] = (byte) 0xF8; // now claims fixed blocking, whose frame numbers end at 31 bits
        assertMessageContains(decodeFails(info, fixCrc8(f, headerLength(f))), "coded number");
    }

    @Test
    void frameMustMatchTheStream() {
        int[] m = noise(16, 256, 210);
        byte[] stereo = FlacWriter.frame(FrameSpec.of(16, m, m));
        assertMessageContains(decodeFails(info(1, 16), stereo), "channel");
        byte[] mono24 = FlacWriter.frame(FrameSpec.of(24, m));
        assertMessageContains(decodeFails(info(1, 16), mono24), "bit");
    }

    @Test
    void streamInfoIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> new FlacFrameDecoder(info(0, 16)));
        assertThrows(IllegalArgumentException.class, () -> new FlacFrameDecoder(info(9, 16)));
        assertThrows(IllegalArgumentException.class, () -> new FlacFrameDecoder(info(2, 3)));
        assertThrows(IllegalArgumentException.class, () -> new FlacFrameDecoder(info(2, 33)));
        assertThrows(IllegalArgumentException.class,
                () -> new FlacStreamInfo(16, 4096, 0, 0, 44100, 2, 16, 0, new byte[15]));
        byte[] md5 = new byte[16];
        FlacStreamInfo i = new FlacStreamInfo(16, 4096, 0, 0, 44100, 2, 16, 0, md5);
        md5[0] = 1;
        assertEquals(0, i.md5()[0], "the record keeps its own copy");
        i.md5()[1] = 1;
        assertEquals(0, i.md5()[1]);
    }

    @Test
    void streamInfoComparesTheMd5ByContent() {
        FlacStreamInfo a = new FlacStreamInfo(16, 4096, 14, 9000, 96000, 2, 24, 123456789L, new byte[16]);
        FlacStreamInfo b = new FlacStreamInfo(16, 4096, 14, 9000, 96000, 2, 24, 123456789L, new byte[16]);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("FlacStreamInfo[minBlock=16, maxBlock=4096, minFrame=14, maxFrame=9000, sampleRate=96000, "
                + "channels=2, bitsPerSample=24, totalSamples=123456789, md5=00000000000000000000000000000000]",
                a.toString());
        byte[] md5 = new byte[16];
        md5[15] = (byte) 0xAB;
        FlacStreamInfo c = new FlacStreamInfo(16, 4096, 14, 9000, 96000, 2, 24, 123456789L, md5);
        assertNotEquals(a, c);
        assertTrue(c.toString().endsWith("md5=000000000000000000000000000000ab]"), c::toString);
        assertNotEquals(a, new FlacStreamInfo(16, 4096, 14, 9000, 96000, 2, 24, 123456788L, new byte[16]));
    }

    // ---- invalid subframes ----------------------------------------------------------------------------

    @Test
    void reservedSubframeTypesAreRejected() {
        int[] m = noise(8, 256, 220);
        byte[] good = FlacWriter.frame(FrameSpec.of(8, m).kind(VERBATIM));
        int sub = headerLength(good); // the subframe header byte: padding bit, 6-bit type, wasted flag
        for (int type = 2; type < 32; type++) {
            if (type >= 8 && type <= 12) {
                continue;
            }
            byte[] f = good.clone();
            f[sub] = (byte) (type << 1);
            assertMessageContains(decodeFails(info(1, 8), fixCrc16(f)), "subframe");
        }
        byte[] f = good.clone();
        f[sub] |= (byte) 0x80; // the zero padding bit
        assertMessageContains(decodeFails(info(1, 8), fixCrc16(f)), "subframe");
    }

    @Test
    void forbiddenLpcFieldsAreRejected() {
        int[] m = smooth(16, 1024, 230);
        DecodeException precision = decodeFails(info(1, 16),
                FlacWriter.frame(FrameSpec.of(16, m).kind(LPC(2, new int[] {3, -1}, 16, 1))));
        assertMessageContains(precision, "precision");
        DecodeException shift = decodeFails(info(1, 16),
                FlacWriter.frame(FrameSpec.of(16, m).kind(LPC(2, new int[] {3, -1}, 4, -1))));
        assertMessageContains(shift, "shift");
    }

    @Test
    void invalidResidualsAreRejected() {
        FlacStreamInfo info = info(1, 8);
        int[] m = smooth(8, 255, 240);
        byte[] good = FlacWriter.frame(FrameSpec.of(8, m).kind(FIXED(0)));
        int res = headerLength(good) + 1; // FIXED order 0: the residual starts right after the subframe header
        for (int method = 2; method <= 3; method++) {
            byte[] f = good.clone();
            f[res] = (byte) ((f[res] & 0x3F) | (method << 6));
            assertMessageContains(decodeFails(info, fixCrc16(f)), "residual");
        }
        byte[] odd = good.clone(); // block size 255 cannot be split into two partitions
        odd[res] = (byte) ((odd[res] & 0xC3) | (1 << 2));
        assertMessageContains(decodeFails(info, fixCrc16(odd)), "partition");

        // 16 >> 2 = 4 samples per partition, all of the first taken by the order-4 warm-up. RFC 9639 §9.2.7 asks
        // encoders for partitions larger than the order, but an empty first partition decodes unambiguously.
        int[] s = smooth(8, 16, 241);
        byte[] fixed4 = FlacWriter.frame(FrameSpec.of(8, s).kind(FIXED(4)).partitionOrder(2));
        assertArrayEquals(s, assertDoesNotThrowDecode(info, fixed4), "first partition of zero samples");
        int r4 = headerLength(fixed4) + 1 + 4; // after the subframe header and four 8-bit warm-up samples
        fixed4[r4] = (byte) ((fixed4[r4] & 0xC3) | (3 << 2)); // 16 >> 3 = 2 samples per partition < order 4
        assertMessageContains(decodeFails(info, fixCrc16(fixed4)), "partition");

        int[] three = {1, 2, 3};
        byte[] tiny = FlacWriter.frame(FrameSpec.of(8, three).kind(VERBATIM));
        int sub = headerLength(tiny);
        tiny[sub] = (byte) (12 << 1); // FIXED order 4 in a block of 3 samples
        assertMessageContains(decodeFails(info, fixCrc16(tiny)), "order");
    }

    private static int[] assertDoesNotThrowDecode(FlacStreamInfo info, byte[] frame) {
        try {
            return decode(info, frame);
        } catch (DecodeException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void wastedBitsMustLeaveSampleBits() {
        byte[] f = FlacWriter.frame(FrameSpec.of(8, new int[64]).kind(CONSTANT).wasted(8));
        assertMessageContains(decodeFails(info(1, 8), f), "wasted");
    }

    @Test
    void samplesOutsideTheBitDepthAreRejected() {
        int[] m = new int[64];
        m[1] = 40000; // does not fit 16 bits; the residual carries it
        byte[] f = FlacWriter.frame(FrameSpec.of(16, m).kind(FIXED(1)).unchecked().number(9));
        DecodeException e = decodeFails(info(1, 16), f);
        assertMessageContains(e, "range");
        assertMessageContains(e, "frame 9");
    }

    // ---- whole files (FlacWriter.writeFile, reused by the stream decoder's tests) ---------------------

    @Test
    void writtenFileDecodesFrameByFrame(@TempDir Path dir) throws Exception {
        int rate = 96000;
        int frames = 250000; // 2.6 s: 61 frames of 4096 and a last one of 144
        int[] l = smooth(24, frames, 300);
        int[] r = smooth(24, frames, 301);
        int[] samples = interleave(l, r);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("TITLE", "Ünïcödé title");
        tags.put("ARTIST", "First\0Second");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        Path p = FlacWriter.writeFile(dir, "t.flac", rate, 24, 2, samples, 4096, FIXED(2), MID_SIDE, true, tags, png);
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p));
        assertEquals("fLaC", new String(b.array(), 0, 4, StandardCharsets.US_ASCII));
        b.position(4);

        FlacStreamInfo info = null;
        List<Integer> types = new ArrayList<>();
        List<long[]> seekPoints = new ArrayList<>();
        boolean last = false;
        while (!last) {
            int head = b.get() & 0xFF;
            last = (head & 0x80) != 0;
            int type = head & 0x7F;
            int length = ((b.get() & 0xFF) << 16) | ((b.get() & 0xFF) << 8) | (b.get() & 0xFF);
            int start = b.position();
            types.add(type);
            switch (type) {
                case 0 -> {
                    assertEquals(34, length);
                    long a = b.getLong(); // min block 16, max block 16, min frame 24, first 8 bits of max frame
                    long c = b.getLong(); // rest of max frame 16, rate 20, channels 3, bps 5, total 20 of 36
                    int d = b.getShort() & 0xFFFF; // last 16 bits of total
                    byte[] md5 = new byte[16];
                    b.get(md5);
                    long total = ((c & 0xFFFFF) << 16) | d;
                    info = new FlacStreamInfo((int) (a >>> 48), (int) (a >>> 32) & 0xFFFF, (int) (a >>> 8) & 0xFFFFFF,
                            (int) (((a & 0xFF) << 16) | (c >>> 48)), (int) (c >>> 28) & 0xFFFFF,
                            (int) ((c >>> 25) & 7) + 1, (int) ((c >>> 20) & 31) + 1, total, md5);
                }
                case 3 -> {
                    assertEquals(0, length % 18);
                    for (int i = 0; i < length / 18; i++) {
                        seekPoints.add(new long[] {b.getLong(), b.getLong(), b.getShort() & 0xFFFF});
                    }
                }
                case 4 -> {
                    ByteBuffer v = b.slice(start, length).order(ByteOrder.LITTLE_ENDIAN);
                    byte[] vendor = new byte[v.getInt()];
                    v.get(vendor);
                    assertEquals("Purist FlacWriter", new String(vendor, StandardCharsets.UTF_8));
                    List<String> comments = new ArrayList<>();
                    for (int i = v.getInt(); i > 0; i--) {
                        byte[] c = new byte[v.getInt()];
                        v.get(c);
                        comments.add(new String(c, StandardCharsets.UTF_8));
                    }
                    assertEquals(List.of("TITLE=Ünïcödé title", "ARTIST=First", "ARTIST=Second"), comments);
                }
                case 6 -> {
                    assertEquals(3, b.getInt());
                    byte[] mime = new byte[b.getInt()];
                    b.get(mime);
                    assertEquals("image/png", new String(mime, StandardCharsets.US_ASCII));
                    assertEquals(0, b.getInt()); // description
                    b.position(b.position() + 16);
                    byte[] data = new byte[b.getInt()];
                    b.get(data);
                    assertArrayEquals(png, data);
                }
                case 1 -> assertTrue(last, "padding is the last block");
                default -> throw new AssertionError("block type " + type);
            }
            b.position(start + length);
        }
        assertEquals(List.of(0, 3, 4, 6, 1), types);
        assertEquals(new FlacStreamInfo(4096, 4096, info.minFrame(), info.maxFrame(), rate, 2, 24, frames, info.md5()),
                info);

        int firstFrame = b.position();
        byte[] file = b.array();
        FlacFrameDecoder d = new FlacFrameDecoder(info);
        int[] out = new int[4096 * 2];
        int[] decoded = new int[samples.length];
        int pos = firstFrame;
        int at = 0;
        int minFrame = Integer.MAX_VALUE;
        int maxFrame = 0;
        List<long[]> frameStarts = new ArrayList<>(); // {sample, offset, samples}
        for (long number = 0; pos < file.length; number++) {
            int used = d.decode(file, pos, file.length - pos, out);
            FlacFrameHeader h = d.lastHeader();
            assertEquals(number, h.codedNumber());
            assertFalse(h.variableBlocksize());
            frameStarts.add(new long[] {at / 2, pos - firstFrame, h.blockSize()});
            System.arraycopy(out, 0, decoded, at, h.blockSize() * 2);
            at += h.blockSize() * 2;
            pos += used;
            minFrame = Math.min(minFrame, used);
            maxFrame = Math.max(maxFrame, used);
        }
        assertArrayEquals(samples, decoded);
        assertEquals(minFrame, info.minFrame());
        assertEquals(maxFrame, info.maxFrame());

        MessageDigest md5 = MessageDigest.getInstance("MD5");
        for (int v : decoded) {
            md5.update(new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16)});
        }
        assertArrayEquals(md5.digest(), info.md5());

        // Seek points {sample, offset from the first frame, samples}: the first frame to start in each second
        // (frames 0, 24 and 47: samples 0, 98304 and 192512), then one placeholder.
        assertEquals(4, seekPoints.size());
        assertArrayEquals(new long[] {0, 0, 4096}, seekPoints.get(0));
        assertEquals(98304, seekPoints.get(1)[0]);
        assertArrayEquals(frameStarts.get(24), seekPoints.get(1));
        assertEquals(192512, seekPoints.get(2)[0]);
        assertArrayEquals(frameStarts.get(47), seekPoints.get(2));
        assertArrayEquals(new long[] {-1L, 0, 0}, seekPoints.get(3));
    }

    @Test
    void writtenFileWithoutOptionalBlocks(@TempDir Path dir) throws IOException {
        int[] m = smooth(16, 1000, 310);
        Path p = FlacWriter.writeFile(dir, "plain.flac", 44100, 16, 1, m, 192, VERBATIM, INDEPENDENT, false, null,
                null);
        byte[] file = Files.readAllBytes(p);
        assertEquals(0, file[4] & 0x7F, "STREAMINFO first");
        int next = 4 + 4 + 34;
        assertEquals(0x81, file[next] & 0xFF, "then only the last-block PADDING");
        int firstFrame = next + 4 + 16;
        FlacFrameDecoder d = new FlacFrameDecoder(info(1, 16));
        int[] out = new int[192];
        int[] decoded = new int[1000];
        int pos = firstFrame;
        int at = 0;
        while (pos < file.length) {
            pos += d.decode(file, pos, file.length - pos, out);
            System.arraycopy(out, 0, decoded, at, d.lastHeader().blockSize());
            at += d.lastHeader().blockSize();
        }
        assertArrayEquals(m, decoded);
        assertEquals(1000 - 5 * 192, d.lastHeader().blockSize(), "a short last frame");
    }
}
