package purist.asio;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import purist.win.Ole32;

/**
 * The one thread that owns an ASIO driver (spec §4, §6): a daemon thread named {@code "asio-control"} that
 * enters a COM single-threaded apartment before anything else, then runs submitted tasks one at a time, in
 * order. Every IASIO call except the callbacks belongs here.
 *
 * <p>{@link #post} takes no lock but allocates a queue node, and throws once the thread is closed. Work requested
 * from a thread that must not allocate, such as the driver's callback thread asking for a rebuild after a reset
 * request, goes through a {@link Signal} made beforehand with {@link #newSignal}: raising it allocates nothing
 * and never throws. A posted task or signal action that throws is reported to the thread's uncaught-exception
 * handler and the thread carries on.
 */
public final class AsioControlThread implements AutoCloseable {

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    /** Raised signals not yet taken by the worker: a lock-free stack linked through {@link Signal#next}. */
    private final AtomicReference<Signal> raised = new AtomicReference<>();
    private final Thread thread;
    private final String name;
    private final boolean multithreaded;
    private volatile boolean closing;
    /** Set by the worker once it has taken its last task; see {@link #post}. */
    private volatile boolean terminated;

    /**
     * Starts the thread and waits until it is in its apartment.
     *
     * @throws IllegalStateException if COM could not be initialised on it
     */
    public AsioControlThread() {
        this("asio-control", false);
    }

    /**
     * A control thread named {@code name}, in a single-threaded apartment or, if {@code multithreaded}, in the
     * multithreaded one (WASAPI's audio clients live there, see {@code purist.wasapi.WasapiSink}).
     *
     * @throws IllegalStateException if COM could not be initialised on it
     */
    public AsioControlThread(String name, boolean multithreaded) {
        this.name = Objects.requireNonNull(name, "name");
        this.multithreaded = multithreaded;
        CompletableFuture<Void> ready = new CompletableFuture<>();
        thread = new Thread(() -> run(ready), name);
        thread.setDaemon(true);
        thread.start();
        try {
            ready.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException(name + " could not enter a COM apartment: " + cause.getMessage(),
                    cause);
        }
    }

    /** True when the calling thread is this control thread. */
    public boolean isControlThread() {
        return Thread.currentThread() == thread;
    }

    /**
     * Runs {@code task} on the control thread and returns its result, rethrowing whatever it threw unchanged.
     * Called on the control thread itself, it runs the task inline. The wait is not interruptible, since a
     * driver call cannot be abandoned half way; an interrupt that arrives meanwhile is restored on return.
     *
     * @throws IllegalStateException if the thread has been {@linkplain #close closed}
     */
    public <T> T call(Callable<T> task) throws Exception {
        Objects.requireNonNull(task, "task");
        if (isControlThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<>(task);
        post(future);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (CancellationException e) {
                    throw closedError();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception x) {
                        throw x;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Queues {@code task} to run on the control thread after everything queued before it, and returns at once.
     * Lock-free, but one queue node is allocated: a thread that must not allocate raises a {@link Signal}.
     *
     * @throws IllegalStateException if the thread has been {@linkplain #close closed}
     */
    public void post(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (closing) {
            throw closedError();
        }
        queue.offer(task);
        LockSupport.unpark(thread);
        // If the worker had already taken its last task, this one would never run: take it back. The worker
        // sets `terminated` before its final sweep of the queue, so either that sweep sees the task (and
        // cancels it) or this check sees the flag.
        if (terminated && queue.remove(task)) {
            throw closedError();
        }
    }

    /**
     * Makes a {@link Signal} that runs {@code action} on this thread each time it is raised. Allocate it ahead of
     * time, on any thread; raising it later allocates nothing.
     */
    public Signal newSignal(Runnable action) {
        return new Signal(Objects.requireNonNull(action, "action"));
    }

    /**
     * A preallocated, coalescing request for work on the control thread, for threads that must not allocate or
     * throw (the driver's callback thread). {@link #raise} costs a couple of compare-and-sets and an unpark. The
     * action then runs once on the control thread, before the next task that thread starts. Raising again while a
     * run is pending does nothing; raising while the action runs schedules one more run.
     */
    public final class Signal {

        private final Runnable action;
        /** True from a successful {@link #raise} until the worker is about to run the action. */
        private final AtomicBoolean pending = new AtomicBoolean();
        /**
         * The next raised signal on the stack. Written by {@link #raise} before the push and read by the worker
         * after it has taken the stack; {@code pending} keeps the two from overlapping.
         */
        private Signal next;

        private Signal(Runnable action) {
            this.action = action;
        }

        /**
         * Asks for the action to run on the control thread. Allocates nothing, takes no lock and never throws.
         *
         * @return true if this call scheduled a run; false if one was already pending or the thread is closing
         */
        public boolean raise() {
            if (closing || !pending.compareAndSet(false, true)) {
                return false;
            }
            Signal head;
            do {
                head = raised.get();
                next = head;
            } while (!raised.compareAndSet(head, this));
            LockSupport.unpark(thread);
            return true;
        }
    }

    /**
     * Runs every signal raised so far, in the order raised; false if there were none. A signal raised again
     * while this runs waits for the next turn of the loop, so one that keeps raising itself cannot starve tasks.
     */
    private boolean runRaisedSignals() {
        Signal taken = raised.getAndSet(null);
        if (taken == null) {
            return false;
        }
        Signal inOrder = null; // the stack is newest first: reverse it
        while (taken != null) {
            Signal next = taken.next;
            taken.next = inOrder;
            inOrder = taken;
            taken = next;
        }
        while (inOrder != null) {
            Signal s = inOrder;
            inOrder = s.next;
            s.next = null;
            s.pending.set(false); // from here a raise schedules another run
            runReporting(s.action);
        }
        return true;
    }

    /**
     * Runs what is already queued, leaves the apartment ({@code CoUninitialize}) and ends the thread, waiting
     * for it unless called on the control thread itself. Release every driver first. Idempotent.
     */
    @Override
    public void close() {
        closing = true;
        LockSupport.unpark(thread);
        if (isControlThread()) {
            return; // the loop ends once the current task returns
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(CompletableFuture<Void> ready) {
        try {
            if (multithreaded) {
                Ole32.coInitializeMta();
            } else {
                Ole32.coInitializeSta();
            }
        } catch (Throwable t) {
            closing = true;
            terminated = true;
            ready.completeExceptionally(t);
            return;
        }
        ready.complete(null);
        try {
            while (true) {
                // Read the flag before polling: a task posted before close() is then seen by this poll.
                boolean last = closing;
                Runnable task = queue.poll();
                // Signals after the poll: one raised before `task` was posted is then seen and runs first.
                boolean ranSignals = runRaisedSignals();
                if (task != null) {
                    runReporting(task);
                } else if (ranSignals) {
                    continue;
                } else if (last) {
                    break;
                } else {
                    LockSupport.park(this);
                }
            }
        } finally {
            try {
                Ole32.coUninitialize();
            } finally {
                terminated = true;
                for (Runnable straggler; (straggler = queue.poll()) != null; ) {
                    if (straggler instanceof Future<?> f) {
                        f.cancel(false); // its caller gets IllegalStateException
                    }
                }
            }
        }
    }

    private void runReporting(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
        }
    }

    private IllegalStateException closedError() {
        return new IllegalStateException(name + " has been closed");
    }
}
