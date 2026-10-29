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
            0xFFFFFF, 0x111111, 0x9A9A9A, 0x111111, 0xFFFFFF, 0xFFFFFF, 0x111111),
    /** Graphite and steel blue, after the foobar2000 skin DarkOne. */
    MIDNIGHT("Midnight", true,
            0x1E2127, 0x323843, 0x0D1A26, 0x6CB8F0, 0x2F4F68,
            0x16191E, 0xD5DAE1, 0x6B7480, 0x2F6FA8, 0xFFFFFF, 0x2A2F37, 0xD5DAE1),
    /** Near black with a red LED readout, after the foobar2000 skin Crimson. */
    CRIMSON("Crimson", true,
            0x141012, 0x3A1F24, 0x1A0607, 0xFF4A3D, 0x5A1C19,
            0x0F0C0D, 0xE8DCDC, 0x7D6A6B, 0xB3202A, 0xFFFFFF, 0x261A1C, 0xE8DCDC),
    /** Deep navy with a cyan neon readout, after the foobar2000 skin Ocean Neon. */
    OCEAN_NEON("Ocean Neon", true,
            0x0B1626, 0x173452, 0x03101C, 0x2FF3E0, 0x115A5A,
            0x08111E, 0xBFE9F2, 0x4E7A8C, 0x0E7C86, 0xEFFFFF, 0x112640, 0xBFE9F2),
    /** Lavender with a plum display, after the foobar2000 skin Pink Amethyst. */
    AMETHYST("Amethyst", false,
            0xF3E8F1, 0xD9C2D6, 0x2B1633, 0xF59BD0, 0x6A3F6E,
            0xFBF6FA, 0x2B1633, 0x9A7F98, 0x8E3C8A, 0xFFFFFF, 0xFFFFFF, 0x2B1633),
    /** Ivory with an ebony display, after the foobar2000 skin Ebony & Ivory. */
    IVORY("Ebony & Ivory", false,
            0xF4EEDF, 0xD8CDB4, 0x1C1814, 0xF2E6C8, 0x5E5446,
            0xFBF7EE, 0x1C1814, 0x8F8573, 0x1C1814, 0xF4EEDF, 0xFFFDF7, 0x1C1814);

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
