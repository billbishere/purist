package purist.wasapi;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import purist.win.ComException;
import purist.win.ComObject;
import purist.win.Native;
import purist.win.Ole32;

/**
 * The real WASAPI backend: thin FFM bindings for {@code IMMDeviceEnumerator}, {@code IMMDeviceCollection},
 * {@code IMMDevice}, {@code IPropertyStore}, {@code IAudioClient} and {@code IAudioRenderClient}, called through
 * their vtables with {@link ComObject} ({@code this} first, as on every x64 COM call).
 *
 * <p>Every method runs on the thread that made this object, which must be in the multithreaded apartment (the
 * sink's control thread); the one exception is the render client's {@code GetBuffer}/{@code ReleaseBuffer}, which
 * the render thread (also in the MTA) calls. The device enumerator is created on first use and released by
 * {@link #close}.
 */
final class WasapiClient implements WasapiBackend {

    private static final String CLSID_MM_DEVICE_ENUMERATOR = "{BCDE0395-E52F-467C-8E3D-C4579291692E}";
    private static final String IID_IMM_DEVICE_ENUMERATOR = "{A95664D2-9614-4F35-A746-DE8DB63617E6}";
    private static final String IID_IAUDIO_CLIENT = "{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}";
    private static final String IID_IAUDIO_RENDER_CLIENT = "{F294ACFC-3146-4483-A7BF-ADDCA7C260E2}";
    /** PKEY_Device_FriendlyName: {A45C254E-DF1C-4EFD-8020-67D146A850E0}, 14. */
    private static final String FRIENDLY_NAME_FMTID = "{A45C254E-DF1C-4EFD-8020-67D146A850E0}";
    private static final int FRIENDLY_NAME_PID = 14;

    private static final int E_RENDER = 0;
    private static final int DEVICE_STATE_ACTIVE = 1;
    private static final int CLSCTX_ALL = 23;
    private static final int STGM_READ = 0;
    private static final short VT_LPWSTR = 31;
    private static final long PROPVARIANT_BYTES = 24;
    private static final int AUDCLNT_SHAREMODE_EXCLUSIVE = 1;
    private static final int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    private static final int E_FAIL = 0x80004005;

