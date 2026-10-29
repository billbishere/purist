package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.JCheckBox;
import org.junit.jupiter.api.Test;
import purist.asio.AsioDriverInfo;
import purist.wasapi.WasapiDevice;

/** The "This DAC supports DoP" opt-in on the Settings → Output section. */
class SettingsViewTest {

    private static final AsioDriverInfo ASIO = new AsioDriverInfo("Topping USB Audio Device ASIO driver", "{T}");
    private static final WasapiDevice VOLT = new WasapiDevice("{0.0.0.00000000}.{volt}", "MONITOR L/R (Volt 176)");
    private static final WasapiDevice D50 = new WasapiDevice("{0.0.0.00000000}.{d50}", "Speakers (2- TOPPING USB DAC)");

    private final List<OutputChoice> choices = OutputChoices.list(List.of(ASIO), List.of(VOLT, D50));
    private final Set<String> dopOn = new HashSet<>();
    private final List<String> calls = new ArrayList<>();

    private SettingsView view(OutputChoice selected) {
        return new SettingsView(choices, selected, c -> { }, () -> { }, s -> { }, c -> dopOn.contains(c.key()),
                (c, on) -> calls.add(c.key() + "=" + on));
    }

    @Test
    void theBoxIsOnlyShownForAWasapiDevice() {
        assertFalse(view(choices.get(0)).dopBox().isVisible(), "ASIO plays DSD natively: no DoP question");
        JCheckBox box = view(choices.get(1)).dopBox();
        assertTrue(box.isVisible());
        assertEquals("This DAC supports DoP (DSD over PCM)", box.getText());
    }

    @Test
    void itIsOffByDefaultAndShowsTheSavedAnswerPerDevice() {
        dopOn.add(D50.id());
        SettingsView v = view(choices.get(1));
        assertFalse(v.dopBox().isSelected(), "the Volt has no saved answer");
        v.selectOutput(choices.get(2));
        assertTrue(v.dopBox().isSelected(), "the D50 was marked as DoP-capable");
        assertTrue(v.dopBox().isVisible());
        v.selectOutput(choices.get(0));
        assertFalse(v.dopBox().isVisible());
    }

    @Test
    void tickingItReportsTheDeviceAndTheNewAnswer() {
        SettingsView v = view(choices.get(2));
        v.dopBox().doClick();
        v.dopBox().doClick();
        assertEquals(List.of(D50.id() + "=true", D50.id() + "=false"), calls);
    }

    @Test
    void showingADevicesAnswerDoesNotReportIt() {
        dopOn.add(D50.id());
        SettingsView v = view(choices.get(1));
        v.selectOutput(choices.get(2));
        assertEquals(List.of(), calls);
    }

    @Test
    void aWasapiDeviceThatIsNotConnectedStillShowsTheBox() {
        OutputChoice absent = new OutputChoice(OutputChoice.Type.WASAPI, "{gone}", "Old DAC", false);
        SettingsView v = new SettingsView(List.of(absent), absent, c -> { }, () -> { }, s -> { }, c -> true,
                (c, on) -> calls.add(c.key()));
        assertTrue(v.dopBox().isVisible());
        assertTrue(v.dopBox().isSelected());
    }

    private SettingsView viewReporting(List<String> picked, OutputChoice selected) {
        return new SettingsView(choices, selected, c -> picked.add(c.key()), () -> { }, s -> { },
                c -> dopOn.contains(c.key()), (c, on) -> { });
    }

    @Test
    void theOutputListTakesNoKeyboardFocus() {
        assertFalse(view(choices.get(0)).outputsBox().isFocusable(), "arrow keys must not change the output");
    }

    /**
     * Opens the combo's popup the way Swing does: its listeners fire. Headless has no window to show a popup in, so
     * the UI is a Basic one (same popup, same list handlers as the real look and feel) that, like the real one,
     * closes the popup from inside {@code setPopupVisible(false)}, which the list's own mouse handler calls.
     */
    private static void openPopup(javax.swing.JComboBox<?> box) {
        if (!(box.getUI() instanceof HidingUi)) {
            box.setUI(new HidingUi());
        }
        var ev = new javax.swing.event.PopupMenuEvent(box);
        for (var l : box.getPopupMenuListeners()) {
            l.popupMenuWillBecomeVisible(ev);
        }
    }

    private static final class HidingUi extends javax.swing.plaf.basic.BasicComboBoxUI {
        @Override
        public void setPopupVisible(javax.swing.JComboBox c, boolean visible) {
            if (!visible) {
                closePopup(c);
            }
        }
    }

    private static void closePopup(javax.swing.JComboBox<?> box) {
        var ev = new javax.swing.event.PopupMenuEvent(box);
        for (var l : box.getPopupMenuListeners()) {
            l.popupMenuWillBecomeInvisible(ev);
        }
    }

    /** The user's click on row {@code index} of the open list: the list's OWN mouse listeners get the release. */
    private static void clickListRow(javax.swing.JComboBox<?> box, int index) {
        javax.swing.JList<?> list = ((javax.swing.plaf.basic.ComboPopup) box.getUI().getAccessibleChild(box, 0))
                .getList();
        list.setSelectedIndex(index); // the hover that precedes the click
        // The press is skipped: BasicListUI's own press handler asks the (absent) headless toolkit for modifiers.
        var e = new java.awt.event.MouseEvent(list, java.awt.event.MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1);
        for (var l : list.getMouseListeners()) {
            l.mouseReleased(e);
        }
    }

    @Test
    void aProgrammaticOrKeyboardStyleChangeOfTheSelectionDoesNotSwitchTheOutput() {
        List<String> picked = new ArrayList<>();
        SettingsView v = viewReporting(picked, choices.get(0));
        v.outputsBox().setSelectedItem(choices.get(1));
        v.outputsBox().setSelectedIndex(2);
        assertEquals(List.of(), picked, "no popup open: not the user's pick");
    }

    @Test
    void anItemClickedInTheOpenListSwitchesTheOutputOnce() {
        List<String> picked = new ArrayList<>();
        SettingsView v = viewReporting(picked, choices.get(0));
        openPopup(v.outputsBox());
        clickListRow(v.outputsBox(), 2); // the release closes the popup, as it does for real
        assertEquals(List.of(choices.get(2).key()), picked);
        // clicking the same row again in a new popup changes nothing
        openPopup(v.outputsBox());
        clickListRow(v.outputsBox(), 2); // the release closes the popup, as it does for real
        assertEquals(List.of(choices.get(2).key()), picked);
        assertEquals(choices.get(2), v.outputsBox().getSelectedItem());
    }

    @Test
    void clickingTheOutputAlreadyInUseDoesNothing() {
        List<String> picked = new ArrayList<>();
        SettingsView v = viewReporting(picked, choices.get(1));
        openPopup(v.outputsBox());
        clickListRow(v.outputsBox(), 1);
        assertEquals(List.of(), picked);
    }

    @Test
    void afterTheListClosesAProgrammaticChangeIsNotAPickAgain() {
        List<String> picked = new ArrayList<>();
        SettingsView v = viewReporting(picked, choices.get(0));
        openPopup(v.outputsBox());
        clickListRow(v.outputsBox(), 1);
        v.outputsBox().setSelectedItem(choices.get(2));
        assertEquals(List.of(choices.get(1).key()), picked);
    }

    @Test
    void theDopBoxIsNotFocusable() {
        assertFalse(view(choices.get(1)).dopBox().isFocusable(), "Space must not toggle DoP");
    }
}
