package purist.win;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class NativeTest {

    @Test
    void wstrIsUtf16LeAndNulTerminated() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = Native.wstr(arena, "Az");
            assertEquals(6, s.byteSize());
            byte[] expected = {'A', 0, 'z', 0, 0, 0};
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], s.get(JAVA_BYTE, i), "byte " + i);
            }
        }
    }

    @Test
    void roundTripsNonAsciiAndSurrogatePairs() {
        String text = "Béla ♪ 音楽 𝄞";
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(text, Native.readWstr(Native.wstr(arena, text), 64));
        }
    }

    @Test
    void readWstrStopsAtNul() {
        try (Arena arena = Arena.ofConfined()) {
            assertEquals("abc", Native.readWstr(Native.wstr(arena, "abc"), 64));
        }
    }

    @Test
    void readWstrHonoursMaxChars() {
        try (Arena arena = Arena.ofConfined()) {
            assertEquals("abc", Native.readWstr(Native.wstr(arena, "abcdef"), 3));
            assertEquals("", Native.readWstr(Native.wstr(arena, "abcdef"), 0));
        }
    }

    @Test
    void readWstrReadsRawPointers() {
        // Pointers returned by native calls arrive as zero-length segments.
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment s = Native.wstr(arena, "from native");
            MemorySegment raw = MemorySegment.ofAddress(s.address());
            assertEquals("from native", Native.readWstr(raw, 64));
        }
    }
}
