package purist.decode.sacd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import purist.audio.BitOrder;
import purist.decode.Decoder;
import purist.decode.TrackInfo;

/**
 * Demuxes the user's real SACD ISO (read-only). Run with
 * {@code mvn test "-Dtest.excluded=" -Dgroups=library -Dtest=SacdRealDiscFramesTest}.
 */
@Tag("library")
class SacdRealDiscFramesTest {

    private static final int FRAME = 9408; // 4704 bytes x 2 channels

    @Test
    void firstFramesOfTrackOneAreWholeAndSequential() throws IOException {
        try (SacdImage img = SacdImage.open(SacdRealDiscTest.DISC)) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdTrack t1 = a.tracks().get(0);
            SacdFrameReader r = new SacdFrameReader(img, a, t1.startLsn(), t1.startLsn() + t1.lengthSectors());
            byte[] frame = new byte[FRAME];
            boolean anyNonZero = false;
            for (int k = 0; k < 750; k++) {
                java.util.Arrays.fill(frame, (byte) 0x5A);
                assertEquals(150 + k, r.nextFrame(frame), "frame " + k);
                for (byte b : frame) {
                    anyNonZero |= b != 0x5A;
                }
            }
            assertTrue(anyNonZero);
        }
    }

    @Test
    void trackNineReadsToItsDeclaredLength() throws IOException {
        try (SacdImage img = SacdImage.open(SacdRealDiscTest.DISC)) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdTrack t9 = a.tracks().get(8);
            SacdFrameReader r = new SacdFrameReader(img, a, t9.startLsn(), t9.startLsn() + t9.lengthSectors());
            byte[] frame = new byte[FRAME];
            long frames = 0;
            long tc;
            long expected = t9.startTimeFrames();
            while ((tc = r.nextFrame(frame)) >= 0) {
                assertEquals(expected++, tc, "frame " + frames);
                frames++;
            }
            System.out.println("track 9: " + frames + " frames read, " + t9.lengthFrames() + " declared");
            assertTrue(Math.abs(frames - t9.lengthFrames()) <= 1, frames + " vs " + t9.lengthFrames());
        }
    }

    @Test
    void probeListsNineSupportedTracks() throws IOException {
        List<TrackInfo> infos = new SacdHandler().probe(SacdRealDiscTest.DISC);
        assertEquals(9, infos.size());
        for (TrackInfo t : infos) {
            assertTrue(t.supported(), t.reason());
            assertEquals("SACD", t.codec());
            assertEquals("DSD64", t.badge());
            assertEquals(BitOrder.MSB_FIRST, t.format().dsdOrder());
            System.out.println(t.subsong() + "  " + t.displayTitle() + "  " + t.displayArtist() + "  "
                    + t.format().seconds(t.format().lengthFrames()) + " s  " + t.tags().first("DATE").orElse("-"));
        }
        assertEquals(1, infos.get(0).subsong());
        assertEquals("Stranglehold", infos.get(0).displayTitle());
        assertEquals("Ted Nugent", infos.get(0).tags().first("ALBUM").orElseThrow());
    }

    @Test
    void everyTrackDecodesToItsDeclaredLengthWithoutAGap() throws IOException {
        SacdHandler handler = new SacdHandler();
        for (TrackInfo t : handler.probe(SacdRealDiscTest.DISC)) {
            long declared = t.format().lengthFrames();
            try (Decoder d = handler.open(SacdRealDiscTest.DISC, t.subsong()); Arena arena = Arena.ofConfined()) {
                MemorySegment dst = arena.allocate(2L * 1_000_000, 1);
                long total = 0;
                int n;
                while ((n = d.read(dst, 1_000_000)) > 0) {
                    total += n;
                }
                System.out.println("track " + t.subsong() + ": " + total + " of " + declared + " bytes per channel");
                assertTrue(declared - total <= 4704 && total <= declared, "track " + t.subsong() + ": " + total
                        + " of " + declared);
            }
        }
    }

    @Test
    void seekMatchesSequentialReading() throws IOException {
        SacdHandler handler = new SacdHandler();
        for (int[] c : new int[][] {{1, 300, 1234}, {2, 1500, 17}, {2, 0, 0}, {5, 777, 4703}}) {
            long target = 4704L * c[1] + c[2];
            byte[] viaSeek;
            byte[] sequential;
            try (Decoder d = handler.open(SacdRealDiscTest.DISC, c[0])) {
                d.seek(target);
                assertEquals(target, d.position());
                viaSeek = purist.testutil.DsdDecode.some(d, 20_000);
            }
            try (Decoder d = handler.open(SacdRealDiscTest.DISC, c[0]); Arena arena = Arena.ofConfined()) {
                MemorySegment dst = arena.allocate(2L * 1_000_000, 1);
                long left = target;
                while (left > 0) {
                    int n = d.read(dst, (int) Math.min(left, 1_000_000));
                    assertTrue(n > 0);
                    left -= n;
                }
                sequential = purist.testutil.DsdDecode.some(d, 20_000);
            }
            assertEquals(40_000, viaSeek.length);
            assertArrayEquals(sequential, viaSeek, "track " + c[0] + " at " + target);
        }
    }
}
