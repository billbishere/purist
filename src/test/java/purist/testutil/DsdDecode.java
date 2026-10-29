package purist.testutil;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import purist.decode.Decoder;

/** Test helper: drains a DSD decoder into a byte array. */
public final class DsdDecode {

    private DsdDecode() {
    }

    /** Reads until the decoder reports the end, in steps of {@code step} frames. */
    public static byte[] all(Decoder d, int step) throws IOException {
        int bytesPerFrame = d.format().bytesPerDecodedFrame();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate((long) step * bytesPerFrame, 1);
            while (true) {
                int frames = d.read(dst, step);
                if (frames == 0) {
                    break;
                }
                assertTrue(frames > 0 && frames <= step);
                out.writeBytes(dst.asSlice(0, (long) frames * bytesPerFrame).toArray(ValueLayout.JAVA_BYTE));
            }
        }
        return out.toByteArray();
    }

    /** Reads up to {@code frames} frames in one call. */
    public static byte[] some(Decoder d, int frames) throws IOException {
        int bytesPerFrame = d.format().bytesPerDecodedFrame();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dst = arena.allocate((long) Math.max(frames, 1) * bytesPerFrame, 1);
            int got = d.read(dst, frames);
            return dst.asSlice(0, (long) got * bytesPerFrame).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
