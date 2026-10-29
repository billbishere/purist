package purist.wasapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/** The WAVEFORMATEXTENSIBLE bytes handed to IsFormatSupported and Initialize. */
class WaveFormatTest {

    @Test
    void writes24In32StereoAsWaveFormatExtensiblePcm() {
        MemorySegment s = MemorySegment.ofArray(new byte[(int) WaveFormat.BYTES]);
        new WaveFormat(96000, 2, 32, 24).writeTo(s);
        byte[] expected = {
            (byte) 0xFE, (byte) 0xFF,       // wFormatTag WAVE_FORMAT_EXTENSIBLE
            2, 0,                           // nChannels
            0x00, 0x77, 0x01, 0x00,         // nSamplesPerSec 96000
            0x00, (byte) 0xB8, 0x0B, 0x00,  // nAvgBytesPerSec 768000
            8, 0,                           // nBlockAlign
            32, 0,                          // wBitsPerSample (the container)
            22, 0,                          // cbSize
            24, 0,                          // wValidBitsPerSample
            3, 0, 0, 0,                     // dwChannelMask FRONT_LEFT | FRONT_RIGHT
            // KSDATAFORMAT_SUBTYPE_PCM {00000001-0000-0010-8000-00AA00389B71}
            1, 0, 0, 0, 0, 0, 0x10, 0, (byte) 0x80, 0, 0, (byte) 0xAA, 0, 0x38, (byte) 0x9B, 0x71,
        };
        assertArrayEquals(expected, s.toArray(ValueLayout.JAVA_BYTE));
    }

    @Test
    void describesTheContainer() {
        assertEquals("16-bit", new WaveFormat(44100, 2, 16, 16).describe());
        assertEquals("24-bit", new WaveFormat(44100, 2, 24, 24).describe());
        assertEquals("24-in-32", new WaveFormat(44100, 2, 32, 24).describe());
        assertEquals("32-bit", new WaveFormat(44100, 2, 32, 32).describe());
        assertEquals(6, new WaveFormat(44100, 2, 24, 24).blockAlign());
    }

    @Test
    void rejectsImpossibleShapes() {
        assertThrows(IllegalArgumentException.class, () -> new WaveFormat(44100, 2, 24, 32));
        assertThrows(IllegalArgumentException.class, () -> new WaveFormat(44100, 2, 20, 20));
        assertThrows(IllegalArgumentException.class, () -> new WaveFormat(0, 2, 16, 16));
    }
}
