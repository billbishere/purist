package purist.decode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import purist.decode.aiff.AiffHandler;
import purist.decode.cue.CueHandler;
import purist.decode.dsd.DffHandler;
import purist.decode.dsd.DsfHandler;
import purist.decode.flac.FlacHandler;
import purist.decode.sacd.SacdHandler;
import purist.decode.wav.WavHandler;
import purist.playlist.TrackRef;

/**
 * Finds the {@link FormatHandler} for a file by its extension (case-insensitive). Formats Purist recognises but
 * will not play in v1 are still "audio files": the library lists them greyed out with the reason.
 */
public final class DecoderRegistry {

    /** Extensions of audio formats that are recognised and deliberately not played. */
    private static final Set<String> KNOWN_UNSUPPORTED =
            Set.of("mp3", "m4a", "m4b", "aac", "ogg", "opus", "wma", "ape", "wv", "ts");

    private final Map<String, FormatHandler> byExtension = new ConcurrentHashMap<>();

    /** An empty registry; {@link #standard()} is the one with the built-in formats. */
    public DecoderRegistry() {
    }

    /** WAV, AIFF, FLAC, DSF, DFF, SACD ISO, and CUE sheets over any of the single-track ones. */
    public static DecoderRegistry standard() {
        DecoderRegistry r = new DecoderRegistry();
        r.register(new WavHandler());
        r.register(new AiffHandler());
        r.register(new FlacHandler());
        r.register(new DsfHandler());
        r.register(new DffHandler());
        r.register(new SacdHandler());
        r.register(new CueHandler(r));
        return r;
    }

    /** Adds a handler for each of its extensions, replacing any handler already registered for one. */
    public void register(FormatHandler handler) {
        for (String ext : handler.extensions()) {
            byExtension.put(ext.toLowerCase(Locale.ROOT), handler);
        }
    }

    public Optional<FormatHandler> handlerFor(Path p) {
        return Optional.ofNullable(byExtension.get(extension(p)));
    }

    /** True if a handler plays it or it is a known-unsupported audio format. */
    public boolean isAudioFile(Path p) {
        String ext = extension(p);
        return byExtension.containsKey(ext) || KNOWN_UNSUPPORTED.contains(ext);
    }

    /** Every extension (lower-case, no dot) {@link #isAudioFile} accepts: the handlers' and the known-unsupported ones. */
    public Set<String> audioExtensions() {
        Set<String> all = new TreeSet<>(KNOWN_UNSUPPORTED);
        all.addAll(byExtension.keySet());
        return Collections.unmodifiableSet(all);
    }

    /**
     * Describes the tracks in {@code p}: the handler's answer, one unsupported row for a known-unsupported
     * format, or nothing for any other file.
     */
    public List<TrackInfo> probe(Path p) throws IOException {
        String ext = extension(p);
        FormatHandler handler = byExtension.get(ext);
        if (handler != null) {
            return handler.probe(p);
        }
        if (KNOWN_UNSUPPORTED.contains(ext)) {
            return List.of(TrackInfo.unsupported(p, upper(ext), notSupportedReason(ext)));
        }
        return List.of();
    }

    /**
     * Opens the track.
     *
     * @throws UnsupportedFormatException if no handler plays the file, with the reason
     */
    public Decoder open(TrackRef ref) throws IOException {
        Path p = ref.path();
        String ext = extension(p);
        FormatHandler handler = byExtension.get(ext);
        if (handler != null) {
            return handler.open(p, ref.subsong());
        }
        if (KNOWN_UNSUPPORTED.contains(ext)) {
            throw new UnsupportedFormatException(notSupportedReason(ext));
        }
        if (ext.isEmpty()) {
            Path name = p.getFileName();
            throw new UnsupportedFormatException(
                    (name == null ? p.toString() : name.toString()) + " has no file extension");
        }
        throw new UnsupportedFormatException(upper(ext) + " is not an audio format Purist can play");
    }

    private static String notSupportedReason(String ext) {
        return upper(ext) + " is not supported in v1";
    }

    private static String upper(String ext) {
        return ext.toUpperCase(Locale.ROOT);
    }

    /** The lower-case text after the last dot of the file name; empty if there is none (a leading dot is not one). */
    private static String extension(Path p) {
        Path fileName = p.getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
