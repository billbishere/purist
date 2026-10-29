package purist.wasapi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import purist.win.Native;

/**
 * kernel32 events and waits, and avrt's MMCSS registration, for the render thread. Handles are passed as
 * {@code long}s (a pointer-sized integer on x64). {@link #setEvent} and {@link #waitFor} allocate nothing.
 */
final class Win32 {

    static final int WAIT_OBJECT_0 = 0;
    static final int WAIT_TIMEOUT = 0x102;

    private static final MethodHandle CREATE_EVENT = Native.fn("kernel32", "CreateEventW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle SET_EVENT =
            Native.fn("kernel32", "SetEvent", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle WAIT =
            Native.fn("kernel32", "WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle CLOSE_HANDLE =
            Native.fn("kernel32", "CloseHandle", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle AV_SET = Native.fn("avrt", "AvSetMmThreadCharacteristicsW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle AV_REVERT =
            Native.fn("avrt", "AvRevertMmThreadCharacteristics", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    private Win32() {
    }

    /** An unnamed auto-reset event, initially not signalled. */
    static long createEvent() {
        long h;
        try {
            h = (long) CREATE_EVENT.invokeExact(MemorySegment.NULL, 0, 0, MemorySegment.NULL);
        } catch (Throwable t) {
            throw rethrow("CreateEventW", t);
        }
        if (h == 0) {
            throw new IllegalStateException("CreateEventW failed");
        }
        return h;
    }

    /** Signals {@code event}; false if Windows said no. Allocation-free. */
    static boolean setEvent(long event) {
        try {
            return (int) SET_EVENT.invokeExact(event) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code WaitForSingleObject}: {@link #WAIT_OBJECT_0}, {@link #WAIT_TIMEOUT}, or a failure code. Allocation-free. */
    static int waitFor(long handle, int millis) throws Throwable {
        return (int) WAIT.invokeExact(handle, millis);
    }

    static void closeHandle(long handle) {
        try {
            int ok = (int) CLOSE_HANDLE.invokeExact(handle);
            if (ok == 0) {
                throw new IllegalStateException("CloseHandle failed");
            }
        } catch (Throwable t) {
            throw rethrow("CloseHandle", t);
        }
    }

    /** Registers the calling thread with MMCSS as {@code task} ("Pro Audio"); 0 if that failed (it is optional). */
    static long joinMmcss(String task) {
        try (Arena a = Arena.ofConfined()) {
            return (long) AV_SET.invokeExact(Native.wstr(a, task), a.allocate(JAVA_INT));
        } catch (Throwable t) {
            return 0;
        }
    }

    static void leaveMmcss(long handle) {
        if (handle == 0) {
            return;
        }
        try {
            int ignored = (int) AV_REVERT.invokeExact(handle);
        } catch (Throwable t) {
            // the thread is ending either way
        }
    }

    private static RuntimeException rethrow(String what, Throwable t) {
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof RuntimeException r) {
            return r;
        }
        return new IllegalStateException(what + " failed", t);
    }
}
