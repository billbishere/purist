package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreeNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderTreeTest {

    @TempDir
    Path tmp;

    private void layout() throws IOException {
        Files.createDirectories(tmp.resolve("beta"));
        Files.createDirectories(tmp.resolve("Alpha").resolve("inner"));
        Files.createDirectories(tmp.resolve("$RECYCLE.BIN"));
        Files.createDirectories(tmp.resolve("System Volume Information"));
        Files.writeString(tmp.resolve("a file.txt"), "x");
    }

    @Test
    void subfoldersAreTheDirectoriesOnlySortedIgnoringCaseWithoutSystemOnes() throws IOException {
        layout();
        List<String> names = FolderTree.subfolders(tmp).stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("Alpha", "beta"), names);
    }

    @Test
    void aFolderThatCannotBeListedHasNoSubfolders() {
        assertEquals(List.of(), FolderTree.subfolders(tmp.resolve("missing")));
    }

    @Test
    void expandingARootListsItsFoldersOffTheUiThreadAndFillsThemIn() throws Exception {
        layout();
        List<String> ranOn = new ArrayList<>();
        FolderTree[] tree = new FolderTree[1];
        SwingUtilities.invokeAndWait(() -> {
            tree[0] = new FolderTree(r -> {
                Thread t = new Thread(() -> {
                    ranOn.add(SwingUtilities.isEventDispatchThread() ? "edt" : "io");
                    r.run();
                });
                t.start();
            });
            tree[0].setRoots(List.of(tmp));
            tree[0].tree().expandRow(0);
        });
        long deadline = System.currentTimeMillis() + 5000;
        List<String> kids = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            kids.clear();
            SwingUtilities.invokeAndWait(() -> {
                TreeNode root = (TreeNode) tree[0].tree().getModel().getRoot();
                TreeNode first = root.getChildAt(0);
                for (int i = 0; i < first.getChildCount(); i++) {
                    kids.add(first.getChildAt(i).toString());
                }
            });
            if (kids.equals(List.of("Alpha", "beta"))) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(List.of("Alpha", "beta"), kids);
        assertEquals(List.of("io"), ranOn);
        assertTrue(tree[0].selectedFolder() == null, "nothing is selected until the user picks a folder");
    }

    @Test
    void theSameRootsAgainLeaveTheOpenTreeAlone() throws Exception {
        layout();
        FolderTree[] tree = new FolderTree[1];
        SwingUtilities.invokeAndWait(() -> {
            tree[0] = new FolderTree(Runnable::run);
            tree[0].setRoots(List.of(tmp));
            tree[0].tree().expandRow(0);
            assertTrue(tree[0].tree().isExpanded(0));
            Object node = tree[0].tree().getModel().getChild(tree[0].tree().getModel().getRoot(), 0);

            tree[0].setRoots(List.of(tmp)); // a scan changed the library; the folders did not change

            assertTrue(tree[0].tree().isExpanded(0), "still open");
            assertTrue(node == tree[0].tree().getModel().getChild(tree[0].tree().getModel().getRoot(), 0));
            tree[0].setRoots(List.of(tmp, tmp.resolve("Alpha"))); // really different: rebuilt
            assertEquals(2, tree[0].tree().getModel().getChildCount(tree[0].tree().getModel().getRoot()));
        });
    }
}
