package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JLabel;
import javax.swing.plaf.basic.BasicHTML;
import org.junit.jupiter.api.Test;
import purist.ui.kit.PlainText;
import purist.audio.SampleType;
import purist.audio.SignalPath;
import purist.audio.StreamFormat;
import purist.audio.Transform;
import purist.decode.TrackInfo;
import purist.player.PlayerState;
import purist.player.PlayerStatus;
import purist.tags.TagMap;

class DeckTest {

    private static PlayerStatus playing(long dropouts, Optional<String> reason) {
        StreamFormat f = StreamFormat.pcm(96000, 24, 2, 96000L * 300);
        TagMap tags = new TagMap();
        tags.add("TITLE", "Thriller");
        tags.add("ARTIST", "Michael Jackson");
        TrackInfo t = new TrackInfo(Path.of("E:\\m\\04 Thriller.flac"), 0, "FLAC", f, tags, true, null);
        SignalPath p = new SignalPath("Topping USB Audio Device", f, SampleType.INT32_LSB, 96000, 512,
                new Transform(8, false, false));
        return new PlayerStatus(PlayerState.PLAYING, 3, t, 133.4, 357.0, p, dropouts, reason);
    }

    @Test
    void showsBadgeAndIndicator() {
        Deck d = new Deck(new Deck.Actions() {});
        d.update(playing(0, Optional.empty()));
        assertEquals("FLAC 24/96", d.badgeText());
        assertEquals("24 BIT   96.0 kHz", d.rateText());
        assertEquals("BIT-PERFECT", d.indicatorText());
        assertEquals("PLAY · TRACK 04", d.stateText());
        assertEquals("24-bit 96 kHz > INT32_LSB @ 96000 Hz", d.pathText());
        assertFalse(d.dropoutsShown());
        assertEquals("THRILLER — MICHAEL JACKSON", d.titleText());
    }

    @Test
    void dropoutsAndReasonShowWhenNotBitPerfect() {
        Deck d = new Deck(new Deck.Actions() {});
        d.update(playing(3, Optional.of("3 dropouts")));
        assertEquals("NOT BIT-PERFECT", d.indicatorText());
        assertTrue(d.dropoutsShown());
    }

    @Test
    void blankWhenNothingLoaded() {
        Deck d = new Deck(new Deck.Actions() {});
        d.update((PlayerStatus) null);
        assertEquals("", d.badgeText());
        assertEquals("", d.indicatorText());
        assertEquals("STOP", d.stateText());
        assertFalse(d.dropoutsShown());
    }

    @Test
    void htmlLookingTagsAreDrawnLiterally() {
        StreamFormat f = StreamFormat.pcm(44100, 16, 2, 44100L * 60);
        TagMap tags = new TagMap();
        tags.add("TITLE", "<html><b>Boo<img src=\"http://example.invalid/t.png\">");
        tags.add("ARTIST", "<html><i>Band");
        TrackInfo t = new TrackInfo(Path.of("E:\\m\\x.flac"), 0, "FLAC", f, tags, true, null);
        SignalPath p = new SignalPath("Topping USB Audio Device", f, SampleType.INT32_LSB, 44100, 512,
                new Transform(8, false, false));
        Deck d = new Deck(new Deck.Actions() {});
        d.update(new PlayerStatus(PlayerState.PLAYING, 0, t, 1, 60, p, 0, Optional.empty()));
        assertTrue(d.titleText().startsWith("<HTML><B>BOO"), d.titleText());

        List<JLabel> labels = new ArrayList<>();
        collect(d, labels);
        assertFalse(labels.isEmpty());
        for (JLabel l : labels) {
            assertTrue(PlainText.is(l), "label showing \"" + l.getText() + "\" would render HTML");
            assertNull(l.getClientProperty(BasicHTML.propertyKey), "an HTML view was built for \"" + l.getText() + "\"");
        }
    }

    private static void collect(Container c, List<JLabel> out) {
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel l) {
                out.add(l);
            }
            if (k instanceof Container cc) {
                collect(cc, out);
            }
        }
    }

    @Test
    void timeFormat() {
        assertEquals("2:05", Deck.time(125.4));
        assertEquals("1:02:05", Deck.time(3725));
        assertEquals("0:00", Deck.time(-3));
    }
}
