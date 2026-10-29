package purist.win;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** kernel32 file attributes, used to spot cloud placeholders that would trigger a download on read. */
public final class FileAttributes {

    /** Returned by {@code GetFileAttributesW} when the path cannot be queried. */
    private static final int INVALID_FILE_ATTRIBUTES = -1;
    private static final int MAX_PATH = 260;
    private static final String EXTENDED_PREFIX = "\\\\?\\";
    private static final int FILE_ATTRIBUTE_OFFLINE = 0x1000;
    private static final int FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS = 0x400000;

    private static final MethodHandle GET_FILE_ATTRIBUTES_W =
            Native.fn("kernel32", "GetFileAttributesW", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private FileAttributes() {
    }

    /** The {@code FILE_ATTRIBUTE_*} bits of {@code p}, or -1 if it does not exist or cannot be queried. */
    public static int get(Path p) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) GET_FILE_ATTRIBUTES_W.invokeExact(Native.wstr(arena, win32Path(p)));
        } catch (Throwable t) {
            throw Native.unchecked("GetFileAttributesW", t);
        }
    }

    /**
     * True if reading {@code p} would pull it down from the cloud (OneDrive and similar placeholders).
     * A path that does not exist is not cloud-only.
     */
    public static boolean isCloudOnly(Path p) {
        return isCloudOnly(get(p));
    }

    /**
     * The path as Win32 wants it. The JVM is not long-path aware, so a path of {@code MAX_PATH} or more
     * characters needs the {@code \\?\} prefix, otherwise a file Java can see reads as missing.
     */
    static String win32Path(Path p) {
        String absolute = p.toAbsolutePath().toString();
        if (absolute.length() < MAX_PATH || absolute.startsWith(EXTENDED_PREFIX)) {
            return absolute;
        }
        String normalized = p.toAbsolutePath().normalize().toString(); // the prefix switches `..` handling off
        if (normalized.startsWith("\\\\")) {
            return EXTENDED_PREFIX + "UNC\\" + normalized.substring(2);
        }
        return EXTENDED_PREFIX + normalized;
    }

    static boolean isCloudOnly(int attributes) {
        if (attributes == INVALID_FILE_ATTRIBUTES) {
            return false; // every bit is set in the error value
        }
        return (attributes & (FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS | FILE_ATTRIBUTE_OFFLINE)) != 0;
    }
}
