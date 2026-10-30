package purist.decode.cue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import purist.audio.Kind;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.playlist.TrackRef;
import purist.tags.TagMap;

/**
 * CUE sheets over whole-album files ({@code album.flac} + {@code album.cue}). Each audio track of the sheet is a
 * subsong, numbered as the sheet numbers it, playing from its {@code INDEX 01} to the next track's in the same file
 * (or the end of the file): the pre-gap belongs to the track before, as on the disc, so the album plays back without
 * a gap or a lost sample. The audio file is decoded by its own handler; nothing is converted.
 *
 * <ul>
 *   <li>A sheet written next to separate track files ({@link CueSheet#perTrackFiles}) yields no tracks: those files
 *       are the tracks.
 *   <li>A FILE that does not exist is looked for under the same name with another audio extension, since rippers
 *       often name {@code album.wav} and the file was compressed to {@code album.flac} afterwards.
 *   <li>Tags come from the sheet, then from the audio file for whatever the sheet leaves out.
 * </ul>
 */
public final class CueHandler implements FormatHandler {

    /** Track-level keys of the album file's own tags; they describe the whole file, never one of its tracks. */
    private static final Set<String> FILE_ONLY_KEYS = Set.of("TITLE", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS",
            "CUESHEET");

    private final DecoderRegistry registry;

