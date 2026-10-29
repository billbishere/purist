package purist.audio;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * What happens to a stream between the decoder and the DAC, for the Playback view and the BIT-PERFECT light.
 *
 * @param deviceName   the output device's name
 * @param source       the format being decoded
 * @param outType      the sample type written to the driver
 * @param deviceRate   the rate the device actually runs at, read back from the driver (for DSD the bit rate
 *                     per channel, as for {@link StreamFormat#sampleRate()})
 * @param bufferFrames the driver's buffer size in frames
 * @param transform    the complete set of sample changes applied between decoder and driver
 * @param link         what the device is sent, when the sink describes it itself (WASAPI: {@code "WASAPI exclusive
 *                     24-in-32 @ 96000 Hz"}, or DoP); null for ASIO, whose sample type says it. For DoP
 *                     {@code deviceRate} is the DSD bit rate carried, sixteen times the PCM rate in the label.
 */
public record SignalPath(String deviceName, StreamFormat source, SampleType outType, double deviceRate,
        int bufferFrames, Transform transform, String link) {

    public SignalPath {
        Objects.requireNonNull(deviceName, "deviceName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(outType, "outType");
        Objects.requireNonNull(transform, "transform");
    }

    /** A path with no link label: the driver's sample type is what the device receives (ASIO). */
    public SignalPath(String deviceName, StreamFormat source, SampleType outType, double deviceRate,
            int bufferFrames, Transform transform) {
        this(deviceName, source, outType, deviceRate, bufferFrames, transform, null);
    }

    /** True when the device runs at exactly the source's rate: nothing is being resampled. */
    public boolean rateMatches() {
        return deviceRate == source.sampleRate();
    }

    /**
     * Why this is not bit-perfect, or empty if it is: a rate mismatch first, then dropouts. The transform
     * needs no check: {@link Packer} only ever produces lossless ones.
     *
     * @param dropouts underruns since the track started
     */
    public Optional<String> notBitPerfectReason(long dropouts) {
        if (!rateMatches()) {
            return Optional.of("device runs at " + plain(BigDecimal.valueOf(deviceRate)) + " Hz, source is "
                    + source.sampleRate() + " Hz");
        }
        if (dropouts > 0) {
            return Optional.of(dropouts + " dropouts");
        }
        return Optional.empty();
    }

    /**
     * One line for the signal-path card, such as {@code 24-bit 96 kHz → INT32_LSB @ 96000 Hz} or
     * {@code DSD128 → native DSD (DSD_INT8_MSB1) @ 5.6448 MHz}; with a {@link #link} label, the source then the
     * label: {@code 24-bit 96 kHz → WASAPI exclusive 24-in-32 @ 96000 Hz}. Numbers never depend on the default
     * locale.
     */
    public String describe() {
        BigDecimal rate = BigDecimal.valueOf(deviceRate);
        if (link != null) {
            return sourceLabel() + " → " + link;
        }
        if (source.kind() == Kind.PCM) {
            return source.bits() + "-bit " + plain(BigDecimal.valueOf(source.sampleRate()).movePointLeft(3))
                    + " kHz → " + outType.name() + " @ " + plain(rate) + " Hz";
        }
        return source.label() + " → native DSD (" + outType.name() + ") @ " + plain(rate.movePointLeft(6)) + " MHz";
    }

    private String sourceLabel() {
        if (source.kind() == Kind.PCM) {
            return source.bits() + "-bit " + plain(BigDecimal.valueOf(source.sampleRate()).movePointLeft(3)) + " kHz";
        }
        return source.label();
    }

    /** {@code 44.100 -> "44.1"}, {@code 96.000 -> "96"}, {@code 96000.0 -> "96000"}. */
    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
