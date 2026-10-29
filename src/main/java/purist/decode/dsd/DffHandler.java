package purist.decode.dsd;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.decode.UnsupportedFormatException;
import purist.decode.io.FileSource;
import purist.tags.Id3Result;
import purist.tags.Id3v2;
import purist.tags.TagMap;

/**
 * DFF, Philips DSDIFF (spec section 7). Big-endian chunks with 64-bit sizes, padded to an even length:
 * {@code FRM8 "DSD "} holding {@code FVER}, {@code PROP "SND "} (sub-chunks {@code FS  }, {@code CHNL},
 * {@code CMPR}), the {@code DSD } sound data, optionally {@code DIIN} (with {@code DITI} and {@code DIAR}) and an
 * {@code ID3 } chunk. The sound data is byte-interleaved and MSB-first, so it is passed on as it is.
 *
 * <p>Only {@code CMPR = "DSD "} is played. DST-compressed files are refused ("DST-compressed DFF is not supported
 * yet"), as is anything with more than two channels.
 *
 * <p><b>Channel order.</b> {@code CHNL} lists the channel IDs in interleave order. A stereo file must name a left
 * ({@code SLFT} or {@code MLFT}) and a right ({@code SRGT} or {@code MRGT}) channel: left first is played as filed;
 * right first has its two channels swapped (a reorder only, no sample changes) so left stays on the left. Any other
 * pair (centre and LFE, surrounds, a repeated ID, an unknown one) is refused with the IDs named, never guessed at. A
 * mono file is played as it is when its one channel is a front or centre one.
 *
 * <p>The chunk walk ignores the FRM8 size (writers get it wrong, and taggers append after it) and looks at every
 * chunk up to the end of the file. The first {@code PROP "SND "}, {@code DSD } and {@code ID3 } chunk win. A sound
 * chunk longer than the file is clamped to the whole frames present. Tags come from {@code DIIN} (TITLE, ARTIST) and
 * the ID3 chunk; where both have a key the ID3 one wins.
 */
public final class DffHandler implements FormatHandler {

    static final String CODEC = "DFF";
    static final String DST_REFUSAL = "DST-compressed DFF is not supported yet";

    private static final int HEADER_BYTES = 12;
    /** A DIIN text longer than this is not read; real ones are a few dozen bytes. */
    private static final int MAX_TEXT_BYTES = 64 * 1024;

    /**
     * The result of parsing a DFF header.
     *
     * @param format      DSD, MSB-first, {@code lengthFrames} = whole frames present
     * @param swapChannels true when the file interleaves right before left, so the decoder must swap each pair
     * @param soundOffset file offset of the first sound byte
     * @param tags        from {@code DIIN} only (the ID3 chunk is read by {@link #probe}); never null
     * @param id3Offset   file offset of the ID3 chunk's payload, or -1 if there is none
     * @param id3Length   length of that payload, clamped to the file; 0 if there is none
     */
    record DffFile(StreamFormat format, boolean swapChannels, long soundOffset, TagMap tags, long id3Offset,
            long id3Length) {}

    @Override
    public List<String> extensions() {
        return List.of("dff");
    }

    @Override
    public List<TrackInfo> probe(Path p) throws IOException {
        try (FileSource src = FileSource.open(p)) {
            DffFile f = parse(src);
            TagMap tags = new TagMap();
            tags.putAll(f.tags());
            readId3(src, f).ifPresent(id3 -> tags.putAll(id3.tags())); // ID3 wins, key by key
            return List.of(new TrackInfo(p, 0, CODEC, f.format(), tags, true, null));
        } catch (UnsupportedFormatException e) {
            return List.of(TrackInfo.unsupported(p, CODEC, e.reason()));
        }
    }

