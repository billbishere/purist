package purist.ui;

import java.awt.Rectangle;
import java.util.List;
import java.util.Optional;

/**
 * Saving and restoring the window's place: bounds as {@code "x,y,w,h"} in {@code settings.properties}, put back on a
 * screen that still exists. Pure arithmetic; the window reads the screens.
 */
public final class WindowState {

    private WindowState() {
    }

    /** The bounds in {@code text}; empty if it is not four whole numbers with a positive width and height. */
    public static Optional<Rectangle> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            int w = Integer.parseInt(parts[2].trim());
            int h = Integer.parseInt(parts[3].trim());
            return w > 0 && h > 0 ? Optional.of(new Rectangle(x, y, w, h)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** {@code "x,y,w,h"}. */
    public static String format(Rectangle r) {
        return r.x + "," + r.y + "," + r.width + "," + r.height;
    }

    /** {@code r} made to fit inside {@code screen}: shrunk if it is bigger, then moved in. */
    public static Rectangle clampToScreen(Rectangle r, Rectangle screen) {
        int w = Math.min(r.width, screen.width);
        int h = Math.min(r.height, screen.height);
        int x = Math.max(screen.x, Math.min(r.x, screen.x + screen.width - w));
        int y = Math.max(screen.y, Math.min(r.y, screen.y + screen.height - h));
        return new Rectangle(x, y, w, h);
    }

    /**
     * {@code r} clamped to the screen it overlaps most; when it overlaps none (its monitor was unplugged) the first
     * screen, which is the main one. With no screens at all, {@code r} as it is.
     */
    public static Rectangle clamp(Rectangle r, List<Rectangle> screens) {
        if (screens.isEmpty()) {
            return new Rectangle(r);
        }
        Rectangle best = screens.get(0);
        long bestArea = -1;
        for (Rectangle s : screens) {
            Rectangle i = r.intersection(s);
            long area = i.isEmpty() ? 0 : (long) i.width * i.height;
            if (area > bestArea) {
                bestArea = area;
                best = s;
            }
        }
        return clampToScreen(r, best);
    }
}
