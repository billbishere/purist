package purist.ui.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The application icon: a Paper LCD deck whose display shows a sample-and-hold waveform, the samples drawn as they are
 * in the file. It is drawn in code so the window icon and the installer's {@code purist.ico} come from one source.
 * <ul>
 *   <li>48 px and up: the chassis, the LCD with the waveform, and from 64 px the deck's
 *       BIT-PERFECT dot and transport buttons;</li>
 *   <li>below 48 px: a compact layout, snapped to whole pixels, with fewer and thicker steps.</li>
 * </ul>
 * {@link #main} writes the .ico that {@code scripts\package.ps1} hands to jpackage.
 */
public final class AppIcon {

    /** The sizes in the .ico and the window icon list; Windows picks the nearest for each place it shows one. */
    public static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private static final Color CHASSIS_TOP = new Color(0xF8F7F3);
    private static final Color CHASSIS_BOTTOM = new Color(0xE2E0D7);
    private static final Color OUTLINE = new Color(0x9C998D);
    private static final Color LCD_TOP = new Color(0xC9D0B6);
    private static final Color LCD_BOTTOM = Skin.PAPER.lcd;
    private static final Color LCD_RIM = new Color(0x8F977E);
    private static final Color SEGMENT = Skin.PAPER.segment;
    private static final Color BUTTON = new Color(0xFFFFFF);

    private AppIcon() {
    }

    /** The icon at every size in {@link #SIZES}, for {@code Window.setIconImages}. */
    public static List<Image> images() {
        List<Image> out = new ArrayList<>();
        for (int s : SIZES) {
            out.add(render(s));
        }
        return out;
    }

    /** The icon drawn at {@code size} x {@code size}, on a transparent background. */
    public static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (size >= 48) {
                drawFull(g, size);
            } else {
                drawCompact(g, size);
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Drawn on a 256-unit grid scaled to {@code size}. */
    private static void drawFull(Graphics2D g, int size) {
        double u = size / 256.0;
        chassis(g, 10 * u, 10 * u, 236 * u, 236 * u, 58 * u, Math.max(1, 4 * u));

        double lx = 34 * u;
        double ly = 40 * u;
        double lw = 188 * u;
        double lh = size >= 64 ? 134 * u : 162 * u;
        lcd(g, lx, ly, lw, lh, 18 * u, Math.max(1, 3 * u));

        double left = lx + 18 * u;
        double width = lw - 36 * u;
        double mid = ly + lh / 2;
        double amp = lh * 0.30;
        g.setColor(SEGMENT);
        g.setStroke(new BasicStroke((float) (10 * u), BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
        g.draw(steps(left, width, mid, amp, 8));

        if (size >= 64) {
            double row = 202 * u;
            g.setColor(SEGMENT);
            g.fill(new Ellipse2D.Double(42 * u, row - 9 * u, 18 * u, 18 * u));
            for (int i = 0; i < 3; i++) {
                RoundRectangle2D b = new RoundRectangle2D.Double((84 + i * 46) * u, row - 10 * u, 38 * u, 20 * u,
                        10 * u, 10 * u);
                g.setColor(BUTTON);
                g.fill(b);
                g.setColor(OUTLINE);
                g.setStroke(new BasicStroke((float) Math.max(1, 2.5 * u)));
                g.draw(b);
            }
        }
    }

    /** Below 48 px: whole-pixel geometry, so the steps stay crisp. */
    private static void drawCompact(Graphics2D g, int size) {
        chassis(g, 0.5, 0.5, size - 1, size - 1, size * 0.36, 1);
        int inset = Math.max(2, Math.round(size / 10f));
        int top = Math.max(3, Math.round(size * 0.17f));
        int lw = size - 2 * inset;
        int lh = size - top - Math.max(3, Math.round(size * 0.15f));
        lcd(g, inset, top, lw, lh, Math.max(2, size / 8.0), 1);

        // Four samples, mid, peak, mid, trough: more steps than that blur into a zigzag at these sizes.
        double[] levels = {0, 1, 0, -1};
        int n = levels.length;
        int stroke = size >= 40 ? 3 : size >= 24 ? 2 : 1;
        int stepW = (lw - (size >= 24 ? 4 : 2)) / n;
        int left = inset + (lw - stepW * n) / 2;
        double mid = top + lh / 2.0;
        double amp = (lh - (size >= 24 ? 4.0 : 2.0) - stroke) / 2.0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(SEGMENT);
        // Each sample is a bar held for one step, joined to the next by a riser: whole pixels, so crisp at any size.
        int prevY = 0;
        for (int i = 0; i < n; i++) {
            int x = left + i * stepW;
            int y = (int) Math.round(mid - amp * levels[i] - stroke / 2.0);
            if (i > 0) {
                g.fillRect(x, Math.min(prevY, y), stroke, Math.abs(prevY - y) + stroke);
            }
            g.fillRect(x, y, stepW, stroke);
            prevY = y;
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private static void chassis(Graphics2D g, double x, double y, double w, double h, double arc, double line) {
        RoundRectangle2D body = new RoundRectangle2D.Double(x, y, w, h, arc, arc);
        g.setPaint(new GradientPaint(0, (float) y, CHASSIS_TOP, 0, (float) (y + h), CHASSIS_BOTTOM));
        g.fill(body);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke((float) line));
        g.draw(body);
    }

    private static void lcd(Graphics2D g, double x, double y, double w, double h, double arc, double line) {
        RoundRectangle2D glass = new RoundRectangle2D.Double(x, y, w, h, arc, arc);
        g.setPaint(new GradientPaint(0, (float) y, LCD_TOP, 0, (float) (y + h), LCD_BOTTOM));
        g.fill(glass);
        g.setColor(LCD_RIM);
        g.setStroke(new BasicStroke((float) line));
        g.draw(glass);
    }

    /** One period of a sine, sampled {@code n} times at mid-step and held: the staircase a DAC is handed. */
    private static Path2D steps(double left, double width, double mid, double amp, int n) {
        Path2D p = new Path2D.Double();
        double stepW = width / n;
        for (int i = 0; i < n; i++) {
            double y = mid - amp * Math.sin(2 * Math.PI * (i + 0.5) / n);
            double x = left + i * stepW;
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
            p.lineTo(x + stepW, y);
        }
        return p;
    }

    /**
     * A Windows .ico holding every size in {@link #SIZES}: 256 px as PNG, the rest as 32-bit BGRA bitmaps, which every
     * part of Windows (Explorer, the taskbar, Add/Remove Programs, resource compilers) reads.
     */
    public static byte[] ico() {
        List<byte[]> images = new ArrayList<>();
        for (int s : SIZES) {
            images.add(s >= 256 ? png(render(s)) : dib(render(s)));
        }
        int offset = 6 + 16 * SIZES.length;
        ByteBuffer dir = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        dir.putShort((short) 0).putShort((short) 1).putShort((short) SIZES.length);
        for (int i = 0; i < SIZES.length; i++) {
            int s = SIZES[i];
            dir.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            dir.putShort((short) 1).putShort((short) 32);
            dir.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(offset);
        out.writeBytes(dir.array());
        images.forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static byte[] png(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** BITMAPINFOHEADER, bottom-up BGRA rows, then an all-zero AND mask (the alpha channel does the masking). */
    private static byte[] dib(BufferedImage img) {
        int s = img.getWidth();
        int maskRow = (s + 31) / 32 * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + s * s * 4 + maskRow * s).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(s).putInt(2 * s).putShort((short) 1).putShort((short) 32).putInt(0)
                .putInt(s * s * 4 + maskRow * s).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = s - 1; y >= 0; y--) {
            for (int x = 0; x < s; x++) {
                int argb = img.getRGB(x, y);
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        return b.array();
    }

    /** Writes the .ico to {@code args[0]}, and with a second argument a preview PNG of every size there. */
    public static void main(String[] args) throws IOException {
        Files.write(Path.of(args[0]), ico());
        if (args.length > 1) {
            int w = 0;
            for (int s : SIZES) {
                w += s + 16;
            }
            BufferedImage sheet = new BufferedImage(w + 16, 2 * 256 + 48, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            int x = 16;
            for (int s : SIZES) {
                g.setColor(Color.WHITE);
                g.fillRect(x - 8, 0, s + 16, 256 + 24);
                g.drawImage(render(s), x, 12 + 256 - s, null);
                g.setColor(new Color(0x202020));
                g.fillRect(x - 8, 256 + 24, s + 16, 256 + 24);
                g.drawImage(render(s), x, 256 + 36 + 256 - s, null);
                x += s + 16;
            }
            g.dispose();
            ImageIO.write(sheet, "png", Path.of(args[1]).toFile());
        }
    }
}
