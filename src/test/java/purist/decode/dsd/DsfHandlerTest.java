package purist.decode.dsd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.testutil.DsdDecode;
import purist.testutil.DsfWriter;
import purist.testutil.Id3Writer;

class DsfHandlerTest {

    private static final int DSD64 = 2_822_400;
    private static final int DSD128 = 5_644_800;

    @TempDir
    Path dir;

    private final DsfHandler handler = new DsfHandler();

    private TrackInfo probeOne(Path p) throws IOException {
        List<TrackInfo> l = handler.probe(p);
        assertEquals(1, l.size());
        return l.get(0);
    }

    // ---- probe -----------------------------------------------------------------------------------

    @Test
    void probeDsd64Stereo() throws IOException {
        int n = 5000;
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(n, 1), DsfWriter.bytes(n, 2)).write(dir, "a.dsf");
        TrackInfo t = probeOne(p);
        assertEquals(StreamFormat.dsd(DSD64, 2, n, BitOrder.LSB_FIRST), t.format());
        assertEquals("DSF", t.codec());
        assertEquals(0, t.subsong());
        assertEquals(p, t.path());
        assertTrue(t.supported());
        assertEquals("DSD64", t.badge());
        assertTrue(t.tags().isEmpty());
        assertEquals(List.of("dsf"), handler.extensions());
    }

    @Test
    void dsd128Label() throws IOException {
        Path p = DsfWriter.dsd(DSD128, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).write(dir, "a.dsf");
        TrackInfo t = probeOne(p);
        assertEquals("DSD128", t.format().label());
        assertEquals(DSD128, t.format().sampleRate());
    }

    @Test
    void sampleCountNotAMultipleOfEightRoundsUpToWholeBytes() throws IOException {
        byte[] a = DsfWriter.bytes(10, 1);
        byte[] b = DsfWriter.bytes(10, 2);
        Path p = DsfWriter.dsd(DSD64, a, b).sampleCount(8 * 9 + 3).write(dir, "a.dsf");
        assertEquals(10, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(DsfWriter.interleave(a, b), DsdDecode.all(d, 64));
        }
    }

    @Test
    void monoIsSupported() throws IOException {
        byte[] a = DsfWriter.bytes(300, 1);
        Path p = DsfWriter.dsd(DSD64, a).write(dir, "m.dsf");
        assertEquals(1, probeOne(p).format().channels());
        try (Decoder d = handler.open(p, 0)) {
            assertArrayEquals(a, DsdDecode.all(d, 100));
        }
    }

    // ---- decoding --------------------------------------------------------------------------------

    @Test
    void deinterleavesBlocksExact() throws IOException {
        int n = 2 * DsfWriter.BLOCK + 1000; // two full blocks and a partial last one
        byte[] a = DsfWriter.bytes(n, 11);
        byte[] b = DsfWriter.bytes(n, 12);
        Path p = DsfWriter.dsd(DSD64, a, b).write(dir, "a.dsf");
        byte[] expected = DsfWriter.interleave(a, b);
        for (int step : new int[] {1, 7, 4096, 4097, 100_000}) {
            try (Decoder d = handler.open(p, 0)) {
                assertEquals(StreamFormat.dsd(DSD64, 2, n, BitOrder.LSB_FIRST), d.format());
                assertArrayEquals(expected, DsdDecode.all(d, step), "step " + step);
                assertEquals(n, d.position());
                assertEquals(0, DsdDecode.some(d, 10).length); // the end stays the end
            }
        }
    }

    @Test
    void doesNotEmitThePaddingOfTheLastBlock() throws IOException {
        int n = DsfWriter.BLOCK + 1;
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(n, 1), DsfWriter.bytes(n, 2)).write(dir, "a.dsf");
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(2 * n, DsdDecode.all(d, 1000).length);
        }
    }

    @Test
    void seekByteAligned() throws IOException {
        int n = 3 * DsfWriter.BLOCK;
        byte[] a = DsfWriter.bytes(n, 21);
        byte[] b = DsfWriter.bytes(n, 22);
        Path p = DsfWriter.dsd(DSD64, a, b).write(dir, "a.dsf");
        try (Decoder d = handler.open(p, 0)) {
            d.seek(5000);
            assertEquals(5000, d.position());
            byte[] got = DsdDecode.some(d, 4);
            assertArrayEquals(new byte[] {a[5000], b[5000], a[5001], b[5001], a[5002], b[5002], a[5003], b[5003]},
                    got);
            assertEquals(5004, d.position());

            d.seek(0);
            assertArrayEquals(new byte[] {a[0], b[0]}, DsdDecode.some(d, 1));

            // a read that spans the block boundary at 4096
            d.seek(4094);
            assertArrayEquals(new byte[] {a[4094], b[4094], a[4095], b[4095], a[4096], b[4096], a[4097], b[4097]},
                    DsdDecode.some(d, 4));

            d.seek(n - 1);
            assertArrayEquals(new byte[] {a[n - 1], b[n - 1]}, DsdDecode.some(d, 10));
            assertEquals(0, DsdDecode.some(d, 10).length);

            d.seek(Long.MAX_VALUE); // beyond the end clamps to the end
            assertEquals(n, d.position());
            assertEquals(0, DsdDecode.some(d, 10).length);

            assertThrows(IllegalArgumentException.class, () -> d.seek(-1));
        }
    }

    @Test
    void msbFirstVariant() throws IOException {
        byte[] a = DsfWriter.bytes(200, 1);
        byte[] b = DsfWriter.bytes(200, 2);
        Path p = DsfWriter.dsd(DSD64, a, b).msbFirst().write(dir, "a.dsf");
        assertEquals(BitOrder.MSB_FIRST, probeOne(p).format().dsdOrder());
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(BitOrder.MSB_FIRST, d.format().dsdOrder());
            // the bits are never altered: the bytes come out exactly as stored
            assertArrayEquals(DsfWriter.interleave(a, b), DsdDecode.all(d, 50));
        }
    }

    @Test
    void readValidatesItsArguments() throws IOException {
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).write(dir, "a.dsf");
        Decoder d = handler.open(p, 0);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment small = arena.allocate(10);
            assertThrows(IllegalArgumentException.class, () -> d.read(small, 6));
            assertThrows(IllegalArgumentException.class, () -> d.read(small, -1));
        }
        d.close();
        d.close(); // idempotent
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(10);
            assertThrows(IOException.class, () -> d.read(dst, 1));
            assertThrows(IOException.class, () -> d.seek(0));
        }
    }

    @Test
    void aStreamThatEndsEarlyReturnsTheGoodFramesThenThrows() throws IOException {
        int n = 100_000; // 25 groups of 8192 bytes: far more than the 64 KiB read-ahead of the file source
        byte[] a = DsfWriter.bytes(n, 1);
        byte[] b = DsfWriter.bytes(n, 2);
        Path p = DsfWriter.dsd(DSD64, a, b).write(dir, "a.dsf");
        try (Decoder d = handler.open(p, 0)) {
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
                ch.truncate(Files.size(p) - 100_000); // 12 whole groups and part of a 13th are left
            }
            byte[] all = DsfWriter.interleave(a, b);
            byte[] got = new byte[0];
            try {
                while (true) {
                    byte[] part = DsdDecode.some(d, 30_000);
                    got = Arrays.copyOf(got, got.length + part.length);
                    System.arraycopy(part, 0, got, got.length - part.length, part.length);
                    assertTrue(part.length > 0, "0 must not be returned for damage");
                }
            } catch (IOException expected) {
                // the next call after the good frames throws
            }
            assertEquals(12 * DsfWriter.BLOCK * 2, got.length); // exactly the whole groups that are there
            assertArrayEquals(Arrays.copyOf(all, got.length), got);
            assertThrows(IOException.class, () -> DsdDecode.some(d, 10)); // and keeps throwing
            d.seek(10); // until a seek
            assertEquals(2, DsdDecode.some(d, 1).length);
        }
    }

    // ---- tags ------------------------------------------------------------------------------------

    @Test
    void id3AtMetadataPointer() throws IOException {
        byte[] art = {1, 2, 3, 4, 5};
        byte[] tag = Id3Writer.v23()
                .text("TIT2", Id3Writer.LATIN1, "Billie Jean")
                .text("TPE1", Id3Writer.LATIN1, "Michael Jackson")
                .apic("image/jpeg", 3, "", Id3Writer.LATIN1, art)
                .build();
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(5000, 1), DsfWriter.bytes(5000, 2)).id3(tag).write(dir, "a.dsf");
        TrackInfo t = probeOne(p);
        assertEquals("Billie Jean", t.displayTitle());
        assertEquals("Michael Jackson", t.displayArtist());
        assertEquals(5000, t.format().lengthFrames());
        Optional<byte[]> cover = handler.embeddedArt(p, 0);
        assertTrue(cover.isPresent());
        assertArrayEquals(art, cover.get());
        assertTrue(handler.embeddedArt(p, 1).isEmpty());
    }

    @Test
    void noMetadataPointerMeansNoTagsAndNoArt() throws IOException {
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).write(dir, "a.dsf");
        assertTrue(probeOne(p).tags().isEmpty());
        assertTrue(handler.embeddedArt(p, 0).isEmpty());
    }

    @Test
    void aMetadataPointerThatIsNotAnId3TagGivesNoTags() throws IOException {
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2))
                .id3(new byte[] {'J', 'U', 'N', 'K', 0, 0, 0, 0, 0, 0, 0, 0}).write(dir, "a.dsf");
        assertTrue(probeOne(p).tags().isEmpty());
    }

    // ---- refusals and damage ---------------------------------------------------------------------

    @Test
    void multichannelIsRefused() throws IOException {
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2), DsfWriter.bytes(100, 3))
                .write(dir, "m.dsf");
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals("multichannel DSF (3 ch)", t.reason());
        assertEquals("DSF", t.codec());
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 0));
        assertEquals("multichannel DSF (3 ch)", e.reason());
    }

    @Test
    void unknownFormatIdIsRefused() throws IOException {
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).formatId(7)
                .write(dir, "f.dsf");
        TrackInfo t = probeOne(p);
        assertFalse(t.supported());
        assertEquals("unknown DSF format id 7", t.reason());
    }

    @Test
    void notADsfIsADecodeException() throws IOException {
        Path junk = dir.resolve("junk.dsf");
        Files.write(junk, "This is not a DSF file, only text that is long enough to have a header.".getBytes());
        assertThrows(DecodeException.class, () -> handler.probe(junk));
        assertThrows(DecodeException.class, () -> handler.open(junk, 0));
        Path empty = dir.resolve("empty.dsf");
        Files.write(empty, new byte[0]);
        assertThrows(DecodeException.class, () -> handler.probe(empty));
    }

    @Test
    void aTruncatedHeaderIsADecodeException() throws IOException {
        byte[] good = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).build();
        for (int len : new int[] {20, 40, 79, 85}) {
            Path p = dir.resolve("t" + len + ".dsf");
            Files.write(p, Arrays.copyOf(good, len));
            assertThrows(DecodeException.class, () -> handler.probe(p), "length " + len);
        }
    }

    @Test
    void aFileWhoseDataIsShorterThanItsSampleCountIsClampedToWholeBlocks() throws IOException {
        int n = 3 * DsfWriter.BLOCK;
        byte[] a = DsfWriter.bytes(n, 1);
        byte[] b = DsfWriter.bytes(n, 2);
        byte[] whole = DsfWriter.dsd(DSD64, a, b).build();
        Path p = dir.resolve("cut.dsf");
        Files.write(p, Arrays.copyOf(whole, whole.length - DsfWriter.BLOCK - 10)); // 2 groups + part of the third
        assertEquals(2L * DsfWriter.BLOCK, probeOne(p).format().lengthFrames());
        try (Decoder d = handler.open(p, 0)) {
            assertEquals(4 * DsfWriter.BLOCK, DsdDecode.all(d, 3000).length);
        }
    }

    @Test
    void aSecondTrackDoesNotExist() throws IOException {
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).write(dir, "a.dsf");
        assertThrows(DecodeException.class, () -> handler.open(p, 1));
    }

    // ---- registry --------------------------------------------------------------------------------

    @Test
    void theStandardRegistryPlaysDsfCaseInsensitively() throws IOException {
        DecoderRegistry r = DecoderRegistry.standard();
        Path p = DsfWriter.dsd(DSD64, DsfWriter.bytes(100, 1), DsfWriter.bytes(100, 2)).write(dir, "A.DSF");
        assertTrue(r.handlerFor(p).orElseThrow() instanceof DsfHandler);
        assertTrue(r.isAudioFile(p));
        assertTrue(r.probe(p).get(0).supported());
    }
}
