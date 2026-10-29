package purist.win;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.junit.jupiter.api.Test;

class Ole32Test {

    private static final String IID_IUNKNOWN = "{00000000-0000-0000-C000-000000000046}";
    private static final String CLSID_SHELL_LINK = "{00021401-0000-0000-C000-000000000046}";
    private static final int REGDB_E_CLASSNOTREG = 0x80040154;

    private static final FunctionDescriptor ADD_REF = FunctionDescriptor.of(JAVA_INT);

    @Test
    void parsesIUnknownClsid() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment guid = Ole32.clsid(arena, IID_IUNKNOWN);
            assertEquals(16, guid.byteSize());
            assertEquals(0, guid.get(JAVA_BYTE, 0));
            assertEquals((byte) 0xC0, guid.get(JAVA_BYTE, 8));
            assertEquals(0x46, guid.get(JAVA_BYTE, 15));
        }
    }

    @Test
    void badClsidThrows() {
        try (Arena arena = Arena.ofConfined()) {
            assertThrows(IllegalArgumentException.class, () -> Ole32.clsid(arena, "not-a-clsid"));
            assertThrows(IllegalArgumentException.class, () -> Ole32.clsid(arena, "{1234}"));
            assertThrows(IllegalArgumentException.class, () -> Ole32.clsid(arena, ""));
        }
    }

    @Test
    void coInitializeStaSucceedsAndIsReentrant() {
        int first = Ole32.coInitializeSta();
        try {
            assertTrue(first == 0 || first == 1, "S_OK or S_FALSE, got " + first);
            // A second call on the same thread is S_FALSE and must also be balanced.
            int second = Ole32.coInitializeSta();
            try {
                assertEquals(1, second);
            } finally {
                Ole32.coUninitialize();
            }
        } finally {
            Ole32.coUninitialize();
        }
    }

    @Test
    void createsComObjectAndCallsVtableSlots() throws Throwable {
        Ole32.coInitializeSta();
        try {
            ComObject link = Ole32.coCreateInstance(CLSID_SHELL_LINK, IID_IUNKNOWN);
            try {
                MethodHandle addRef = link.method(1, ADD_REF);
                assertSame(addRef, link.method(1, ADD_REF), "bound handle is cached per slot");
                assertThrows(IllegalArgumentException.class,
                        () -> link.method(1, FunctionDescriptor.of(JAVA_LONG)),
                        "a slot cannot be re-bound with a different signature");
                assertEquals(2, (int) addRef.invokeExact());
                assertEquals(1, link.release());
            } finally {
                assertEquals(0, link.release());
            }
        } finally {
            Ole32.coUninitialize();
        }
    }

    @Test
    void unregisteredClassReportsHresult() {
        Ole32.coInitializeSta();
        try {
            ComException e = assertThrows(ComException.class,
                    () -> Ole32.coCreateInstance("{12345678-1234-1234-1234-123456789ABC}", IID_IUNKNOWN));
            assertEquals(REGDB_E_CLASSNOTREG, e.hresult());
            assertTrue(e.getMessage().contains("0x80040154"), e.getMessage());
        } finally {
            Ole32.coUninitialize();
        }
    }

    @Test
    void malformedClsidInCreateInstanceIsAComException() {
        Ole32.coInitializeSta();
        try {
            ComException e = assertThrows(ComException.class,
                    () -> Ole32.coCreateInstance("garbage", IID_IUNKNOWN));
            assertTrue(e.hresult() < 0, "failure HRESULT expected, got " + e.hresult());
        } finally {
            Ole32.coUninitialize();
        }
    }
}
