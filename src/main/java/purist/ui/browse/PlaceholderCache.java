package purist.ui.browse;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import purist.library.art.ThumbnailCache;

/**
 * The pictures drawn for albums without art. A placeholder depends only on the album's initials and the skin, so
 * one is shared by every album with the same initials, and only a few dozen are kept (least recently used first
 * out) however big the library is. {@link #clear} on a skin change. EDT only.
 */
final class PlaceholderCache {

    /** More than a screenful of different initials; a few MB at most. */
    static final int MAX = 64;

    private final int px;
    private final Map<String, BufferedImage> byInitials = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > MAX;
        }
    };

    PlaceholderCache(int px) {
        this.px = px;
    }

    BufferedImage get(String album) {
        return byInitials.computeIfAbsent(ThumbnailCache.initials(album), k -> ThumbnailCache.placeholder(album, px));
    }

    void clear() {
        byInitials.clear();
    }

    int size() {
        return byInitials.size();
    }
}
