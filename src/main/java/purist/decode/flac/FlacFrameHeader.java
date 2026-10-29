package purist.decode.flac;

import java.util.Objects;
import purist.decode.DecodeException;
import purist.decode.flac.FlacDamageException.Kind;
import purist.decode.flac.FlacFrameDecoder.IncompleteFrameException;

/**
 * A parsed FLAC frame header (RFC 9639 §9.1), with every "get it from STREAMINFO" code already resolved.
 *
 * @param blockSize the number of inter-channel samples in the frame, 1 to 65535
 * @param sampleRate in Hz
 * @param channelAssignment 0 to 7 for 1 to 8 independent channels, 8 left/side, 9 side/right, 10 mid/side
 * @param bitsPerSample the bit depth of the decoded samples
 * @param codedNumber the frame number (fixed block size) or the number of the first sample (variable)
 * @param variableBlocksize the blocking strategy bit
 * @param headerLength in bytes, including the CRC-8
 */
public record FlacFrameHeader(int blockSize, int sampleRate, int channelAssignment, int bitsPerSample,
        long codedNumber, boolean variableBlocksize, int headerLength) {

    private static final int[] TABLE_RATES = {0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100,
        48000, 96000};
    private static final int[] TABLE_DEPTHS = {0, 8, 12, -1, 16, 20, 24, 32};

    /** The number of channels in the frame. */
    public int channels() {
        return channelAssignment < 8 ? channelAssignment + 1 : 2;
    }

    /**
     * Parses the header at {@code buf[off]} and checks its CRC-8.
     *
     * @throws IncompleteFrameException if the header runs past {@code off + len}
     * @throws DecodeException if there is no sync code, a field holds a reserved or invalid value, or the CRC-8
     *     does not match
     */
    static FlacFrameHeader parse(byte[] buf, int off, int len, FlacStreamInfo info) throws DecodeException {
        Objects.checkFromIndexSize(off, len, buf.length);
        int end = off + len;
        if (len < 2) {
            throw new IncompleteFrameException();
        }
        int sync = (buf[off] & 0xFF) << 8 | buf[off + 1] & 0xFF;
        if ((sync & 0xFFFE) != 0xFFF8) {
            throw new FlacDamageException(Kind.OVERSIZED_FRAME, "no frame sync");
        }
        boolean variable = (sync & 1) != 0;
        if (len < 4) {
            throw new IncompleteFrameException();
        }
        int sizeCode = (buf[off + 2] & 0xFF) >>> 4;
        int rateCode = buf[off + 2] & 0x0F;
        int assignment = (buf[off + 3] & 0xFF) >>> 4;
        int depthCode = (buf[off + 3] >>> 1) & 7;
        if (sizeCode == 0) {
            throw new DecodeException("FLAC frame uses the reserved block size code 0");
        }
        if (rateCode == 15) {
            throw new DecodeException("FLAC frame uses the invalid sample rate code 15");
        }
        if (assignment > 10) {
            throw new DecodeException("FLAC frame uses the reserved channel assignment " + assignment);
        }
        if (depthCode == 3) {
            throw new DecodeException("FLAC frame uses the reserved bit depth code 3");
        }
        if ((buf[off + 3] & 1) != 0) {
            throw new DecodeException("FLAC frame header has its reserved bit set");
        }

        // The coded number: UTF-8-style, 1 to 7 bytes; a fixed-blocksize frame number has at most 31 bits.
        int p = off + 4;
        if (p >= end) {
            throw new IncompleteFrameException();
        }
        int lead = buf[p++] & 0xFF;
        long number = lead;
        if (lead >= 0x80) {
            int bytes = Integer.numberOfLeadingZeros(~lead << 24); // the leading one bits (32 for 0xFF)
            if (bytes == 1 || bytes > 7 || bytes == 7 && !variable) {
                throw new DecodeException("FLAC frame has an invalid coded number");
            }
            number = lead & (0x7F >>> bytes);
            for (int i = 1; i < bytes; i++) {
                if (p >= end) {
                    throw new IncompleteFrameException();
                }
                int b = buf[p++] & 0xFF;
                if ((b & 0xC0) != 0x80) {
                    throw new DecodeException("FLAC frame has an invalid coded number");
                }
                number = number << 6 | b & 0x3F;
            }
        }

        int blockSize;
        if (sizeCode == 6 || sizeCode == 7) {
            int n = sizeCode == 6 ? 1 : 2;
            if (end - p < n) {
                throw new IncompleteFrameException();
            }
            blockSize = (n == 1 ? buf[p] & 0xFF : (buf[p] & 0xFF) << 8 | buf[p + 1] & 0xFF) + 1;
            p += n;
            if (blockSize > 65535) {
                throw new DecodeException("FLAC frame has a block size of 65536, above the maximum of 65535");
            }
        } else if (sizeCode == 1) {
            blockSize = 192;
        } else if (sizeCode <= 5) {
            blockSize = 576 << (sizeCode - 2);
        } else {
            blockSize = 256 << (sizeCode - 8);
        }

        int sampleRate;
        if (rateCode >= 12) {
            int n = rateCode == 12 ? 1 : 2;
            if (end - p < n) {
                throw new IncompleteFrameException();
            }
            int v = n == 1 ? buf[p] & 0xFF : (buf[p] & 0xFF) << 8 | buf[p + 1] & 0xFF;
            p += n;
            sampleRate = rateCode == 12 ? v * 1000 : rateCode == 13 ? v : v * 10;
        } else {
            sampleRate = rateCode == 0 ? info.sampleRate() : TABLE_RATES[rateCode];
        }

        int bitsPerSample = depthCode == 0 ? info.bitsPerSample() : TABLE_DEPTHS[depthCode];

        if (p >= end) {
            throw new IncompleteFrameException();
        }
        if (Crc.crc8(buf, off, p - off) != (buf[p] & 0xFF)) {
            throw new FlacDamageException(Kind.CRC_MISMATCH, "CRC mismatch at frame " + number);
        }
        return new FlacFrameHeader(blockSize, sampleRate, assignment, bitsPerSample, number, variable,
                p + 1 - off);
    }
}
