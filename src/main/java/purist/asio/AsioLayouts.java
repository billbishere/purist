package purist.asio;

import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.StructLayout;

/**
 * The ASIO structs as they sit in memory on x64 Windows. The SDK declares them under {@code pragma
 * pack(push, 4)} with {@code long} = 4 bytes; every member here already falls on its natural boundary,
 * so the packing adds no padding.
 */
public final class AsioLayouts {

    private AsioLayouts() {
    }

    /** {@code ASIOBufferInfo {long isInput; long channelNum; void* buffers[2];}} - 24 bytes. */
    public static final StructLayout BUFFER_INFO = structLayout(
            JAVA_INT.withName("isInput"),
            JAVA_INT.withName("channelNum"),
            sequenceLayout(2, ADDRESS).withName("buffers"));

    /**
     * {@code ASIOChannelInfo {long channel; long isInput; long isActive; long channelGroup; long type;
     * char name[32];}} - 52 bytes.
     */
    public static final StructLayout CHANNEL_INFO = structLayout(
            JAVA_INT.withName("channel"),
            JAVA_INT.withName("isInput"),
            JAVA_INT.withName("isActive"),
            JAVA_INT.withName("channelGroup"),
            JAVA_INT.withName("type"),
            sequenceLayout(32, JAVA_BYTE).withName("name"));

    /** {@code ASIOCallbacks}: four function pointers - 32 bytes. */
    public static final StructLayout CALLBACKS = structLayout(
            ADDRESS.withName("bufferSwitch"),
            ADDRESS.withName("sampleRateDidChange"),
            ADDRESS.withName("asioMessage"),
            ADDRESS.withName("bufferSwitchTimeInfo"));

    /** {@code ASIOIoFormat {long FormatType; char future[508];}} - 512 bytes. */
    public static final StructLayout IO_FORMAT = structLayout(
            JAVA_INT.withName("formatType"),
            sequenceLayout(508, JAVA_BYTE).withName("future"));
}
