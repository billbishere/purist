package purist.ui.theme;

import java.awt.Color;

/**
 * A colour set for the deck (spec §9). Each skin has two parts:
 * <ul>
 *   <li>the chassis around the controls and the LCD display inside it, with its segment and dim colours;</li>
 *   <li>the playlist, with its text, dim and highlight colours.</li>
 * </ul>
 * Album art is the only other colour on screen. The skin is chosen in Settings and persisted as
 * {@value #SETTING}.
 */
public enum Skin {

    /** The default: a light reflective LCD, like an old calculator. */
    PAPER("Paper LCD", false,
            0xF2F1EC, 0xD6D4CC, 0xD7DCC8, 0x1E2318, 0x7B8270,
            0xFFFFFF, 0x1A1A1A, 0x8A8A84, 0x1E2318, 0xD7DCC8, 0xFFFFFF, 0x1A1A1A),
    /** Green phosphor on black. */
    PHOSPHOR("Phosphor", true,
            0x1D1F22, 0x34373B, 0x0A0E09, 0x8BE36B, 0x3E6332,
            0x0F1210, 0x8BE36B, 0x4C7A3E, 0x8BE36B, 0x0A0E09, 0x2A2D31, 0xC9CCD1),
    /** Pure black and white. */
    INK("Ink", false,
            0xFFFFFF, 0x111111, 0x111111, 0xFFFFFF, 0x8C8C8C,
            0xFFFFFF, 0x111111, 0x9A9A9A, 0x111111, 0xFFFFFF, 0xFFFFFF, 0x111111);

    /** The settings key holding the skin's {@link #name()}. */
    public static final String SETTING = "ui.skin";

    public final String label;
    /** Whether the chassis is dark, so the dark FlatLaf base is used for popups and scrollbars. */
    public final boolean dark;
    public final Color chassis;
    public final Color edge;
    public final Color lcd;
    public final Color segment;
    public final Color dim;
    public final Color list;
    public final Color listText;
    public final Color listDim;
    public final Color highlight;
    public final Color highlightText;
    public final Color button;
    public final Color buttonText;

    Skin(String label, boolean dark, int chassis, int edge, int lcd, int segment, int dim, int list, int listText,
            int listDim, int highlight, int highlightText, int button, int buttonText) {
        this.label = label;
        this.dark = dark;
        this.chassis = new Color(chassis);
        this.edge = new Color(edge);
        this.lcd = new Color(lcd);
        this.segment = new Color(segment);
        this.dim = new Color(dim);
        this.list = new Color(list);
        this.listText = new Color(listText);
        this.listDim = new Color(listDim);
        this.highlight = new Color(highlight);
        this.highlightText = new Color(highlightText);
        this.button = new Color(button);
        this.buttonText = new Color(buttonText);
    }

    /** The skin named by a settings value; {@link #PAPER} when it is missing or unknown. */
    public static Skin fromSetting(String value) {
        if (value != null) {
            for (Skin s : values()) {
                if (s.name().equalsIgnoreCase(value.trim())) {
                    return s;
                }
            }
        }
        return PAPER;
    }

    @Override
    public String toString() {
        return label;
    }
}
