package purist.decode.flac;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import purist.decode.DecodeException;
import purist.decode.UnsupportedFormatException;
import purist.decode.flac.FlacDamageException.Kind;
import purist.win.FileAttributes;

/**
 * Decodes FLAC files with {@link FlacDecoder}, the decoder the player uses, and compares the result with the MD5
 * of the audio that STREAMINFO carries (RFC 9639 section 8.2: the little-endian samples of {@code ceil(bits / 8)}
 * bytes each, interleaved). Only reads files.
 *
 * <p>Command line: {@code java --enable-native-access=ALL-UNNAMED -cp target/classes purist.decode.flac.FlacVerify
 * <file-or-folder>...} prints the counts and every file that is not OK, and exits with status 1 if any is a
 * MISMATCH or an ERROR (a path that does not exist is one). Files whose content is still in the cloud (OneDrive
 * placeholders) are never opened, since opening one would download it; they are counted and skipped.
 */
public final class FlacVerify {

    /** OK: decoded and the MD5 matches. NO_MD5: decoded, but STREAMINFO has no MD5 (all zero) to compare. */
    public enum Status { OK, MISMATCH, NO_MD5, ERROR }

    /**
     * @param detail for a MISMATCH the two MD5s, for an ERROR what went wrong; empty otherwise
     * @param damage for an ERROR the decoder proved to be damage in the file, which kind; null for everything else
     *               (a file that decodes, a MISMATCH, a decoder or I/O fault, a root that does not exist, a folder
     *               that cannot be listed). It comes from the exception's type, never from its message.
     */
    public record Result(Path path, Status status, String detail, Kind damage) {

        public Result(Path path, Status status, String detail) {
            this(path, status, detail, null);
        }
    }

    /**
     * What {@link #scan} found.
     *
     * @param cloudOnly files skipped because their content is in the cloud, not counted in the other numbers
     * @param problems  every result that is not OK, in path order; this includes folders that could not be listed
     */
    public record Summary(int ok, int mismatch, int noMd5, int error, int cloudOnly, List<Result> problems) {

        /** {@code "1204 OK, 0 MISMATCH, 0 NO_MD5, 0 ERROR"}. */
        public String line() {
            return ok + " OK, " + mismatch + " MISMATCH, " + noMd5 + " NO_MD5, " + error + " ERROR";
        }
    }

    private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int CHUNK_FRAMES = 16384;
    private static final int PROGRESS_EVERY = 100;
    private static final int THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

    private FlacVerify() {}

    /** Decodes the whole file and compares it with its MD5. Never throws for the content or readability of the file. */
    public static Result verify(Path p) {
        try (FlacDecoder d = FlacDecoder.open(p); Arena arena = Arena.ofConfined()) {
            FlacStreamInfo info = d.streamInfo();
            int channels = info.channels();
            int width = (info.bitsPerSample() + 7) / 8;
            MessageDigest md5 = newMd5();
            MemorySegment dst = arena.allocate((long) CHUNK_FRAMES * channels * 4, 4);
            byte[] bytes = new byte[CHUNK_FRAMES * channels * width];
            int n;
            while ((n = d.read(dst, CHUNK_FRAMES)) > 0) { // a short read is not the end: damage comes with the next
                int o = 0;
                for (int i = 0, samples = n * channels; i < samples; i++) {
                    int v = dst.get(LE_INT, i * 4L);
                    for (int b = 0; b < width; b++) {
                        bytes[o++] = (byte) (v >> (8 * b));
                    }
                }
                md5.update(bytes, 0, o);
            }
            byte[] expected = info.md5();
            if (isZero(expected)) {
                return new Result(p, Status.NO_MD5, "");
            }
            byte[] actual = md5.digest();
            if (Arrays.equals(expected, actual)) {
                return new Result(p, Status.OK, "");
            }
            return new Result(p, Status.MISMATCH, "the audio's MD5 is " + HexFormat.of().formatHex(actual)
                    + " but STREAMINFO says " + HexFormat.of().formatHex(expected));
        } catch (FlacDamageException e) {
            return new Result(p, Status.ERROR, describe(e), e.kind());
        } catch (IOException | RuntimeException e) {
            return new Result(p, Status.ERROR, describe(e));
        }
    }

