package purist.asio;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import purist.win.Registry;
import purist.win.Registry.Hive;

/** Finds the ASIO drivers registered under {@code HKLM\SOFTWARE\ASIO}. */
public final class AsioDriverList {

    private static final String ASIO_KEY = "SOFTWARE\\ASIO";

    private AsioDriverList() {
    }

    /**
     * Every registered driver that has a {@code CLSID} value, sorted by name (ignoring case). Keys
     * without a CLSID cannot be created and are skipped.
     */
    public static List<AsioDriverInfo> installed() {
        return Registry.subKeys(Hive.HKLM, ASIO_KEY).stream()
                .map(AsioDriverList::describe)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(AsioDriverInfo::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AsioDriverInfo::name))
                .toList();
    }

    private static Optional<AsioDriverInfo> describe(String name) {
        return Registry.stringValue(Hive.HKLM, ASIO_KEY + "\\" + name, "CLSID")
                .map(String::strip)
                .filter(clsid -> !clsid.isEmpty())
                .map(clsid -> new AsioDriverInfo(name, clsid));
    }
}