    /** @param registry reads and opens the audio files the sheets name */
    public CueHandler(DecoderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<String> extensions() {
        return List.of("cue");
    }

    /**
     * The audio files {@code cue} plays from; empty for a sheet that adds nothing: one over separate track files, or
     * one left behind after its album file was split or deleted (none of its files exists).
     */
    public List<Path> audioFiles(Path cue) throws IOException {
        return audioFiles(CueSheet.read(cue));
    }

    private List<Path> audioFiles(CueSheet sheet) {
        if (sheet.perTrackFiles()) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        for (Path p : sheet.files()) {
            out.add(locate(p));
        }
        return out.stream().anyMatch(Files::exists) ? out : List.of();
    }

    @Override
    public List<TrackInfo> probe(Path cue) throws IOException {
        return probe(cue, true);
    }

    /**
     * As {@link #probe(Path)}; with {@code readAudio} false the audio files are never opened (their content is still
     * in the cloud), so each track comes from the sheet alone, with no format, as a cloud-only file does.
     */
    public List<TrackInfo> probe(Path cue, boolean readAudio) throws IOException {
        CueSheet sheet = CueSheet.read(cue);
        if (audioFiles(sheet).isEmpty()) {
            return List.of();
        }
        List<TrackInfo> out = new ArrayList<>();
        for (Path file : sheet.files()) {
            Path audio = locate(file);
            String codec = codec(audio);
            TrackInfo whole = null;
            if (readAudio) {
                whole = wholeFile(audio);
                if (!whole.supported()) {
                    return List.of(TrackInfo.unsupported(cue, codec, whole.reason()));
                }
            }
            List<CueSheet.Track> tracks = tracksIn(sheet, file);
            for (int i = 0; i < tracks.size(); i++) {
                CueSheet.Track t = tracks.get(i);
                StreamFormat format = null;
                if (whole != null && whole.format() != null) {
                    StreamFormat f = whole.format();
                    long from = toFrames(t.start(), f);
                    long to = i + 1 < tracks.size() ? toFrames(tracks.get(i + 1).start(), f) : f.lengthFrames();
                    if (to >= 0 && to < from) {
                        throw new DecodeException("track " + t.number() + " of the CUE sheet starts after the end of "
                                + audio.getFileName());
                    }
                    format = new StreamFormat(f.kind(), f.sampleRate(), f.bits(), f.channels(),
                            to < 0 ? -1 : to - from, f.dsdOrder());
                }
                out.add(new TrackInfo(cue, t.number(), codec, format,
                        tags(sheet, t, whole == null ? null : whole.tags()), true, null));
            }
        }
        return out;
    }

    @Override
    public Decoder open(Path cue, int subsong) throws IOException {
        CueSheet sheet = CueSheet.read(cue);
        CueSheet.Track track = sheet.tracks().stream().filter(t -> t.number() == subsong).findFirst()
                .orElseThrow(() -> new DecodeException("the CUE sheet has no track " + subsong));
        Path audio = locate(track.file());
        if (!Files.exists(audio)) {
            throw new DecodeException("the CUE sheet's file is missing: " + track.file().getFileName());
        }
        List<CueSheet.Track> tracks = tracksIn(sheet, track.file());
        int i = tracks.indexOf(track);
        Decoder d = registry.open(new TrackRef(audio, 0));
        StreamFormat f = d.format();
        long from = toFrames(track.start(), f);
        long to = i + 1 < tracks.size() ? toFrames(tracks.get(i + 1).start(), f) : f.lengthFrames();
        if (to < 0) {
            d.close();
            throw new UnsupportedFormatException("the length of " + audio.getFileName() + " is not known");
        }
        return new RangeDecoder(d, from, Math.max(from, to));
    }

    @Override
    public Optional<byte[]> embeddedArt(Path cue, int subsong) throws IOException {
        CueSheet sheet = CueSheet.read(cue);
        for (CueSheet.Track t : sheet.tracks()) {
            if (t.number() == subsong) {
                Path audio = locate(t.file());
                Optional<FormatHandler> h = registry.handlerFor(audio);
                return h.isPresent() && !(h.get() instanceof CueHandler) ? h.get().embeddedArt(audio, 0)
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** CD frames to decoder frames: PCM frames at the sample rate, or DSD bytes per channel (rate / 8). */
    static long toFrames(long cdFrames, StreamFormat f) {
        long perSecond = f.kind() == Kind.PCM ? f.sampleRate() : f.sampleRate() / 8L;
        return Math.multiplyExact(cdFrames, perSecond) / CueSheet.FRAMES_PER_SECOND;
    }

    /** The one track the audio file holds, from its own handler. */
    private TrackInfo wholeFile(Path audio) throws IOException {
        if (!Files.exists(audio)) {
            throw new DecodeException("the CUE sheet's file is missing: " + audio.getFileName());
        }
        Optional<FormatHandler> h = registry.handlerFor(audio);
        if (h.isPresent() && (h.get() instanceof CueHandler || audio.toString().toLowerCase(Locale.ROOT)
                .endsWith(".iso"))) {
            throw new DecodeException("a CUE sheet cannot play " + audio.getFileName());
        }
        List<TrackInfo> infos = registry.probe(audio);
        if (infos.size() != 1 || infos.get(0).subsong() != 0) {
            return TrackInfo.unsupported(audio, codec(audio), codec(audio) + " is not an audio format Purist can play");
        }
        return infos.get(0);
    }

    /** {@code file}, or the same name with another audio extension next to it, or {@code file} if neither exists. */
    private Path locate(Path file) {
        if (Files.exists(file)) {
            return file;
        }
        Path dir = file.getParent();
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (dir == null || base.isEmpty()) {
            return file;
        }
        for (String ext : List.of("flac", "wav", "aiff", "aif", "dsf", "dff")) {
            Path other = dir.resolve(base + "." + ext);
            if (Files.exists(other)) {
                return other;
            }
        }
        return file;
    }

    private static List<CueSheet.Track> tracksIn(CueSheet sheet, Path file) {
        return sheet.tracks().stream().filter(t -> t.file().equals(file)).toList();
    }

    private static TagMap tags(CueSheet sheet, CueSheet.Track t, TagMap fileTags) {
        TagMap out = new TagMap();
        out.putAll(t.tags());
        out.putAllAbsent(sheet.album());
        out.add("TOTALTRACKS", Integer.toString(sheet.tracks().size()));
        if (out.first("ARTIST").isEmpty()) {
            sheet.album().first("ALBUMARTIST").ifPresent(a -> out.add("ARTIST", a));
        }
        if (fileTags != null) {
            TagMap rest = new TagMap();
            for (Map.Entry<String, List<String>> e : fileTags.asMap().entrySet()) {
                if (!FILE_ONLY_KEYS.contains(e.getKey())) {
                    e.getValue().forEach(v -> rest.add(e.getKey(), v));
                }
            }
            out.putAllAbsent(rest);
        }
        return out;
    }

    private static String codec(Path audio) {
        String name = audio.getFileName() == null ? "" : audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toUpperCase(Locale.ROOT) : "CUE";
    }
}
