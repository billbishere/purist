package purist.ui.theme;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AppIconTest {

    @Test
    void everySizeHasClearCornersAndTheLcdInTheMiddle() {
        for (int s : AppIcon.SIZES) {
            BufferedImage img = AppIcon.render(s);
            assertEquals(s, img.getWidth());
            assertEquals(0, img.getRGB(0, 0) >>> 24, s + " px corner");
            assertEquals(0, img.getRGB(s - 1, s - 1) >>> 24, s + " px corner");
            assertEquals(0xFF, img.getRGB(s / 2, s / 2) >>> 24, s + " px middle");
        }
    }

    @Test
    void everySizeShowsTheWaveform() {
        int segment = Skin.PAPER.segment.getRGB();
        for (int s : AppIcon.SIZES) {
            BufferedImage img = AppIcon.render(s);
            long dark = Arrays.stream(img.getRGB(0, 0, s, s, null, 0, s)).filter(p -> p == segment).count();
            assertTrue(dark >= s / 2, s + " px: " + dark + " segment pixels");
        }
    }

    @Test
    void theIcoListsEverySizeWithInRangeImages() {
        byte[] ico = AppIcon.ico();
        ByteBuffer b = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, b.getShort(0));
        assertEquals(1, b.getShort(2));
        assertEquals(AppIcon.SIZES.length, b.getShort(4));
        for (int i = 0; i < AppIcon.SIZES.length; i++) {
            int e = 6 + 16 * i;
            int s = AppIcon.SIZES[i];
            assertEquals(s % 256, Byte.toUnsignedInt(b.get(e)), "width byte");
            assertEquals(32, b.getShort(e + 6));
            int size = b.getInt(e + 8);
            int offset = b.getInt(e + 12);
            assertTrue(offset + size <= ico.length, s + " px image in range");
            if (s >= 256) {
                assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, Arrays.copyOfRange(ico, offset, offset + 4));
            } else {
                assertEquals(40, b.getInt(offset), "BITMAPINFOHEADER");
                assertEquals(s, b.getInt(offset + 4));
                assertEquals(2 * s, b.getInt(offset + 8), "height covers colour and mask");
            }
        }
    }
}
