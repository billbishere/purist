package purist.decode.aiff;

import java.lang.foreign.MemorySegment;
import purist.decode.PcmFileDecoder;
import purist.decode.io.FileSource;

/**
 * Reads the samples of an integer-PCM AIFF or AIFF-C exactly. Each sample is taken from its container
 * ({@code ceil(bits / 8)} bytes, big-endian, or little-endian for {@code sowt}), shifted right arithmetically
 * past the container padding (AIFF left-justifies samples whose bit count is not a multiple of eight) and stored
 * as a sign-extended {@code int32}. AIFF samples are signed at every depth, 8-bit included, so nothing is
 * re-centred.
 *
 * <p>The reading, seeking and closing are {@link PcmFileDecoder}'s; this class only converts the samples.
 */
final class AiffDecoder extends PcmFileDecoder {

    private final int containerBytes;
    private final int shift;
    /** Index, within one sample, of its most significant byte; the rest follow at {@link #step}. */
    private final int lead;
    private final int step;

    AiffDecoder(FileSource src, AiffHandler.AiffFile aiff) {
        super(src, aiff.format(), aiff.blockAlign(), aiff.dataOffset());
        this.containerBytes = aiff.containerBytes();
        this.shift = containerBytes * 8 - aiff.format().bits();
        this.lead = aiff.littleEndian() ? containerBytes - 1 : 0;
        this.step = aiff.littleEndian() ? -1 : 1;
    }

    @Override
    protected void convert(byte[] c, int frames, MemorySegment dst, long dstOffset) {
        final int samples = frames * format().channels();
        final int s = shift;
        final int step1 = step;
        final int step2 = 2 * step;
        final int step3 = 3 * step;
        final int width = containerBytes;
        long out = dstOffset;
        switch (width) {
            case 1 -> {
                for (int i = 0; i < samples; i++, out += 4) {
                    dst.set(LE_INT, out, c[i] >> s);
                }
            }
            case 2 -> {
                for (int i = 0, j = lead; i < samples; i++, j += width, out += 4) {
                    dst.set(LE_INT, out, (c[j] << 8 | (c[j + step1] & 0xFF)) >> s);
                }
            }
            case 3 -> {
                for (int i = 0, j = lead; i < samples; i++, j += width, out += 4) {
                    dst.set(LE_INT, out, (c[j] << 16 | (c[j + step1] & 0xFF) << 8 | (c[j + step2] & 0xFF)) >> s);
                }
            }
            case 4 -> {
                for (int i = 0, j = lead; i < samples; i++, j += width, out += 4) {
                    dst.set(LE_INT, out,
                            (c[j] << 24 | (c[j + step1] & 0xFF) << 16 | (c[j + step2] & 0xFF) << 8
                                    | (c[j + step3] & 0xFF)) >> s);
                }
            }
            default -> throw new IllegalStateException("unexpected container size: " + width);
        }
    }
}
