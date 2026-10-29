package purist.ui.kit;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.util.Locale;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.border.EmptyBorder;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;

/**
 * A flat text button for actions such as {@code OPEN FILES…} and {@code OPEN DRIVER PANEL}: UPPERCASE IBM Plex Mono
 * Medium 11 px with wide tracking, a 1 px edge and a 3 px radius. A primary button, or a selected one (a view switch,
 * the current skin), is filled with the highlight colours; use at most one primary per view.
 */
public class TextButton extends JButton {

    private static final int ARC = 6;
    private final boolean primary;

    public TextButton(String text, boolean primary) {
        super(text.toUpperCase(Locale.ROOT));
        this.primary = primary;
        setContentAreaFilled(false);
        setFocusPainted(false);
        setFocusable(false); // see SquareButton: window-level shortcuts must always fire
        setOpaque(false);
        restyle();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        restyle();
    }

    private void restyle() {
        // A look-and-feel switch resets font and border, so both are reapplied after every updateUI.
        setFont(Fonts.monoMedium(11f).deriveFont(Map.of(TextAttribute.TRACKING, 0.12f)));
        setBorderPainted(false);
        setBorder(new EmptyBorder(new Insets(7, 14, 7, 14)));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Skin s = PuristTheme.skin();
        boolean filled = (primary || isSelected()) != getModel().isPressed();
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 1;
            int h = getHeight() - 1;
            g.setColor(filled ? s.highlight : getModel().isRollover() ? s.chassis : s.button);
            g.fillRoundRect(0, 0, w, h, ARC, ARC);
            g.setColor(filled ? s.highlight : s.edge);
            g.drawRoundRect(0, 0, w, h, ARC, ARC);
        } finally {
            g.dispose();
        }
        setForeground(filled ? s.highlightText : s.buttonText);
        super.paintComponent(g0);
    }
}
