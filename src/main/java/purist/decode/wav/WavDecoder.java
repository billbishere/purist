package purist.decode.wav;

import java.lang.foreign.MemorySegment;
import purist.decode.PcmFileDecoder;
import purist.decode.io.FileSource;

/**
 * Reads the samples of an integer-PCM WAV exactly. Each sample is taken from its little-endian container,
 * shifted right arithmetically past the container padding (WAV left-justifies samples whose valid bits are fewer
 * than the container's) and stored as a sign-extended {@code int32}. 8-bit samples are unsigned in the file and
 * are re-centred by subtracting 128, which loses nothing.
 *
 * <p>The reading, seeking and closing are {@link PcmFileDecoder}'s; this class only converts the samples.
 */
final class WavDecoder extends PcmFileDecoder {

    private final int containerBytes;
    private final int shift;

    WavDecoder(FileSource src, WavHandler.WavFile wav) {
        super(src, wav.format(), wav.blockAlign(), wav.dataOffset());
        this.containerBytes = wav.containerBytes();
        this.shift = containerBytes * 8 - wav.format().bits();
    }

    @Override
    protected void convert(byte[] c, int frames, MemorySegment dst, long dstOffset) {
        final int samples = frames * format().channels();
        final int s = shift;
        long out = dstOffset;
        switch (containerBytes) {
            case 1 -> {
                for (int i = 0; i < samples; i++, out += 4) {
                    dst.set(LE_INT, out, ((c[i] & 0xFF) - 128) >> s);
                }
            }
            case 2 -> {
                for (int i = 0, j = 0; i < samples; i++, j += 2, out += 4) {
                    dst.set(LE_INT, out, (c[j + 1] << 8 | (c[j] & 0xFF)) >> s);
                }
            }
            case 3 -> {
                for (int i = 0, j = 0; i < samples; i++, j += 3, out += 4) {
                    dst.set(LE_INT, out, (c[j + 2] << 16 | (c[j + 1] & 0xFF) << 8 | (c[j] & 0xFF)) >> s);
                }
            }
            case 4 -> {
                for (int i = 0, j = 0; i < samples; i++, j += 4, out += 4) {
                    dst.set(LE_INT, out,
                            (c[j + 3] << 24 | (c[j + 2] & 0xFF) << 16 | (c[j + 1] & 0xFF) << 8 | (c[j] & 0xFF)) >> s);
                }
            }
            default -> throw new IllegalStateException("unexpected container size: " + containerBytes);
        }
    }
}
