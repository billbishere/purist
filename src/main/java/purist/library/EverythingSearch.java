package purist.library;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import purist.app.AppLog;
import purist.win.Native;

/**
 * Whole-drive discovery through the user's running Everything (voidtools), spec §8. Everything keeps an index of
 * every NTFS drive, so "every audio file on {@code E:\}" is one query answered in about a second, with no disk
 * walk and no admin rights.
 *
 * <p>The query goes through the Everything SDK's {@code Everything64.dll} (bundled, MIT-style licence, called
 * through FFM). The SDK keeps its request and result list in global state, so every call is made on one private
 * thread, under one lock. The DLL is copied once to {@code <profile>\lib\} and loaded from there.
 *
 * <p>If Everything is not running, or has not finished loading its index, {@link #available()} is false and the
 * scanner walks the drive instead.
 */
public final class EverythingSearch {

    private static final Logger LOG = AppLog.logger(EverythingSearch.class);

    private static final String DLL_NAME = "Everything64.dll";
    private static final String RESOURCE = "/native/" + DLL_NAME;

    // Everything.h
    private static final int EVERYTHING_ERROR_IPC = 2;
    private static final int EVERYTHING_REQUEST_FILE_NAME = 0x00000001;
    private static final int EVERYTHING_REQUEST_PATH = 0x00000002;

    /** Characters in the buffer a result path is copied into (the longest NTFS path is 32767). */
    private static final int PATH_CHARS = 32768;

    /**
     * How long {@link #available()} waits for Everything. A hung Everything (its IPC window not pumping, a huge
     * index loading) must read as "not available" so the scanner walks the drive, instead of freezing the caller.
     */
    static volatile long availableTimeoutMillis = 5_000;

    /** How long one file-list query may take: longer, since a million-file index takes seconds to list. */
    static volatile long searchTimeoutMillis = 30_000;

    private static final Object LOCK = new Object();
    private static ExecutorService worker;
    private static Api api;
    private static boolean loadFailed;

    private EverythingSearch() {
    }

