package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AsioExceptionTest {

    @Test
    void messageNamesTheOperationAndTheError() {
        AsioException e = new AsioException("setSampleRate", -995, "");
        assertEquals("setSampleRate failed: ASE_NoClock (-995)", e.getMessage());
        assertEquals(-995, e.code());
    }

    @Test
    void driverMessageIsAppendedAfterAnEmDash() {
        AsioException e = new AsioException("init", 0, "Device is not connected");
        assertEquals("init failed: ASE_OK (0) — Device is not connected", e.getMessage());
    }

    @Test
    void absentDriverMessageAddsNothing() {
        assertEquals("start failed: ASE_NotPresent (-1000)", new AsioException("start", -1000, null).getMessage());
        assertEquals("start failed: ASE_NotPresent (-1000)", new AsioException("start", -1000, "  ").getMessage());
    }

    @Test
    void unknownCodesStillFormat() {
        assertEquals("CoCreateInstance failed: Unknown (-2147221164)",
                new AsioException("CoCreateInstance", 0x80040154, "").getMessage());
    }
}
