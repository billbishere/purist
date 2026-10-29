package purist.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Installs FlatLaf coloured by the current {@link Skin}, with IBM Plex Mono as the default font, square-ish corners
 * and no focus glow (spec §9). The light FlatLaf base is used for light skins and the dark base for dark ones, so
 * popups and scrollbars match. Kit components read {@link #skin()} when they paint, so {@link #apply} re-skins a
 * running window. Call on the EDT once the UI exists.
 */
public final class PuristTheme {

    private static final List<Consumer<Skin>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Skin current = Skin.PAPER;

    private PuristTheme() {
    }

    /** The skin last installed. */
    public static Skin skin() {
        return current;
    }

    /** Registers the fonts and installs the look and feel for {@code skin}; true on success. */
    public static boolean setup(Skin skin) {
        Fonts.register();
        FlatLaf laf = skin.dark ? new Dark() : new Light();
        laf.setExtraDefaults(defaults(skin));
        current = skin;
        return FlatLaf.setup(laf);
    }

    /** Switches a running UI to {@code skin} and tells the listeners. */
    public static void apply(Skin skin) {
        setup(skin);
        FlatLaf.updateUI();
        for (Consumer<Skin> l : LISTENERS) {
            l.accept(skin);
        }
    }

    public static void addListener(Consumer<Skin> l) {
        LISTENERS.add(l);
    }

    static Map<String, String> defaults(Skin s) {
        Map<String, String> d = new HashMap<>();
        d.put("defaultFont", "13 \"" + Fonts.monoFamily() + "\"");
        d.put("@background", hex(s.chassis));
        d.put("@foreground", hex(s.listText));
        d.put("@accentColor", hex(s.highlight));
        d.put("@selectionBackground", hex(s.highlight));
        d.put("@selectionForeground", hex(s.highlightText));
        d.put("@selectionInactiveBackground", hex(s.highlight));
        d.put("@selectionInactiveForeground", hex(s.highlightText));
        d.put("Component.borderColor", hex(s.edge));
        d.put("Component.focusWidth", "0");
        d.put("Component.innerFocusWidth", "0");
        d.put("Component.arc", "3");
        d.put("Button.arc", "3");
        d.put("TextComponent.arc", "3");
        d.put("Table.background", hex(s.list));
        d.put("Table.foreground", hex(s.listText));
        d.put("Table.showHorizontalLines", "false");
        d.put("Table.showVerticalLines", "false");
        d.put("TableHeader.background", hex(s.list));
        d.put("List.background", hex(s.list));
        d.put("ScrollPane.background", hex(s.list));
        d.put("Viewport.background", hex(s.list));
        d.put("ToolTip.background", hex(s.lcd));
        d.put("ToolTip.foreground", hex(s.segment));
        d.put("ScrollBar.width", "8");
        d.put("ScrollBar.thumbArc", "0");
        return d;
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    /** The FlatLaf base for light skins. */
    public static final class Light extends FlatLightLaf {
        @Override
        public String getName() {
            return "Purist";
        }
    }

    /** The FlatLaf base for dark skins. */
    public static final class Dark extends FlatDarkLaf {
        @Override
        public String getName() {
            return "Purist Dark";
        }
    }
}
