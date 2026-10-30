package purist.ui;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFileChooser;
import purist.app.AppLog;
import purist.decode.DecoderRegistry;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;

/** Turns files and folders the user picked into queue entries (Ctrl+O, the eject button, the empty-queue buttons). */
public final class OpenFilesAction {

    private static final Logger LOG = AppLog.logger(OpenFilesAction.class);

    private OpenFilesAction() {
    }

    /**
     * Expands a selection into tracks:
     * <ul>
     *   <li>folders are walked recursively; a folder that cannot be read (locked, no permission) is logged and
     *       skipped, and the walk goes on with the rest;</li>
     *   <li>only files with a handler are kept;</li>
     *   <li>the result is sorted case-insensitively by full path;</li>
     *   <li>an ISO becomes one entry per playable subsong from {@code probe}.</li>
     * </ul>
     * This reads the disk (a folder walk, and a probe of every ISO), so call it off the event thread.
     */
    public static List<TrackRef> expand(List<Path> selection, DecoderRegistry reg) {
        List<Path> files = new ArrayList<>();
        for (Path p : selection) {
            if (Files.isDirectory(p)) {
                try {
                    Files.walkFileTree(p, collector(files));
                } catch (IOException | UncheckedIOException e) {
                    LOG.log(Level.WARNING, "Cannot list " + p, e);
                }
            } else if (Files.isRegularFile(p)) {
                files.add(p);
            }
        }
        files.removeIf(f -> reg.handlerFor(f).isEmpty());
        files.sort(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER));
        // A whole-album file opened along with its CUE sheet plays as the sheet's tracks, not a second time whole.
        for (Path f : List.copyOf(files)) {
            if (reg.handlerFor(f).orElse(null) instanceof purist.decode.cue.CueHandler cue) {
                try {
                    files.removeAll(cue.audioFiles(f));
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Cannot read " + f, e);
                }
            }
        }
        List<TrackRef> refs = new ArrayList<>(files.size());
        for (Path f : files) {
            String lower = f.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".iso") || lower.endsWith(".cue")) {
                try {
                    for (TrackInfo t : reg.probe(f)) {
                        if (t.supported()) {
                            refs.add(new TrackRef(f, t.subsong()));
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Cannot read " + f, e);
                }
            } else {
                refs.add(new TrackRef(f, 0));
            }
        }
        return refs;
    }

    /**
     * Collects the regular files of a tree. A directory or file that fails (visitFileFailed, or a failure after
     * listing a directory) is logged and skipped instead of ending the walk.
     */
    static FileVisitor<Path> collector(List<Path> out) {
        return new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() || Files.isRegularFile(file)) { // the second follows a link to a file
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                LOG.log(Level.WARNING, "Skipping " + file, e);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                if (e != null) {
                    LOG.log(Level.WARNING, "Incomplete listing of " + dir, e);
                }
                return FileVisitResult.CONTINUE;
            }
        };
    }

    /** Shows a chooser for files and folders; returns the expanded selection, empty if cancelled. */
    public static List<TrackRef> choose(Component parent, boolean folders, DecoderRegistry reg, File startDir) {
        return expand(pick(parent, folders, startDir), reg);
    }

    /**
     * Shows a chooser for files and folders and returns what was picked, empty if cancelled. Only the chooser
     * runs here (on the event thread); hand the result to {@link #expand} on a background thread.
     */
    public static List<Path> pick(Component parent, boolean folders, File startDir) {
        JFileChooser fc = new JFileChooser(startDir);
        fc.setDialogTitle(folders ? "Open folder" : "Open files");
        fc.setMultiSelectionEnabled(true);
        fc.setFileSelectionMode(folders ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_AND_DIRECTORIES);
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return List.of();
        }
        File[] picked = fc.getSelectedFiles();
        if (picked.length == 0 && fc.getSelectedFile() != null) {
            picked = new File[] {fc.getSelectedFile()};
        }
        return Arrays.stream(picked).map(File::toPath).toList();
    }
}
