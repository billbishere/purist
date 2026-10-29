package purist.audio;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * The only place sample bits are transformed between decoder and DAC. Every transform here is exactly
 * lossless (spec section 5): PCM is placed in the driver's integer container by a left shift (zero-padding
 * the LSBs), DSD bytes are bit-reversed when the bit orders differ, the interleaved stream is split into
 * per-channel buffers, and a mono source is copied to both channels. A pairing that would need anything
 * else is refused by {@link #create}.
 *
 * <p>Input is what the decoders emit: PCM as interleaved little-endian int32 with each sample
 * right-aligned and sign-extended; DSD as interleaved bytes, one byte per channel per frame, in
 * {@link StreamFormat#dsdOrder()}. Output is always exactly two channels.
 *
 * <p>{@link #pack} runs on the decoder thread and allocates nothing. A Packer is immutable and may be
 * shared between threads.
 */
public final class Packer {

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    /** {@code REVERSED[b]} is {@code b} with its 8 bits in the opposite order. */
    private static final byte[] REVERSED = new byte[256];

    static {
        for (int b = 0; b < 256; b++) {
            REVERSED[b] = (byte) reverseBits(b);
        }
    }

    private final Transform transform;
    private final boolean dsd;
    private final boolean stereo;
    private final int srcChannels;
    private final int srcBits;
    private final int containerBytes;

    private Packer(Transform transform, boolean dsd, int srcChannels, int srcBits, int containerBytes) {
        this.transform = transform;
        this.dsd = dsd;
        this.stereo = srcChannels == 2;
        this.srcChannels = srcChannels;
        this.srcBits = srcBits;
        this.containerBytes = containerBytes;
    }

    /**
     * A Packer for {@code src} written as {@code out}, or {@link NotBitPerfectException} saying why the
     * pairing cannot be bit-perfect. The output is always 2 channels.
     */
    public static Packer create(StreamFormat src, SampleType out) throws NotBitPerfectException {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(out, "out");
        if (src.channels() < 1) {
            throw new NotBitPerfectException("unsupported channel count (" + src.channels() + ")");
        }
        if (src.channels() > 2) {
            throw new NotBitPerfectException("multichannel (" + src.channels() + " ch) is not supported in v1");
        }
        boolean mono = src.channels() == 1;

        if (src.kind() == Kind.PCM) {
            if (out.isDsd()) {
                throw new NotBitPerfectException("PCM source cannot go to a DSD output type");
            }
            if (src.bits() < 1 || src.bits() > 32) {
                throw new NotBitPerfectException("unsupported PCM bit depth (" + src.bits() + ")");
            }
            int shift = out.validBits() - src.bits();
            if (shift < 0) {
                throw new NotBitPerfectException(src.bits() + "-bit source does not fit " + out.name());
            }
            return new Packer(new Transform(shift, false, mono), false, src.channels(), src.bits(),
                    out.containerBytes());
        }

        if (!out.isDsd()) {
            throw new NotBitPerfectException(
                    "DSD source needs a DSD output type (driver offered " + out.name() + ")");
        }
        if (src.dsdOrder() == null) {
            throw new NotBitPerfectException("DSD source has no bit order");
        }
        boolean reverse = src.dsdOrder() != out.bitOrder();
        return new Packer(new Transform(0, reverse, mono), true, src.channels(), 1, out.containerBytes());
    }

    public Transform transform() {
        return transform;
    }

    /**
     * Converts {@code frames} frames from {@code src} (interleaved, as the decoders emit) into
     * {@code dst[0]} and {@code dst[1]}, each receiving {@code frames * containerBytes} bytes from offset 0.
     *
     * @throws IllegalArgumentException if a segment is too small, or if a PCM sample does not fit the depth
     *         the source declared. The sample check runs over the whole batch before the exception is
     *         thrown, so {@code dst} then holds truncated audio and must be discarded. A decoder that obeys
     *         its contract never triggers this: it exists so a decoder bug is a loud failure rather than
     *         silently truncated audio.
     */
    public void pack(MemorySegment src, int frames, MemorySegment[] dst) {
        if (frames < 0) {
            throw new IllegalArgumentException("negative frame count: " + frames);
        }
        if (dst.length < 2) {
            throw new IllegalArgumentException("two destination channels are required, got " + dst.length);
        }
        MemorySegment left = dst[0];
        MemorySegment right = dst[1];
        long srcNeeded = (long) frames * (dsd ? srcChannels : 4L * srcChannels);
        long dstNeeded = (long) frames * containerBytes;
        if (src.byteSize() < srcNeeded) {
            throw new IllegalArgumentException(
                    "source holds " + src.byteSize() + " bytes but " + frames + " frames need " + srcNeeded);
        }
        if (left.byteSize() < dstNeeded || right.byteSize() < dstNeeded) {
            throw new IllegalArgumentException("destination channels need " + dstNeeded + " bytes for "
                    + frames + " frames, got " + left.byteSize() + " and " + right.byteSize());
        }
        if (frames == 0) {
            return;
        }

        if (dsd) {
            packDsd(src, frames, left, right);
            return;
        }
        int outOfRange = switch (containerBytes) {
            case 4 -> pack32(src, frames, left, right);
            case 3 -> pack24(src, frames, left, right);
            case 2 -> pack16(src, frames, left, right);
            default -> throw new IllegalStateException("unexpected PCM container: " + containerBytes);
        };
        if (outOfRange != 0) {
            throw new IllegalArgumentException(
                    "a sample does not fit the declared " + srcBits + "-bit depth; refusing to truncate it");
        }
    }

    // The PCM loops return a non-zero value when any sample did not fit srcBits. The check is
    // ((v << s) >> s) ^ v with s = 32 - srcBits: zero exactly when v survives a round trip through
    // srcBits signed bits. It is OR-accumulated so the loop stays branch-free.

    private int pack32(MemorySegment src, int frames, MemorySegment left, MemorySegment right) {
        final int shift = transform.shift();
        final int fit = 32 - srcBits;
        final long stride = 4L * srcChannels;
        int bad = 0;
        long in = 0;
        long out = 0;
        for (int i = 0; i < frames; i++, in += stride, out += 4) {
            int a = src.get(LE_INT, in);
            int b = stereo ? src.get(LE_INT, in + 4) : a;
            bad |= (((a << fit) >> fit) ^ a) | (((b << fit) >> fit) ^ b);
            left.set(LE_INT, out, a << shift);
            right.set(LE_INT, out, b << shift);
        }
        return bad;
    }

    private int pack24(MemorySegment src, int frames, MemorySegment left, MemorySegment right) {
        final int shift = transform.shift();
        final int fit = 32 - srcBits;
        final long stride = 4L * srcChannels;
        int bad = 0;
        long in = 0;
        long out = 0;
        for (int i = 0; i < frames; i++, in += stride, out += 3) {
            int a = src.get(LE_INT, in);
            int b = stereo ? src.get(LE_INT, in + 4) : a;
            bad |= (((a << fit) >> fit) ^ a) | (((b << fit) >> fit) ^ b);
            int pa = a << shift;
            int pb = b << shift;
            left.set(LE_SHORT, out, (short) pa);
            left.set(BYTE, out + 2, (byte) (pa >> 16));
            right.set(LE_SHORT, out, (short) pb);
            right.set(BYTE, out + 2, (byte) (pb >> 16));
        }
        return bad;
    }

    private int pack16(MemorySegment src, int frames, MemorySegment left, MemorySegment right) {
        final int shift = transform.shift();
        final int fit = 32 - srcBits;
        final long stride = 4L * srcChannels;
        int bad = 0;
        long in = 0;
        long out = 0;
        for (int i = 0; i < frames; i++, in += stride, out += 2) {
            int a = src.get(LE_INT, in);
            int b = stereo ? src.get(LE_INT, in + 4) : a;
            bad |= (((a << fit) >> fit) ^ a) | (((b << fit) >> fit) ^ b);
            left.set(LE_SHORT, out, (short) (a << shift));
            right.set(LE_SHORT, out, (short) (b << shift));
        }
        return bad;
    }

    private void packDsd(MemorySegment src, int frames, MemorySegment left, MemorySegment right) {
        final boolean reverse = transform.bitReverse();
        long in = 0;
        for (int i = 0; i < frames; i++, in += srcChannels) {
            byte a = src.get(BYTE, in);
            byte b = stereo ? src.get(BYTE, in + 1) : a;
            if (reverse) {
                a = REVERSED[a & 0xFF];
                b = REVERSED[b & 0xFF];
            }
            left.set(BYTE, i, a);
            right.set(BYTE, i, b);
        }
    }

    /** The low 8 bits of {@code b} in the opposite order, as 0..255: {@code reverseBits(0x01) == 0x80}. */
    public static int reverseBits(int b) {
        return Integer.reverse(b & 0xFF) >>> 24;
    }
}
