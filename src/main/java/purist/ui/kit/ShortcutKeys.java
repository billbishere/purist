package purist.ui.kit;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;

/**
 * The keys MainWindow binds for the whole window: pause, seek, previous and next. A table or list that has the
 * focus would otherwise keep Space and the arrows for itself, so each one gives them up here.
 */
public final class ShortcutKeys {

    public static final KeyStroke[] KEYS = {
        KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK),
        KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK),
    };

    private ShortcutKeys() {
    }

    /** Binds {@link #KEYS} to a name with no action, which hides the look and feel's own binding. */
    public static void release(JComponent c) {
        InputMap in = c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        for (KeyStroke k : KEYS) {
            in.put(k, "none");
        }
    }
}
