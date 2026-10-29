package purist.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;
import org.junit.jupiter.api.Test;

class FontsTest {

    @Test
    void registersBundledFonts() {
        Fonts.register();
        assertEquals("Share Tech Mono", Fonts.display(52f).getFamily());
        assertEquals("IBM Plex Mono", Fonts.mono(13f).getFamily());
        assertEquals("IBM Plex Mono", Fonts.monoFamily());
        assertEquals(13f, Fonts.mono(13f).getSize2D());
        List<String> families = List.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        assertTrue(families.contains("Share Tech Mono"), families.toString());
        assertTrue(families.contains("IBM Plex Mono"), families.toString());
    }

    @Test
    void keepsMonoForLatin1() {
        Font f = Fonts.mono(13f);
        assertSame(f, Fonts.forText("Motörhead — Ace of Spades", f));
    }

    @Test
    void fallsBackForCjk() {
        Font f = Fonts.monoMedium(17f);
        Font g = Fonts.forText("ジャズ", f);
        assertEquals(Font.MONOSPACED, g.getFamily());
        assertEquals(17f, g.getSize2D());
    }
}