    /**
     * Verifies every {@code .flac} file under {@code roots} (a root may also be a single file), in parallel, and
     * prints to {@code out} every result that is not OK (with its damage kind, if any), then {@link Summary#line()},
     * then {@code "<n> cloud-only skipped"}. A root that does not exist is an ERROR (without a damage kind), so a
     * mistyped or unmounted path cannot pass as a clean scan of nothing.
     */
    public static Summary scan(List<Path> roots, PrintStream out) {
        return scan(roots, out, FileAttributes::isCloudOnly);
    }

    /** As {@link #scan(List, PrintStream)}, with the cloud-only test supplied (it is asked before a file is opened). */
    static Summary scan(List<Path> roots, PrintStream out, Predicate<Path> cloudOnly) {
        List<Path> files = new ArrayList<>();
        List<Result> problems = new ArrayList<>();
        for (Path root : roots) {
            collect(root, files, problems);
        }
        int skipped = 0;
        List<Path> toCheck = new ArrayList<>();
        for (Path f : files) {
            if (cloudOnly.test(f)) {
                skipped++;
            } else {
                toCheck.add(f);
            }
        }

        int ok = 0;
        int mismatch = 0;
        int noMd5 = 0;
        int error = problems.size(); // folders that could not be listed
        ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "flac-verify");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Result>> pending = new ArrayList<>();
            for (Path f : toCheck) {
                pending.add(pool.submit(() -> verify(f)));
            }
            int done = 0;
            for (int i = 0; i < pending.size(); i++) {
                Result r;
                try {
                    r = pending.get(i).get();
                } catch (ExecutionException e) {
                    r = new Result(toCheck.get(i), Status.ERROR, describe(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                switch (r.status()) {
                    case OK -> ok++;
                    case MISMATCH -> mismatch++;
                    case NO_MD5 -> noMd5++;
                    case ERROR -> error++;
                }
                if (r.status() != Status.OK) {
                    problems.add(r);
                }
                if (++done % PROGRESS_EVERY == 0) {
                    out.println("checked " + done + " of " + toCheck.size());
                }
            }
        } finally {
            pool.shutdown();
        }

        problems.sort(Comparator.comparing(r -> r.path().toString()));
        Summary summary = new Summary(ok, mismatch, noMd5, error, skipped, List.copyOf(problems));
        for (Result r : summary.problems()) {
            out.println(r.status() + (r.damage() == null ? "" : " [" + r.damage() + "]") + "  " + r.path()
                    + (r.detail().isEmpty() ? "" : ": " + r.detail()));
        }
        out.println(summary.line());
        out.println(skipped + " cloud-only skipped");
        return summary;
    }

    public static void main(String[] roots) {
        if (roots.length == 0) {
            System.err.println("usage: FlacVerify <file-or-folder>...");
            System.exit(2);
        }
        Summary s = scan(Arrays.stream(roots).map(Path::of).toList(), System.out);
        if (s.mismatch() + s.error() > 0) {
            System.exit(1);
        }
    }

    /**
     * Adds the FLAC files at or under {@code root} to {@code files}; a root that does not exist, or what cannot be
     * listed, goes to {@code problems}.
     */
    private static void collect(Path root, List<Path> files, List<Result> problems) {
        if (Files.isRegularFile(root)) {
            files.add(root);
            return;
        }
        if (!Files.isDirectory(root)) {
            problems.add(new Result(root, Status.ERROR, "not found"));
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".flac")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    problems.add(new Result(file, Status.ERROR, "cannot list or read: " + describe(e)));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            problems.add(new Result(root, Status.ERROR, "cannot list: " + describe(e)));
        }
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("every JDK has MD5", e);
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    /** The message of a decoder's own exceptions (written for the user); the class name plus message for others. */
    private static String describe(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String message = e.getMessage();
        if (e instanceof DecodeException || e instanceof UnsupportedFormatException) {
            return message;
        }
        return message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }
}
