package purist.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatLaf;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PuristThemeTest {

    @AfterEach
    void backToPaper() {
        PuristTheme.setup(Skin.PAPER);
    }

    @Test
    void paperIsTheLightDefault() {
        assertTrue(PuristTheme.setup(Skin.PAPER));
        assertSame(Skin.PAPER, PuristTheme.skin());
        assertFalse(FlatLaf.isLafDark());
        assertEquals(Skin.PAPER.chassis, UIManager.getColor("Panel.background"));
        assertEquals(Skin.PAPER.list, UIManager.getColor("Table.background"));
        assertEquals(Skin.PAPER.highlight, UIManager.getColor("Table.selectionBackground"));
        assertEquals("IBM Plex Mono", UIManager.getFont("defaultFont").getFamily());
        assertEquals(13, UIManager.getFont("defaultFont").getSize());
    }

    @Test
    void phosphorUsesTheDarkBase() {
        assertTrue(PuristTheme.setup(Skin.PHOSPHOR));
        assertTrue(FlatLaf.isLafDark());
        assertEquals(Skin.PHOSPHOR.chassis, UIManager.getColor("Panel.background"));
        assertEquals(Skin.PHOSPHOR.listText, UIManager.getColor("Table.foreground"));
        assertEquals("IBM Plex Mono", UIManager.getFont("defaultFont").getFamily());
    }

    @Test
    void inkIsBlackAndWhite() {
        assertTrue(PuristTheme.setup(Skin.INK));
        assertEquals(Skin.INK.chassis, UIManager.getColor("Panel.background"));
        assertEquals(Skin.INK.highlight, UIManager.getColor("Table.selectionBackground"));
    }

    @Test
    void everySkinSetsUpWithItsOwnBase() {
        for (Skin s : Skin.values()) {
            assertTrue(PuristTheme.setup(s), s.label);
            assertEquals(s.dark, FlatLaf.isLafDark(), s.label);
            assertEquals(s.chassis, UIManager.getColor("Panel.background"), s.label);
            assertSame(s, Skin.fromSetting(s.name().toLowerCase()));
        }
    }

    /** The dark flag picks the popup and scrollbar base, so it has to match how the chassis actually looks. */
    @Test
    void theDarkFlagMatchesTheChassis() {
        for (Skin s : Skin.values()) {
            assertEquals(s.dark, luminance(s.chassis) < 0.5, s.label);
        }
    }

    /** Text, segments and highlights stay readable: at least 4.5:1 (WCAG AA) against what they sit on. */
    @Test
    void everySkinKeepsItsTextReadable() {
        for (Skin s : Skin.values()) {
            assertReadable(s.label + " list", s.listText, s.list);
            assertReadable(s.label + " LCD", s.segment, s.lcd);
            assertReadable(s.label + " highlight", s.highlightText, s.highlight);
            assertReadable(s.label + " button", s.buttonText, s.button);
        }
    }

    private static void assertReadable(String what, java.awt.Color fg, java.awt.Color bg) {
        double a = luminance(fg) + 0.05;
        double b = luminance(bg) + 0.05;
        double ratio = Math.max(a, b) / Math.min(a, b);
        assertTrue(ratio >= 4.5, what + " contrast " + String.format("%.2f", ratio));
    }

    private static double luminance(java.awt.Color c) {
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(int channel) {
        double v = channel / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    @Test
    void unknownSettingFallsBackToPaper() {
        assertSame(Skin.PAPER, Skin.fromSetting(null));
        assertSame(Skin.PAPER, Skin.fromSetting("winamp-classic"));
        assertSame(Skin.PHOSPHOR, Skin.fromSetting(" phosphor "));
        assertSame(Skin.INK, Skin.fromSetting("INK"));
    }
}
