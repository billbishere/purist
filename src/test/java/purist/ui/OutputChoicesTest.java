package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import purist.app.Settings;
import purist.asio.AsioDriverInfo;
import purist.wasapi.WasapiDevice;

/** The one output list: ASIO drivers and WASAPI endpoints, the {@code output.device} setting and its migration. */
class OutputChoicesTest {

    private static final AsioDriverInfo ASIO4ALL = new AsioDriverInfo("ASIO4ALL v2", "{A}");
    private static final AsioDriverInfo TOPPING = new AsioDriverInfo("Topping USB Audio Device ASIO driver", "{T}");
    private static final WasapiDevice VOLT = new WasapiDevice("{0.0.0.00000000}.{volt}", "MONITOR L/R (Volt 176)");
    private static final WasapiDevice D50 = new WasapiDevice("{0.0.0.00000000}.{d50}", "Speakers (2- TOPPING USB DAC)");

    private static Settings settings(Path dir) throws Exception {
        return Settings.load(dir.resolve("settings.properties"));
    }

    @Test
    void listsAsioDriversThenWasapiDevicesWithTheirKinds() {
        List<OutputChoice> l = OutputChoices.list(List.of(ASIO4ALL, TOPPING), List.of(VOLT, D50));
        assertEquals(List.of("ASIO · ASIO4ALL v2", "ASIO · Topping USB Audio Device ASIO driver",
                "WASAPI · MONITOR L/R (Volt 176)", "WASAPI · Speakers (2- TOPPING USB DAC)"),
                l.stream().map(OutputChoice::label).toList());
        assertEquals("asio:ASIO4ALL v2", l.get(0).settingValue());
        assertEquals("wasapi:{0.0.0.00000000}.{volt}", l.get(2).settingValue());
    }

    @Test
    void migratesTheOldDriverKeyOnce(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        s.put("output.driver", TOPPING.name());
        OutputChoices.migrate(s);
        assertEquals("asio:" + TOPPING.name(), s.get("output.device", null));
        assertNull(s.get("output.driver", null), "the old key is gone");

        s.put("output.device", "wasapi:" + D50.id());
        s.put("output.driver", "stale");
        OutputChoices.migrate(s);
        assertEquals("wasapi:" + D50.id(), s.get("output.device", null), "a new-style choice is never overwritten");
        assertNull(s.get("output.driver", null));
    }

    @Test
    void theMigratedDriverIsSelected(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        s.put("output.driver", TOPPING.name());
        OutputChoices.migrate(s);
        OutputChoices.Resolved r = OutputChoices.resolve(s, OutputChoices.list(List.of(ASIO4ALL, TOPPING), List.of(VOLT)));
        assertEquals("ASIO · Topping USB Audio Device ASIO driver", r.selected().label());
    }

    @Test
    void remembersAndRestoresAWasapiDevice(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        List<OutputChoice> l = OutputChoices.list(List.of(ASIO4ALL), List.of(VOLT, D50));
        OutputChoices.remember(s, l.get(2));
        s.save();

        Settings t = settings(dir);
        OutputChoices.migrate(t);
        OutputChoices.Resolved r = OutputChoices.resolve(t, l);
        assertEquals(l.get(2), r.selected());
        assertEquals(l, r.choices());
    }

    @Test
    void defaultsToTheFirstAsioDriverWithNoPreferenceForAnyBrand(@TempDir Path dir) throws Exception {
        OutputChoices.Resolved r = OutputChoices.resolve(settings(dir),
                OutputChoices.list(List.of(ASIO4ALL, TOPPING), List.of(D50)));
        assertEquals("ASIO · ASIO4ALL v2", r.selected().label());
    }

    @Test
    void defaultsToTheFirstWasapiDeviceWithoutAsio(@TempDir Path dir) throws Exception {
        OutputChoices.Resolved r = OutputChoices.resolve(settings(dir), OutputChoices.list(List.of(), List.of(VOLT, D50)));
        assertEquals("WASAPI · MONITOR L/R (Volt 176)", r.selected().label());
    }

    @Test
    void nothingToChooseGivesNoSelection(@TempDir Path dir) throws Exception {
        OutputChoices.Resolved r = OutputChoices.resolve(settings(dir), List.of());
        assertNull(r.selected());
        assertTrue(r.choices().isEmpty());
    }

