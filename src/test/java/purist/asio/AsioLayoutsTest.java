package purist.asio;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.PathElement.sequenceElement;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AsioLayoutsTest {

    @Test
    void structSizesMatchTheDriverAbi() {
        assertEquals(24, AsioLayouts.BUFFER_INFO.byteSize());
        assertEquals(52, AsioLayouts.CHANNEL_INFO.byteSize());
        assertEquals(32, AsioLayouts.CALLBACKS.byteSize());
        assertEquals(512, AsioLayouts.IO_FORMAT.byteSize());
    }

    @Test
    void bufferInfoOffsets() {
        assertEquals(0, AsioLayouts.BUFFER_INFO.byteOffset(groupElement("isInput")));
        assertEquals(4, AsioLayouts.BUFFER_INFO.byteOffset(groupElement("channelNum")));
        assertEquals(8, AsioLayouts.BUFFER_INFO.byteOffset(groupElement("buffers")));
        assertEquals(8, AsioLayouts.BUFFER_INFO.byteOffset(groupElement("buffers"), sequenceElement(0)));
        assertEquals(16, AsioLayouts.BUFFER_INFO.byteOffset(groupElement("buffers"), sequenceElement(1)));
    }

    @Test
    void channelInfoOffsets() {
        assertEquals(0, AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("channel")));
        assertEquals(4, AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("isInput")));
        assertEquals(8, AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("isActive")));
        assertEquals(12, AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("channelGroup")));
        assertEquals(16, AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("type")));
        assertEquals(20, AsioLayouts.CHANNEL_INFO.byteOffset(groupElement("name")));
    }

    @Test
    void callbacksAreFourConsecutivePointers() {
        assertEquals(0, AsioLayouts.CALLBACKS.byteOffset(groupElement("bufferSwitch")));
        assertEquals(8, AsioLayouts.CALLBACKS.byteOffset(groupElement("sampleRateDidChange")));
        assertEquals(16, AsioLayouts.CALLBACKS.byteOffset(groupElement("asioMessage")));
        assertEquals(24, AsioLayouts.CALLBACKS.byteOffset(groupElement("bufferSwitchTimeInfo")));
    }

    @Test
    void ioFormatStartsWithTheFormatType() {
        assertEquals(0, AsioLayouts.IO_FORMAT.byteOffset(groupElement("formatType")));
        assertEquals(4, AsioLayouts.IO_FORMAT.byteOffset(groupElement("future")));
    }
}
