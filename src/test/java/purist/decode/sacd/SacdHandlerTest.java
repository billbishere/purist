package purist.decode.sacd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
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
import purist.playlist.TrackRef;
import purist.testutil.DsdDecode;
import purist.testutil.SacdImageBuilder;

class SacdHandlerTest {

    private static final int FRAME = 4704; // bytes per channel in one SACD frame

    @TempDir
    Path dir;

    private final SacdHandler handler = new SacdHandler();

    private SacdImageBuilder disc() {
        return new SacdImageBuilder()
                .albumTitle("Live at Home").albumArtist("The Band").date(2014, 6, 10)
                .track("Opening", "The Band", 11)
                .track("Second", "Guest", 8)
                .track("Closing", "The Band", 20);
    }

    private Path write(SacdImageBuilder b) throws IOException {
        return b.write(dir, "disc.iso");
    }

    // ---- registry ---------------------------------------------------------------------------------------------

    @Test
    void standardRegistryPlaysIsoFiles() {
        assertInstanceOf(SacdHandler.class, DecoderRegistry.standard().handlerFor(Path.of("E:\\m\\disc.iso")).orElseThrow());
        assertInstanceOf(SacdHandler.class, DecoderRegistry.standard().handlerFor(Path.of("E:\\m\\DISC.ISO")).orElseThrow());
        assertEquals(List.of("iso"), handler.extensions());
    }

    // ---- probe ------------------------------------------------------------------------------------------------

    @Test
    void probeGivesOneTrackInfoPerStereoTrack() throws IOException {
        SacdImageBuilder b = disc();
        Path p = write(b);
        List<TrackInfo> infos = handler.probe(p);
        assertEquals(3, infos.size());
        String[] titles = {"Opening", "Second", "Closing"};
        String[] artists = {"The Band", "Guest", "The Band"};
        for (int i = 0; i < 3; i++) {
            TrackInfo t = infos.get(i);
            assertEquals(i + 1, t.subsong());
            assertEquals("SACD", t.codec());
            assertEquals(p, t.path());
            assertTrue(t.supported());
            assertNull(t.reason());
            assertEquals(StreamFormat.dsd(2_822_400, 2, (long) b.lengthFrames(i) * FRAME, BitOrder.MSB_FIRST),
                    t.format());
            assertEquals("DSD64", t.badge());
            assertEquals(titles[i], t.tags().first("TITLE").orElseThrow());
            assertEquals(artists[i], t.tags().first("ARTIST").orElseThrow());
            assertEquals("Live at Home", t.tags().first("ALBUM").orElseThrow());
            assertEquals("The Band", t.tags().first("ALBUMARTIST").orElseThrow());
            assertEquals("2014-06-10", t.tags().first("DATE").orElseThrow());
            assertEquals(Integer.toString(i + 1), t.tags().first("TRACKNUMBER").orElseThrow());
        }
        assertEquals("Live at Home", infos.get(0).displayAlbum());
    }

    @Test
    void probeWithoutAlbumTextOrDateOrTrackTextLeavesTagsOut() throws IOException {
        Path p = write(new SacdImageBuilder().albumTitle(null).albumArtist(null).date(0, 0, 0).track(null, null, 5));
        TrackInfo t = handler.probe(p).get(0);
        assertTrue(t.supported());
        assertTrue(t.tags().first("ALBUM").isEmpty());
        assertTrue(t.tags().first("ALBUMARTIST").isEmpty());
        assertTrue(t.tags().first("DATE").isEmpty());
        assertTrue(t.tags().first("TITLE").isEmpty());
    }

    @Test
    void dstAreaMakesEveryTrackUnsupported() throws IOException {
        Path p = write(disc().frameFormat(0));
        List<TrackInfo> infos = handler.probe(p);
        assertEquals(3, infos.size());
        for (int i = 0; i < 3; i++) {
            TrackInfo t = infos.get(i);
            assertEquals(i + 1, t.subsong());
            assertFalse(t.supported());
            assertNull(t.format());
            assertEquals("DST-compressed SACD is not supported yet", t.reason());
            assertEquals("SACD", t.codec());
        }
        assertEquals("Opening", infos.get(0).tags().first("TITLE").orElseThrow());
    }

    @Test
    void noStereoAreaIsOneUnsupportedRow() throws IOException {
        List<TrackInfo> infos = handler.probe(write(disc().noStereoArea()));
        assertEquals(1, infos.size());
        assertFalse(infos.get(0).supported());
        assertEquals("multichannel-only SACD is not supported in v1", infos.get(0).reason());
    }

    @Test
    void otherSamplingFrequencyIsOneUnsupportedRow() throws IOException {
        List<TrackInfo> infos = handler.probe(write(disc().fsCode(5)));
        assertEquals(1, infos.size());
        assertFalse(infos.get(0).supported());
    }

