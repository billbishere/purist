package purist.ui.kit;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Optional;
import javax.swing.Icon;
import javax.swing.JLabel;

/**
 * The bit-perfect light (spec §9). It shows a filled dot and {@code BIT-PERFECT} when the path is bit-perfect, or a
 * hollow dot and {@code NOT BIT-PERFECT} with the reason in the tooltip. It is blank when nothing is playing. The
 * dot is drawn, not typed, because the LCD font has no ● or ○. It has no colour of its own: the parent decides,
 * e.g. segment colour on the LCD.
 */
public class BitPerfectIndicator extends JLabel {

    public static final String ON = "BIT-PERFECT";
    public static final String OFF = "NOT BIT-PERFECT";

    private boolean bitPerfect;

    public BitPerfectIndicator() {
        PlainText.of(this);
        setIconTextGap(6);
    }

    /**
     * @param notBitPerfectReason empty when bit-perfect
     * @param active              false when nothing is playing (the indicator goes blank)
     */
    public void update(Optional<String> notBitPerfectReason, boolean active) {
        bitPerfect = active && notBitPerfectReason.isEmpty();
        if (!active) {
            setText("");
            setIcon(null);
            setToolTipText(null);
        } else if (bitPerfect) {
            setText(ON);
            setIcon(new Dot(true));
            setToolTipText(null);
        } else {
            setText(OFF);
            setIcon(new Dot(false));
            setToolTipText(notBitPerfectReason.get());
        }
    }

    /** True while the light shows bit-perfect. */
    public boolean bitPerfect() {
        return bitPerfect;
    }

    /** A filled or hollow dot in the label's foreground colour, sized to the font. */
    private final class Dot implements Icon {
        private final boolean filled;

        Dot(boolean filled) {
            this.filled = filled;
        }

        private int size() {
            return Math.max(6, Math.round(getFont().getSize2D() * 0.6f));
        }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getForeground());
                int d = size();
                if (filled) {
                    g.fillOval(x, y, d, d);
                } else {
                    g.drawOval(x, y, d - 1, d - 1);
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return size();
        }

        @Override
        public int getIconHeight() {
            return size();
        }
    }
}
