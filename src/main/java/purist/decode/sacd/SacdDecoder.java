package purist.decode.sacd;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import purist.audio.BitOrder;
import purist.audio.StreamFormat;
import purist.decode.DecodeException;
import purist.decode.Decoder;

/**
 * Plays one track of a plain-DSD SACD stereo area as byte-interleaved DSD: one byte per channel per frame, MSB
 * first, exactly the bytes on the disc. A "frame" of this decoder is one byte per channel (8 DSD bits); an SACD
 * frame (1/75 s) is {@value SacdArea#FRAME_BYTES_PER_CHANNEL} of them. The decoder holds one SACD frame in a buffer
 * made at construction and hands it out in pieces, so {@link #read} allocates nothing.
 *
 * <p>The time code of every SACD frame must follow the one before it. If the disc is damaged (a sector that cannot
 * be read or demultiplexed, or a frame that is missing), {@link #read} returns the frames that are good and then
 * throws on every call until a {@link #seek}.
 *
 * <p>A seek is lazy: it records the position, and the next {@link #read} finds the frame, so a seek never fails
 * because of damage at the place it points to; that read does.
 */
final class SacdDecoder implements Decoder {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final int PER_CHANNEL = SacdArea.FRAME_BYTES_PER_CHANNEL;
    /** Attempts to land a seek on its frame before giving up. */
    private static final int MAX_SEEK_TRIES = 64;

    private final SacdImage image;
    private final SacdTrack track;
    private final SacdFrameReader reader;
    private final StreamFormat format;
    private final int channels;
    private final int frameBytes;
    private final long totalBytes;
    private final byte[] frame;

    /** Bytes of {@link #frame} already handed out; {@code frameBytes} when it is empty. */
    private int frameOffset;
    /** Index in the track of the next frame the reader will give. */
    private long nextFrameIndex;
    private long position;
    private boolean needsLocate;
    private IOException failure;
    private boolean closed;

    SacdDecoder(SacdImage image, SacdArea area, SacdTrack track) {
        this.image = image;
        this.track = track;
        this.channels = area.channels();
        this.frameBytes = PER_CHANNEL * channels;
        this.totalBytes = track.lengthFrames() * PER_CHANNEL;
        this.format = StreamFormat.dsd(area.dsdRate(), channels, totalBytes, BitOrder.MSB_FIRST);
        this.frame = new byte[frameBytes];
        this.frameOffset = frameBytes;
        this.reader = new SacdFrameReader(image, area, track.startLsn(), track.startLsn() + track.lengthSectors());
    }

    @Override
    public StreamFormat format() {
        return format;
    }

    @Override
    public int read(MemorySegment dst, int maxFrames) throws IOException {
        if (closed) {
            throw new IOException("decoder is closed");
        }
        if (maxFrames < 0) {
            throw new IllegalArgumentException("negative frame count: " + maxFrames);
        }
        long needed = (long) maxFrames * channels;
        if (dst.byteSize() < needed) {
            throw new IllegalArgumentException(
                    "destination holds " + dst.byteSize() + " bytes but " + maxFrames + " frames need " + needed);
        }
        if (failure != null) {
            throw failure;
        }
        int want = (int) Math.min(maxFrames, totalBytes - position);
        int done = 0;
        try {
            if (want > 0 && needsLocate) {
                locate();
            }
            while (done < want) {
                if (frameOffset == frameBytes && !pullFrame()) {
                    break;
                }
                int n = Math.min(want - done, (frameBytes - frameOffset) / channels);
                MemorySegment.copy(frame, frameOffset, dst, BYTE, (long) done * channels, n * channels);
                frameOffset += n * channels;
                done += n;
            }
        } catch (IOException e) {
            failure = e;
        }
        position += done;
        if (done == 0 && failure != null) {
            throw failure;
        }
        return done;
    }

    /** Takes the next SACD frame from the reader; false at the end of the track's sectors. */
    private boolean pullFrame() throws IOException {
        long timeCode = reader.nextFrame(frame);
        if (timeCode < 0) {
            return false;
        }
        long expected = track.startTimeFrames() + nextFrameIndex;
        if (timeCode != expected) {
            throw new DecodeException("SACD track " + track.number() + ": time code " + timeCode
                    + " where " + expected + " should be (a frame is missing or out of order)");
        }
        nextFrameIndex++;
        frameOffset = 0;
        return true;
    }

    /**
     * Puts the reader on the frame holding {@link #position}: estimates its sector from the track's average
     * sectors per frame, reads frames until the time code is the wanted one (stepping back if the estimate was
     * after the frame), and skips the bytes of that frame before {@link #position}.
     */
    private void locate() throws IOException {
        needsLocate = false;
        long f = position / PER_CHANNEL;
        int skip = (int) (position % PER_CHANNEL);
        long target = track.startTimeFrames() + f;
        long first = track.startLsn();
        long last = first + track.lengthSectors() - 1;
        long sector = Math.min(first + f * track.lengthSectors() / track.lengthFrames(), last);
        for (int tries = 0; tries < MAX_SEEK_TRIES; tries++) {
            reader.seekToSector(sector);
            long timeCode = reader.nextFrame(frame);
            if (timeCode < 0 || timeCode > target) {
                if (sector <= first) {
                    throw new DecodeException("SACD track " + track.number() + ": frame " + target
                            + " is not where the track starts");
                }
                long behind = timeCode < 0 ? 1 : timeCode - target;
                long step = behind * track.lengthSectors() / track.lengthFrames() + 2;
                sector = Math.max(first, sector - step);
                continue;
            }
            while (timeCode >= 0 && timeCode < target) {
                timeCode = reader.nextFrame(frame);
            }
            if (timeCode != target) {
                throw new DecodeException("SACD track " + track.number() + ": frame " + target
                        + " is missing" + (timeCode < 0 ? " (the track ends first)" : ""));
            }
            nextFrameIndex = f + 1;
            frameOffset = skip * channels;
            return;
        }
        throw new DecodeException("SACD track " + track.number() + ": cannot find frame " + target);
    }

    @Override
    public void seek(long frame) throws IOException {
        if (closed) {
            throw new IOException("decoder is closed");
        }
        if (frame < 0) {
            throw new IllegalArgumentException("negative frame: " + frame);
        }
        failure = null;
        position = Math.min(frame, totalBytes);
        frameOffset = frameBytes;
        needsLocate = true;
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            image.close();
        }
    }
}
