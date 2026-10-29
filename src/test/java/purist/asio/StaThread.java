package purist.asio;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import purist.win.Ole32;

/** Test helper: runs a body on a fresh thread inside a COM single-threaded apartment, as ASIO drivers need. */
final class StaThread {

    private static final long TIMEOUT_MS = 60_000;

    private StaThread() {
    }

    /** Runs {@code body} on a new STA thread and returns its result, rethrowing whatever it threw. */
    static <T> T call(Callable<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            Ole32.coInitializeSta();
            try {
                result.set(body.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                Ole32.coUninitialize();
            }
        }, "asio-sta-test");
        thread.start();
        thread.join(TIMEOUT_MS);
        assertFalse(thread.isAlive(), "STA body did not finish within " + TIMEOUT_MS + " ms");
        Throwable t = failure.get();
        if (t instanceof Exception e) {
            throw e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return result.get();
    }
}
