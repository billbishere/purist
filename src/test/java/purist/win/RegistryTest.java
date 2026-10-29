package purist.win;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import purist.win.Registry.Hive;

class RegistryTest {

    private static final String CURRENT_VERSION = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion";

    @Test
    void readsWindowsProductName() {
        Optional<String> name = Registry.stringValue(Hive.HKLM, CURRENT_VERSION, "ProductName");
        assertTrue(name.isPresent(), "ProductName should exist");
        assertTrue(name.get().startsWith("Windows"), name.get());
        assertFalse(name.get().contains("\0"), "trailing NUL must be stripped");
    }

    @Test
    void missingKeyGivesEmpty() {
        assertTrue(Registry.subKeys(Hive.HKLM, "SOFTWARE\\NoSuchKey123").isEmpty());
        assertTrue(Registry.stringValue(Hive.HKLM, "SOFTWARE\\NoSuchKey123", "ProductName").isEmpty());
    }

    @Test
    void missingValueGivesEmpty() {
        assertTrue(Registry.stringValue(Hive.HKLM, CURRENT_VERSION, "NoSuchValue123").isEmpty());
    }

    @Test
    void nonStringValueGivesEmpty() {
        // CurrentMajorVersionNumber is a REG_DWORD.
        assertTrue(Registry.stringValue(Hive.HKLM, CURRENT_VERSION, "CurrentMajorVersionNumber").isEmpty());
    }

    @Test
    void listsSubKeys() {
        List<String> keys = Registry.subKeys(Hive.HKLM, "SOFTWARE\\Microsoft\\Windows NT");
        assertTrue(keys.contains("CurrentVersion"), keys.toString());
    }

    @Test
    void enumeratesLargeKeysCompletely() {
        // HKLM\SOFTWARE\Microsoft has well over a hundred sub keys; make sure enumeration does not stop early.
        List<String> keys = Registry.subKeys(Hive.HKLM, "SOFTWARE\\Microsoft");
        assertTrue(keys.size() > 100, "got " + keys.size());
        assertEquals(keys.size(), keys.stream().distinct().count());
    }

    @Test
    void readsFromCurrentUserHive() {
        List<String> keys = Registry.subKeys(Hive.HKCU, "SOFTWARE\\Microsoft\\Windows");
        assertTrue(keys.contains("CurrentVersion"), keys.toString());
    }
}
