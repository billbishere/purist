package purist.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import purist.decode.aiff.AiffHandler;
import purist.decode.flac.FlacHandler;
import purist.decode.wav.WavHandler;
import purist.playlist.TrackRef;

class DecoderRegistryTest {

    /** A handler that records what it was asked to open. */
    private static class Recording implements FormatHandler {
        Path openedPath;
        int openedSubsong = -1;

        @Override
        public List<String> extensions() {
            return List.of("xyz");
        }

        @Override
        public List<TrackInfo> probe(Path p) {
            return List.of(TrackInfo.unsupported(p, "XYZ", "probed"));
        }

        @Override
        public Decoder open(Path p, int subsong) {
            openedPath = p;
            openedSubsong = subsong;
            return null;
        }
    }

    @Test
    void mp3ProbesAsUnsupported() throws IOException {
        Path p = Path.of("C:\\m\\song.mp3");
        List<TrackInfo> infos = DecoderRegistry.standard().probe(p);
        assertEquals(1, infos.size());
        TrackInfo t = infos.get(0);
        assertEquals(p, t.path());
        assertEquals("MP3", t.codec());
        assertFalse(t.supported());
        assertEquals("MP3 is not supported in v1", t.reason());
    }

    @Test
    void everyKnownUnsupportedExtensionProbesAsUnsupportedAndIsAudio() throws IOException {
        DecoderRegistry r = DecoderRegistry.standard();
        for (String ext : List.of("mp3", "m4a", "m4b", "aac", "ogg", "opus", "wma", "ape", "wv", "ts")) {
            Path p = Path.of("C:\\m\\a." + ext);
            assertTrue(r.isAudioFile(p), ext);
            assertTrue(r.handlerFor(p).isEmpty(), ext);
            TrackInfo t = r.probe(p).get(0);
            assertEquals(ext.toUpperCase(), t.codec());
            assertEquals(ext.toUpperCase() + " is not supported in v1", t.reason());
        }
    }

    @Test
    void handlerByExtensionCaseInsensitive() {
        DecoderRegistry r = DecoderRegistry.standard();
        assertInstanceOf(WavHandler.class, r.handlerFor(Path.of("C:\\m\\a.wav")).orElseThrow());
        assertInstanceOf(WavHandler.class, r.handlerFor(Path.of("C:\\m\\A.WAV")).orElseThrow());
        assertInstanceOf(FlacHandler.class, r.handlerFor(Path.of("C:\\m\\a.FlAc")).orElseThrow());
        for (String ext : List.of("aif", "AIFF", "aifc")) {
            assertInstanceOf(AiffHandler.class, r.handlerFor(Path.of("C:\\m\\a." + ext)).orElseThrow(), ext);
        }
        assertTrue(r.isAudioFile(Path.of("C:\\m\\a.FLAC")));
    }

    @Test
    void nonAudioIgnored() throws IOException {
        DecoderRegistry r = DecoderRegistry.standard();
        Path jpg = Path.of("C:\\m\\cover.jpg");
        assertEquals(List.of(), r.probe(jpg));
        assertTrue(r.handlerFor(jpg).isEmpty());
        assertFalse(r.isAudioFile(jpg));
    }

    @Test
    void noExtensionIsNotAudio() throws IOException {
        DecoderRegistry r = DecoderRegistry.standard();
        for (String name : List.of("C:\\m\\README", "C:\\m\\.flac", "C:\\m\\trailing.")) {
            Path p = Path.of(name);
            assertFalse(r.isAudioFile(p), name);
            assertEquals(List.of(), r.probe(p), name);
        }
    }

    @Test
    void registeredHandlerIsFoundAndOpenedWithPathAndSubsong() throws IOException {
        DecoderRegistry r = DecoderRegistry.standard();
        Recording h = new Recording();
        r.register(h);
        Path p = Path.of("C:\\m\\disc.xyz");
        assertSame(h, r.handlerFor(p).orElseThrow());
        assertTrue(r.isAudioFile(p));
        assertEquals("probed", r.probe(p).get(0).reason());
        r.open(new TrackRef(p, 0));
        assertEquals(p, h.openedPath);
        assertEquals(0, h.openedSubsong);
    }

    @Test
    void isoSubsongReachesTheHandler() throws IOException {
        DecoderRegistry r = new DecoderRegistry();
        Recording h = new Recording() {
            @Override
            public List<String> extensions() {
                return List.of("iso");
            }
        };
        r.register(h);
        r.open(TrackRef.parse("file://E:\\x\\disc.iso#5"));
        assertEquals(Path.of("E:\\x\\disc.iso"), h.openedPath);
        assertEquals(5, h.openedSubsong);
    }

    @Test
    void registeredHandlerReplacesBuiltInForSameExtension() {
        DecoderRegistry r = DecoderRegistry.standard();
        FormatHandler custom = new Recording() {
            @Override
            public List<String> extensions() {
                return List.of("wav");
            }
        };
        r.register(custom);
        assertSame(custom, r.handlerFor(Path.of("C:\\m\\a.wav")).orElseThrow());
    }

    @Test
    void openingKnownUnsupportedThrowsWithReason() {
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class,
                () -> DecoderRegistry.standard().open(new TrackRef(Path.of("C:\\m\\a.m4a"), 0)));
        assertEquals("M4A is not supported in v1", e.reason());
    }

    @Test
    void openingUnknownExtensionThrowsClearReason() {
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class,
                () -> DecoderRegistry.standard().open(new TrackRef(Path.of("C:\\m\\cover.jpg"), 0)));
        assertEquals("JPG is not an audio format Purist can play", e.reason());
        UnsupportedFormatException none = assertThrows(UnsupportedFormatException.class,
                () -> DecoderRegistry.standard().open(new TrackRef(Path.of("C:\\m\\README"), 0)));
        assertEquals("README has no file extension", none.reason());
    }

    @Test
    void audioExtensionsListsEveryPlayedAndKnownUnsupportedExtension() {
        java.util.Set<String> exts = DecoderRegistry.standard().audioExtensions();
        assertTrue(exts.containsAll(java.util.Set.of("wav", "aiff", "flac", "dsf", "dff", "iso")), exts.toString());
        assertTrue(exts.containsAll(java.util.Set.of("mp3", "m4a", "m4b", "aac", "ogg", "opus", "wma", "ape", "wv", "ts")),
                exts.toString());
        for (String e : exts) {
            assertEquals(e, e.toLowerCase(java.util.Locale.ROOT));
        }
        assertTrue(new DecoderRegistry().audioExtensions().containsAll(java.util.Set.of("mp3")));
    }
}
