package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import purist.audio.NotBitPerfectException;
import purist.win.Ole32;

@Timeout(30)
class AsioControlThreadTest {

    @Test
    void runsTasksOnOneDaemonThreadNamedAsioControl() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            Thread worker = ctl.call(Thread::currentThread);
            assertEquals("asio-control", worker.getName());
            assertTrue(worker.isDaemon());
            assertSame(worker, ctl.call(Thread::currentThread), "every task runs on the same thread");
            assertTrue(ctl.call(ctl::isControlThread));
            assertFalse(ctl.isControlThread());
        }
    }

    @Test
    void theThreadIsInASingleThreadedApartment() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            int hr = ctl.call(() -> {
                int again = Ole32.coInitializeSta(); // S_FALSE (1): this thread already entered an STA
                Ole32.coUninitialize();
                return again;
            });
            assertEquals(1, hr);
        }
    }

    @Test
    void callRethrowsWhatTheTaskThrewUnchanged() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            NotBitPerfectException refusal = new NotBitPerfectException("nope");
            assertSame(refusal, assertThrows(NotBitPerfectException.class, () -> ctl.call(() -> {
                throw refusal;
            })));
            IllegalArgumentException bug = new IllegalArgumentException("bug");
            assertSame(bug, assertThrows(IllegalArgumentException.class, () -> ctl.call(() -> {
                throw bug;
            })));
            assertEquals(7, ctl.call(() -> 7), "the thread survives a failing task");
        }
    }

    @Test
    void postedTasksRunInOrderBeforeALaterCall() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            List<Integer> ran = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                int n = i;
                ctl.post(() -> ran.add(n));
            }
            List<Integer> seen = ctl.call(() -> List.copyOf(ran));
            assertEquals(IntStream.range(0, 100).boxed().toList(), seen);
        }
    }

    @Test
    void aCallFromTheControlThreadRunsInline() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            assertEquals(42, ctl.call(() -> ctl.call(() -> 42)));
        }
    }

    @Test
    void anInterruptedCallerStillGetsTheResultAndKeepsTheInterrupt() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            Thread.currentThread().interrupt();
            try {
                assertEquals("done", ctl.call(() -> {
                    Thread.sleep(50);
                    return "done";
                }));
            } finally {
                assertTrue(Thread.interrupted(), "the caller's interrupt is restored (and cleared here)");
            }
        }
    }

    /** Occupies the control thread until {@code release} opens; returns once the thread is inside the task. */
    private static void block(AsioControlThread ctl, CountDownLatch release) throws InterruptedException {
        CountDownLatch busy = new CountDownLatch(1);
        ctl.post(() -> {
            busy.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        busy.await();
    }

    @Test
    void aSignalRunsOnTheControlThreadAndCoalescesWhilePending() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            List<String> ran = new CopyOnWriteArrayList<>();
            AsioControlThread.Signal signal = ctl.newSignal(() -> ran.add(Thread.currentThread().getName()));
            CountDownLatch release = new CountDownLatch(1);
            block(ctl, release);
            assertTrue(signal.raise(), "the first raise schedules a run");
            assertFalse(signal.raise(), "already pending: coalesced");
            assertFalse(signal.raise());
            release.countDown();
            ctl.call(() -> null);
            assertEquals(List.of("asio-control"), ran, "one run, on the control thread, before the later call");

            assertTrue(signal.raise(), "it can be raised again once it has run");
            ctl.call(() -> null);
            assertEquals(2, ran.size());
        }
    }

    @Test
    void aRaisedSignalRunsBeforeTheNextTask() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            List<String> order = new CopyOnWriteArrayList<>();
            AsioControlThread.Signal signal = ctl.newSignal(() -> order.add("signal"));
            CountDownLatch release = new CountDownLatch(1);
            block(ctl, release);
            ctl.post(() -> order.add("task"));
            signal.raise();
            release.countDown();
            ctl.call(() -> null);
            assertEquals(List.of("signal", "task"), order);
        }
    }

    @Test
    void raisingDuringItsRunSchedulesOneMoreRun() throws Exception {
        try (AsioControlThread ctl = new AsioControlThread()) {
            AtomicInteger runs = new AtomicInteger();
            AtomicReference<AsioControlThread.Signal> self = new AtomicReference<>();
            self.set(ctl.newSignal(() -> {
                if (runs.incrementAndGet() == 1) {
                    assertTrue(self.get().raise(), "not pending while it runs");
                }
            }));
            self.get().raise();
            // The re-raise is taken up on the worker's next turn, so two round trips cover both runs.
            ctl.call(() -> null);
            ctl.call(() -> null);
            assertEquals(2, runs.get());
        }
    }

    @Test
    void raiseAfterCloseIsRefusedQuietly() throws Exception {
        AsioControlThread ctl = new AsioControlThread();
        AtomicInteger runs = new AtomicInteger();
        AsioControlThread.Signal signal = ctl.newSignal(runs::incrementAndGet);
        ctl.close();
        assertFalse(signal.raise(), "no exception, nothing scheduled");
        assertEquals(0, runs.get());
    }

    /**
     * {@link AsioControlThread.Signal#raise} is what the driver's thread uses, so it must allocate nothing: each
     * round raises the signal and spins until the control thread has run it. Measured in windows, passing on the
     * first clean one, as in the other allocation tripwires.
     */
    @Test
    void raiseAllocatesNothing() throws Exception {
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled());
        try (AsioControlThread ctl = new AsioControlThread()) {
            AtomicInteger runs = new AtomicInteger();
            AsioControlThread.Signal signal = ctl.newSignal(runs::incrementAndGet);
            for (int i = 0; i < 20_000; i++) { // warm-up
                raiseAndAwait(signal, runs);
            }
            long smallest = Long.MAX_VALUE;
            for (int window = 0; window < 20 && smallest != 0; window++) {
                long before = threads.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < 5_000; i++) {
                    raiseAndAwait(signal, runs);
                }
                smallest = Math.min(smallest, threads.getCurrentThreadAllocatedBytes() - before);
            }
            assertEquals(0, smallest, "bytes allocated by 5000 raise-and-run rounds in the cleanest window");
        }
    }

    private static void raiseAndAwait(AsioControlThread.Signal signal, AtomicInteger runs) {
        int target = runs.get() + 1;
        if (!signal.raise()) {
            throw new AssertionError("the previous run had finished, so this raise should schedule one");
        }
        while (runs.get() < target) {
            Thread.onSpinWait();
        }
    }

    @Test
    void closeEndsTheThreadAndRefusesFurtherWork() throws Exception {
        AsioControlThread ctl = new AsioControlThread();
        Thread worker = ctl.call(Thread::currentThread);
        List<String> ran = new ArrayList<>();
        ctl.post(() -> ran.add("before close"));
        ctl.close();

        assertFalse(worker.isAlive(), "close() waits for the thread to finish");
        assertEquals(List.of("before close"), ran, "work posted before close still runs");
        assertThrows(IllegalStateException.class, () -> ctl.post(() -> ran.add("after close")));
        assertThrows(IllegalStateException.class, () -> ctl.call(() -> "after close"));
        ctl.close(); // idempotent
        assertEquals(List.of("before close"), ran);
    }
}
