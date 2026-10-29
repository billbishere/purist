package purist.win;

import static java.lang.foreign.ValueLayout.JAVA_CHAR_UNALIGNED;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/** Shared plumbing for the FFM bindings: library lookup, downcall handles and UTF-16 strings. */
public final class Native {

    /** The native linker; also used by {@link ComObject} to bind vtable entries. */
    public static final Linker LINKER = Linker.nativeLinker();

    /** Loaded libraries, kept for the life of the process. */
    private static final ConcurrentHashMap<String, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    private Native() {
    }

    /**
     * Binds {@code symbol} from the DLL {@code lib} (for example {@code "ole32"}) to a downcall handle.
     * Callers keep the result in a {@code static final} field.
     *
     * @throws UnsatisfiedLinkError if the library or symbol cannot be found
     */
    public static MethodHandle fn(String lib, String symbol, FunctionDescriptor fd) {
        SymbolLookup lookup = LIBRARIES.computeIfAbsent(lib, name -> SymbolLookup.libraryLookup(name, Arena.global()));
        MemorySegment address = lookup.find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError(lib + "!" + symbol + " not found"));
        return LINKER.downcallHandle(address, fd);
    }

    /** Copies {@code s} into {@code a} as a NUL-terminated UTF-16LE string (a Win32 {@code LPCWSTR}). */
    public static MemorySegment wstr(Arena a, String s) {
        return a.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    /**
     * Reads a NUL-terminated UTF-16 string of at most {@code maxChars} UTF-16 code units.
     *
     * <p>A zero-length segment is taken to be a raw pointer returned by native code and is widened to
     * {@code maxChars}; a sized segment is never read past its own bounds.
     */
    public static String readWstr(MemorySegment s, int maxChars) {
        MemorySegment seg;
        int limit;
        if (s.byteSize() == 0) {
            seg = s.reinterpret(2L * maxChars);
            limit = maxChars;
        } else {
            seg = s;
            limit = (int) Math.min(maxChars, s.byteSize() / 2);
        }
        char[] chars = new char[limit];
        int n = 0;
        while (n < limit) {
            char c = seg.get(JAVA_CHAR_UNALIGNED, 2L * n);
            if (c == 0) {
                break;
            }
            chars[n++] = c;
        }
        return new String(chars, 0, n);
    }

    /** Rethrows what a downcall's {@code invokeExact} threw: unchecked as-is, anything else wrapped. */
    static RuntimeException unchecked(String what, Throwable t) {
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof RuntimeException r) {
            return r;
        }
        return new IllegalStateException(what + " failed", t);
    }
}
