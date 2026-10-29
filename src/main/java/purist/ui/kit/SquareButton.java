package purist.ui.kit;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.JButton;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * A square transport button (spec §9): a 1 px edge, a 3 px radius and a line icon. Pressed or selected, it inverts
 * to the skin's highlight colours. Use {@link #setSelected} for toggles such as shuffle and repeat.
 */
public class SquareButton extends JButton {

    private static final int ARC = 6;

    /**
     * @param icon   makes the icon from a size and a colour supplier, e.g. {@code Icons::play}
     * @param size   the button's width and height in px
     * @param tooltip the tooltip, which also names the button for screen readers
     */
    public SquareButton(BiFunction<Integer, Supplier<Color>, Icon> icon, int size, String tooltip) {
        setIcon(icon.apply(size * 4 / 7, this::iconColor));
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        // Never take the keyboard focus: SPACE and the arrows are window-level shortcuts (MainWindow), and a
        // focused button would swallow SPACE as a click.
        setFocusable(false);
        setOpaque(false);
        Dimension d = new Dimension(size, size);
        setPreferredSize(d);
        setMinimumSize(d);
        setMaximumSize(d);
    }

    private boolean inverted() {
        return getModel().isPressed() || isSelected();
    }

    private Color iconColor() {
        Skin s = PuristTheme.skin();
        return inverted() ? s.highlightText : s.buttonText;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Skin s = PuristTheme.skin();
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 1;
            int h = getHeight() - 1;
            g.setColor(inverted() ? s.highlight : getModel().isRollover() ? s.chassis : s.button);
            g.fillRoundRect(0, 0, w, h, ARC, ARC);
            g.setColor(s.edge);
            g.drawRoundRect(0, 0, w, h, ARC, ARC);
        } finally {
            g.dispose();
        }
        super.paintComponent(g0);
    }
}
