package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.decode.Decoder;
import purist.decode.DecoderRegistry;
import purist.decode.FormatHandler;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;
import purist.tags.TagMap;

class OpenFilesActionTest {

    private static Path touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        return Files.write(p, new byte[] {1});
    }

    /** A regular file's attributes; only {@code isRegularFile} matters to the walk. */
    private static final BasicFileAttributes REGULAR = new BasicFileAttributes() {
        @Override public FileTime lastModifiedTime() { return FileTime.fromMillis(0); }
        @Override public FileTime lastAccessTime() { return FileTime.fromMillis(0); }
        @Override public FileTime creationTime() { return FileTime.fromMillis(0); }
        @Override public boolean isRegularFile() { return true; }
        @Override public boolean isDirectory() { return false; }
        @Override public boolean isSymbolicLink() { return false; }
        @Override public boolean isOther() { return false; }
        @Override public long size() { return 1; }
        @Override public Object fileKey() { return null; }
    };

    @Test
    void aFolderThatFailsDoesNotEndTheWalkOrDropTheRest() throws IOException {
        Logger log = Logger.getLogger(OpenFilesAction.class.getName()); // the walk logs what it skips: keep it quiet
        Level before = log.getLevel();
        log.setLevel(Level.OFF);
        try {
            walkWithFailures();
        } finally {
            log.setLevel(before);
        }
    }

    private static void walkWithFailures() throws IOException {
        List<Path> out = new ArrayList<>();
        FileVisitor<Path> v = OpenFilesAction.collector(out);
        Path root = Path.of("album");
        assertEquals(FileVisitResult.CONTINUE, v.visitFile(root.resolve("a.flac"), REGULAR));
        // A locked or unreadable subfolder: the walk is told about it and must go on.
        assertEquals(FileVisitResult.CONTINUE,
                v.visitFileFailed(root.resolve("locked"), new AccessDeniedException("locked")));
        assertEquals(FileVisitResult.CONTINUE,
                v.postVisitDirectory(root.resolve("half"), new IOException("listing broke part-way")));
        assertEquals(FileVisitResult.CONTINUE, v.visitFile(root.resolve("z.flac"), REGULAR));
        assertEquals(List.of(root.resolve("a.flac"), root.resolve("z.flac")), out);
    }

    @Test
    void expandsFoldersSortedAndSkipsNonAudio(@TempDir Path tmp) throws IOException {
        Path b2 = touch(tmp.resolve("Album/CD2/b.flac"));
        Path a1 = touch(tmp.resolve("Album/CD1/A.wav"));
        Path c1 = touch(tmp.resolve("Album/CD1/c.AIFF"));
        touch(tmp.resolve("Album/CD1/cover.jpg"));
        touch(tmp.resolve("Album/CD1/notes.txt"));
        touch(tmp.resolve("Album/CD1/lossy.mp3"));
        Path single = touch(tmp.resolve("z-single.flac"));

        List<TrackRef> refs = OpenFilesAction.expand(List.of(single, tmp.resolve("Album")), DecoderRegistry.standard());

        assertEquals(List.of(new TrackRef(a1, 0), new TrackRef(c1, 0), new TrackRef(b2, 0), new TrackRef(single, 0)),
                refs);
    }

    @Test
    void isoExpandsToSubsongs(@TempDir Path tmp) throws IOException {
        Path iso = touch(tmp.resolve("disc.iso"));
        DecoderRegistry reg = new DecoderRegistry();
        reg.register(new FormatHandler() {
            @Override
            public List<String> extensions() {
                return List.of("iso");
            }

            @Override
            public List<TrackInfo> probe(Path p) {
                return List.of(
                        new TrackInfo(p, 1, "ISO", null, new TagMap(), true, null),
                        new TrackInfo(p, 2, "ISO", null, new TagMap(), false, "DST is not supported"),
                        new TrackInfo(p, 3, "ISO", null, new TagMap(), true, null));
            }

            @Override
            public Decoder open(Path p, int subsong) {
                throw new UnsupportedOperationException();
            }
        });

        assertEquals(List.of(new TrackRef(iso, 1), new TrackRef(iso, 3)), OpenFilesAction.expand(List.of(tmp), reg));
    }
}
