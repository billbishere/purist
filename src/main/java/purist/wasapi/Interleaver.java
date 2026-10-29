package purist.wasapi;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Turns the two planar channels {@link purist.audio.StreamCore#fill} produces into the interleaved frames a WASAPI
 * buffer holds. No sample bit is changed: PCM containers are copied whole (the core already placed each sample in
 * its container), and DoP only adds markers around DSD bytes.
 *
 * <p><b>DoP v1.1.</b> The core runs as {@code DSD_INT8_MSB1}: one DSD byte per channel per core frame, first bit in
 * the MSB (a DSF source has already been bit-reversed by the {@link purist.audio.Packer}). Each device frame takes
 * the next two bytes of each channel and sends, per channel, the 24-bit word {@code marker, earlier byte, later
 * byte}, the marker being {@code 0x05} and {@code 0xFA} on alternate frames and the same on every channel of a
 * frame. In a 32-bit container the word is left-justified over a zero low byte. The alternation runs on across
 * calls and buffers, padding and pauses included (padding is the idle pattern {@code 0x69}, so a pause is a
 * properly framed DoP idle stream).
 *
 * <p>{@link #write} allocates nothing and takes no lock. One thread at a time: the render thread, or the control
 * thread while the render thread is idle.
 */
final class Interleaver {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final int MARKER_A = 0x05;
    private static final int MARKER_B = 0xFA;

    private final boolean dop;
    private final int containerBytes;
    /** DoP only: the marker for the next frame. */
    private int marker = MARKER_A;

    private Interleaver(boolean dop, int containerBytes) {
        this.dop = dop;
        this.containerBytes = containerBytes;
    }

    /** PCM: copies each channel's {@code containerBytes}-byte samples (2, 3 or 4) into interleaved frames. */
    static Interleaver pcm(int containerBytes) {
        if (containerBytes < 2 || containerBytes > 4) {
            throw new IllegalArgumentException("unsupported PCM container: " + containerBytes + " bytes");
        }
        return new Interleaver(false, containerBytes);
    }

    /** DoP into a 3- or 4-byte container. */
    static Interleaver dop(int containerBytes) {
        if (containerBytes != 3 && containerBytes != 4) {
            throw new IllegalArgumentException("DoP needs a 24- or 32-bit container, not " + containerBytes + " bytes");
        }
        return new Interleaver(true, containerBytes);
    }

    /** Core frames {@link #write} reads per device frame: 2 for DoP (16 DSD bits), else 1. */
    int coreFramesPerFrame() {
        return dop ? 2 : 1;
    }

    /**
     * Writes {@code frames} stereo device frames at {@code dst + offset}, reading
     * {@code frames × coreFramesPerFrame()} core frames from offset 0 of {@code planar[0]} and {@code planar[1]}.
     */
    void write(MemorySegment[] planar, MemorySegment dst, long offset, int frames) {
        MemorySegment l = planar[0];
        MemorySegment r = planar[1];
        if (dop) {
            writeDop(l, r, dst, offset, frames);
            return;
        }
        long o = offset;
        switch (containerBytes) {
            case 4 -> {
                for (long i = 0; i < frames; i++, o += 8) {
                    dst.set(LE_INT, o, l.get(LE_INT, i * 4));
                    dst.set(LE_INT, o + 4, r.get(LE_INT, i * 4));
                }
            }
            case 3 -> {
                for (long i = 0; i < frames; i++, o += 6) {
                    long s = i * 3;
                    dst.set(LE_SHORT, o, l.get(LE_SHORT, s));
                    dst.set(BYTE, o + 2, l.get(BYTE, s + 2));
                    dst.set(LE_SHORT, o + 3, r.get(LE_SHORT, s));
                    dst.set(BYTE, o + 5, r.get(BYTE, s + 2));
                }
            }
            default -> {
                for (long i = 0; i < frames; i++, o += 4) {
                    dst.set(LE_SHORT, o, l.get(LE_SHORT, i * 2));
                    dst.set(LE_SHORT, o + 2, r.get(LE_SHORT, i * 2));
                }
            }
        }
    }

    private void writeDop(MemorySegment l, MemorySegment r, MemorySegment dst, long offset, int frames) {
        int m = marker;
        long o = offset;
        boolean wide = containerBytes == 4;
        for (long i = 0; i < frames; i++) {
            long s = i * 2;
            int word0 = m << 16 | (l.get(BYTE, s) & 0xFF) << 8 | l.get(BYTE, s + 1) & 0xFF;
            int word1 = m << 16 | (r.get(BYTE, s) & 0xFF) << 8 | r.get(BYTE, s + 1) & 0xFF;
            if (wide) {
                dst.set(LE_INT, o, word0 << 8);
                dst.set(LE_INT, o + 4, word1 << 8);
                o += 8;
            } else {
                dst.set(LE_SHORT, o, (short) word0);
                dst.set(BYTE, o + 2, (byte) (word0 >>> 16));
                dst.set(LE_SHORT, o + 3, (short) word1);
                dst.set(BYTE, o + 5, (byte) (word1 >>> 16));
                o += 6;
            }
            m = m == MARKER_A ? MARKER_B : MARKER_A;
        }
        marker = m;
    }
}
