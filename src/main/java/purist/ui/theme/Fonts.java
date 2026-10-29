package purist.ui.theme;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The bundled fonts (SIL OFL 1.1, latin subsets, licences beside them in {@code /fonts}):
 * <ul>
 *   <li>Share Tech Mono for the LCD display;</li>
 *   <li>IBM Plex Mono Regular and Medium for everything else.</li>
 * </ul>
 * Tags in other scripts fall back to the logical {@code Monospaced} font through {@link #forText}.
 */
public final class Fonts {

    /** The logical font used when a bundled font lacks a glyph; Java maps it to fonts that cover CJK and more. */
    public static final String FALLBACK = Font.MONOSPACED;

    private static Font display;
    private static Font mono;
    private static Font monoMedium;

    private Fonts() {
    }

    /** Loads the bundled fonts and registers them with the graphics environment. Idempotent. */
    public static synchronized void register() {
        if (display != null) {
            return;
        }
        display = load("ShareTechMono-Regular.ttf");
        mono = load("IBMPlexMono-Regular.ttf");
        monoMedium = load("IBMPlexMono-Medium.ttf");
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        ge.registerFont(display);
        ge.registerFont(mono);
        ge.registerFont(monoMedium);
    }

    /** The LCD face (Share Tech Mono) at {@code size} px. */
    public static Font display(float size) {
        register();
        return display.deriveFont(size);
    }

    /** IBM Plex Mono Regular at {@code size} px. */
    public static Font mono(float size) {
        register();
        return mono.deriveFont(size);
    }

    /** IBM Plex Mono Medium at {@code size} px. */
    public static Font monoMedium(float size) {
        register();
        return monoMedium.deriveFont(size);
    }

    /** The family name of {@link #mono}, as FlatLaf's {@code defaultFont} needs it. */
    public static String monoFamily() {
        register();
        return mono.getFamily();
    }

    /**
     * {@code preferred} if it can draw every character of {@code text}; otherwise {@value #FALLBACK} at the same
     * size and style, so titles in Japanese or Cyrillic never show boxes.
     */
    public static Font forText(String text, Font preferred) {
        if (text == null || preferred.canDisplayUpTo(text) == -1) {
            return preferred;
        }
        return new Font(FALLBACK, preferred.getStyle(), 1).deriveFont(preferred.getSize2D());
    }

    private static Font load(String file) {
        try (InputStream in = Fonts.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing font resource /fonts/" + file);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read font " + file, e);
        } catch (FontFormatException e) {
            throw new IllegalStateException("Bad font " + file, e);
        }
    }
}