    // IMMDeviceEnumerator
    private static final FunctionDescriptor ENUM_AUDIO_ENDPOINTS = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor GET_DEVICE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    // IMMDeviceCollection
    private static final FunctionDescriptor GET_COUNT = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor ITEM = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    // IMMDevice
    private static final FunctionDescriptor ACTIVATE = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor OPEN_PROPERTY_STORE = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor GET_ID = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    // IPropertyStore
    private static final FunctionDescriptor GET_VALUE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    // IAudioClient
    private static final FunctionDescriptor INITIALIZE =
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS);
    private static final FunctionDescriptor OUT_PTR = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor IS_FORMAT_SUPPORTED = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor TWO_OUT_PTRS = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor NO_ARGS = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor SET_EVENT_HANDLE = FunctionDescriptor.of(JAVA_INT, JAVA_LONG);
    // IAudioRenderClient
    private static final FunctionDescriptor GET_BUFFER = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor RELEASE_BUFFER = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT);

    private ComObject enumerator;

    /** Every active render endpoint, in Windows' order. The calling thread must be in a COM apartment. */
    List<WasapiDevice> activeDevices() throws ComException {
        List<WasapiDevice> out = new ArrayList<>();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(ADDRESS);
            check("IMMDeviceEnumerator::EnumAudioEndpoints", (int) enumerator().method(3, ENUM_AUDIO_ENDPOINTS)
                    .invokeExact(E_RENDER, DEVICE_STATE_ACTIVE, p));
            ComObject collection = new ComObject(p.get(ADDRESS, 0));
            try {
                MemorySegment count = a.allocate(JAVA_INT);
                check("IMMDeviceCollection::GetCount", (int) collection.method(3, GET_COUNT).invokeExact(count));
                for (int i = 0; i < count.get(JAVA_INT, 0); i++) {
                    check("IMMDeviceCollection::Item", (int) collection.method(4, ITEM).invokeExact(i, p));
                    ComObject device = new ComObject(p.get(ADDRESS, 0));
                    try {
                        out.add(new WasapiDevice(id(device), friendlyName(device)));
                    } finally {
                        device.release();
                    }
                }
            } finally {
                collection.release();
            }
        } catch (ComException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("EnumAudioEndpoints failed", t);
        }
        return out;
    }

    @Override
    public AudioClient activate(String endpointId) throws ComException {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(ADDRESS);
            check("IMMDeviceEnumerator::GetDevice",
                    (int) enumerator().method(5, GET_DEVICE).invokeExact(Native.wstr(a, endpointId), p));
            ComObject device = new ComObject(p.get(ADDRESS, 0));
            try {
                MemorySegment iid = Ole32.clsid(a, IID_IAUDIO_CLIENT);
                check("IMMDevice::Activate", (int) device.method(3, ACTIVATE)
                        .invokeExact(iid, CLSCTX_ALL, MemorySegment.NULL, p));
                return new ComAudioClient(new ComObject(p.get(ADDRESS, 0)));
            } finally {
                device.release();
            }
        } catch (ComException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Activate failed", t);
        }
    }

    @Override
    public void close() {
        ComObject e = enumerator;
        enumerator = null;
        if (e != null) {
            e.release();
        }
    }

    private ComObject enumerator() throws ComException {
        if (enumerator == null) {
            enumerator = Ole32.coCreateInstance(CLSID_MM_DEVICE_ENUMERATOR, IID_IMM_DEVICE_ENUMERATOR);
        }
        return enumerator;
    }

    private static String id(ComObject device) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(ADDRESS);
            check("IMMDevice::GetId", (int) device.method(5, GET_ID).invokeExact(p));
            MemorySegment s = p.get(ADDRESS, 0);
            try {
                return Native.readWstr(s, 1024);
            } finally {
                Ole32.coTaskMemFree(s);
            }
        }
    }

    private static String friendlyName(ComObject device) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(ADDRESS);
            check("IMMDevice::OpenPropertyStore", (int) device.method(4, OPEN_PROPERTY_STORE).invokeExact(STGM_READ, p));
            ComObject store = new ComObject(p.get(ADDRESS, 0));
            try {
                MemorySegment key = a.allocate(20, 4);
                MemorySegment.copy(Ole32.clsid(a, FRIENDLY_NAME_FMTID), 0, key, 0, 16);
                key.set(JAVA_INT, 16, FRIENDLY_NAME_PID);
                MemorySegment pv = a.allocate(PROPVARIANT_BYTES, 8);
                check("IPropertyStore::GetValue", (int) store.method(5, GET_VALUE).invokeExact(key, pv));
                try {
                    if (pv.get(JAVA_SHORT, 0) != VT_LPWSTR) {
                        return "(unnamed device)";
                    }
                    return Native.readWstr(pv.get(ADDRESS, 8), 512);
                } finally {
                    Ole32.propVariantClear(pv);
                }
            } finally {
                store.release();
            }
        }
    }

    private static void check(String op, int hr) throws ComException {
        if (hr < 0) {
            throw new ComException(op, hr);
        }
    }

    // ---- IAudioClient -----------------------------------------------------------------------------------------

    /** One {@code IAudioClient}, used on the thread that activated it. */
    private static final class ComAudioClient implements AudioClient {

        private final ComObject client;
        private final Arena arena = Arena.ofShared();
        private final MemorySegment format = arena.allocate(WaveFormat.BYTES, 8);
        private final MemorySegment out = arena.allocate(16, 8);

        ComAudioClient(ComObject client) {
            this.client = client;
        }

        @Override
        public int isFormatSupported(WaveFormat f) {
            f.writeTo(format);
            try {
                // Exclusive mode: ppClosestMatch must be NULL; the answer is S_OK or AUDCLNT_E_UNSUPPORTED_FORMAT.
                return (int) client.method(7, IS_FORMAT_SUPPORTED)
                        .invokeExact(AUDCLNT_SHAREMODE_EXCLUSIVE, format, MemorySegment.NULL);
            } catch (Throwable t) {
                return E_FAIL;
            }
        }

        @Override
        public long defaultDevicePeriod() throws ComException {
            try {
                check("IAudioClient::GetDevicePeriod",
                        (int) client.method(9, TWO_OUT_PTRS).invokeExact(out, out.asSlice(8)));
            } catch (ComException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("GetDevicePeriod failed", t);
            }
            return out.get(JAVA_LONG, 0);
        }

        @Override
        public int initialize(WaveFormat f, long periodHns) {
            f.writeTo(format);
            try {
                return (int) client.method(3, INITIALIZE).invokeExact(AUDCLNT_SHAREMODE_EXCLUSIVE,
                        AUDCLNT_STREAMFLAGS_EVENTCALLBACK, periodHns, periodHns, format, MemorySegment.NULL);
            } catch (Throwable t) {
                return E_FAIL;
            }
        }

        @Override
        public int bufferSize() throws ComException {
            return outInt("IAudioClient::GetBufferSize", 4);
        }

        @Override
        public int currentPadding() throws ComException {
            return outInt("IAudioClient::GetCurrentPadding", 6);
        }

        private int outInt(String op, int slot) throws ComException {
            try {
                check(op, (int) client.method(slot, OUT_PTR).invokeExact(out));
            } catch (ComException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(op + " failed", t);
            }
            return out.get(JAVA_INT, 0);
        }

        @Override
        public void setEventHandle(long event) throws ComException {
            try {
                check("IAudioClient::SetEventHandle", (int) client.method(13, SET_EVENT_HANDLE).invokeExact(event));
            } catch (ComException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("SetEventHandle failed", t);
            }
        }

        @Override
        public RenderClient renderClient() throws ComException {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment iid = Ole32.clsid(a, IID_IAUDIO_RENDER_CLIENT);
                check("IAudioClient::GetService", (int) client.method(14, TWO_OUT_PTRS).invokeExact(iid, out));
                return new ComRenderClient(new ComObject(out.get(ADDRESS, 0)));
            } catch (ComException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("GetService failed", t);
            }
        }

        @Override
        public void start() throws ComException {
            noArgs("IAudioClient::Start", 10);
        }

        @Override
        public void stop() throws ComException {
            noArgs("IAudioClient::Stop", 11);
        }

        @Override
        public void reset() throws ComException {
            noArgs("IAudioClient::Reset", 12);
        }

        private void noArgs(String op, int slot) throws ComException {
            try {
                check(op, (int) client.method(slot, NO_ARGS).invokeExact());
            } catch (ComException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(op + " failed", t);
            }
        }

        @Override
        public void release() {
            try {
                client.release();
            } finally {
                arena.close();
            }
        }
    }

    // ---- IAudioRenderClient -----------------------------------------------------------------------------------

    /**
     * One {@code IAudioRenderClient}. Its two buffer methods are bound once, here, and its out-pointer is
     * preallocated, so the render thread's calls allocate nothing; the address comes back as a {@code long}.
     */
    private static final class ComRenderClient implements RenderClient {

        private final ComObject render;
        private final Arena arena = Arena.ofShared();
        private final MemorySegment out = arena.allocate(ADDRESS);
        private final MethodHandle getBuffer;
        private final MethodHandle releaseBuffer;

        ComRenderClient(ComObject render) {
            this.render = render;
            this.getBuffer = render.method(3, GET_BUFFER);
            this.releaseBuffer = render.method(4, RELEASE_BUFFER);
        }

        @Override
        public int getBuffer(int frames) {
            try {
                return (int) getBuffer.invokeExact(frames, out);
            } catch (Throwable t) {
                return E_FAIL;
            }
        }

        @Override
        public long buffer() {
            return out.get(JAVA_LONG, 0);
        }

        @Override
        public int releaseBuffer(int frames, int flags) {
            try {
                return (int) releaseBuffer.invokeExact(frames, flags);
            } catch (Throwable t) {
                return E_FAIL;
            }
        }

        @Override
        public void release() {
            try {
                render.release();
            } finally {
                arena.close();
            }
        }
    }
}
