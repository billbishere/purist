package purist.decode.sacd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.DecodeException;
import purist.testutil.SacdImageBuilder;

class SacdFrameReaderTest {

    private static final int FRAME = 9408;

    @TempDir
    Path dir;

    private SacdImageBuilder disc() {
        return new SacdImageBuilder().track("One", "A", 11).track("Two", "A", 8).track("Three", "A", 20);
    }

    private SacdImage open(byte[] image) throws IOException {
        Path p = dir.resolve("disc.iso");
        Files.write(p, image);
        return SacdImage.open(p);
    }

    private static SacdFrameReader readerFor(SacdImage img, SacdTrack t) {
        return new SacdFrameReader(img, img.stereoArea().orElseThrow(), t.startLsn(), t.startLsn() + t.lengthSectors());
    }

    /** Everything the reader gives, frame bytes concatenated; the time codes go to {@code timeCodes}. */
    private static byte[] drain(SacdFrameReader r, List<Long> timeCodes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] frame = new byte[FRAME];
        while (true) {
            long tc = r.nextFrame(frame);
            if (tc < 0) {
                assertEquals(-1, tc);
                return out.toByteArray();
            }
            timeCodes.add(tc);
            out.write(frame, 0, FRAME);
        }
    }

    @Test
    void assemblesFramesAcrossSectors() throws IOException {
        SacdImageBuilder b = disc();
        byte[] image = b.build();
        // the premise: frame 0 is 2016 + 2016 + 2016 + 2016 + 1344 bytes of audio over sectors 624..628, each
        // sector also carrying a supplementary packet (type 3)
        int[] audioLens = new int[5];
        for (int s = 0; s < 5; s++) {
            int base = (624 + s) * 2048;
            int np = (image[base] & 0xFF) >>> 5;
            int total = 0;
            boolean supplementary = false;
            for (int i = 0; i < np; i++) {
                int v = (image[base + 1 + 2 * i] & 0xFF) << 8 | (image[base + 2 + 2 * i] & 0xFF);
                if (((v >>> 11) & 7) == 2) {
                    if (s < 4 || i < 2) {
                        total += v & 0x7FF;
                    }
                } else if (((v >>> 11) & 7) == 3) {
                    supplementary = true;
                }
            }
            audioLens[s] = total;
            assertTrue(supplementary, "sector " + (624 + s));
        }
        assertArrayEquals(new int[] {2016, 2016, 2016, 2016, 1344}, audioLens);

        try (SacdImage img = open(image)) {
            for (int t = 0; t < 3; t++) {
                List<Long> tcs = new ArrayList<>();
                byte[] got = drain(readerFor(img, img.stereoArea().orElseThrow().tracks().get(t)), tcs);
                assertArrayEquals(b.trackAudio(t), got, "track " + (t + 1));
                assertEquals(b.lengthFrames(t), tcs.size(), "frames of track " + (t + 1));
            }
        }
    }

    @Test
    void timeCodesSequential() throws IOException {
        SacdImageBuilder b = disc();
        try (SacdImage img = open(b.build())) {
            for (int t = 0; t < 3; t++) {
                List<Long> tcs = new ArrayList<>();
                drain(readerFor(img, img.stereoArea().orElseThrow().tracks().get(t)), tcs);
                for (int k = 0; k < tcs.size(); k++) {
                    assertEquals(b.startFrame(t) + k, tcs.get(k), "track " + (t + 1) + " frame " + k);
                }
            }
            assertEquals(150L, b.startFrame(0));
        }
    }

    @Test
    void firstFrameOfAMidSectorTrackStartsAtItsOwnTimeCode() throws IOException {
        SacdImageBuilder b = disc();
        try (SacdImage img = open(b.build())) {
            SacdTrack two = img.stereoArea().orElseThrow().tracks().get(1);
            SacdFrameReader r = readerFor(img, two);
            byte[] frame = new byte[FRAME];
            assertEquals(161, r.nextFrame(frame)); // the tail of frame 160 sits in the first sector and is dropped
            assertArrayEquals(SacdImageBuilder.frame(161, 2), frame);
        }
    }

    @Test
    void seekResyncsOnFrameStart() throws IOException {
        SacdImageBuilder b = disc();
        try (SacdImage img = open(b.build())) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdFrameReader r = new SacdFrameReader(img, a, a.trackAreaStart(), a.trackAreaEnd() + 1);
            byte[] frame = new byte[FRAME];
            // frame 0 begins in sector 624, frame 1 in sector 628: seeking into the middle of frame 0 skips to frame 1
            r.seekToSector(626);
            assertEquals(1, r.nextFrame(frame));
            assertArrayEquals(SacdImageBuilder.frame(1, 2), frame);
            assertEquals(2, r.nextFrame(frame));
            assertArrayEquals(SacdImageBuilder.frame(2, 2), frame);

            // a seek in the middle of assembling a frame forgets the partial frame
            r.seekToSector(a.trackAreaStart() + 700); // frame 150 starts exactly here
            assertEquals(150, r.nextFrame(frame));
            assertArrayEquals(SacdImageBuilder.frame(150, 2), frame);
            r.seekToSector(a.trackAreaStart() + 703);
            long tc = r.nextFrame(frame);
            assertEquals(151, tc);
            assertArrayEquals(SacdImageBuilder.frame(151, 2), frame);

            // and back to the start
            r.seekToSector(624);
            assertEquals(0, r.nextFrame(frame));
            assertArrayEquals(SacdImageBuilder.frame(0, 2), frame);
        }
    }

    @Test
    void readerStopsAtTheEndSectorAndStaysThere() throws IOException {
        try (SacdImage img = open(disc().build())) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdFrameReader r = new SacdFrameReader(img, a, 624, 624 + 14); // exactly three frames
            byte[] frame = new byte[FRAME];
            assertEquals(0, r.nextFrame(frame));
            assertEquals(1, r.nextFrame(frame));
            assertEquals(2, r.nextFrame(frame));
            assertEquals(-1, r.nextFrame(frame));
            assertEquals(-1, r.nextFrame(frame));
        }
    }

    @Test
    void dstSectorThrows() throws IOException {
        byte[] image = disc().build();
        image[630 * 2048] |= 1; // the DST flag of sector 630
        try (SacdImage img = open(image)) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdFrameReader r = new SacdFrameReader(img, a, 624, a.trackAreaEnd() + 1);
            byte[] frame = new byte[FRAME];
            assertEquals(0, r.nextFrame(frame)); // frame 0 ends in sector 628, before the bad sector
            assertArrayEquals(SacdImageBuilder.frame(0, 2), frame);
            DecodeException e = assertThrows(DecodeException.class, () -> r.nextFrame(frame));
            assertEquals("DST frame in plain-DSD area", e.getMessage());
        }
    }

    @Test
    void aFrameStartInsideAFrameThrows() throws IOException {
        byte[] image = disc().build();
        // sector 626 becomes a copy of sector 624, which starts a frame: frame 0 is still unfinished
        System.arraycopy(image, 624 * 2048, image, 626 * 2048, 2048);
        try (SacdImage img = open(image)) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdFrameReader r = new SacdFrameReader(img, a, 624, a.trackAreaEnd() + 1);
            assertThrows(DecodeException.class, () -> r.nextFrame(new byte[FRAME]));
        }
    }

    @Test
    void packetsLongerThanTheSectorThrow() throws IOException {
        byte[] image = disc().build();
        int base = 625 * 2048;
        image[base + 3] = (byte) 0x17; // the audio packet (type 2) claims 2047 bytes
        image[base + 4] = (byte) 0xFF;
        try (SacdImage img = open(image)) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdFrameReader r = new SacdFrameReader(img, a, 624, a.trackAreaEnd() + 1);
            assertThrows(DecodeException.class, () -> r.nextFrame(new byte[FRAME]));
        }
    }

    @Test
    void truncatedImageThrowsOnceTheEndIsReached() throws IOException {
        byte[] image = disc().build();
        byte[] cut = Arrays.copyOf(image, 640 * 2048);
        try (SacdImage img = open(cut)) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdFrameReader r = new SacdFrameReader(img, a, 624, a.trackAreaEnd() + 1);
            byte[] frame = new byte[FRAME];
            assertEquals(0, r.nextFrame(frame));
            assertEquals(1, r.nextFrame(frame));
            assertEquals(2, r.nextFrame(frame));
            assertThrows(DecodeException.class, () -> r.nextFrame(frame));
        }
    }

    @Test
    void outputBufferMustHoldAFrame() throws IOException {
        try (SacdImage img = open(disc().build())) {
            SacdArea a = img.stereoArea().orElseThrow();
            SacdFrameReader r = new SacdFrameReader(img, a, 624, 700);
            assertThrows(IllegalArgumentException.class, () -> r.nextFrame(new byte[FRAME - 1]));
        }
    }
}
