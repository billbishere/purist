package purist.ui.kit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ButtonsTest {

    @Test
    void buttonsNeverTakeTheFocus() {
        // A focused button would turn SPACE into a click and hide the window-level shortcuts.
        assertFalse(new TextButton("Open files…", true).isFocusable());
        assertFalse(new SquareButton(Icons::play, 30, "Play").isFocusable());
    }

    @Test
    void sectionLabelsAndTheIndicatorDrawTextLiterally() {
        assertTrue(PlainText.is(new SectionLabel("Output")));
        assertTrue(PlainText.is(new BitPerfectIndicator()));
    }
}
