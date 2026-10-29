package purist.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes small DFF (DSDIFF) files for tests, laid out from the DSDIFF 1.5 specification: big-endian chunks with
 * 64-bit sizes padded to an even length, {@code FRM8 "DSD "} holding {@code FVER}, {@code PROP "SND "} (with
 * {@code FS  }, {@code CHNL}, {@code CMPR}), the {@code DSD } sound data, an optional {@code DIIN} with
 * {@code DITI}/{@code DIAR}, and an optional {@code ID3 } chunk.
 *
 * <p>Channels are passed as one byte array each; the writer interleaves them, one byte per channel per frame.
 */
public final class DffWriter {

    private final int rate;
    private final byte[][] channels;
    private String cmpr = "DSD ";
    private String title;
    private String artist;
    private byte[] id3;
    private boolean noSound;
    private String[] channelIds;

    private DffWriter(int rate, byte[][] channels) {
        this.rate = rate;
        this.channels = channels;
    }

    public static DffWriter dsd(int rate, byte[]... channels) {
        return new DffWriter(rate, channels);
    }

    /** The compression type in {@code CMPR}; with {@code "DST "} the sound chunk is a {@code DST } chunk of junk. */
    public DffWriter cmpr(String type) {
        cmpr = type;
        return this;
    }

    public DffWriter title(String t) {
        title = t;
        return this;
    }

    public DffWriter artist(String a) {
        artist = a;
        return this;
    }

    public DffWriter id3(byte[] tag) {
        id3 = tag;
        return this;
    }

    /** The four-character channel IDs listed in {@code CHNL}, in the order the channels are interleaved (default SLFT, SRGT, ...). */
    public DffWriter channelIds(String... ids) {
        channelIds = ids;
        return this;
    }

    /** Leaves out the sound data chunk. */
    public DffWriter withoutSound() {
        noSound = true;
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(ascii("DSD "));
        body.writeBytes(chunk("FVER", u32(0x01050000)));

        ByteArrayOutputStream prop = new ByteArrayOutputStream();
        prop.writeBytes(ascii("SND "));
        prop.writeBytes(chunk("FS  ", u32(rate)));
        ByteArrayOutputStream chnl = new ByteArrayOutputStream();
        chnl.writeBytes(u16(channels.length));
        String[] ids = {"SLFT", "SRGT", "MLFT", "MRGT", "LS  ", "RS  ", "C   ", "LFE "};
        for (int c = 0; c < channels.length; c++) {
            chnl.writeBytes(ascii(channelIds != null ? channelIds[c] : ids[c % ids.length]));
        }
        prop.writeBytes(chunk("CHNL", chnl.toByteArray()));
        ByteArrayOutputStream cm = new ByteArrayOutputStream();
        cm.writeBytes(ascii(cmpr));
        String name = cmpr.equals("DSD ") ? "not compressed" : "DST Encoded";
        cm.write(name.length());
        cm.writeBytes(ascii(name));
        prop.writeBytes(chunk("CMPR", cm.toByteArray()));
        body.writeBytes(chunk("PROP", prop.toByteArray()));

        if (!noSound) {
            int n = channels[0].length;
            byte[] sound = cmpr.equals("DSD ") ? DsfWriter.interleave(channels) : new byte[Math.max(n, 16)];
            body.writeBytes(chunk(cmpr.equals("DSD ") ? "DSD " : "DST ", sound));
        }
        if (title != null || artist != null) {
            ByteArrayOutputStream diin = new ByteArrayOutputStream();
            if (title != null) {
                diin.writeBytes(chunk("DITI", text(title)));
            }
            if (artist != null) {
                diin.writeBytes(chunk("DIAR", text(artist)));
            }
            body.writeBytes(chunk("DIIN", diin.toByteArray()));
        }
        if (id3 != null) {
            body.writeBytes(chunk("ID3 ", id3));
        }
        byte[] payload = body.toByteArray();
        ByteBuffer out = ByteBuffer.allocate(12 + payload.length);
        out.put(ascii("FRM8")).putLong(payload.length).put(payload);
        return out.array();
    }

    public Path write(Path dir, String name) throws IOException {
        return Files.write(dir.resolve(name), build());
    }

    /** A chunk: id, 64-bit size of the payload, the payload, a pad byte if the payload is odd. */
    public static byte[] chunk(String id, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(12 + payload.length + (payload.length & 1));
        b.put(ascii(id)).putLong(payload.length).put(payload);
        return b.array();
    }

    private static byte[] text(String s) {
        byte[] t = s.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + t.length);
        b.putInt(t.length).put(t);
        return b.array();
    }

    private static byte[] u32(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    private static byte[] u16(int v) {
        return ByteBuffer.allocate(2).putShort((short) v).array();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
