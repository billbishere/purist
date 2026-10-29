package purist.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Application logging (spec §10): a {@code java.util.logging} file handler on the {@code purist} logger, 1 MB per
 * file and 3 files ({@code purist.log.0} is the current one), with a one-line format, plus a default
 * uncaught-exception handler that logs.
 */
public final class AppLog {

    private static final Logger ROOT = Logger.getLogger("purist");
    private static final int LIMIT_BYTES = 1_000_000;
    private static final int FILES = 3;

    /** Logs an uncaught exception at SEVERE. The UI chains a toast after it (Task 16). */
    public static final Thread.UncaughtExceptionHandler LOGGING_HANDLER =
            (t, e) -> ROOT.log(Level.SEVERE, "Uncaught exception on thread " + t.getName(), e);

    private static FileHandler handler;

    private AppLog() {
    }

    /** Starts logging to {@code logFile} (rotated as {@code logFile.0..2}). Idempotent. */
    public static synchronized void init(Path logFile) throws IOException {
        if (handler != null) {
            return;
        }
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        // FileHandler patterns treat '%' as an escape; with more than one file it appends ".%g" itself.
        String pattern = logFile.toAbsolutePath().toString().replace("%", "%%");
        FileHandler h = new FileHandler(pattern, LIMIT_BYTES, FILES, true);
        h.setEncoding("UTF-8");
        h.setFormatter(new OneLine());
        ROOT.addHandler(h);
        ROOT.setLevel(Level.INFO);
        handler = h;
        Thread.setDefaultUncaughtExceptionHandler(LOGGING_HANDLER);
    }

    /** The logger for {@code c}; every {@code purist.*} class logs under the configured root. */
    public static Logger logger(Class<?> c) {
        return Logger.getLogger(c.getName());
    }

    /** {@code 2026-10-22 19:10:01.123 WARNING purist.player.Player: message}, then any stack trace. */
    static final class OneLine extends Formatter {
        private static final DateTimeFormatter TIME =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(128)
                    .append(TIME.format(Instant.ofEpochMilli(r.getMillis()))).append(' ')
                    .append(r.getLevel().getName()).append(' ')
                    .append(r.getLoggerName()).append(": ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }
    }
}
