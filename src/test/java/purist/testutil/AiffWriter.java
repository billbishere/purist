package purist.testutil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes small AIFF and AIFF-C files for tests. Samples are passed as {@code int}s holding the signed,
 * right-aligned values (what a decoder must hand back); the writer left-justifies them into a container of
 * {@code ceil(bits / 8)} bytes exactly as real files do, big-endian unless the compression type is {@code sowt}.
 * Unlike WAV, 8-bit AIFF samples are signed, so there is no special case.
 *
 * <p>Layout: {@code FORM, [FVER], COMM, [unknown chunk], [NAME], [AUTH], SSND, [ID3 ]}. Options move, resize or
 * corrupt individual pieces. The sample rate is encoded with an encoder of its own, independent of the code under
 * test.
 */
public final class AiffWriter {

    /** One writer option; build them with the static factories below. */
    @FunctionalInterface
    public interface AiffOpt {
        void apply(Spec spec);
    }

    /** Everything the options can set. */
    public static final class Spec {
        String compression; // null = plain AIFF, otherwise AIFC with this fourcc
        String compressionName = "not compressed";
        String name;
        String author;
        byte[] id3;
        String id3Id = "ID3 ";
        long commFrames = -1;
        long ssndSize = -1;
        int ssndOffset;
        int junkBytes = -1;
        int commBits = -1;
        int commChannels = -1;
        byte[] rateBytes;
        boolean commAfterSsnd;
        boolean omitSsnd;
        boolean omitComm;
        byte[] nameBytes;
    }

    /** FORM type {@code AIFC} with this compression fourcc ({@code NONE}, {@code twos}, {@code sowt}, {@code fl32}). */
    public static AiffOpt aifc(String compression) {
        return s -> s.compression = compression;
    }

    /** The shorthand for {@code aifc("sowt")}: little-endian samples. */
    public static AiffOpt sowt() {
        return aifc("sowt");
    }

    /** A {@code NAME} chunk holding this text (UTF-8, not NUL-terminated). */
    public static AiffOpt name(String text) {
        return s -> s.name = text;
    }

    /** A {@code NAME} chunk holding exactly these bytes. */
    public static AiffOpt nameBytes(byte[] text) {
        return s -> s.nameBytes = text;
    }

    /** An {@code AUTH} chunk holding this text (UTF-8, not NUL-terminated). */
    public static AiffOpt author(String text) {
        return s -> s.author = text;
    }

    /** An {@code ID3 } chunk holding exactly these bytes. */
    public static AiffOpt id3(byte[] tag) {
        return s -> {
            s.id3 = tag;
            s.id3Id = "ID3 ";
        };
    }

    /** Like {@link #id3} but with the lower-case chunk id {@code id3 }. */
    public static AiffOpt id3Lower(byte[] tag) {
        return s -> {
            s.id3 = tag;
            s.id3Id = "id3 ";
        };
    }

    /** Writes this value as the frame count in {@code COMM} instead of the real one. */
    public static AiffOpt declaredFrames(long frames) {
        return s -> s.commFrames = frames;
    }

    /** Writes this value as the size of the {@code SSND} chunk instead of the real one. */
    public static AiffOpt declaredSsndSize(long size) {
        return s -> s.ssndSize = size;
    }

    /** Puts this many padding bytes between the {@code SSND} header and the samples, and records it as the offset. */
    public static AiffOpt ssndOffset(int offset) {
        return s -> s.ssndOffset = offset;
    }

    /** An unknown {@code JUNK} chunk of this many bytes between {@code COMM} and {@code SSND} (odd sizes get a pad byte). */
    public static AiffOpt junkBeforeSsnd(int bytes) {
        return s -> s.junkBytes = bytes;
    }

    /** Writes this sample size in {@code COMM} while still packing the data at the real depth. */
    public static AiffOpt declaredBits(int bits) {
        return s -> s.commBits = bits;
    }

    /** Writes this channel count in {@code COMM} while still packing the data at the real count. */
    public static AiffOpt declaredChannels(int channels) {
        return s -> s.commChannels = channels;
    }

    /** Writes exactly these ten bytes as the 80-bit sample rate. */
    public static AiffOpt rateBytes(byte[] ten) {
        return s -> s.rateBytes = ten;
    }

    /** Puts {@code SSND} before {@code COMM}, which the format allows. */
    public static AiffOpt commAfterSsnd() {
        return s -> s.commAfterSsnd = true;
    }

    /** Leaves out the {@code SSND} chunk entirely. */
    public static AiffOpt omitSsnd() {
        return s -> s.omitSsnd = true;
    }

    /** Leaves out the {@code COMM} chunk entirely. */
    public static AiffOpt omitComm() {
        return s -> s.omitComm = true;
    }

    private AiffWriter() {}

