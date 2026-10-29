package purist.ui.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaceholderCacheTest {

    @Test
    void albumsWithTheSameInitialsShareOnePicture() {
        PlaceholderCache c = new PlaceholderCache(32);
        assertSame(c.get("Abbey Road"), c.get("Alpha Romeo"));
        assertEquals(1, c.size());
    }

    @Test
    void aBigLibraryOfDifferentAlbumsKeepsOnlyAFewPictures() {
        PlaceholderCache c = new PlaceholderCache(16);
        for (int i = 0; i < 5000; i++) {
            c.get((char) ('A' + i % 26) + "x " + (char) ('A' + (i / 26) % 26) + "y");
        }
        assertTrue(c.size() <= PlaceholderCache.MAX, "kept " + c.size());
    }

    @Test
    void clearingDropsThemSoANewSkinIsRedrawn() {
        PlaceholderCache c = new PlaceholderCache(16);
        var first = c.get("Abbey Road");
        c.clear();
        assertEquals(0, c.size());
        assertNotSame(first, c.get("Abbey Road"));
    }
}
