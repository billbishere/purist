package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ComAsioDriver#load} failure mapping; needs COM but no ASIO hardware. */
class ComAsioDriverLoadTest {

    private static final int REGDB_E_CLASSNOTREG = 0x80040154;
    private static final int CO_E_CLASSSTRING = 0x800401F3;

    @Test
    void unregisteredClsidIsReportedAsACoCreateInstanceFailure() throws Exception {
        AsioException e = StaThread.call(() -> assertThrows(AsioException.class,
                () -> ComAsioDriver.load(new AsioDriverInfo("nope", "{00000000-0000-0000-0000-000000000001}"))));
        assertEquals(REGDB_E_CLASSNOTREG, e.code());
        assertTrue(e.getMessage().startsWith("CoCreateInstance failed: "), e.getMessage());
    }

    @Test
    void malformedClsidIsReportedTheSameWay() throws Exception {
        AsioException e = StaThread.call(() -> assertThrows(AsioException.class,
                () -> ComAsioDriver.load(new AsioDriverInfo("nope", "not-a-guid"))));
        assertEquals(CO_E_CLASSSTRING, e.code());
        assertTrue(e.getMessage().startsWith("CoCreateInstance failed: "), e.getMessage());
    }
}
