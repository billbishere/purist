package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ImageScaleTest {

    private static BufferedImage solid(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    @Test
    void aBigPictureIsScaledToTheSquareRequested() {
        BufferedImage out = ImageScale.square(solid(320, 320, 0x336699), 70);
        assertEquals(70, out.getWidth());
        assertEquals(70, out.getHeight());
        assertEquals(0x336699, out.getRGB(35, 35) & 0xFFFFFF, "a flat colour stays that colour through halving");
    }

    @Test
    void aPictureNotSquareIsCroppedToItsMiddle() {
        BufferedImage wide = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 400; x++) {
                wide.setRGB(x, y, x < 100 || x >= 300 ? 0xFF0000 : 0x00FF00);
            }
        }
        BufferedImage out = ImageScale.square(wide, 50);
        assertEquals(0x00FF00, out.getRGB(25, 25) & 0xFFFFFF);
        assertEquals(0x00FF00, out.getRGB(2, 2) & 0xFFFFFF, "the red sides were cropped away");
    }

    @Test
    void aSmallPictureStillFillsTheSquare() {
        BufferedImage out = ImageScale.square(solid(30, 30, 0xAA0000), 70);
        assertEquals(70, out.getWidth());
        assertEquals(70, out.getHeight());
    }

    @Test
    void aPictureAlreadyTheSizeIsReturnedAsIs() {
        BufferedImage img = solid(70, 70, 0x000000);
        assertSame(img, ImageScale.square(img, 70));
    }
}
