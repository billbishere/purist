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
    void unknownSettingFallsBackToPaper() {
        assertSame(Skin.PAPER, Skin.fromSetting(null));
        assertSame(Skin.PAPER, Skin.fromSetting("winamp-classic"));
        assertSame(Skin.PHOSPHOR, Skin.fromSetting(" phosphor "));
        assertSame(Skin.INK, Skin.fromSetting("INK"));
    }
}
