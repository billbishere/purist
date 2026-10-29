package purist.testutil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes small WAV and RF64 files for tests. Samples are passed as {@code int}s: for every depth above 8 they
 * are the signed, right-aligned values (what a decoder must hand back), and the writer left-justifies them
 * into the container exactly as real files do. For 8-bit files the ints are the raw unsigned bytes
 * ({@code 0x00..0xFF}), because that is what the file holds.
 *
 * <p>Layout: {@code RIFF|RF64, [ds64], fmt, [JUNK], data, [LIST INFO], [id3 ]}. The metadata chunks come after
 * the data chunk, the way most tools write them.
 */
public final class WavWriter {

    /** One writer option; build them with the static factories below. */
    @FunctionalInterface
    public interface WavOpt {
        void apply(Spec spec);
    }

    /** Everything the options can set. */
    public static final class Spec {
        boolean rf64;
        boolean extensible;
        int validBits;
        int containerBits;
        Map<String, String> info;
        byte[] id3;
        String id3Id = "id3 ";
        boolean floatFormat;
        int formatTag = -1;
        long declaredDataSize = -1;
        int junkBytes = -1;
    }

    /** An RF64 file: {@code RF64} magic, a {@code ds64} chunk, and {@code 0xFFFFFFFF} in the data size. */
    public static final WavOpt RF64 = s -> s.rf64 = true;

    /** WAVE_FORMAT_EXTENSIBLE with the given valid bits inside a container of {@code containerBits}. */
    public static WavOpt extensible(int validBits, int containerBits) {
        return s -> {
            s.extensible = true;
            s.validBits = validBits;
            s.containerBits = containerBits;
        };
    }

    /** A {@code LIST/INFO} chunk; keys are RIFF ids such as {@code INAM}, values are written as UTF-8. */
    public static WavOpt info(Map<String, String> entries) {
        return s -> s.info = entries;
    }

    /** An {@code id3 } chunk holding exactly these bytes. */
    public static WavOpt id3(byte[] tag) {
        return s -> {
            s.id3 = tag;
            s.id3Id = "id3 ";
        };
    }

    /** Like {@link #id3} but with the upper-case chunk id {@code ID3 }. */
    public static WavOpt id3Upper(byte[] tag) {
        return s -> {
            s.id3 = tag;
            s.id3Id = "ID3 ";
        };
    }

    /** Tags the format as IEEE float (format 3, or the float sub-format when extensible). */
    public static WavOpt floatFormat() {
        return s -> s.floatFormat = true;
    }

    /** Writes this value in the data chunk header (or in {@code ds64} for RF64) instead of the real size. */
    public static WavOpt declaredDataSize(long size) {
        return s -> s.declaredDataSize = size;
    }

    /** Writes a format tag other than PCM (the data is still the samples); for refusal tests. */
    public static WavOpt formatTag(int tag) {
        return s -> s.formatTag = tag;
    }

    /** A {@code JUNK} chunk of this many bytes between {@code fmt } and {@code data} (odd sizes get a pad byte). */
    public static WavOpt junkBeforeData(int bytes) {
        return s -> s.junkBytes = bytes;
    }

    private WavWriter() {}

