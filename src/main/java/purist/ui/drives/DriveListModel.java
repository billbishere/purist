package purist.ui.drives;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import purist.library.ScanProgress;
import purist.library.ScanResult;

/**
 * What the drives view shows: every fixed NTFS drive with whether it is a watched root, and, for the drives being
 * scanned, how far the scan has got and how the last one ended. Drives are scanned one at a time (the library
 * service has one thread), so a ticked drive waits as {@link State#QUEUED} until its first file is reported.
 * EDT only.
 */
public final class DriveListModel {

    /** A drive, as the user sees it. */
    public record Drive(Path root, String label, long totalBytes) {
    }

    public enum State { IDLE, QUEUED, SCANNING, DONE }

    /** One line of the view. */
    public record Row(Drive drive, boolean ticked, State state, String status, String notice) {
    }

    private static final NumberFormat COUNT = NumberFormat.getIntegerInstance(Locale.US);

    private List<Drive> drives = List.of();
    private final Map<String, Boolean> ticked = new HashMap<>();
    private final Map<String, State> states = new HashMap<>();
    private final Map<String, String> statuses = new HashMap<>();
    private final Map<String, String> notices = new HashMap<>();

    /** Replaces the drives (they were listed again); what is known about each drive that is still there stays. */
    public void setDrives(List<Drive> drives) {
        this.drives = List.copyOf(drives);
    }

    /** Marks as ticked the drives that are among {@code roots}; a folder inside a drive does not tick it. */
    public void setWatched(Collection<Path> roots) {
        Map<String, Boolean> now = new HashMap<>();
        for (Path r : roots) {
            now.put(key(r), true);
        }
        ticked.clear();
        for (Drive d : drives) {
            if (now.containsKey(key(d.root()))) {
                ticked.put(key(d.root()), true);
            }
        }
    }

    /** The user ticked {@code root}: it is queued for a scan. */
    public void tick(Path root) {
        ticked.put(key(root), true);
        states.put(key(root), State.QUEUED);
        statuses.put(key(root), "QUEUED");
        notices.remove(key(root)); // a new scan: what the last one said is stale
    }

    /** The user cleared {@code root}: it is no longer in the library, so nothing is known about it any more. */
    public void untick(Path root) {
        ticked.remove(key(root));
        states.remove(key(root));
        statuses.remove(key(root));
        notices.remove(key(root));
    }

    /**
     * A scan reported a file. Only the scan of a whole drive (its root is the drive's root) is the drive's: a scan of
     * a folder on it, or of a directory a watcher reported, says nothing about the drive row.
     */
    public void progress(ScanProgress p) {
        Drive d = p.root() == null ? null : driveRootedAt(p.root());
        if (d != null) {
            String k = key(d.root());
            if (states.get(k) != State.SCANNING) {
                notices.remove(k); // a new scan began
            }
            states.put(k, State.SCANNING);
            statuses.put(k, progressText(p));
        }
    }

    /** A scan of {@code folder} ended; a drive's result line replaces its progress. */
    public void finished(Path folder, ScanResult r) {
        Drive d = driveRootedAt(folder);
        if (d == null) {
            return;
        }
        states.put(key(d.root()), State.DONE);
        statuses.put(key(d.root()), resultText(r));
        if (r.walkedBecauseUnindexed()) {
            notices.put(key(d.root()), purist.library.LibraryService.unindexedNotice(d.root()));
        } else {
            notices.remove(key(d.root()));
        }
    }

    /** {@code SCANNING 12,345 / 40,000}. */
    public static String progressText(ScanProgress p) {
        return "SCANNING " + COUNT.format(p.done()) + " / " + COUNT.format(p.total());
    }

    /** {@code 12 ADDED  1 UPDATED  3 REMOVED  40,000 UNCHANGED  2 ERRORS}: what a scan did. */
    public static String resultText(ScanResult r) {
        return COUNT.format(r.added()) + " ADDED  " + COUNT.format(r.updated()) + " UPDATED  "
                + COUNT.format(r.removed()) + " REMOVED  " + COUNT.format(r.unchanged()) + " UNCHANGED  "
                + COUNT.format(r.errors()) + " ERRORS";
    }

    public List<Row> rows() {
        List<Row> out = new ArrayList<>(drives.size());
        for (Drive d : drives) {
            String k = key(d.root());
            out.add(new Row(d, ticked.containsKey(k), states.getOrDefault(k, State.IDLE),
                    statuses.getOrDefault(k, ""), notices.getOrDefault(k, "")));
        }
        return out;
    }

    /** The drive whose root is exactly {@code root}, or null. */
    private Drive driveRootedAt(Path root) {
        for (Drive d : drives) {
            if (key(d.root()).equals(key(root))) {
                return d;
            }
        }
        return null;
    }

    private static String key(Path p) {
        return p.toString().toLowerCase(Locale.ROOT);
    }

    /** {@code E:\  DATA  1.8 TB}: the letter, the label if there is one, the size. */
    public static String title(Drive d) {
        String label = d.label() == null || d.label().isBlank() ? "" : d.label().toUpperCase(Locale.ROOT) + "  ";
        return d.root() + "  " + label + sizeText(d.totalBytes());
    }

    /** A drive size in the units Windows shows: {@code 1.8 TB}, {@code 953 GB}, {@code 512 MB}. */
    public static String sizeText(long bytes) {
        double tb = bytes / (1024.0 * 1024 * 1024 * 1024);
        if (tb >= 1) {
            return String.format(Locale.ROOT, "%.1f TB", tb);
        }
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) {
            return Math.round(gb) + " GB";
        }
        double mb = bytes / (1024.0 * 1024);
        if (mb >= 1) {
            return Math.round(mb) + " MB";
        }
        return bytes + " B";
    }
}
