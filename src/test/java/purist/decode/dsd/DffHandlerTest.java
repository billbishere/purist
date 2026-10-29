package purist.decode.dsd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.testutil.DffWriter;
import purist.testutil.DsdDecode;
import purist.testutil.DsfWriter;
import purist.testutil.Id3Writer;

class DffHandlerTest {

    private static final int DSD64 = 2_822_400;

    @TempDir
    Path dir;

    private final DffHandler handler = new DffHandler();

    private TrackInfo probeOne(Path p) throws IOException {
        List<TrackInfo> l = handler.probe(p);
        assertEquals(1, l.size());
        return l.get(0);
    }

    @Test
    void probe() throws IOException {
        int n = 5000;
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(n, 1), DsfWriter.bytes(n, 2)).write(dir, "a.dff");
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.dsd(DSD64, 2, n, BitOrder.MSB_FIRST), t.format());
        assertEquals("DFF", t.codec());
        assertEquals(0, t.subsong());
        assertEquals(p, t.path());
        assertTrue(t.supported());
        assertEquals("DSD64", t.badge());
        assertTrue(t.tags().isEmpty());
        assertEquals(List.of("dff"), handler.extensions());
    }

    @Test
    void decodeExact() throws IOException {
        int n = 10_001;
        byte[] a = DsfWriter.bytes(n, 5);
        byte[] b = DsfWriter.bytes(n, 6);
        Path p = DffWriter.dsd(DSD64, a, b).write(dir, "a.dff");
        byte[] expected = DsfWriter.interleave(a, b);
        for (int step : new int[] {1, 13, 4096, 100_000}) {
            try (Decoder d = handler.open(p, 0)) {
                assertEquals(BitOrder.MSB_FIRST, d.format().dsdOrder());
                assertArrayEquals(expected, DsdDecode.all(d, step), "step " + step);
                assertEquals(n, d.position());
                assertEquals(0, DsdDecode.some(d, 10).length);
            }
        }
    }

    @Test
    void oddSizedSoundChunkIsPaddedAndFollowingChunksAreStillFound() throws IOException {
        byte[] a = DsfWriter.bytes(333, 1); // mono: 333 data bytes, odd, so a pad byte follows the chunk
        Path p = DffWriter.dsd(DSD64, a).title("Odd").write(dir, "odd.dff");
        TrackInfo t = probeOne(p);
        assertEquals(333, t.format().lengthFrames());
        assertEquals("Odd", t.displayTitle());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(a, DsdDecode.all(d, 100));
        }
    }

    @Test
    void seek() throws IOException {
        int n = 9000;
        byte[] a = DsfWriter.bytes(n, 7);
        byte[] b = DsfWriter.bytes(n, 8);
        Path p = DffWriter.dsd(DSD64, a, b).write(dir, "a.dff");
        try (Decoder d = handler.open(p, 0)) {
            d.seek(5000);
            assertEquals(5000, d.position());
            assertArrayEquals(new byte[] {a[5000], b[5000], a[5001], b[5001]}, DsdDecode.some(d, 2));
            d.seek(n + 99); // clamps
            assertEquals(n, d.position());
            assertEquals(0, DsdDecode.some(d, 5).length);
            assertThrows(IllegalArgumentException.class, () -> d.seek(-1));
        }
    }

    @Test
    void dstRefused() throws IOException {
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).cmpr("DST ")
                .write(dir, "dst.dff");
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals("DST-compressed DFF is not supported yet", t.reason());
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0));
        assertEquals("DST-compressed DFF is not supported yet", e.reason());
        assertEquals("DST-compressed DFF is not supported yet", e.getMessage());
    }

    @Test
    void anUnknownCompressionIsRefusedWithItsName() throws IOException {
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).cmpr("XYZ ")
                .write(dir, "x.dff");
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertTrue(t.reason().contains("XYZ"), t.reason());
    }

    @Test
    void multichannelIsRefused() throws IOException {
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2), DsfWriter.bytes(100, 3))
                .write(dir, "m.dff");
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals("multichannel DFF (3 ch)", t.reason());
    }

    // ---- CHNL channel order ---------------------------------------------------------------------------------

    @Test
    void aFileListingTheRightChannelFirstPlaysLeftOnTheLeft() throws IOException {
        byte[] right = DsfWriter.bytes(777, 1); // interleaved first, because SRGT is listed first
        byte[] left = DsfWriter.bytes(777, 2);
        Path p = DffWriter.dsd(DSD64, right, left).channelIds("SRGT", "SLFT").write(dir, "swapped.dff");
        assertTrue(probeOne(p).supported());
        for (int step : new int[] {1, 100, 100_000}) {
            try (Decoder d = handler.open(p, 0)) {
                assertArrayEquals(DsfWriter.interleave(left, right), DsdDecode.all(d, step), "step " + step);
            }
        }
    }

    @Test
    void theSwapSurvivesASeek() throws IOException {
        byte[] right = DsfWriter.bytes(9000, 3);
        byte[] left = DsfWriter.bytes(9000, 4);
        Path p = DffWriter.dsd(DSD64, right, left).channelIds("MRGT", "MLFT").write(dir, "swapped.dff");
        try (Decoder d = handler.open(p, 0)) {
            d.seek(5000);
            assertArrayEquals(new byte[] {left[5000], right[5000], left[5001], right[5001]}, DsdDecode.some(d, 2));
        }
    }

    @Test
    void leftThenRightInEitherNamingPlaysAsFiled() throws IOException {
        byte[] a = DsfWriter.bytes(500, 5);
        byte[] b = DsfWriter.bytes(500, 6);
        for (String[] ids : new String[][] {{"SLFT", "SRGT"}, {"MLFT", "MRGT"}}) {
            Path p = DffWriter.dsd(DSD64, a, b).channelIds(ids).write(dir, ids[0] + ".dff");
            try (Decoder d = handler.open(p, 0)) {
                assertArrayEquals(DsfWriter.interleave(a, b), DsdDecode.all(d, 64), ids[0]);
            }
        }
    }

    @Test
    void anUnknownTwoChannelLayoutIsRefusedWithAReason() throws IOException {
        for (String[] ids : new String[][] {{"C   ", "LFE "}, {"LS  ", "RS  "}, {"SLFT", "SLFT"}, {"SLFT", "C   "},
                {"ABCD", "EFGH"}}) {
            Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).channelIds(ids)
                    .write(dir, "layout.dff");
            TrackInfo t = probeOne(p);
            assertFalse(t.supported(), String.join(",", ids));
            assertTrue(t.reason().startsWith("DFF channel layout "), t.reason());
            assertTrue(t.reason().contains(ids[0].strip()), t.reason());
            assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0));
        }
    }

    @Test
    void aMonoFileWithAKnownChannelIsStillPlayed() throws IOException {
        byte[] a = DsfWriter.bytes(300, 7);
        for (String id : new String[] {"SLFT", "MLFT", "C   "}) {
            Path p = DffWriter.dsd(DSD64, a).channelIds(id).write(dir, "mono.dff");
            assertTrue(probeOne(p).supported(), id);
            try (Decoder d = handler.open(p, 0)) {
                assertArrayEquals(a, DsdDecode.all(d, 50), id);
            }
        }
    }

    @Test
    void aChnlChunkWithoutItsChannelIdsIsADecodeException() throws IOException {
        java.io.ByteArrayOutputStream prop = new java.io.ByteArrayOutputStream();
        prop.writeBytes("SND ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        prop.writeBytes(DffWriter.chunk("FS  ", java.nio.ByteBuffer.allocate(4).putInt(DSD64).array()));
        prop.writeBytes(DffWriter.chunk("CHNL", new byte[] {0, 2})); // a count of 2 and no IDs
        prop.writeBytes(DffWriter.chunk("CMPR", "DSD \0\0\0\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes("DSD ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        body.writeBytes(DffWriter.chunk("PROP", prop.toByteArray()));
        body.writeBytes(DffWriter.chunk("DSD ", new byte[200]));
        byte[] payload = body.toByteArray();
        java.nio.ByteBuffer file = java.nio.ByteBuffer.allocate(12 + payload.length);
        file.put("FRM8".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)).putLong(payload.length).put(payload);
        Path p = dir.resolve("noids.dff");
        Files.write(p, file.array());
        DecodeException e = assertThrows(DecodeException.class, () -> handler.probe(p));
        assertTrue(e.getMessage().contains("CHNL"), e.getMessage());
    }

    @Test
    void diinTitleArtist() throws IOException {
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2))
                .title("Thriller").artist("Michael Jackson").write(dir, "a.dff");
        TrackInfo t = probeOne(p);
        assertEquals("Thriller", t.displayTitle());
        assertEquals("Michael Jackson", t.displayArtist());
        assertEquals("Thriller", t.tags().first("TITLE").orElseThrow());
        assertEquals("Michael Jackson", t.tags().first("ARTIST").orElseThrow());
    }

    @Test
    void id3ChunkTags() throws IOException {
        byte[] art = {9, 8, 7};
        byte[] tag = Id3Writer.v23()
                .text("TIT2", Id3Writer.LATIN1, "From ID3")
                .text("TALB", Id3Writer.LATIN1, "The Album")
                .apic("image/png", 3, "", Id3Writer.LATIN1, art)
                .build();
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2))
                .title("From DIIN").artist("DIIN Artist").id3(tag).write(dir, "a.dff");
        TrackInfo t = probeOne(p);
        assertEquals("From ID3", t.displayTitle()); // ID3 wins key by key
        assertEquals("DIIN Artist", t.displayArtist()); // keys only DIIN has are kept
        assertEquals("The Album", t.displayAlbum());
        assertArrayEquals(art, handler.embeddedArt(p, 0).orElseThrow());
        assertTrue(handler.embeddedArt(p, 1).isEmpty());
    }

    @Test
    void notADffIsADecodeException() throws IOException {
        Path junk = dir.resolve("junk.dff");
        Files.write(junk, "This is not a DSDIFF file, only some text that is long enough.".getBytes());
        assertThrows(DecodeException.class, () -> handler.probe(junk));
        assertThrows(DecodeException.class, () -> handler.open(junk, 0));
        Path empty = dir.resolve("empty.dff");
        Files.write(empty, new byte[0]);
        assertThrows(DecodeException.class, () -> handler.probe(empty));
    }

    @Test
    void aFileWithoutSoundDataIsADecodeException() throws IOException {
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).withoutSound()
                .write(dir, "nosound.dff");
        assertThrows(DecodeException.class, () -> handler.probe(p));
    }

    @Test
    void aTruncatedHeaderIsADecodeException() throws IOException {
        byte[] good = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).build();
        for (int len : new int[] {12, 30, 60, 100}) {
            Path p = dir.resolve("t" + len + ".dff");
            Files.write(p, Arrays.copyOf(good, len));
            assertThrows(DecodeException.class, () -> handler.probe(p), "length " + len);
        }
    }

    @Test
    void aFileCutShortInTheSoundDataIsClampedToWholeFrames() throws IOException {
        byte[] a = DsfWriter.bytes(1000, 1);
        byte[] b = DsfWriter.bytes(1000, 2);
        byte[] whole = DffWriter.dsd(DSD64, a, b).build();
        Path p = dir.resolve("cut.dff");
        Files.write(p, Arrays.copyOf(whole, whole.length - 501)); // loses 501 bytes: 1499 are left, which is 749 whole frames and a stray byte
        assertEquals(749, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            byte[] expected = Arrays.copyOf(DsfWriter.interleave(a, b), 1498);
            assertArrayEquals(expected, DsdDecode.all(d, 200));
        }
    }

    @Test
    void aStreamThatEndsEarlyReturnsTheGoodFramesThenThrows() throws IOException {
        byte[] a = DsfWriter.bytes(100_000, 1);
        byte[] b = DsfWriter.bytes(100_000, 2);
        Path p = DffWriter.dsd(DSD64, a, b).write(dir, "a.dff");
        try (Decoder d = handler.open(p, 0)) {
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                ch.truncate(Files.size(p) - 100_000); // half the sound is gone after open
            }
            byte[] all = DsfWriter.interleave(a, b);
            byte[] got = new byte[0];
            try {
                while (true) {
                    byte[] part = DsdDecode.some(d, 30_000);
                    got = concat(got, part);
                    assertTrue(part.length > 0, "0 must not be returned for damage");
                }
            } catch (IOException expected) {
                // the next call after the good frames throws
            }
            assertTrue(got.length > 0 && got.length < all.length);
            assertArrayEquals(Arrays.copyOf(all, got.length), got);
            assertThrows(IOException.class, () -> DsdDecode.some(d, 10));
            d.seek(0);
            assertEquals(2, DsdDecode.some(d, 1).length);
        }
    }

    @Test
    void aSecondTrackDoesNotExist() throws IOException {
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).write(dir, "a.dff");
        assertThrows(DecodeException.class, () -> handler.open(p, 1));
    }

    @Test
    void theStandardRegistryPlaysDffCaseInsensitively() throws IOException {
        DecoderRegistry r = DecoderRegistry.standard();
        Path p = DffWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).write(dir, "A.DFF");
        assertTrue(r.handlerFor(p).orElseThrow() instanceof DffHandler);
        assertTrue(r.isAudioFile(p));
        assertTrue(r.probe(p).get(0).supported());
    }

    private static byte[] concat(byte[] x, byte[] y) {
        byte[] r = Arrays.copyOf(x, x.length + y.length);
        System.arraycopy(y, 0, r, x.length, y.length);
        return r;
    }
}