    /** The front cover of the file's ID3 chunk; empty if none, or if {@code subsong} is not 0. */
    @Override
    public Optional<byte[]> embeddedArt(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            return Optional.empty();
        }
        try (FileSource src = FileSource.open(p)) {
            return readId3(src, parse(src)).flatMap(Id3Result::frontCover);
        } catch (UnsupportedFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Id3Result> readId3(FileSource src, DffFile f) throws IOException {
        if (f.id3Offset() < 0) {
            return Optional.empty();
        }
        return Id3v2.readAt(src, f.id3Offset(), f.id3Length());
    }

    @Override
    public Decoder open(Path p, int subsong) throws IOException {
        if (subsong != 0) {
            throw new DecodeException("a DFF file has one track; subsong " + subsong + " does not exist");
        }
        FileSource src = FileSource.open(p);
        try {
            return new DffDecoder(src, parse(src));
        } catch (IOException | RuntimeException e) {
            try {
                src.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * @throws UnsupportedFormatException for DST or another compression, and for more than two channels
     * @throws DecodeException for a file that is not a well-formed DFF
     */
    static DffFile parse(FileSource src) throws IOException {
        try {
            return parseChunks(src);
        } catch (EOFException e) {
            throw new DecodeException("truncated DFF header", e);
        }
    }

    private static DffFile parseChunks(FileSource src) throws IOException {
        long size = src.size();
        src.seek(0);
        if (!src.fourcc().equals("FRM8")) {
            throw new DecodeException("not a DFF file (no FRM8 header)");
        }
        src.u64be(); // the FRM8 size is not trusted: see the class comment
        if (!src.fourcc().equals("DSD ")) {
            throw new DecodeException("not a DFF file (FRM8 form type is not \"DSD \")");
        }

        long rate = -1;
        int channels = -1;
        String[] channelIds = null;
        String cmpr = null;
        boolean haveProp = false;
        long soundOffset = -1;
        long soundLength = 0;
        long id3Offset = -1;
        long id3Length = 0;
        TagMap tags = new TagMap();

        long pos = 16;
        while (pos + HEADER_BYTES <= size) {
            src.seek(pos);
            String id = src.fourcc();
            long sz = src.u64be();
            long payload = pos + HEADER_BYTES;
            long avail = size - payload;
            if (sz < 0) {
                throw new DecodeException("DFF chunk \"" + id + "\" has an impossible size");
            }
            switch (id) {
                case "PROP" -> {
                    if (!haveProp && sz >= 4 && avail >= 4 && src.fourcc().equals("SND ")) {
                        haveProp = true;
                        long end = payload + Math.min(sz, avail);
                        long sub = payload + 4;
                        while (sub + HEADER_BYTES <= end) {
                            src.seek(sub);
                            String subId = src.fourcc();
                            long subSize = src.u64be();
                            long subPayload = sub + HEADER_BYTES;
                            if (subSize < 0) {
                                throw new DecodeException("DFF chunk \"" + subId + "\" has an impossible size");
                            }
                            long subAvail = end - subPayload;
                            switch (subId) {
                                case "FS  " -> {
                                    if (rate < 0 && subSize >= 4 && subAvail >= 4) {
                                        rate = src.u32be();
                                    }
                                }
                                case "CHNL" -> {
                                    if (channels < 0 && subSize >= 2 && subAvail >= 2) {
                                        channels = src.u16be();
                                        // The count is followed by one 4-byte ID per channel; only 1 or 2 are played.
                                        long idBytes = 4L * channels;
                                        if (channels >= 1 && channels <= 2 && subSize >= 2 + idBytes
                                                && subAvail >= 2 + idBytes) {
                                            channelIds = new String[channels];
                                            for (int i = 0; i < channels; i++) {
                                                channelIds[i] = src.fourcc();
                                            }
                                        }
                                    }
                                }
                                case "CMPR" -> {
                                    if (cmpr == null && subSize >= 4 && subAvail >= 4) {
                                        cmpr = src.fourcc();
                                    }
                                }
                                default -> { }
                            }
                            if (subSize > subAvail) {
                                break;
                            }
                            sub = subPayload + subSize + (subSize & 1);
                        }
                    }
                }
                case "DSD " -> {
                    if (soundOffset < 0) {
                        soundOffset = payload;
                        soundLength = Math.min(sz, Math.max(avail, 0));
                    }
                }
                case "DIIN" -> readDiin(src, payload, Math.min(sz, Math.max(avail, 0)), tags);
                case "ID3 ", "id3 " -> {
                    if (id3Offset < 0) {
                        id3Offset = payload;
                        id3Length = Math.min(sz, Math.max(avail, 0));
                    }
                }
                default -> { }
            }
            if (sz > avail) {
                break; // this chunk runs past the end of the file, so nothing follows it
            }
            pos = payload + sz + (sz & 1); // chunks are padded to an even length
        }

        if (cmpr != null && !cmpr.equals("DSD ")) {
            throw new UnsupportedFormatException(
                    cmpr.equals("DST ") ? DST_REFUSAL : "DFF compression \"" + cmpr.strip() + "\" is not supported");
        }
        if (!haveProp) {
            throw new DecodeException("DFF file has no PROP \"SND \" chunk");
        }
        if (rate < 0) {
            throw new DecodeException("DFF file has no FS chunk");
        }
        if (channels < 0) {
            throw new DecodeException("DFF file has no CHNL chunk");
        }
        if (cmpr == null) {
            throw new DecodeException("DFF file has no CMPR chunk");
        }
        if (channels > 2) {
            throw new UnsupportedFormatException(String.format("multichannel DFF (%d ch)", channels));
        }
        if (channels < 1) {
            throw new DecodeException("DFF declares no channels");
        }
        if (rate < 1 || rate > Integer.MAX_VALUE) {
            throw new DecodeException("DFF sampling frequency " + rate + " Hz is out of range");
        }
        if (soundOffset < 0) {
            throw new DecodeException("DFF file has no DSD sound data chunk");
        }
        if (channelIds == null) {
            throw new DecodeException("DFF CHNL chunk does not list its " + channels + " channel ID(s)");
        }
        boolean swap = swapNeeded(channelIds);
        long frames = soundLength / channels;
        StreamFormat format = StreamFormat.dsd((int) rate, channels, frames, BitOrder.MSB_FIRST);
        return new DffFile(format, swap, soundOffset, tags, id3Offset, id3Length);
    }

    private static boolean isLeft(String id) {
        return id.equals("SLFT") || id.equals("MLFT");
    }

    private static boolean isRight(String id) {
        return id.equals("SRGT") || id.equals("MRGT");
    }

    /**
     * Whether the interleave order of {@code ids} has right before left.
     *
     * @throws UnsupportedFormatException for a layout that is not a front left and right pair (or one front/centre
     *         channel)
     */
    static boolean swapNeeded(String[] ids) throws UnsupportedFormatException {
        if (ids.length == 1) {
            String id = ids[0];
            if (isLeft(id) || isRight(id) || id.equals("C   ")) {
                return false;
            }
        } else if (isLeft(ids[0]) && isRight(ids[1])) {
            return false;
        } else if (isRight(ids[0]) && isLeft(ids[1])) {
            return true;
        }
        StringBuilder names = new StringBuilder();
        for (String id : ids) {
            names.append(names.isEmpty() ? "" : " + ").append(id.strip());
        }
        throw new UnsupportedFormatException("DFF channel layout " + names
                + " is not supported (needs a front left and right pair)");
    }

    /** Reads the {@code DITI} (TITLE) and {@code DIAR} (ARTIST) sub-chunks of a DIIN chunk. */
    private static void readDiin(FileSource src, long payload, long length, TagMap tags) throws IOException {
        long end = payload + length;
        long sub = payload;
        while (sub + HEADER_BYTES <= end) {
            src.seek(sub);
            String id = src.fourcc();
            long sz = src.u64be();
            long subPayload = sub + HEADER_BYTES;
            long avail = end - subPayload;
            if (sz < 0) {
                return;
            }
            if ((id.equals("DITI") || id.equals("DIAR")) && sz >= 4 && avail >= 4) {
                long count = src.u32be();
                long n = Math.min(Math.min(count, sz - 4), Math.min(avail - 4, MAX_TEXT_BYTES));
                String text = decodeText(src.bytes((int) n));
                tags.add(id.equals("DITI") ? "TITLE" : "ARTIST", text);
            }
            if (sz > avail) {
                return;
            }
            sub = subPayload + sz + (sz & 1);
        }
    }

    /** UTF-8 if the bytes are valid UTF-8, else Latin-1; trailing NULs and blanks are dropped. */
    private static String decodeText(byte[] b) {
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            s = new String(b, StandardCharsets.ISO_8859_1);
        }
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == 0 || Character.isWhitespace(s.charAt(end - 1)))) {
            end--;
        }
        return s.substring(0, end);
    }
}
