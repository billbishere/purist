package purist.wasapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;
import purist.audio.BitOrder;
import purist.audio.SampleType;
import purist.audio.StreamCore;
import purist.audio.StreamFormat;

/**
 * {@link Interleaver}: planar {@link StreamCore} channels into the device's interleaved container, and DoP v1.1
 * framing. Every expected byte is written out by hand or computed with plain arithmetic.
 */
class InterleaverTest {

    private static MemorySegment[] planar(byte[] left, byte[] right) {
        return new MemorySegment[] {MemorySegment.ofArray(left.clone()), MemorySegment.ofArray(right.clone())};
    }

    private static byte[] run(Interleaver il, MemorySegment[] planar, int frames, int blockAlign) {
        byte[] out = new byte[frames * blockAlign];
        il.write(planar, MemorySegment.ofArray(out), 0, frames);
        return out;
    }

    // ---- DoP ----------------------------------------------------------------------------------------------

    @Test
    void dopIn24BitContainerPutsMarkerOnTopThenTheEarlierByteThenTheLater() {
        Interleaver il = Interleaver.dop(3);
        byte[] l = {0x11, 0x22, 0x33, 0x44};
        byte[] r = {(byte) 0xA1, (byte) 0xA2, (byte) 0xA3, (byte) 0xA4};
        byte[] out = run(il, planar(l, r), 2, 6);
        assertArrayEquals(new byte[] {
            // frame 0, marker 0x05: L = 05 11 22, R = 05 A1 A2, little-endian
            0x22, 0x11, 0x05, (byte) 0xA2, (byte) 0xA1, 0x05,
            // frame 1, marker 0xFA
            0x44, 0x33, (byte) 0xFA, (byte) 0xA4, (byte) 0xA3, (byte) 0xFA,
        }, out);
    }

    @Test
    void dopIn32BitContainerIsLeftJustifiedWithAZeroLowByte() {
        Interleaver il = Interleaver.dop(4);
        byte[] out = run(il, planar(new byte[] {0x11, 0x22}, new byte[] {0x33, 0x44}), 1, 8);
        assertArrayEquals(new byte[] {0, 0x22, 0x11, 0x05, 0, 0x44, 0x33, 0x05}, out);
    }

    @Test
    void dopMarkersAlternateAcrossCallsWhateverTheirLength() {
        Interleaver il = Interleaver.dop(3);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        int total = 0;
        for (int frames : new int[] {3, 1, 4, 5}) {
            byte[] zeros = new byte[2 * frames];
            all.writeBytes(run(il, planar(zeros, zeros), frames, 6));
            total += frames;
        }
        byte[] out = all.toByteArray();
        for (int i = 0; i < total; i++) {
            int marker = i % 2 == 0 ? 0x05 : 0xFA;
            assertEquals(marker, out[i * 6 + 2] & 0xFF, "frame " + i + " left");
            assertEquals(marker, out[i * 6 + 5] & 0xFF, "frame " + i + " right: the same marker on all channels");
        }
    }

    @Test
    void dopEndToEndFromAnLsbFirstDsfSourceIsBitExact() throws Exception {
        // DSF bytes are LSB-first; DoP plays the MSB first, so each byte arrives bit-reversed, and nothing else.
        StreamFormat dsf = StreamFormat.dsd(2822400, 2, -1, BitOrder.LSB_FIRST);
        int bytes = 64;
        byte[] src = new byte[bytes * 2];
        for (int i = 0; i < src.length; i++) {
            src[i] = (byte) (i * 37 + 5);
        }
        try (StreamCore core = new StreamCore(SampleType.DSD_INT8_MSB1, 32, 1024); Arena a = Arena.ofConfined()) {
            core.setSource(dsf);
            core.write(MemorySegment.ofArray(src), bytes);
            MemorySegment[] pl = {a.allocate(32), a.allocate(32)};
            Interleaver il = Interleaver.dop(4);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (int fill = 0; fill < 4; fill++) { // 4 fills of 16 DoP frames = 64 DSD bytes per channel
                core.fill(pl, 32);
                byte[] out = new byte[16 * 8];
                il.write(pl, MemorySegment.ofArray(out), 0, 16);
                all.writeBytes(out);
            }
            byte[] out = all.toByteArray();
            for (int f = 0; f < 32; f++) {
                for (int ch = 0; ch < 2; ch++) {
                    int word = MemorySegment.ofArray(out).get(ValueLayout.JAVA_INT_UNALIGNED
                            .withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), (f * 2L + ch) * 4);
                    int first = Integer.reverse(src[(2 * f) * 2 + ch] & 0xFF) >>> 24;
                    int second = Integer.reverse(src[(2 * f + 1) * 2 + ch] & 0xFF) >>> 24;
                    int marker = f % 2 == 0 ? 0x05 : 0xFA;
                    assertEquals(marker << 24 | first << 16 | second << 8, word, "frame " + f + " ch " + ch);
                }
            }
        }
    }

    @Test
    void dopRejectsContainersThatCannotHold24Bits() {
        assertThrows(IllegalArgumentException.class, () -> Interleaver.dop(2));
    }

    // ---- PCM ----------------------------------------------------------------------------------------------

    @Test
    void pcm16Interleaves() {
        Interleaver il = Interleaver.pcm(2);
        byte[] out = run(il, planar(new byte[] {1, 2, 3, 4}, new byte[] {5, 6, 7, 8}), 2, 4);
        assertArrayEquals(new byte[] {1, 2, 5, 6, 3, 4, 7, 8}, out);
    }

    @Test
    void pcm24Interleaves() {
        Interleaver il = Interleaver.pcm(3);
        byte[] out = run(il, planar(new byte[] {1, 2, 3, 4, 5, 6}, new byte[] {7, 8, 9, 10, 11, 12}), 2, 6);
        assertArrayEquals(new byte[] {1, 2, 3, 7, 8, 9, 4, 5, 6, 10, 11, 12}, out);
    }

    @Test
    void pcm32InterleavesAtAnOffset() {
        Interleaver il = Interleaver.pcm(4);
        byte[] out = new byte[3 + 8];
        il.write(planar(new byte[] {1, 2, 3, 4}, new byte[] {5, 6, 7, 8}), MemorySegment.ofArray(out), 3, 1);
        assertArrayEquals(new byte[] {0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8}, out);
    }
}