    /**
     * Writes {@code dir/name} and returns its path.
     *
     * @param bits sample depth, 1 to 64
     * @param interleaved frame-interleaved samples; its length must be a multiple of {@code channels}
     */
    public static Path write(Path dir, String name, int rate, int bits, int channels, int[] interleaved,
            AiffOpt... opts) throws IOException {
        Spec s = new Spec();
        for (AiffOpt o : opts) {
            o.apply(s);
        }
        boolean aifc = s.compression != null;
        boolean little = "sowt".equals(s.compression);
        int cbytes = (bits + 7) / 8;
        byte[] data = samples(interleaved, cbytes, bits, little);
        long realFrames = interleaved.length / channels;

        ByteBuffer comm = be(18 + 4 + 1 + s.compressionName.length() + 1);
        comm.putShort((short) (s.commChannels >= 0 ? s.commChannels : channels));
        comm.putInt((int) (s.commFrames >= 0 ? s.commFrames : realFrames));
        comm.putShort((short) (s.commBits >= 0 ? s.commBits : bits));
        comm.put(s.rateBytes != null ? s.rateBytes : extended80(rate));
        if (aifc) {
            comm.put(ascii(s.compression));
            byte[] pstring = ascii(s.compressionName);
            comm.put((byte) pstring.length).put(pstring);
            if (((1 + pstring.length) & 1) != 0) {
                comm.put((byte) 0); // the pascal string is padded to an even total length
            }
        }
        byte[] commPayload = trimmed(comm);

        ByteBuffer ssnd = be(8 + s.ssndOffset + data.length);
        ssnd.putInt(s.ssndOffset).putInt(0); // offset, blockSize
        ssnd.put(new byte[s.ssndOffset]);
        ssnd.put(data);

        int room = 128 + commPayload.length + ssnd.capacity() + Math.max(0, s.junkBytes)
                + (s.id3 == null ? 0 : s.id3.length) + (s.name == null ? 0 : s.name.length() * 4)
                + (s.nameBytes == null ? 0 : s.nameBytes.length) + (s.author == null ? 0 : s.author.length() * 4);
        ByteBuffer out = be(room);
        out.put(ascii("FORM")).putInt(0).put(ascii(aifc ? "AIFC" : "AIFF"));
        if (aifc) {
            chunk(out, "FVER", be(4).putInt(0xA2805140).array());
        }
        if (!s.commAfterSsnd && !s.omitComm) {
            chunk(out, "COMM", commPayload);
        }
        if (s.junkBytes >= 0) {
            chunk(out, "JUNK", new byte[s.junkBytes]);
        }
        if (s.name != null) {
            chunk(out, "NAME", s.name.getBytes(StandardCharsets.UTF_8));
        }
        if (s.nameBytes != null) {
            chunk(out, "NAME", s.nameBytes);
        }
        if (s.author != null) {
            chunk(out, "AUTH", s.author.getBytes(StandardCharsets.UTF_8));
        }
        if (!s.omitSsnd) {
            out.put(ascii("SSND")).putInt((int) (s.ssndSize >= 0 ? s.ssndSize : ssnd.capacity()));
            out.put(ssnd.array());
            if ((ssnd.capacity() & 1) != 0) {
                out.put((byte) 0);
            }
        }
        if (s.commAfterSsnd && !s.omitComm) {
            chunk(out, "COMM", commPayload);
        }
        if (s.id3 != null) {
            chunk(out, s.id3Id, s.id3);
        }

        byte[] file = trimmed(out);
        ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN).putInt(4, file.length - 8);
        Path path = dir.resolve(name);
        Files.write(path, file);
        return path;
    }

    /**
     * The 80-bit IEEE extended encoding of a positive whole number: {@code 1.m * 2^e} with the integer bit
     * explicit, the exponent biased by 16383.
     */
    public static byte[] extended80(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("only positive whole numbers: " + value);
        }
        int exponent = 63 - Long.numberOfLeadingZeros(value);
        long mantissa = value << (63 - exponent);
        int biased = exponent + 16383;
        ByteBuffer b = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) biased).putLong(mantissa);
        return b.array();
    }

    private static byte[] samples(int[] interleaved, int cbytes, int valid, boolean little) {
        ByteBuffer d = be(interleaved.length * cbytes);
        for (int v : interleaved) {
            long x = ((long) v) << (cbytes * 8 - valid);
            for (int k = 0; k < cbytes; k++) {
                int shift = little ? 8 * k : 8 * (cbytes - 1 - k);
                d.put((byte) (x >> shift));
            }
        }
        return d.array();
    }

    /** Appends {@code id, size, payload}, plus a pad byte when the payload length is odd. */
    private static void chunk(ByteBuffer out, String id, byte[] payload) {
        out.put(ascii(id)).putInt(payload.length).put(payload);
        if ((payload.length & 1) != 0) {
            out.put((byte) 0);
        }
    }

    private static byte[] trimmed(ByteBuffer b) {
        byte[] result = new byte[b.position()];
        b.flip();
        b.get(result);
        return result;
    }

    private static ByteBuffer be(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
