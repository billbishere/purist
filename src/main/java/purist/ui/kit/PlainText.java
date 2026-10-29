package purist.ui.kit;

import javax.swing.JComponent;

/**
 * Swing renders any label text that starts with {@code <html>} as HTML, and an {@code <img src=http://…>} in it
 * makes the UI fetch that URL. Tag and file text comes from files the user did not write, so every component that
 * can show it is marked {@code html.disable}, which makes it draw the text literally.
 */
public final class PlainText {

    /** The client property Swing's {@code BasicHTML} checks before it treats text as HTML. */
    public static final String KEY = "html.disable";

    private PlainText() {
    }

    /** Marks {@code c} so that it draws its text literally; returns {@code c}. */
    public static <T extends JComponent> T of(T c) {
        c.putClientProperty(KEY, Boolean.TRUE);
        return c;
    }

    /** True if {@code c} draws its text literally. */
    public static boolean is(JComponent c) {
        return Boolean.TRUE.equals(c.getClientProperty(KEY));
    }
}