    /**
     * True if Everything is running and its index is loaded, so {@link #audioFiles} can answer. Waits at most a few
     * seconds for it, so call it from a background thread, never the UI thread.
     */
    public static boolean available() {
        try {
            return onWorker(() -> {
                Api a = api();
                return a != null && a.isDbLoaded();
            }, availableTimeoutMillis);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "Everything is not available", e);
            return false;
        }
    }

    /**
     * Every file under {@code driveRoot} (a drive such as {@code E:\}, or any folder) whose extension is in
     * {@code extensions} (lower-case, no dot), as Everything's index knows them now.
     *
     * @throws IllegalStateException if Everything is not running, the query fails, or it does not answer in time
     */
    public static List<Path> audioFiles(Path driveRoot, Set<String> extensions) {
        if (extensions.isEmpty()) {
            return List.of();
        }
        return search(query(driveRoot, extensions), driveRoot);
    }

    /** Runs {@code search} and keeps the results under {@code root}. */
    static List<Path> search(String search, Path driveRoot) {
        return onWorker(() -> {
            Api a = api();
            if (a == null) {
                throw new IllegalStateException("The Everything SDK could not be loaded");
            }
            a.reset();
            try {
                a.setSearch(search);
                a.setRequestFlags(EVERYTHING_REQUEST_FILE_NAME | EVERYTHING_REQUEST_PATH);
                if (!a.query()) {
                    int error = a.lastError();
                    throw new IllegalStateException(error == EVERYTHING_ERROR_IPC
                            ? "Everything is not running"
                            : "Everything could not answer the query (error " + error + ")");
                }
                int count = a.numResults();
                List<Path> out = new ArrayList<>(count);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment buffer = arena.allocate(2L * PATH_CHARS, 2);
                    for (int i = 0; i < count; i++) {
                        int length = a.fullPath(i, buffer);
                        if (length <= 0) {
                            continue;
                        }
                        Path p = toPath(Native.readWstr(buffer, Math.min(length, PATH_CHARS)));
                        if (p != null && p.startsWith(driveRoot)) {
                            out.add(p);
                        }
                    }
                }
                return out;
            } finally {
                a.reset(); // frees the result list
            }
        }, searchTimeoutMillis);
    }

    /** This class as a {@link FileSource}, for the scanner. */
    public static FileSource source() {
        return SOURCE;
    }

    private static final FileSource SOURCE = new FileSource() {
        @Override
        public boolean available() {
            return EverythingSearch.available();
        }

        @Override
        public List<Path> audioFiles(Path driveRoot, Set<String> extensions) {
            return EverythingSearch.audioFiles(driveRoot, extensions);
        }
    };

    // ---- the query ----

    /**
     * The search text: the root in quotes with a trailing separator (so {@code D:\Music} does not claim
     * {@code D:\Music2}), files only, and the extensions as one {@code ext:} list.
     */
    static String query(Path root, Set<String> extensions) {
        String r = root.toString();
        if (!r.endsWith("\\")) {
            r += "\\";
        }
        Set<String> sorted = new TreeSet<>();
        for (String e : extensions) {
            sorted.add(e.toLowerCase(Locale.ROOT));
        }
        return "\"" + r + "\" file: ext:" + String.join(";", sorted);
    }

    private static Path toPath(String s) {
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    // ---- the DLL ----

    /**
     * Copies the bundled DLL into {@code libDir} unless an identical copy is already there.
     *
     * @return the DLL's path
     */
    static Path extract(Path libDir) throws IOException {
        byte[] bundled;
        try (InputStream in = EverythingSearch.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException(DLL_NAME + " is not bundled with this build");
            }
            bundled = in.readAllBytes();
        }
        Files.createDirectories(libDir);
        Path target = libDir.resolve(DLL_NAME);
        if (Files.isRegularFile(target) && Files.size(target) == bundled.length
                && Arrays.equals(Files.readAllBytes(target), bundled)) {
            return target;
        }
        Path temp = Files.createTempFile(libDir, "Everything64", ".tmp");
        try {
            Files.write(temp, bundled);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Another Purist has the DLL loaded and locked. Same size means the same SDK build; use it.
                if (!(Files.isRegularFile(target) && Files.size(target) == bundled.length)) {
                    throw e;
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    /** The loaded SDK, or null if it cannot be loaded (remembered, so a broken install is not retried per call). */
    private static Api api() {
        if (api == null && !loadFailed) {
            try {
                Path dll = extract(purist.app.Profile.current().dir().resolve("lib"));
                api = new Api(SymbolLookup.libraryLookup(dll, Arena.global()));
            } catch (IOException | RuntimeException | LinkageError e) {
                loadFailed = true;
                LOG.log(Level.WARNING, "Cannot load the Everything SDK", e);
            }
        }
        return api;
    }

    /** The SDK functions this class calls. */
    private static final class Api {
        private final MethodHandle reset;
        private final MethodHandle setSearchW;
        private final MethodHandle setRequestFlags;
        private final MethodHandle queryW;
        private final MethodHandle getNumResults;
        private final MethodHandle getResultFullPathNameW;
        private final MethodHandle getLastError;
        private final MethodHandle isDbLoaded;

        Api(SymbolLookup lib) {
            reset = bind(lib, "Everything_Reset", FunctionDescriptor.ofVoid());
            setSearchW = bind(lib, "Everything_SetSearchW", FunctionDescriptor.ofVoid(ADDRESS));
            setRequestFlags = bind(lib, "Everything_SetRequestFlags", FunctionDescriptor.ofVoid(JAVA_INT));
            queryW = bind(lib, "Everything_QueryW", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            getNumResults = bind(lib, "Everything_GetNumResults", FunctionDescriptor.of(JAVA_INT));
            getResultFullPathNameW = bind(lib, "Everything_GetResultFullPathNameW",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
            getLastError = bind(lib, "Everything_GetLastError", FunctionDescriptor.of(JAVA_INT));
            isDbLoaded = bind(lib, "Everything_IsDBLoaded", FunctionDescriptor.of(JAVA_INT));
        }

        private static MethodHandle bind(SymbolLookup lib, String name, FunctionDescriptor fd) {
            MemorySegment address = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name + " not found"));
            return Native.LINKER.downcallHandle(address, fd);
        }

        void reset() {
            try {
                reset.invokeExact();
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        void setSearch(String text) {
            try (Arena arena = Arena.ofConfined()) {
                setSearchW.invokeExact(Native.wstr(arena, text)); // the SDK copies the text
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        void setRequestFlags(int flags) {
            try {
                setRequestFlags.invokeExact(flags);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Everything_QueryW(TRUE): sends the query and waits for the answer. */
        boolean query() {
            try {
                return (int) queryW.invokeExact(1) != 0;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        int numResults() {
            try {
                return (int) getNumResults.invokeExact();
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** Copies result {@code index}'s full path into {@code buffer}; returns the characters copied. */
        int fullPath(int index, MemorySegment buffer) {
            try {
                return (int) getResultFullPathNameW.invokeExact(index, buffer, PATH_CHARS);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        int lastError() {
            try {
                return (int) getLastError.invokeExact();
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        /** False when Everything is not running (the SDK then sets {@code EVERYTHING_ERROR_IPC}) or still loading. */
        boolean isDbLoaded() {
            try {
                return (int) isDbLoaded.invokeExact() != 0;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }

        private static RuntimeException rethrow(Throwable t) {
            if (t instanceof Error e) {
                throw e;
            }
            if (t instanceof RuntimeException r) {
                return r;
            }
            return new IllegalStateException("Everything SDK call failed", t);
        }
    }

    // ---- one thread, one lock ----

    static <T> T onWorker(Callable<T> job, long timeoutMillis) {
        ExecutorService executor;
        synchronized (LOCK) {
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "purist-everything");
                    t.setDaemon(true);
                    return t;
                });
            }
            executor = worker;
        }
        Future<T> future = executor.submit(() -> {
            synchronized (LOCK) {
                return job.call();
            }
        });
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // a call already inside the DLL cannot be interrupted; one still queued is dropped
            throw new IllegalStateException("Everything did not answer within " + timeoutMillis + " ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Everything", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        }
    }
}
