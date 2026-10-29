package purist.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PackerTest {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // ---- helpers -----------------------------------------------------------------------------------

    /** A Packer plus the container size of its output type, so tests can size the destination buffers. */
    private record Rig(Packer packer, int containerBytes) {

        Transform transform() {
            return packer.transform();
        }

        Out pack(MemorySegment src, int frames) {
            byte[] l = new byte[frames * containerBytes];
            byte[] r = new byte[frames * containerBytes];
            packer.pack(src, frames, new MemorySegment[] {MemorySegment.ofArray(l), MemorySegment.ofArray(r)});
            return new Out(l, r);
        }
    }

    private record Out(byte[] left, byte[] right) {
    }

    private static Rig rig(StreamFormat src, SampleType out) throws NotBitPerfectException {
        return new Rig(Packer.create(src, out), out.containerBytes());
    }

    private static String refusal(StreamFormat src, SampleType out) {
        return assertThrows(NotBitPerfectException.class, () -> Packer.create(src, out)).getMessage();
    }

    /** Interleaved little-endian int32 PCM, as the decoders emit it. */
    private static MemorySegment pcmSrc(int... interleaved) {
        MemorySegment seg = MemorySegment.ofArray(new byte[interleaved.length * 4]);
        for (int i = 0; i < interleaved.length; i++) {
            seg.set(LE_INT, i * 4L, interleaved[i]);
        }
        return seg;
    }

    /** Interleaved DSD bytes, one byte per channel per frame. */
    private static MemorySegment dsdSrc(int... interleaved) {
        return MemorySegment.ofArray(bytes(interleaved));
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static int leInt(byte[] b, int index) {
        return MemorySegment.ofArray(b).get(LE_INT, index * 4L);
    }

    // ---- PCM packing ---------------------------------------------------------------------------------

    @Test
    void pcm16IntoInt32LeftJustifies() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT32_LSB);
        // frame 0 = (0x1234, -1), frame 1 = (-1, 0x1234)
        Out o = r.pack(pcmSrc(0x1234, -1, -1, 0x1234), 2);
        assertArrayEquals(bytes(0x00, 0x00, 0x34, 0x12, 0x00, 0x00, 0xFF, 0xFF), o.left());
        assertArrayEquals(bytes(0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00, 0x34, 0x12), o.right());
        assertEquals(16, r.transform().shift());
        assertEquals("zero-pad 16 LSBs", r.transform().describe());
    }

    @Test
    void pcm24IntoInt32() throws Exception {
        Rig r = rig(StreamFormat.pcm(96000, 24, 2, 0), SampleType.INT32_LSB);
        Out o = r.pack(pcmSrc(0x7FFFFF, -8388608, -8388608, 0x7FFFFF), 2);
        assertArrayEquals(bytes(0x00, 0xFF, 0xFF, 0x7F, 0x00, 0x00, 0x00, 0x80), o.left());
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x80, 0x00, 0xFF, 0xFF, 0x7F), o.right());
        assertEquals(8, r.transform().shift());
        assertEquals("zero-pad 8 LSBs", r.transform().describe());
    }

    @Test
    void pcm24IntoInt32Lsb24RightAligned() throws Exception {
        Rig r = rig(StreamFormat.pcm(96000, 24, 2, 0), SampleType.INT32_LSB24);
        Out o = r.pack(pcmSrc(0x123456, -1, -1, 0x123456), 2);
        assertArrayEquals(bytes(0x56, 0x34, 0x12, 0x00, 0xFF, 0xFF, 0xFF, 0xFF), o.left());
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0x56, 0x34, 0x12, 0x00), o.right());
        assertTrue(r.transform().isPassThrough());
    }

    @Test
    void pcm16IntoInt24() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT24_LSB);
        Out o = r.pack(pcmSrc(0x1234, -1, -1, 0x1234), 2);
        assertArrayEquals(bytes(0x00, 0x34, 0x12, 0x00, 0xFF, 0xFF), o.left());
        assertArrayEquals(bytes(0x00, 0xFF, 0xFF, 0x00, 0x34, 0x12), o.right());
        assertEquals(8, r.transform().shift());
    }

    @Test
    void pcm24IntoInt24IsAPlainThreeByteCopy() throws Exception {
        Rig r = rig(StreamFormat.pcm(96000, 24, 2, 0), SampleType.INT24_LSB);
        assertTrue(r.transform().isPassThrough());
        Out o = r.pack(pcmSrc(0x123456, -1, 0x7FFFFF, -8388608), 2);
        assertArrayEquals(bytes(0x56, 0x34, 0x12, 0xFF, 0xFF, 0x7F), o.left());
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x80), o.right());
    }

    @Test
    void pcm16IntoInt16IsAPlainTwoByteCopy() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT16_LSB);
        assertTrue(r.transform().isPassThrough());
        Out o = r.pack(pcmSrc(0x1234, -1, -32768, 32767), 2);
        assertArrayEquals(bytes(0x34, 0x12, 0x00, 0x80), o.left());
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0x7F), o.right());
    }

    @Test
    void pcm32IntoInt32PassThrough() throws Exception {
        Rig r = rig(StreamFormat.pcm(192000, 32, 2, 0), SampleType.INT32_LSB);
        assertTrue(r.transform().isPassThrough());
        assertEquals("pass-through", r.transform().describe());
        Out o = r.pack(pcmSrc(Integer.MIN_VALUE, 0x12345678, Integer.MAX_VALUE, -1), 2);
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x80, 0xFF, 0xFF, 0xFF, 0x7F), o.left());
        assertArrayEquals(bytes(0x78, 0x56, 0x34, 0x12, 0xFF, 0xFF, 0xFF, 0xFF), o.right());
    }

    @Test
    void shallowerContainersJustShiftLess() throws Exception {
        // A 16-bit sample right-aligned into each Int32LSBn container, sign-extended.
        SampleType[] types = {SampleType.INT32_LSB16, SampleType.INT32_LSB18, SampleType.INT32_LSB20,
                SampleType.INT32_LSB24};
        int[] shifts = {0, 2, 4, 8};
        for (int i = 0; i < types.length; i++) {
            Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), types[i]);
            assertEquals(shifts[i], r.transform().shift(), types[i].name());
            Out o = r.pack(pcmSrc(0x1234, -2), 1);
            assertEquals(0x1234 << shifts[i], leInt(o.left(), 0), types[i].name());
            assertEquals(-2 << shifts[i], leInt(o.right(), 0), types[i].name());
        }
    }

    @Test
    void roundTripExhaustive16() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT32_LSB);
        int[] interleaved = new int[65536 * 2];
        for (int i = 0; i < 65536; i++) {
            interleaved[2 * i] = i - 32768;       // left walks every 16-bit value up
            interleaved[2 * i + 1] = 32767 - i;   // right walks them down
        }
        Out o = r.pack(pcmSrc(interleaved), 65536);
        for (int i = 0; i < 65536; i++) {
            assertEquals(i - 32768, leInt(o.left(), i) >> 16, "left " + (i - 32768));
            assertEquals(32767 - i, leInt(o.right(), i) >> 16, "right " + (32767 - i));
            assertEquals(0, leInt(o.left(), i) & 0xFFFF, "padding must be zero");
        }
    }

    @Test
    void roundTrip24AtTheEdgesThroughEveryContainer() throws Exception {
        int[] edges = {-8388608, -8388607, -256, -1, 0, 1, 255, 8388606, 8388607, 0x123456, -0x123456};
        for (SampleType type : new SampleType[] {SampleType.INT32_LSB, SampleType.INT32_LSB24, SampleType.INT24_LSB}) {
            Rig r = rig(StreamFormat.pcm(96000, 24, 2, 0), type);
            int[] interleaved = new int[edges.length * 2];
            for (int i = 0; i < edges.length; i++) {
                interleaved[2 * i] = edges[i];
                interleaved[2 * i + 1] = edges[edges.length - 1 - i];
            }
            Out o = r.pack(pcmSrc(interleaved), edges.length);
            for (int i = 0; i < edges.length; i++) {
                assertEquals(edges[i], decode24(type, o.left(), i), type + " left @" + i);
                assertEquals(edges[edges.length - 1 - i], decode24(type, o.right(), i), type + " right @" + i);
            }
        }
    }

    /** What a DAC does with a 24-bit sample in the given container: recover the signed value. */
    private static int decode24(SampleType type, byte[] channel, int index) {
        if (type.containerBytes() == 4) {
            int raw = leInt(channel, index);
            // INT32_LSB holds the 24 valid bits left-justified, INT32_LSB24 right-aligned.
            return type == SampleType.INT32_LSB ? raw >> 8 : raw;
        }
        int o = index * 3;
        return (channel[o] & 0xFF) | (channel[o + 1] & 0xFF) << 8 | channel[o + 2] << 16;
    }

    @Test
    void zeroFramesWritesNothing() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT32_LSB);
        byte[] l = new byte[4];
        byte[] rr = new byte[4];
        r.packer().pack(pcmSrc(), 0, new MemorySegment[] {MemorySegment.ofArray(l), MemorySegment.ofArray(rr)});
        assertArrayEquals(new byte[4], l);
        assertArrayEquals(new byte[4], rr);
    }

    @Test
    void packOnlyTouchesTheFramesRequested() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT16_LSB);
        byte[] l = bytes(0xAA, 0xAA, 0xAA, 0xAA);
        byte[] rr = bytes(0xBB, 0xBB, 0xBB, 0xBB);
        r.packer().pack(pcmSrc(0x0102, 0x0304, 0x0506, 0x0708), 1,
                new MemorySegment[] {MemorySegment.ofArray(l), MemorySegment.ofArray(rr)});
        assertArrayEquals(bytes(0x02, 0x01, 0xAA, 0xAA), l);
        assertArrayEquals(bytes(0x04, 0x03, 0xBB, 0xBB), rr);
    }

    // ---- refusals ---------------------------------------------------------------------------------------

    @Test
    void pcm24IntoInt16Refused() {
        assertEquals("24-bit source does not fit INT16_LSB",
                refusal(StreamFormat.pcm(96000, 24, 2, 0), SampleType.INT16_LSB));
    }

    @Test
    void deeperSourceThanContainerIsRefusedForEveryNarrowType() {
        assertEquals("32-bit source does not fit INT32_LSB24",
                refusal(StreamFormat.pcm(96000, 32, 2, 0), SampleType.INT32_LSB24));
        assertEquals("24-bit source does not fit INT32_LSB20",
                refusal(StreamFormat.pcm(96000, 24, 2, 0), SampleType.INT32_LSB20));
        assertEquals("20-bit source does not fit INT32_LSB18",
                refusal(StreamFormat.pcm(96000, 20, 2, 0), SampleType.INT32_LSB18));
        assertEquals("17-bit source does not fit INT32_LSB16",
                refusal(StreamFormat.pcm(96000, 17, 2, 0), SampleType.INT32_LSB16));
        assertEquals("32-bit source does not fit INT24_LSB",
                refusal(StreamFormat.pcm(96000, 32, 2, 0), SampleType.INT24_LSB));
    }

    @Test
    void pcmIntoDsdRefused() {
        assertEquals("PCM source cannot go to a DSD output type",
                refusal(StreamFormat.pcm(44100, 16, 2, 0), SampleType.DSD_INT8_MSB1));
        assertEquals("PCM source cannot go to a DSD output type",
                refusal(StreamFormat.pcm(44100, 16, 2, 0), SampleType.DSD_INT8_LSB1));
    }

    @Test
    void dsdIntoPcmRefused() {
        assertEquals("DSD source needs a DSD output type (driver offered INT32_LSB)",
                refusal(StreamFormat.dsd(2822400, 2, 0, BitOrder.MSB_FIRST), SampleType.INT32_LSB));
        assertEquals("DSD source needs a DSD output type (driver offered INT24_LSB)",
                refusal(StreamFormat.dsd(2822400, 2, 0, BitOrder.LSB_FIRST), SampleType.INT24_LSB));
    }

    @Test
    void sixChannelRefused() {
        assertEquals("multichannel (6 ch) is not supported in v1",
                refusal(StreamFormat.pcm(48000, 24, 6, 0), SampleType.INT32_LSB));
        assertEquals("multichannel (6 ch) is not supported in v1",
                refusal(StreamFormat.dsd(2822400, 6, 0, BitOrder.MSB_FIRST), SampleType.DSD_INT8_MSB1));
        assertEquals("multichannel (3 ch) is not supported in v1",
                refusal(StreamFormat.pcm(48000, 16, 3, 0), SampleType.INT32_LSB));
    }

    @Test
    void impossibleSourceFormatsAreRefusedNotGuessedAt() {
        assertEquals("unsupported PCM bit depth (0)",
                refusal(StreamFormat.pcm(44100, 0, 2, 0), SampleType.INT32_LSB));
        assertEquals("unsupported PCM bit depth (33)",
                refusal(StreamFormat.pcm(44100, 33, 2, 0), SampleType.INT32_LSB));
        assertEquals("unsupported channel count (0)",
                refusal(StreamFormat.pcm(44100, 16, 0, 0), SampleType.INT32_LSB));
        assertEquals("DSD source has no bit order",
                refusal(new StreamFormat(Kind.DSD, 2822400, 1, 2, 0, null), SampleType.DSD_INT8_MSB1));
    }

    // ---- bad data and bad arguments ---------------------------------------------------------------------

    @Test
    void aSampleOutsideTheDeclaredDepthIsRefusedNotTruncated() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT32_LSB);
        assertThrows(IllegalArgumentException.class, () -> r.pack(pcmSrc(0, 32768), 1));
        assertThrows(IllegalArgumentException.class, () -> r.pack(pcmSrc(-32769, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> r.pack(pcmSrc(0x12345, 0x12345), 1));
        r.pack(pcmSrc(32767, -32768), 1); // the extremes themselves are fine

        Rig wide = rig(StreamFormat.pcm(96000, 24, 2, 0), SampleType.INT24_LSB);
        assertThrows(IllegalArgumentException.class, () -> wide.pack(pcmSrc(8388608, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> wide.pack(pcmSrc(0, -8388609), 1));
        wide.pack(pcmSrc(8388607, -8388608), 1);

        Rig mono = rig(StreamFormat.pcm(44100, 16, 1, 0), SampleType.INT32_LSB);
        assertThrows(IllegalArgumentException.class, () -> mono.pack(pcmSrc(65536), 1));
    }

    @Test
    void aShortSourceOrDestinationIsRefused() throws Exception {
        Packer p = Packer.create(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT32_LSB);
        MemorySegment[] ok = {MemorySegment.ofArray(new byte[8]), MemorySegment.ofArray(new byte[8])};
        assertThrows(IllegalArgumentException.class, () -> p.pack(pcmSrc(1, 2, 3), 2, ok));
        assertThrows(IllegalArgumentException.class, () -> p.pack(pcmSrc(1, 2, 3, 4), 2,
                new MemorySegment[] {MemorySegment.ofArray(new byte[7]), MemorySegment.ofArray(new byte[8])}));
        assertThrows(IllegalArgumentException.class, () -> p.pack(pcmSrc(1, 2, 3, 4), 2,
                new MemorySegment[] {MemorySegment.ofArray(new byte[8]), MemorySegment.ofArray(new byte[7])}));
        assertThrows(IllegalArgumentException.class, () -> p.pack(pcmSrc(1, 2, 3, 4), 2,
                new MemorySegment[] {MemorySegment.ofArray(new byte[8])}));
        assertThrows(IllegalArgumentException.class, () -> p.pack(pcmSrc(1, 2), -1, ok));
    }

    // ---- DSD and channels --------------------------------------------------------------------------------

    @Test
    void dsdLsbIntoMsbReversesBits() throws Exception {
        assertEquals(0x80, Packer.reverseBits(0x01));
        assertEquals(0x96, Packer.reverseBits(0x69));
        for (int b = 0; b < 256; b++) {
            assertEquals(b, Packer.reverseBits(Packer.reverseBits(b)), "involution at " + b);
            assertTrue(Packer.reverseBits(b) >= 0 && Packer.reverseBits(b) < 256);
        }

        Rig r = rig(StreamFormat.dsd(2822400, 2, 0, BitOrder.LSB_FIRST), SampleType.DSD_INT8_MSB1);
        assertTrue(r.transform().bitReverse());
        assertFalse(r.transform().isPassThrough());
        assertEquals("DSD bit-order reversal", r.transform().describe());

        // Every byte value on both channels.
        int[] interleaved = new int[512];
        for (int i = 0; i < 256; i++) {
            interleaved[2 * i] = i;
            interleaved[2 * i + 1] = 255 - i;
        }
        Out o = r.pack(dsdSrc(interleaved), 256);
        for (int i = 0; i < 256; i++) {
            assertEquals((byte) Packer.reverseBits(i), o.left()[i], "left " + i);
            assertEquals((byte) Packer.reverseBits(255 - i), o.right()[i], "right " + i);
        }
        Out idle = r.pack(dsdSrc(0x01, 0x69), 1);
        assertArrayEquals(bytes(0x80), idle.left());
        assertArrayEquals(bytes(0x96), idle.right());
    }

    @Test
    void dsdMsbIntoLsbAlsoReverses() throws Exception {
        Rig r = rig(StreamFormat.dsd(5644800, 2, 0, BitOrder.MSB_FIRST), SampleType.DSD_INT8_LSB1);
        assertTrue(r.transform().bitReverse());
        Out o = r.pack(dsdSrc(0x69, 0x80), 1);
        assertArrayEquals(bytes(0x96), o.left());
        assertArrayEquals(bytes(0x01), o.right());
    }

    @Test
    void dsdWithMatchingOrderIsACopy() throws Exception {
        for (BitOrder order : BitOrder.values()) {
            SampleType type = order == BitOrder.LSB_FIRST ? SampleType.DSD_INT8_LSB1 : SampleType.DSD_INT8_MSB1;
            Rig r = rig(StreamFormat.dsd(2822400, 2, 0, order), type);
            assertTrue(r.transform().isPassThrough(), order.name());
            assertEquals("pass-through", r.transform().describe());
            Out o = r.pack(dsdSrc(0x69, 0x01, 0xFF, 0x00), 2);
            assertArrayEquals(bytes(0x69, 0xFF), o.left());
            assertArrayEquals(bytes(0x01, 0x00), o.right());
        }
    }

    @Test
    void monoDuplicatesToBothChannels() throws Exception {
        Rig r = rig(StreamFormat.pcm(44100, 16, 1, 0), SampleType.INT32_LSB);
        assertTrue(r.transform().monoDuplicate());
        assertEquals("zero-pad 16 LSBs, mono→both channels", r.transform().describe());
        Out o = r.pack(pcmSrc(0x1234, -1, 0x7FFF), 3);
        byte[] expected = bytes(0x00, 0x00, 0x34, 0x12, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00, 0xFF, 0x7F);
        assertArrayEquals(expected, o.left());
        assertArrayEquals(expected, o.right());

        Rig d = rig(StreamFormat.dsd(2822400, 1, 0, BitOrder.LSB_FIRST), SampleType.DSD_INT8_MSB1);
        assertTrue(d.transform().monoDuplicate());
        assertEquals("DSD bit-order reversal, mono→both channels", d.transform().describe());
        Out od = d.pack(dsdSrc(0x01, 0x69), 2);
        assertArrayEquals(bytes(0x80, 0x96), od.left());
        assertArrayEquals(bytes(0x80, 0x96), od.right());

        Rig m = rig(StreamFormat.pcm(96000, 32, 1, 0), SampleType.INT32_LSB);
        assertEquals("pass-through, mono→both channels", m.transform().describe());
        assertTrue(m.transform().isPassThrough(), "duplicating a channel leaves every sample bit unchanged");
    }

    @Test
    void stereoSourcesAreNotMarkedMono() throws Exception {
        assertFalse(rig(StreamFormat.pcm(44100, 16, 2, 0), SampleType.INT32_LSB).transform().monoDuplicate());
        assertFalse(rig(StreamFormat.dsd(2822400, 2, 0, BitOrder.MSB_FIRST), SampleType.DSD_INT8_MSB1)
                .transform().monoDuplicate());
    }

    // ---- Transform ---------------------------------------------------------------------------------------

    @Test
    void transformDescribe() {
        assertEquals("pass-through", new Transform(0, false, false).describe());
        assertEquals("zero-pad 8 LSBs", new Transform(8, false, false).describe());
        assertEquals("zero-pad 16 LSBs", new Transform(16, false, false).describe());
        assertEquals("DSD bit-order reversal", new Transform(0, true, false).describe());
        assertEquals("pass-through, mono→both channels", new Transform(0, false, true).describe());
        assertTrue(new Transform(0, false, true).isPassThrough());
        assertFalse(new Transform(8, false, false).isPassThrough());
        assertFalse(new Transform(0, true, false).isPassThrough());
    }

    @Test
    void aTransformCanNeverShiftRight() {
        assertThrows(IllegalArgumentException.class, () -> new Transform(-1, false, false));
    }
}
