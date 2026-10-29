package purist.ui.browse;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Scales a picture down to a square without the jagged look of one big bilinear step. */
public final class ImageScale {

    private ImageScale() {
    }

    /**
     * The middle square of {@code src} at {@code px} by {@code px}. A picture already that size is returned as is.
     * Large pictures are halved with bilinear interpolation until they are less than twice the target, then
     * scaled the last bit, which keeps thin lines and text on a cover readable.
     */
    public static BufferedImage square(BufferedImage src, int px) {
        if (src.getWidth() == px && src.getHeight() == px) {
            return src;
        }
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage cur = crop(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side);
        while (cur.getWidth() >= 2 * px) {
            cur = resize(cur, Math.max(px, cur.getWidth() / 2));
        }
        return cur.getWidth() == px ? cur : resize(cur, px);
    }

    private static BufferedImage crop(BufferedImage src, int x, int y, int side) {
        if (x == 0 && y == 0 && src.getWidth() == side && src.getHeight() == side) {
            return src;
        }
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, side, side, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage resize(BufferedImage src, int px) {
        BufferedImage out = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, px, px, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
