package purist.ui.drives;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import purist.app.AppLog;
import purist.ui.drives.DriveListModel.Drive;

/** Lists the drives the library can be built from. Asks Windows about every volume: call it off the UI thread. */
public final class Drives {

    private static final Logger LOG = AppLog.logger(Drives.class);

    private Drives() {
    }

    /**
     * Every fixed NTFS drive: its root, its volume label and its size, in drive-letter order. Removable media, CD
     * drives, drives with no medium in them and drives of any other file system are left out.
     */
    public static List<Drive> fixedNtfs() {
        List<Drive> out = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            try {
                FileStore store = Files.getFileStore(root);
                if (!"NTFS".equalsIgnoreCase(store.type()) || flag(store, "volume:isRemovable")
                        || flag(store, "volume:isCdrom")) {
                    continue;
                }
                out.add(new Drive(root, store.name(), store.getTotalSpace()));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Skipping " + root, e); // an empty card reader, a disconnected drive
            }
        }
        return out;
    }

    private static boolean flag(FileStore store, String attribute) {
        try {
            return Boolean.TRUE.equals(store.getAttribute(attribute));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
