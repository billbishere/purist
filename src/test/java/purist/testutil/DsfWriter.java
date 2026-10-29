package purist.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes small DSF files for tests, laid out from the DSF specification: little-endian {@code "DSD "}, {@code "fmt "}
 * and {@code "data"} chunks, the audio in blocks of 4096 bytes per channel (channel 0's block, then channel 1's, and
 * so on, the last group zero-padded), and an optional ID3v2 tag at the metadata pointer after the data.
 *
 * <p>Channels are passed as one byte array each, all of the same length; those bytes are what a decoder must give
 * back, interleaved one byte per channel.
 */
public final class DsfWriter {

    public static final int BLOCK = 4096;

    private final int rate;
    private final byte[][] channels;
    private boolean msbFirst;
    private byte[] id3;
    private int formatId;
    private int version = 1;
    private long sampleCount = -1;
    private int blockSize = BLOCK;
    private long declaredDataSize = -1;

    private DsfWriter(int rate, byte[][] channels) {
        this.rate = rate;
        this.channels = channels;
    }

    /** A DSF at this DSD bit rate (2822400 for DSD64) holding these channels. */
    public static DsfWriter dsd(int rate, byte[]... channels) {
        return new DsfWriter(rate, channels);
    }

    /** Marks the samples as MSB-first (bits per sample 8) instead of the usual LSB-first (1). */
    public DsfWriter msbFirst() {
        msbFirst = true;
        return this;
    }

    /** Appends this ID3v2 tag after the data and points the header at it. */
    public DsfWriter id3(byte[] tag) {
        id3 = tag;
        return this;
    }

    public DsfWriter formatId(int id) {
        formatId = id;
        return this;
    }

    /** Overrides the sample count per channel (default: 8 bits for every byte given). */
    public DsfWriter sampleCount(long samples) {
        sampleCount = samples;
        return this;
    }

    /** Overrides the declared data chunk size (header included), without changing what is written. */
    public DsfWriter declaredDataSize(long size) {
        declaredDataSize = size;
        return this;
    }

    public byte[] build() {
        int ch = channels.length;
        int n = channels[0].length;
        int groups = (n + blockSize - 1) / blockSize;
        long samples = sampleCount >= 0 ? sampleCount : 8L * n;
        long dataChunk = 12 + (long) groups * blockSize * ch;
        long metaPtr = id3 == null ? 0 : 28 + 52 + dataChunk;
        long total = 28 + 52 + dataChunk + (id3 == null ? 0 : id3.length);

        ByteBuffer b = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        b.put(ascii("DSD ")).putLong(28).putLong(total).putLong(metaPtr);
        b.put(ascii("fmt ")).putLong(52).putInt(version).putInt(formatId).putInt(ch == 1 ? 1 : 2).putInt(ch)
                .putInt(rate).putInt(msbFirst ? 8 : 1).putLong(samples).putInt(blockSize).putInt(0);
        b.put(ascii("data")).putLong(declaredDataSize >= 0 ? declaredDataSize : dataChunk);
        for (int g = 0; g < groups; g++) {
            for (int c = 0; c < ch; c++) {
                int from = g * blockSize;
                int len = Math.max(Math.min(blockSize, n - from), 0);
                b.put(channels[c], from, len);
                b.put(new byte[blockSize - len]);
            }
        }
        if (id3 != null) {
            b.put(id3);
        }
        return b.array();
    }

    public Path write(Path dir, String name) throws IOException {
        return Files.write(dir.resolve(name), build());
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** {@code n} pseudo-random bytes. */
    public static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new java.util.Random(seed).nextBytes(b);
        return b;
    }

    /** What a decoder must produce: the channels interleaved one byte per channel. */
    public static byte[] interleave(byte[]... channels) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < channels[0].length; i++) {
            for (byte[] c : channels) {
                o.write(c[i]);
            }
        }
        return o.toByteArray();
    }
}
