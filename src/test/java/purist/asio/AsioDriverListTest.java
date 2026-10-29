package purist.asio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Reads the real {@code HKLM\SOFTWARE\ASIO} on this machine, where the UA Volt's driver is registered. */
@Tag("hardware")
class AsioDriverListTest {

    @Test
    void listsTheUniversalAudioVolt() {
        List<AsioDriverInfo> drivers = AsioDriverList.installed();
        assertTrue(drivers.contains(
                new AsioDriverInfo("Universal Audio Volt", "{7FA0A3EC-EBB7-4249-9CDC-F5474EE19B74}")),
                drivers.toString());
    }

    @Test
    void isSortedByNameAndEveryEntryHasAClsid() {
        List<AsioDriverInfo> drivers = AsioDriverList.installed();
        List<AsioDriverInfo> sorted = drivers.stream()
                .sorted(Comparator.comparing(AsioDriverInfo::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        assertEquals(sorted, drivers);
        for (AsioDriverInfo d : drivers) {
            assertTrue(d.clsid().startsWith("{"), d.toString());
        }
    }
}
