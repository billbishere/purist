package purist.decode.flac;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The STREAMINFO block (RFC 9639 §8.2): block and frame size bounds (0 when unknown), the sample rate, channel
 * count and bit depth that frames may refer to, the total number of inter-channel samples (0 when unknown) and
 * the MD5 of the decoded audio (all zero when absent).
 *
 * <p>The MD5 array is copied in and out, and {@code equals}, {@code hashCode} and {@code toString} use its
 * contents.
 */
public record FlacStreamInfo(int minBlock, int maxBlock, int minFrame, int maxFrame, int sampleRate, int channels,
        int bitsPerSample, long totalSamples, byte[] md5) {

    public FlacStreamInfo {
        if (md5.length != 16) {
            throw new IllegalArgumentException("an MD5 has 16 bytes, not " + md5.length);
        }
        md5 = md5.clone();
    }

    @Override
    public byte[] md5() {
        return md5.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FlacStreamInfo s && minBlock == s.minBlock && maxBlock == s.maxBlock
                && minFrame == s.minFrame && maxFrame == s.maxFrame && sampleRate == s.sampleRate
                && channels == s.channels && bitsPerSample == s.bitsPerSample && totalSamples == s.totalSamples
                && Arrays.equals(md5, s.md5);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(minBlock, maxBlock, minFrame, maxFrame, sampleRate, channels, bitsPerSample,
                totalSamples) + Arrays.hashCode(md5);
    }

    @Override
    public String toString() {
        return "FlacStreamInfo[minBlock=" + minBlock + ", maxBlock=" + maxBlock + ", minFrame=" + minFrame
                + ", maxFrame=" + maxFrame + ", sampleRate=" + sampleRate + ", channels=" + channels
                + ", bitsPerSample=" + bitsPerSample + ", totalSamples=" + totalSamples + ", md5="
                + HexFormat.of().formatHex(md5) + "]";
    }
}
