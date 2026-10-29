package purist.audio;

import java.util.Optional;

/**
 * The ASIO sample types Purist will write to, with the codes the driver reports. Floating point and
 * big-endian types are deliberately absent: they cannot carry a bit-perfect stream.
 */
public enum SampleType {
    INT16_LSB(16, 2, 16, false),
    INT24_LSB(17, 3, 24, false),
    INT32_LSB(18, 4, 32, false),
    INT32_LSB16(24, 4, 16, false),
    INT32_LSB18(25, 4, 18, false),
    INT32_LSB20(26, 4, 20, false),
    INT32_LSB24(27, 4, 24, false),
    DSD_INT8_LSB1(32, 1, 1, true),
    DSD_INT8_MSB1(33, 1, 1, true);

    private static final SampleType[] ALL = values();

    private final int asioCode;
    private final int containerBytes;
    private final int validBits;
    private final boolean dsd;

    SampleType(int asioCode, int containerBytes, int validBits, boolean dsd) {
        this.asioCode = asioCode;
        this.containerBytes = containerBytes;
        this.validBits = validBits;
        this.dsd = dsd;
    }

    /** The {@code ASIOSampleType} value. */
    public int asioCode() {
        return asioCode;
    }

    /** Bytes one sample of one channel occupies in the driver's buffer. */
    public int containerBytes() {
        return containerBytes;
    }

    /** How many of the container's bits carry sample data. */
    public int validBits() {
        return validBits;
    }

    public boolean isDsd() {
        return dsd;
    }

    /** The type for an ASIO sample-type code, or empty for anything Purist does not write (float, big-endian, ...). */
    public static Optional<SampleType> fromAsio(int code) {
        for (SampleType t : ALL) {
            if (t.asioCode == code) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** The DSD bit order this type expects. Only meaningful for DSD types. */
    public BitOrder bitOrder() {
        return switch (this) {
            case DSD_INT8_LSB1 -> BitOrder.LSB_FIRST;
            case DSD_INT8_MSB1 -> BitOrder.MSB_FIRST;
            default -> throw new IllegalStateException(name() + " is not a DSD type");
        };
    }
}
