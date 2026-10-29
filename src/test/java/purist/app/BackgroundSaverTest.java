package purist.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackgroundSaverTest {

    @Test
    void saveLaterReturnsAtOnceAndTheSaveRunsOnAnotherThread() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Thread> on = new AtomicReference<>();
        try (BackgroundSaver saver = new BackgroundSaver(() -> {
            on.set(Thread.currentThread());
            hold.await(); // a save stuck behind a virus scanner
            ran.countDown();
        })) {
            long start = System.nanoTime();
            saver.saveLater();
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "the caller is not held up by the disk");

            hold.countDown();
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread(), on.get());
        }
    }

    @Test
    void aFailedSaveIsLoggedAndTheNextOneStillRuns() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch second = new CountDownLatch(1);
        try (BackgroundSaver saver = new BackgroundSaver(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("disk full");
            }
            second.countDown();
        })) {
            saver.saveLater();
            saver.saveLater();
            assertTrue(second.await(5, TimeUnit.SECONDS));
        }
        assertEquals(2, calls.get());
    }

    @Test
    void closeWaitsForTheSaveInFlight() throws Exception {
        AtomicInteger done = new AtomicInteger();
        BackgroundSaver saver = new BackgroundSaver(() -> {
            Thread.sleep(300);
            done.incrementAndGet();
        });
        saver.saveLater();
        saver.close();
        assertEquals(1, done.get());
    }
}
