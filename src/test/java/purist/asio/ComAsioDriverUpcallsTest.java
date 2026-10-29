package purist.asio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The driver-thread callback logic, exercised directly so no driver is needed. */
class ComAsioDriverUpcallsTest {

    private record Event(AsioEvent event, int value) {
    }

    private static final class Recorder implements AsioCallbacks {
        final List<Integer> halves = new ArrayList<>();
        final List<Event> events = new ArrayList<>();

        @Override
        public void bufferSwitch(int half) {
            halves.add(half);
        }

        @Override
        public void event(AsioEvent e, int value) {
            events.add(new Event(e, value));
        }
    }

    private static final class Throwing implements AsioCallbacks {
        @Override
        public void bufferSwitch(int half) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void event(AsioEvent e, int value) {
            throw new AssertionError("boom");
        }
    }

    private final AtomicLong errors = new AtomicLong();
    private final Recorder recorder = new Recorder();
    private final ComAsioDriver.Upcalls up = new ComAsioDriver.Upcalls(recorder, errors);

    @Test
    void selectorSupportedAnswersYesForSelectorsOneToSeven() {
        for (int selector = 1; selector <= 7; selector++) {
            assertEquals(1, up.asioMessage(AsioConstants.MSG_SELECTOR_SUPPORTED, selector, 0, 0), "selector " + selector);
        }
        for (int selector : new int[] {0, 8, 9, 15, 100, -1}) {
            assertEquals(0, up.asioMessage(AsioConstants.MSG_SELECTOR_SUPPORTED, selector, 0, 0), "selector " + selector);
        }
    }

    @Test
    void reportsEngineVersionTwoAndNoTimeInfo() {
        assertEquals(2, up.asioMessage(AsioConstants.MSG_ENGINE_VERSION, 0, 0, 0));
        assertEquals(0, up.asioMessage(AsioConstants.MSG_SUPPORTS_TIME_INFO, 0, 0, 0));
        assertEquals(0, up.asioMessage(AsioConstants.MSG_SUPPORTS_TIME_CODE, 0, 0, 0));
        assertEquals(0, up.asioMessage(9999, 0, 0, 0));
        assertTrue(recorder.events.isEmpty());
    }

    @Test
    void acknowledgesTheFourRebuildAndResyncMessages() {
        assertEquals(1, up.asioMessage(AsioConstants.MSG_RESET_REQUEST, 0, 0, 0));
        assertEquals(1, up.asioMessage(AsioConstants.MSG_BUFFER_SIZE_CHANGE, 256, 0, 0));
        assertEquals(1, up.asioMessage(AsioConstants.MSG_RESYNC_REQUEST, 0, 0, 0));
        assertEquals(1, up.asioMessage(AsioConstants.MSG_LATENCIES_CHANGED, 0, 0, 0));
        assertEquals(List.of(
                new Event(AsioEvent.RESET_REQUEST, 0),
                new Event(AsioEvent.BUFFER_SIZE_CHANGE, 256),
                new Event(AsioEvent.RESYNC_REQUEST, 0),
                new Event(AsioEvent.LATENCIES_CHANGED, 0)), recorder.events);
    }

    @Test
    void overloadIsReportedButNotAcknowledged() {
        assertEquals(0, up.asioMessage(AsioConstants.MSG_OVERLOAD, 0, 0, 0));
        assertEquals(List.of(new Event(AsioEvent.OVERLOAD, 0)), recorder.events);
    }

    @Test
    void bufferSwitchForwardsTheHalf() {
        up.bufferSwitch(1, 1);
        up.bufferSwitch(0, 0);
        assertEquals(List.of(1, 0), recorder.halves);
    }

    @Test
    void timeInfoSwitchForwardsTheHalfAndReturnsItsParamsPointer() {
        long params = 0x7ff6_1234_5678L;
        assertEquals(params, up.bufferSwitchTimeInfo(params, 1, 0));
        assertEquals(0L, up.bufferSwitchTimeInfo(0L, 0, 0));
        assertEquals(List.of(1, 0), recorder.halves);
    }

    @Test
    void sampleRateChangeEmitsAnEventCarryingTheRate() {
        up.sampleRateDidChange(96000.0);
        assertEquals(List.of(new Event(AsioEvent.SAMPLE_RATE_CHANGED, 96000)), recorder.events);
    }

    @Test
    void nothingEverEscapesAnUpcallAndEveryFailureIsCounted() {
        ComAsioDriver.Upcalls bad = new ComAsioDriver.Upcalls(new Throwing(), errors);
        long params = 42L;
        assertDoesNotThrow(() -> bad.bufferSwitch(0, 0));
        assertEquals(params, assertDoesNotThrow(() -> bad.bufferSwitchTimeInfo(params, 1, 0)));
        assertDoesNotThrow(() -> bad.sampleRateDidChange(44100.0));
        assertEquals(0, assertDoesNotThrow(() -> bad.asioMessage(AsioConstants.MSG_RESET_REQUEST, 0, 0, 0)));
        assertEquals(4, errors.get());
    }
}
