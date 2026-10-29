package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the real UA Volt 176 through its ASIO driver. Needs the Volt connected and idle (nothing else
 * playing on it), and it sets the device to 48 kHz. Every driver call runs on a COM STA thread.
 */
@Tag("hardware")
class ComAsioDriverIT {

    private static final String VOLT = "Universal Audio Volt";
    private static final double RATE = 48000;

    /** Callbacks that do nothing but keep the buffers silent, so the DAC never plays stale memory. */
    private static final class Silent implements AsioCallbacks {
        private final AsioDriver driver;
        private final long bytes;
        final AtomicInteger switches = new AtomicInteger();

        Silent(AsioDriver driver, long bytes) {
            this.driver = driver;
            this.bytes = bytes;
        }

        @Override
        public void bufferSwitch(int half) {
            for (int i = 0; i < 2; i++) {
                driver.buffer(i, half, bytes).fill((byte) 0);
            }
            switches.incrementAndGet();
            driver.outputReady();
        }

        @Override
        public void event(AsioEvent e, int value) {
            // Resets are not expected while the test owns the device.
        }
    }

    private static AsioDriverInfo volt() {
        return AsioDriverList.installed().stream()
                .filter(d -> d.name().equals(VOLT))
                .findFirst()
                .orElseThrow(() -> new AssertionError(VOLT + " is not installed"));
    }

    @Test
    void voltStreamsSilenceAtTheDriversBufferCadence() throws Exception {
        StaThread.call(() -> {
            try (ComAsioDriver driver = ComAsioDriver.load(volt())) {
                assertEquals(VOLT, driver.name());
                assertEquals(2, driver.outputChannels());
                int preferred = driver.bufferSize().preferred();
                assertTrue(preferred > 0, "preferred buffer size " + preferred);

                assertTrue(driver.canSampleRate(RATE));
                assertFalse(driver.canSampleRate(352800));
                assertEquals(AsioConstants.ST_INT32_LSB, driver.outputChannelType(0));

                driver.setSampleRate(RATE);
                assertEquals(RATE, driver.sampleRate());

                long bytes = preferred * 4L; // Int32LSB container
                Silent counting = new Silent(driver, bytes);
                driver.createBuffers(new int[] {0, 1}, preferred, counting);
                for (int half = 0; half < 2; half++) {
                    for (int i = 0; i < 2; i++) {
                        MemorySegment buf = driver.buffer(i, half, bytes);
                        assertEquals(bytes, buf.byteSize());
                        buf.fill((byte) 0);
                    }
                }
                driver.start();
                Thread.sleep(500);
                driver.stop();

                double expected = 0.5 * RATE / preferred;
                int actual = counting.switches.get();
                assertTrue(Math.abs(actual - expected) <= 0.15 * expected,
                        "bufferSwitch count " + actual + ", expected about " + expected + " (buffer " + preferred + ")");
                assertEquals(0, driver.callbackErrors());

                driver.disposeBuffers();
            }
            return null;
        });
    }

    @Test
    void driverFailuresAreNamedAndLeaveTheDriverUsable() throws Exception {
        StaThread.call(() -> {
            try (ComAsioDriver driver = ComAsioDriver.load(volt())) {
                int preferred = driver.bufferSize().preferred();

                AsioException noClock = assertThrows(AsioException.class, () -> driver.setSampleRate(352800));
                assertEquals(AsioConstants.ASE_NO_CLOCK, noClock.code());
                assertTrue(noClock.getMessage().startsWith("setSampleRate failed: ASE_NoClock (-995)"),
                        noClock.getMessage());

                assertThrows(AsioException.class, () -> driver.outputChannelType(99));

                Silent silent = new Silent(driver, preferred * 4L);
                AsioException badChannel = assertThrows(AsioException.class,
                        () -> driver.createBuffers(new int[] {0, 99}, preferred, silent));
                assertEquals(AsioConstants.ASE_INVALID_PARAMETER, badChannel.code());

                // The failed attempt must not leave a half-created state behind.
                driver.setSampleRate(RATE);
                driver.createBuffers(new int[] {0, 1}, preferred, silent);
                driver.disposeBuffers();
                driver.disposeBuffers(); // nothing to dispose: a no-op, not an error
            }
            return null;
        });
    }

    @Test
    void closeTearsDownARunningStreamAndTheDriverCanBeLoadedAgain() throws Exception {
        StaThread.call(() -> {
            ComAsioDriver driver = ComAsioDriver.load(volt());
            int preferred = driver.bufferSize().preferred();
            driver.setSampleRate(RATE);
            Silent silent = new Silent(driver, preferred * 4L);
            driver.createBuffers(new int[] {0, 1}, preferred, silent);
            driver.start();
            Thread.sleep(100);

            driver.close(); // no stop(), no disposeBuffers()
            driver.close(); // idempotent
            assertThrows(IllegalStateException.class, driver::start);
            assertThrows(IllegalStateException.class, () -> driver.buffer(0, 0, 8));

            try (ComAsioDriver again = ComAsioDriver.load(volt())) {
                assertEquals(RATE, again.sampleRate());
            }
            return null;
        });
    }

    @Test
    void controlCallsFromAnotherThreadAreRefused() throws Exception {
        StaThread.call(() -> {
            try (ComAsioDriver driver = ComAsioDriver.load(volt())) {
                Throwable[] thrown = new Throwable[1];
                Thread other = new Thread(() -> {
                    try {
                        driver.outputChannels();
                    } catch (Throwable t) {
                        thrown[0] = t;
                    }
                });
                other.start();
                other.join();
                assertTrue(thrown[0] instanceof IllegalStateException, String.valueOf(thrown[0]));
            }
            return null;
        });
    }
}
