package purist.audio;

import java.util.Objects;

/**
 * What a decoder produces. PCM is delivered as interleaved little-endian int32 holding each sample
 * right-aligned and sign-extended; DSD as interleaved bytes, one byte (8 DSD bits) per channel per frame,
 * in {@code dsdOrder}.
 *
 * @param sampleRate   PCM sample rate in Hz, or the DSD bit rate per channel (2822400 for DSD64)
 * @param bits         PCM valid bits per sample; 1 for DSD
 * @param lengthFrames length in frames (a DSD frame is 8 bits per channel), or less than zero if unknown
 * @param dsdOrder     bit order inside each DSD byte; null for PCM
 */
public record StreamFormat(Kind kind, int sampleRate, int bits, int channels, long lengthFrames, BitOrder dsdOrder) {

    public static StreamFormat pcm(int rate, int bits, int channels, long frames) {
        return new StreamFormat(Kind.PCM, rate, bits, channels, frames, null);
    }

    public static StreamFormat dsd(int bitRate, int channels, long frames, BitOrder order) {
        Objects.requireNonNull(order, "order");
        return new StreamFormat(Kind.DSD, bitRate, 1, channels, frames, order);
    }

    /** Bytes one frame occupies in the decoder's output: 4 per channel for PCM, 1 per channel for DSD. */
    public int bytesPerDecodedFrame() {
        return kind == Kind.PCM ? 4 * channels : channels;
    }

    /** The duration of {@code frames} frames in seconds. A DSD frame is 8 bits per channel. */
    public double seconds(long frames) {
        return kind == Kind.PCM ? (double) frames / sampleRate : frames * 8.0 / sampleRate;
    }

    /** Display label such as {@code 24/96}, {@code 16/44.1} or {@code DSD128}. */
    public String label() {
        if (kind == Kind.PCM) {
            return bits + "/" + kiloHertz(sampleRate);
        }
        if (sampleRate % 44100 == 0) {
            return "DSD" + (sampleRate / 44100);
        }
        return "DSD " + sampleRate + " Hz";
    }

    /** True when the two formats can share one open audio stream: same kind, rate and channel count. */
    public boolean sameStream(StreamFormat o) {
        return kind == o.kind && sampleRate == o.sampleRate && channels == o.channels;
    }

    /** {@code 44100 -> "44.1"}, {@code 96000 -> "96"}, {@code 88200 -> "88.2"}, with no locale involved. */
    private static String kiloHertz(int hz) {
        int whole = hz / 1000;
        int fraction = hz % 1000;
        if (fraction == 0) {
            return Integer.toString(whole);
        }
        char[] digits = {(char) ('0' + fraction / 100), (char) ('0' + fraction / 10 % 10), (char) ('0' + fraction % 10)};
        int end = digits.length;
        while (digits[end - 1] == '0') {
            end--;
        }
        return whole + "." + new String(digits, 0, end);
    }
}