    @Test
    void aRememberedWasapiDeviceThatIsOffStaysSelectedAsNotConnected(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        OutputChoices.remember(s, OutputChoices.list(List.of(), List.of(D50)).getFirst());
        OutputChoices.Resolved r = OutputChoices.resolve(s, OutputChoices.list(List.of(ASIO4ALL), List.of(VOLT)));
        assertEquals("WASAPI · Speakers (2- TOPPING USB DAC) (not connected)", r.selected().label());
        assertFalse(r.selected().present());
        assertEquals(D50.id(), r.selected().key());
        assertEquals(3, r.choices().size(), "listed so the user sees what is selected");
    }

    @Test
    void aRememberedWasapiEndpointWhoseIdChangedIsFoundByItsFriendlyName(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        OutputChoices.remember(s, OutputChoices.list(List.of(), List.of(D50)).getFirst());
        // Windows hands a re-plugged or re-installed DAC a new endpoint id, but its friendly name is the same.
        WasapiDevice d50Again = new WasapiDevice("{0.0.0.00000000}.{d50-new-guid}", D50.name());
        OutputChoices.Resolved r = OutputChoices.resolve(s,
                OutputChoices.list(List.of(ASIO4ALL), List.of(VOLT, d50Again)));
        assertEquals(d50Again.id(), r.selected().key());
        assertEquals("WASAPI · Speakers (2- TOPPING USB DAC)", r.selected().label());
        assertTrue(r.selected().present());
        assertEquals(3, r.choices().size(), "no extra not-connected entry is added");
    }

    @Test
    void anIdMatchBeatsANameMatch(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        OutputChoices.remember(s, OutputChoices.list(List.of(), List.of(D50)).getFirst());
        WasapiDevice sameNameOtherId = new WasapiDevice("{0.0.0.00000000}.{other}", D50.name());
        OutputChoices.Resolved r = OutputChoices.resolve(s,
                OutputChoices.list(List.of(), List.of(sameNameOtherId, D50)));
        assertEquals(D50.id(), r.selected().key());
    }

    @Test
    void aRememberedWasapiDeviceWithNoIdOrNameMatchStaysSelectedAsNotConnected(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        OutputChoices.remember(s, OutputChoices.list(List.of(), List.of(D50)).getFirst());
        OutputChoices.Resolved r = OutputChoices.resolve(s, OutputChoices.list(List.of(), List.of(VOLT)));
        assertFalse(r.selected().present());
        assertEquals(D50.id(), r.selected().key());
    }

    @Test
    void dopIsOffUntilTheUserTurnsItOnForThatEndpoint(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        assertFalse(OutputChoices.dopEnabled(s, D50.id()), "off by default: DoP sent to a non-DoP DAC is full-scale noise");
        OutputChoices.setDopEnabled(s, D50.id(), true);
        assertEquals("true", s.get("wasapi.dop." + D50.id(), null));
        assertTrue(OutputChoices.dopEnabled(s, D50.id()));
        assertFalse(OutputChoices.dopEnabled(s, VOLT.id()), "per device");
        s.save();
        assertTrue(OutputChoices.dopEnabled(settings(dir), D50.id()), "it survives a restart");

        OutputChoices.setDopEnabled(s, D50.id(), false);
        assertNull(s.get("wasapi.dop." + D50.id(), null), "off removes the key");
        assertFalse(OutputChoices.dopEnabled(s, D50.id()));
    }

    @Test
    void aRememberedAsioDriverThatIsGoneFallsBackToTheDefault(@TempDir Path dir) throws Exception {
        Settings s = settings(dir);
        s.put("output.device", "asio:Uninstalled driver");
        OutputChoices.Resolved r = OutputChoices.resolve(s, OutputChoices.list(List.of(TOPPING), List.of()));
        assertEquals(TOPPING.name(), r.selected().key());
    }

    @Test
    void parsesOnlyKnownPrefixes() {
        assertTrue(OutputChoice.parse("asio:X", null).isPresent());
        assertTrue(OutputChoice.parse("wasapi:{id}", "Name").isPresent());
        assertFalse(OutputChoice.parse("wdm:X", null).isPresent());
        assertFalse(OutputChoice.parse("asio:", null).isPresent());
        assertFalse(OutputChoice.parse(null, null).isPresent());
    }
}
