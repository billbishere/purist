package purist.win;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/** ole32 bindings: COM apartment management, GUID parsing and in-process object creation. */
public final class Ole32 {

    private static final int COINIT_MULTITHREADED = 0;
    private static final int COINIT_APARTMENTTHREADED = 2;
    private static final int CLSCTX_INPROC_SERVER = 1;
    private static final long GUID_BYTES = 16;

    private static final MethodHandle CO_INITIALIZE_EX =
            Native.fn("ole32", "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_TASK_MEM_FREE =
            Native.fn("ole32", "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle PROP_VARIANT_CLEAR =
            Native.fn("ole32", "PropVariantClear", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CO_UNINITIALIZE =
            Native.fn("ole32", "CoUninitialize", FunctionDescriptor.ofVoid());
    private static final MethodHandle CLSID_FROM_STRING =
            Native.fn("ole32", "CLSIDFromString", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CO_CREATE_INSTANCE = Native.fn("ole32", "CoCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    private Ole32() {
    }

    /**
     * Enters a single-threaded apartment on the calling thread ({@code CoInitializeEx(NULL,
     * COINIT_APARTMENTTHREADED)}). Both {@code S_OK} (0) and {@code S_FALSE} (1, already initialised)
     * count as success and each must be balanced by one {@link #coUninitialize()}.
     *
     * @return the success HRESULT, 0 or 1
     * @throws IllegalStateException if COM could not be initialised, for example because the thread is
     *         already in a multithreaded apartment
     */
    public static int coInitializeSta() {
        return coInitialize(COINIT_APARTMENTTHREADED);
    }

    /**
     * Joins the multithreaded apartment on the calling thread ({@code CoInitializeEx(NULL, COINIT_MULTITHREADED)}),
     * as WASAPI's own samples do for both the thread that owns an audio client and its render thread. Same
     * contract as {@link #coInitializeSta()}.
     */
    public static int coInitializeMta() {
        return coInitialize(COINIT_MULTITHREADED);
    }

    private static int coInitialize(int mode) {
        int hr;
        try {
            hr = (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, mode);
        } catch (Throwable t) {
            throw Native.unchecked("CoInitializeEx", t);
        }
        if (hr < 0) {
            throw new IllegalStateException("CoInitializeEx failed with HRESULT 0x%08X".formatted(hr));
        }
        return hr;
    }

    /** {@code CoTaskMemFree(p)}: frees memory COM allocated for the caller (a device id string, say). Null is fine. */
    public static void coTaskMemFree(MemorySegment p) {
        try {
            CO_TASK_MEM_FREE.invokeExact(p);
        } catch (Throwable t) {
            throw Native.unchecked("CoTaskMemFree", t);
        }
    }

    /** {@code PropVariantClear(pv)}: frees what a PROPVARIANT owns and sets it to VT_EMPTY. */
    public static void propVariantClear(MemorySegment pv) {
        int hr;
        try {
            hr = (int) PROP_VARIANT_CLEAR.invokeExact(pv);
        } catch (Throwable t) {
            throw Native.unchecked("PropVariantClear", t);
        }
        if (hr < 0) {
            throw new IllegalStateException("PropVariantClear failed with HRESULT 0x%08X".formatted(hr));
        }
    }

    /** Balances one successful {@link #coInitializeSta()} on the calling thread. */
    public static void coUninitialize() {
        try {
            CO_UNINITIALIZE.invokeExact();
        } catch (Throwable t) {
            throw Native.unchecked("CoUninitialize", t);
        }
    }

    /**
     * Parses a braced GUID string such as {@code "{00000000-0000-0000-C000-000000000046}"} into 16
     * bytes allocated from {@code a}.
     *
     * @throws IllegalArgumentException if the string is not a braced GUID
     */
    public static MemorySegment clsid(Arena a, String braced) {
        try {
            return parseGuid(a, braced);
        } catch (ComException e) {
            throw new IllegalArgumentException("Not a valid CLSID: \"" + braced + "\" (" + e.getMessage() + ")", e);
        }
    }

    /**
     * {@code CoCreateInstance(clsid, NULL, CLSCTX_INPROC_SERVER, iid, &p)}. Many ASIO drivers register
     * their CLSID as the IID too, so the same string is usually passed for both. The calling thread
     * must be in an apartment, see {@link #coInitializeSta()}.
     *
     * @throws ComException if either string is malformed or COM refuses to create the object
     */
    public static ComObject coCreateInstance(String clsid, String iid) throws ComException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rclsid = parseGuid(arena, clsid);
            MemorySegment riid = parseGuid(arena, iid);
            MemorySegment out = arena.allocate(ADDRESS);
            int hr;
            try {
                hr = (int) CO_CREATE_INSTANCE.invokeExact(rclsid, MemorySegment.NULL, CLSCTX_INPROC_SERVER, riid, out);
            } catch (Throwable t) {
                throw Native.unchecked("CoCreateInstance", t);
            }
            if (hr < 0) {
                throw new ComException("CoCreateInstance", hr);
            }
            return new ComObject(out.get(ADDRESS, 0));
        }
    }

    private static MemorySegment parseGuid(Arena a, String braced) throws ComException {
        // CLSIDFromString also accepts ProgIDs and would consult the registry for them; insist on a GUID.
        if (!braced.startsWith("{")) {
            throw new ComException("CLSIDFromString", 0x800401F3); // CO_E_CLASSSTRING
        }
        MemorySegment guid = a.allocate(GUID_BYTES);
        int hr;
        try {
            hr = (int) CLSID_FROM_STRING.invokeExact(Native.wstr(a, braced), guid);
        } catch (Throwable t) {
            throw Native.unchecked("CLSIDFromString", t);
        }
        if (hr < 0) {
            throw new ComException("CLSIDFromString", hr);
        }
        return guid;
    }
}
