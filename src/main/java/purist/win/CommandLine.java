package purist.win;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * The program's arguments as Windows passed them. The Java launcher decodes {@code main}'s arguments with the ANSI
 * code page, so a file named {@code 雨の街.flac} arrives as {@code ???.flac}; the process's UTF-16 command line still
 * holds the real name.
 */
public final class CommandLine {

    private static final int MAX_ARG_CHARS = 32_768; // the longest command line Windows accepts

    private static final MethodHandle GET_COMMAND_LINE_W =
            Native.fn("kernel32", "GetCommandLineW", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle COMMAND_LINE_TO_ARGV_W =
            Native.fn("shell32", "CommandLineToArgvW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle LOCAL_FREE =
            Native.fn("kernel32", "LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));

    private CommandLine() {
    }

    /**
     * {@code javaArgs} with each argument re-read from the UTF-16 command line. Falls back to {@code javaArgs} if the
     * command line cannot be read.
     */
    public static List<String> args(String[] javaArgs) {
        if (javaArgs.length == 0) {
            return List.of();
        }
        try {
            return lastOf(wideArgv(), javaArgs);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return List.of(javaArgs);
        }
    }

    /**
     * The program's arguments are the last {@code javaArgs.length} entries of the full argv (the launcher, its
     * options and the jar or main class come first). The count is reliable even when the characters are not.
     */
    static List<String> lastOf(List<String> wideArgv, String[] javaArgs) {
        if (wideArgv.size() < javaArgs.length) {
            return List.of(javaArgs);
        }
        return List.copyOf(wideArgv.subList(wideArgv.size() - javaArgs.length, wideArgv.size()));
    }

    /** The whole command line split as {@code CommandLineToArgvW} splits it, the program itself first. */
    static List<String> wideArgv() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment line = (MemorySegment) GET_COMMAND_LINE_W.invokeExact();
            MemorySegment count = arena.allocate(JAVA_INT);
            MemorySegment argv = (MemorySegment) COMMAND_LINE_TO_ARGV_W.invokeExact(line, count);
            if (argv.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("CommandLineToArgvW failed");
            }
            try {
                int n = count.get(JAVA_INT, 0);
                MemorySegment pointers = argv.reinterpret(ADDRESS.byteSize() * n);
                List<String> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(Native.readWstr(pointers.getAtIndex(ADDRESS, i), MAX_ARG_CHARS));
                }
                return out;
            } finally {
                MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(argv);
            }
        } catch (Throwable t) {
            throw Native.unchecked("CommandLineToArgvW", t);
        }
    }
}
