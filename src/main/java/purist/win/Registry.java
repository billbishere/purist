package purist.win;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only registry access over advapi32's wide-character functions. Keys are always opened in the
 * 64-bit view, which is where 64-bit ASIO drivers register themselves.
 */
public final class Registry {

    /** The predefined hives Purist reads. */
    public enum Hive {
        HKLM(0x80000002),
        HKCU(0x80000001);

        /** The predefined handle value; the int widens with sign extension, as in winreg.h. */
        private final MemorySegment handle;

        Hive(int handle) {
            this.handle = MemorySegment.ofAddress(handle);
        }
    }

    private static final int KEY_READ = 0x20019;
    private static final int KEY_WOW64_64KEY = 0x0100;
    private static final int REG_SZ = 1;

    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_FILE_NOT_FOUND = 2;
    private static final int ERROR_PATH_NOT_FOUND = 3;
    private static final int ERROR_MORE_DATA = 234;
    private static final int ERROR_NO_MORE_ITEMS = 259;

    /** A key name is at most 255 characters; the buffer also holds the terminating NUL. */
    private static final int KEY_NAME_BUFFER_CHARS = 256;
    /** How often to retry a value that keeps growing between the size query and the read. */
    private static final int READ_ATTEMPTS = 4;

    private static final MethodHandle REG_OPEN_KEY_EX_W = Native.fn("advapi32", "RegOpenKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle REG_ENUM_KEY_EX_W = Native.fn("advapi32", "RegEnumKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_QUERY_VALUE_EX_W = Native.fn("advapi32", "RegQueryValueExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_CLOSE_KEY =
            Native.fn("advapi32", "RegCloseKey", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private Registry() {
    }

    /**
     * The names of the immediate sub keys of {@code path}, in registry order. A key that does not exist
     * has no sub keys.
     *
     * @throws IllegalStateException on any other registry failure, such as access denied
     */
    public static List<String> subKeys(Hive h, String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = open(arena, h, path);
            if (key == null) {
                return List.of();
            }
            try {
                List<String> names = new ArrayList<>();
                MemorySegment name = arena.allocate(2L * KEY_NAME_BUFFER_CHARS);
                MemorySegment length = arena.allocate(JAVA_INT);
                for (int index = 0;; index++) {
                    length.set(JAVA_INT, 0, KEY_NAME_BUFFER_CHARS);
                    int rc = enumKey(key, index, name, length);
                    if (rc == ERROR_NO_MORE_ITEMS) {
                        return List.copyOf(names);
                    }
                    check("RegEnumKeyExW", rc, path);
                    names.add(Native.readWstr(name, length.get(JAVA_INT, 0)));
                }
            } finally {
                close(key);
            }
        }
    }

    /**
     * The {@code REG_SZ} value {@code name} of key {@code path}, with its terminating NUL removed. Empty
     * if the key or value does not exist or the value is of another type. The empty name is the key's
     * default value.
     *
     * @throws IllegalStateException on any other registry failure, such as access denied
     */
    public static Optional<String> stringValue(Hive h, String path, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment key = open(arena, h, path);
            if (key == null) {
                return Optional.empty();
            }
            try {
                MemorySegment wname = Native.wstr(arena, name);
                MemorySegment type = arena.allocate(JAVA_INT);
                MemorySegment size = arena.allocate(JAVA_INT);
                for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
                    // First ask how big the value is, then read it.
                    int rc = queryValue(key, wname, type, MemorySegment.NULL, size);
                    if (rc == ERROR_FILE_NOT_FOUND) {
                        return Optional.empty();
                    }
                    check("RegQueryValueExW", rc, path);
                    if (type.get(JAVA_INT, 0) != REG_SZ) {
                        return Optional.empty();
                    }
                    int bytes = size.get(JAVA_INT, 0);
                    // Two spare bytes keep a terminator in place even if the stored value has none.
                    MemorySegment data = arena.allocate(bytes + 2L);
                    size.set(JAVA_INT, 0, bytes);
                    rc = queryValue(key, wname, type, data, size);
                    if (rc == ERROR_MORE_DATA) {
                        continue; // the value grew between the two calls
                    }
                    if (rc == ERROR_FILE_NOT_FOUND || type.get(JAVA_INT, 0) != REG_SZ) {
                        return Optional.empty(); // replaced or deleted between the two calls
                    }
                    check("RegQueryValueExW", rc, path);
                    return Optional.of(Native.readWstr(data, size.get(JAVA_INT, 0) / 2));
                }
                throw new IllegalStateException("Registry value \"" + name + "\" of " + path + " kept changing size");
            } finally {
                close(key);
            }
        }
    }

    /** Opens {@code path} for reading, or returns null if it does not exist. The caller must close it. */
    private static MemorySegment open(Arena arena, Hive h, String path) {
        MemorySegment out = arena.allocate(ADDRESS);
        int rc;
        try {
            rc = (int) REG_OPEN_KEY_EX_W.invokeExact(h.handle, Native.wstr(arena, path), 0,
                    KEY_READ | KEY_WOW64_64KEY, out);
        } catch (Throwable t) {
            throw Native.unchecked("RegOpenKeyExW", t);
        }
        if (rc == ERROR_FILE_NOT_FOUND || rc == ERROR_PATH_NOT_FOUND) {
            return null;
        }
        check("RegOpenKeyExW", rc, path);
        return out.get(ADDRESS, 0);
    }

    private static int enumKey(MemorySegment key, int index, MemorySegment name, MemorySegment length) {
        try {
            return (int) REG_ENUM_KEY_EX_W.invokeExact(key, index, name, length,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw Native.unchecked("RegEnumKeyExW", t);
        }
    }

    private static int queryValue(MemorySegment key, MemorySegment name, MemorySegment type,
            MemorySegment data, MemorySegment size) {
        try {
            return (int) REG_QUERY_VALUE_EX_W.invokeExact(key, name, MemorySegment.NULL, type, data, size);
        } catch (Throwable t) {
            throw Native.unchecked("RegQueryValueExW", t);
        }
    }

    private static void close(MemorySegment key) {
        try {
            int rc = (int) REG_CLOSE_KEY.invokeExact(key);
            assert rc == ERROR_SUCCESS : "RegCloseKey returned " + rc;
        } catch (Throwable t) {
            throw Native.unchecked("RegCloseKey", t);
        }
    }

    private static void check(String call, int rc, String path) {
        if (rc != ERROR_SUCCESS) {
            throw new IllegalStateException("%s on %s failed with Win32 error %d".formatted(call, path, rc));
        }
    }
}
