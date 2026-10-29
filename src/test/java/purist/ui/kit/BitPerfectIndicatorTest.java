package purist.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BitPerfectIndicatorTest {

    @Test
    void bitPerfect() {
        BitPerfectIndicator i = new BitPerfectIndicator();
        i.update(Optional.empty(), true);
        assertEquals("BIT-PERFECT", i.getText());
        assertTrue(i.bitPerfect());
        assertNotNull(i.getIcon());
        assertNull(i.getToolTipText());
    }

    @Test
    void notBitPerfectCarriesTheReason() {
        BitPerfectIndicator i = new BitPerfectIndicator();
        i.update(Optional.of("3 dropouts"), true);
        assertEquals("NOT BIT-PERFECT", i.getText());
        assertFalse(i.bitPerfect());
        assertNotNull(i.getIcon());
        assertEquals("3 dropouts", i.getToolTipText());
    }

    @Test
    void blankWhenInactive() {
        BitPerfectIndicator i = new BitPerfectIndicator();
        i.update(Optional.of("3 dropouts"), true);
        i.update(Optional.empty(), false);
        assertEquals("", i.getText());
        assertNull(i.getIcon());
        assertFalse(i.bitPerfect());
        assertNull(i.getToolTipText());
    }
}