    /**
     * Writes {@code dir/name} and returns its path.
     *
     * @param bits container depth (16, 24, ...) unless {@link #extensible} is given, which then decides
     * @param interleaved frame-interleaved samples; its length must be a multiple of {@code channels}
     */
    public static Path write(Path dir, String name, int rate, int bits, int channels, int[] interleaved,
            WavOpt... opts) throws IOException {
        Spec s = new Spec();
        for (WavOpt o : opts) {
            o.apply(s);
        }
        int container = s.extensible ? s.containerBits : bits;
        int valid = s.extensible ? s.validBits : bits;
        int cbytes = container / 8;

        byte[] data = samples(interleaved, container, valid);
        long realFrames = interleaved.length / channels;
        long dataField = s.declaredDataSize >= 0 ? s.declaredDataSize : data.length;

        int blockAlign = channels * cbytes;
        int tag = s.formatTag >= 0 ? s.formatTag : s.floatFormat ? 3 : 1;
        ByteBuffer fmt;
        if (s.extensible) {
            fmt = le(40);
            fmt.putShort((short) 0xFFFE).putShort((short) channels).putInt(rate).putInt(rate * blockAlign)
                    .putShort((short) blockAlign).putShort((short) container);
            fmt.putShort((short) 22).putShort((short) valid).putInt(channels == 1 ? 4 : 3);
            fmt.putShort((short) tag).putShort((short) 0).putInt(0x00100000);
            fmt.put(new byte[] {(byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71});
        } else {
            fmt = le(16);
            fmt.putShort((short) tag).putShort((short) channels).putInt(rate).putInt(rate * blockAlign)
                    .putShort((short) blockAlign).putShort((short) container);
        }

        int room = 128 + data.length + Math.max(0, s.junkBytes) + (s.id3 == null ? 0 : s.id3.length)
                + (s.info == null ? 0 : 64 + s.info.size() * 520);
        ByteBuffer out = le(room);
        out.put(ascii(s.rf64 ? "RF64" : "RIFF")).putInt(0).put(ascii("WAVE"));
        if (s.rf64) {
            out.put(ascii("ds64")).putInt(28);
            out.putLong(0).putLong(dataField).putLong(realFrames).putInt(0); // riff size patched below
        }
        chunk(out, "fmt ", fmt.array());
        if (s.junkBytes >= 0) {
            chunk(out, "JUNK", new byte[s.junkBytes]);
        }
        out.put(ascii("data")).putInt(s.rf64 ? 0xFFFFFFFF : (int) dataField);
        out.put(data);
        if ((data.length & 1) != 0) {
            out.put((byte) 0);
        }
        if (s.info != null) {
            chunk(out, "LIST", infoPayload(s.info));
        }
        if (s.id3 != null) {
            chunk(out, s.id3Id, s.id3);
        }

        byte[] file = new byte[out.position()];
        out.flip();
        out.get(file);
        ByteBuffer patch = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        long riffSize = file.length - 8L;
        if (s.rf64) {
            patch.putInt(4, 0xFFFFFFFF);
            patch.putLong(20, riffSize);
        } else {
            patch.putInt(4, (int) riffSize);
        }
        Path path = dir.resolve(name);
        Files.write(path, file);
        return path;
    }

    private static byte[] samples(int[] interleaved, int container, int valid) {
        int cbytes = container / 8;
        ByteBuffer d = le(interleaved.length * cbytes);
        for (int v : interleaved) {
            if (container == 8) {
                d.put((byte) v);
                continue;
            }
            long x = ((long) v) << (container - valid);
            for (int b = 0; b < cbytes; b++) {
                d.put((byte) (x >> (8 * b)));
            }
        }
        return d.array();
    }

    private static byte[] infoPayload(Map<String, String> entries) {
        ByteBuffer b = le(4 + entries.size() * 520);
        b.put(ascii("INFO"));
        for (Map.Entry<String, String> e : entries.entrySet()) {
            byte[] text = e.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] z = new byte[text.length + 1]; // NUL-terminated
            System.arraycopy(text, 0, z, 0, text.length);
            chunk(b, e.getKey(), z);
        }
        byte[] result = new byte[b.position()];
        b.flip();
        b.get(result);
        return result;
    }

    /** Appends {@code id, size, payload}, plus a pad byte when the payload length is odd. */
    private static void chunk(ByteBuffer out, String id, byte[] payload) {
        out.put(ascii(id)).putInt(payload.length).put(payload);
        if ((payload.length & 1) != 0) {
            out.put((byte) 0);
        }
    }

    private static ByteBuffer le(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
