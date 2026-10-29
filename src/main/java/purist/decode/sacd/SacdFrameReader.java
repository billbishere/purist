package purist.decode.sacd;

import java.io.IOException;
import purist.decode.DecodeException;

/**
 * Demultiplexes the plain-DSD audio sectors of an SACD area into frames. A frame is {@code 4704 * channels} bytes
 * (1/75 s of DSD64), byte-interleaved and MSB-first, and it is cut into audio packets that the sectors carry beside
 * supplementary packets and padding; a frame starts in the middle of a sector as often as not.
 *
 * <p>A sector is a 1-byte header ({@code packet_count = b >> 5}, {@code frame_info_count = (b >> 2) & 7},
 * {@code dst = b & 1}), then {@code packet_count} big-endian 16-bit packet infos ({@code frame_start = v >> 15},
 * {@code type = (v >> 11) & 7}, {@code length = v & 0x7FF}), then {@code frame_info_count} 3-byte time codes
 * (minutes, seconds, frames), then the packet payloads in order. A type-2 (audio) packet with {@code frame_start}
 * set begins a frame and takes the next time code of the sector; every other packet type is skipped.
 *
 * <p>Reading starts at the first frame that <em>begins</em> at or after the starting sector: the tail of a frame
 * that began before it is dropped. {@link #nextFrame} allocates nothing. Not thread-safe; after an exception the
 * reader must be repositioned with {@link #seekToSector} before it is used again.
 */
final class SacdFrameReader {

    private static final int SECTOR_BYTES = SacdImage.SECTOR_BYTES;
    private static final int AUDIO_PACKET = 2;

    private final SacdImage image;
    private final int frameBytes;
    private final long endLsn;
    private final byte[] sector = new byte[SECTOR_BYTES];
    /** The frame being assembled. */
    private final byte[] frame;

    private long nextLsn;
    /** Number of packets in the loaded sector; {@code packetIndex == packetCount} means it is used up. */
    private int packetCount;
    private int packetIndex;
    private int frameInfoCount;
    private int frameInfoIndex;
    /** Offset in the loaded sector of the first time code. */
    private int frameInfoBase;
    /** Offset in the loaded sector of the payload of the packet {@code packetIndex}. */
    private int payloadPos;
    private long loadedLsn;

    private boolean frameActive;
    private int frameLength;
    private long frameTimeCode;

    /**
     * @param startLsn        the first sector to read
     * @param endLsnExclusive the sector at which reading stops
     */
    SacdFrameReader(SacdImage image, SacdArea area, long startLsn, long endLsnExclusive) {
        this.image = image;
        this.frameBytes = SacdArea.FRAME_BYTES_PER_CHANNEL * area.channels();
        this.frame = new byte[frameBytes];
        this.endLsn = endLsnExclusive;
        seekToSector(startLsn);
    }

    /** Bytes in one frame: {@code 4704 * channels}. */
    int frameBytes() {
        return frameBytes;
    }

    /** Forgets any frame in progress and continues from the first frame that begins at or after sector {@code lsn}. */
    void seekToSector(long lsn) {
        nextLsn = lsn;
        packetCount = 0;
        packetIndex = 0;
        frameActive = false;
        frameLength = 0;
    }

    /**
     * Fills {@code out[0 .. frameBytes)} with the next whole frame.
     *
     * @return the frame's absolute time code in 1/75 s frames, or -1 at the end of the sector range
     * @throws DecodeException if a sector is damaged, holds DST, or a frame starts inside another
     * @throws IOException if the image cannot be read
     */
    long nextFrame(byte[] out) throws IOException {
        if (out.length < frameBytes) {
            throw new IllegalArgumentException("a frame needs " + frameBytes + " bytes, not " + out.length);
        }
        while (true) {
            if (packetIndex >= packetCount) {
                if (nextLsn >= endLsn) {
                    return -1;
                }
                loadSector();
                continue;
            }
            int info = (sector[1 + 2 * packetIndex] & 0xFF) << 8 | (sector[2 + 2 * packetIndex] & 0xFF);
            packetIndex++;
            int length = info & 0x7FF;
            int type = (info >>> 11) & 7;
            boolean starts = (info >>> 15) == 1;
            int at = payloadPos;
            payloadPos += length;
            if (type != AUDIO_PACKET) {
                continue;
            }
            if (starts) {
                if (frameActive && frameLength > 0) {
                    throw new DecodeException("SACD sector " + loadedLsn + ": a frame starts after only "
                            + frameLength + " of " + frameBytes + " bytes of the previous one");
                }
                if (frameInfoIndex >= frameInfoCount) {
                    throw new DecodeException("SACD sector " + loadedLsn + ": a frame starts without a time code");
                }
                int t = frameInfoBase + 3 * frameInfoIndex++;
                frameTimeCode = ((sector[t] & 0xFFL) * 60 + (sector[t + 1] & 0xFF)) * 75 + (sector[t + 2] & 0xFF);
                frameActive = true;
                frameLength = 0;
            }
            if (!frameActive) {
                continue; // the tail of a frame that began before where reading started
            }
            if (frameLength + length > frameBytes) {
                throw new DecodeException("SACD sector " + loadedLsn + ": frame " + frameTimeCode + " is longer than "
                        + frameBytes + " bytes");
            }
            System.arraycopy(sector, at, frame, frameLength, length);
            frameLength += length;
            if (frameLength == frameBytes) {
                System.arraycopy(frame, 0, out, 0, frameBytes);
                frameActive = false;
                frameLength = 0;
                return frameTimeCode;
            }
        }
    }

    private void loadSector() throws IOException {
        loadedLsn = nextLsn;
        packetCount = 0; // so that a failure below leaves the sector "used up"
        packetIndex = 0;
        image.readSector(nextLsn++, sector);
        int header = sector[0] & 0xFF;
        if ((header & 1) != 0) {
            throw new DecodeException("DST frame in plain-DSD area");
        }
        int packets = header >>> 5;
        int infos = (header >>> 2) & 7;
        int payloadStart = 1 + 2 * packets + 3 * infos;
        int total = payloadStart;
        for (int i = 0; i < packets; i++) {
            total += ((sector[1 + 2 * i] & 0x07) << 8) | (sector[2 + 2 * i] & 0xFF);
        }
        if (total > SECTOR_BYTES) {
            throw new DecodeException("SACD sector " + loadedLsn + ": its packets add up to " + total
                    + " bytes, more than a sector");
        }
        packetCount = packets;
        frameInfoCount = infos;
        frameInfoIndex = 0;
        frameInfoBase = 1 + 2 * packets;
        payloadPos = payloadStart;
    }
}