    @Test
    void anIsoThatIsNotSacdIsUnsupportedNotAnError() throws IOException {
        Path p = dir.resolve("dvd.iso");
        Files.write(p, new byte[3_000_000]);
        List<TrackInfo> infos = handler.probe(p);
        assertEquals(1, infos.size());
        assertFalse(infos.get(0).supported());
        assertEquals("not an SACD image", infos.get(0).reason());
        assertEquals("SACD", infos.get(0).codec());
    }

    @Test
    void damagedTocIsAnError() throws IOException {
        byte[] image = disc().build();
        int at = SacdImageBuilder.AREA_TOC * 2048;
        Arrays.fill(image, at, at + 8, (byte) 0);
        Path p = dir.resolve("bad.iso");
        Files.write(p, image);
        assertThrows(DecodeException.class, () -> handler.probe(p));
    }

    @Test
    void missingFileIsAnIoError() {
        assertThrows(IOException.class, () -> handler.probe(dir.resolve("nope.iso")));
    }

    // ---- open and decode --------------------------------------------------------------------------------------

    @Test
    void everyTrackDecodesByteExact() throws IOException {
        SacdImageBuilder b = disc();
        Path p = write(b);
        for (int i = 0; i < 3; i++) {
            try (Decoder d = handler.open(p, i + 1)) {
                assertEquals(StreamFormat.dsd(2_822_400, 2, (long) b.lengthFrames(i) * FRAME, BitOrder.MSB_FIRST),
                        d.format());
                assertEquals(0, d.position());
                assertArrayEquals(b.trackAudio(i), DsdDecode.all(d, 1000), "track " + (i + 1));
                assertEquals((long) b.lengthFrames(i) * FRAME, d.position());
                assertEquals(0, DsdDecode.some(d, 10).length);
            }
        }
    }

    @Test
    void decodesTheSameWhateverTheReadSize() throws IOException {
        SacdImageBuilder b = disc();
        Path p = write(b);
        for (int step : new int[] {1, 7, 4703, 4704, 4705, 20_000, 1_000_000}) {
            try (Decoder d = handler.open(p, 3)) {
                assertArrayEquals(b.trackAudio(2), DsdDecode.all(d, step), "step " + step);
            }
        }
    }

    @Test
    void seekLandsOnTheExactByte() throws IOException {
        SacdImageBuilder b = disc();
        Path p = write(b);
        for (int track = 1; track <= 3; track++) {
            byte[] audio = b.trackAudio(track - 1);
            int frames = audio.length / 2;
            try (Decoder d = handler.open(p, track)) {
                long[] targets = {0, 1, FRAME - 1, FRAME, FRAME + 1, 3L * FRAME + 1234, frames - 5000, frames - 1};
                for (long target : targets) {
                    d.seek(target);
                    assertEquals(target, d.position());
                    byte[] got = DsdDecode.some(d, 5000);
                    int want = (int) Math.min(5000, frames - target);
                    assertArrayEquals(Arrays.copyOfRange(audio, (int) (2 * target), (int) (2 * (target + want))), got,
                            "track " + track + " seek " + target);
                    assertEquals(target + want, d.position());
                }
                // backwards, after the end, then to the middle again
                d.seek(frames);
                assertEquals(0, DsdDecode.some(d, 10).length);
                d.seek(frames + 1_000_000L);
                assertEquals(frames, d.position());
                assertEquals(0, DsdDecode.some(d, 10).length);
                d.seek(2L * FRAME + 77);
                assertArrayEquals(Arrays.copyOfRange(audio, 2 * (2 * FRAME + 77), 2 * (2 * FRAME + 77 + 100)),
                        DsdDecode.some(d, 100));
            }
        }
    }

    @Test
    void seekToEveryFrameStartAndEdgeIsExact() throws IOException {
        // every frame of track 2, which starts in the middle of a sector
        SacdImageBuilder b = disc();
        byte[] audio = b.trackAudio(1);
        try (Decoder d = handler.open(write(b), 2)) {
            for (int f = b.lengthFrames(1) - 1; f >= 0; f--) {
                d.seek((long) f * FRAME);
                assertArrayEquals(Arrays.copyOfRange(audio, 2 * f * FRAME, 2 * f * FRAME + 64), DsdDecode.some(d, 32),
                        "frame " + f);
            }
        }
    }

    @Test
    void readValidatesItsArguments() throws IOException {
        try (Decoder d = handler.open(write(disc()), 1); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(64, 1);
            assertThrows(IllegalArgumentException.class, () -> d.read(dst, -1));
            assertThrows(IllegalArgumentException.class, () -> d.read(dst, 33)); // 33 frames need 66 bytes
            assertEquals(0, d.read(dst, 0));
            assertThrows(IllegalArgumentException.class, () -> d.seek(-1));
        }
    }

    @Test
    void closedDecoderThrowsAndCloseIsIdempotent() throws IOException {
        Decoder d = handler.open(write(disc()), 1);
        d.close();
        d.close();
        assertThrows(IOException.class, () -> DsdDecode.some(d, 10));
        assertThrows(IOException.class, () -> d.seek(0));
    }

    // ---- refusals ---------------------------------------------------------------------------------------------

    @Test
    void dstTrackCannotBeOpened() throws IOException {
        Path p = write(disc().frameFormat(0));
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 1));
        assertEquals("DST-compressed SACD is not supported yet", e.reason());
    }

    @Test
    void noStereoAreaCannotBeOpened() throws IOException {
        Path p = write(disc().noStereoArea());
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 1));
        assertEquals("multichannel-only SACD is not supported in v1", e.reason());
    }

    @Test
    void notSacdCannotBeOpened() throws IOException {
        Path p = dir.resolve("dvd.iso");
        Files.write(p, new byte[3_000_000]);
        assertThrows(UnsupportedFormatException.class, () -> handler.open(p, 1));
    }

    @Test
    void aTrackThatDoesNotExistIsAnError() throws IOException {
        Path p = write(disc());
        assertThrows(DecodeException.class, () -> handler.open(p, 0));
        assertThrows(DecodeException.class, () -> handler.open(p, 4));
        assertThrows(DecodeException.class, () -> handler.open(p, -1));
    }

    @Test
    void registryOpensTrackBySubsong() throws IOException {
        SacdImageBuilder b = disc();
        Path p = write(b);
        try (Decoder d = DecoderRegistry.standard().open(new TrackRef(p, 2))) {
            assertArrayEquals(b.trackAudio(1), DsdDecode.all(d, 4096));
        }
    }

    // ---- damage -----------------------------------------------------------------------------------------------

    @Test
    void truncatedImageGivesTheGoodFramesThenThrows() throws IOException {
        SacdImageBuilder b = disc();
        byte[] image = b.build();
        // cut the image in the middle of track 3
        long cutSector = b.startLsn(2) + 40;
        Path p = dir.resolve("cut.iso");
        Files.write(p, Arrays.copyOf(image, (int) (cutSector * 2048)));
        byte[] audio = b.trackAudio(2);
        try (Decoder d = handler.open(p, 3); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(2L * 1_000_000, 1);
            long total = 0;
            IOException failure = null;
            while (true) {
                try {
                    int n = d.read(dst, 5000);
                    assertTrue(n > 0, "a clean end would mean the damage was missed");
                    byte[] chunk = dst.asSlice(0, 2L * n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                    assertArrayEquals(Arrays.copyOfRange(audio, (int) (2 * total), (int) (2 * (total + n))), chunk);
                    total += n;
                } catch (IOException e) {
                    failure = e;
                    break;
                }
            }
            assertInstanceOf(DecodeException.class, failure);
            assertTrue(total > 0 && total < audio.length / 2, "good frames before the damage: " + total);
            assertEquals(total, d.position());
            // keeps throwing until a seek
            assertThrows(IOException.class, () -> d.read(dst, 10));
            assertThrows(IOException.class, () -> d.read(dst, 10));
            d.seek(0);
            assertEquals(2 * 100, DsdDecode.some(d, 100).length);
            d.seek(total);
            assertThrows(IOException.class, () -> d.read(dst, 1_000_000));
        }
    }

    @Test
    void aTimeCodeJumpIsDamage() throws IOException {
        SacdImageBuilder b = disc();
        byte[] image = b.build();
        // corrupt the time code of the third frame start of track 1
        int seen = 0;
        for (long s = b.startLsn(0); s < b.startLsn(0) + b.lengthSectors(0); s++) {
            int base = (int) (s * 2048);
            int nf = (image[base] & 0xFF) >>> 2 & 7;
            if (nf == 1 && ++seen == 3) {
                int np = (image[base] & 0xFF) >>> 5;
                image[base + 1 + 2 * np + 2] += 30; // the frame field of the time code
                break;
            }
        }
        Path p = dir.resolve("jump.iso");
        Files.write(p, image);
        byte[] audio = b.trackAudio(0);
        try (Decoder d = handler.open(p, 1); Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate(2L * 100_000, 1);
            long total = 0;
            IOException failure = null;
            while (failure == null) {
                try {
                    int n = d.read(dst, 100_000);
                    assertTrue(n > 0);
                    assertArrayEquals(Arrays.copyOfRange(audio, (int) (2 * total), (int) (2 * (total + n))),
                            dst.asSlice(0, 2L * n).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
                    total += n;
                } catch (IOException e) {
                    failure = e;
                }
            }
            assertInstanceOf(DecodeException.class, failure);
            assertEquals(2L * FRAME, total, "the frames before the bad one");
        }
    }
}
