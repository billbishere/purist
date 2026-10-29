package purist.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JRootPane;
import org.junit.jupiter.api.Test;
import purist.player.PlayerState;
import purist.player.PlayerStatus;

class UiPlayerBridgeTest {

    private final Banner banner = new Banner();
    private final AtomicInteger reopened = new AtomicInteger();
    private final UiPlayerBridge bridge =
            new UiPlayerBridge(new Toasts(new JRootPane()), banner, reopened::incrementAndGet);

    private static PlayerStatus status(PlayerState s) {
        return new PlayerStatus(s, 0, null, 0, 0, null, 0, Optional.empty());
    }

    @Test
    void deviceLossShowsTheBannerWithARetryNoteAndStartsAutoRetry() {
        bridge.deviceLost("Topping USB Audio Device is not available — check it is on and connected.");
        assertTrue(banner.showing());
        assertEquals("Topping USB Audio Device is not available — check it is on and connected. Retrying…",
                banner.text());
        assertTrue(bridge.retrying());
    }

    @Test
    void aMessageWithoutAFullStopGetsOne() {
        assertEquals("Fake was lost: start failed. Retrying…", UiPlayerBridge.withRetryNote("Fake was lost: start failed"));
    }

    @Test
    void aStaleTrackStartedDoesNotClearTheBanner() {
        bridge.deviceLost("gone");
        bridge.trackStarted(0, null, null); // queued before the device went, delivered after
        assertTrue(banner.showing());
        assertTrue(bridge.retrying());
    }

    @Test
    void aStoppedStateDoesNotClearTheBannerEither() {
        bridge.deviceLost("gone");
        bridge.stateChanged(status(PlayerState.STOPPED));
        bridge.stateChanged(status(PlayerState.PAUSED));
        assertTrue(banner.showing());
        assertTrue(bridge.retrying());
    }

    @Test
    void playbackRunningAgainIsTheRecovery() {
        bridge.deviceLost("gone");
        bridge.stateChanged(status(PlayerState.PLAYING));
        assertFalse(banner.showing());
        assertFalse(bridge.retrying());
    }

    @Test
    void cancelRetryStopsTheTimerAndHidesTheBanner() {
        bridge.deviceLost("gone");
        bridge.cancelRetry();
        assertFalse(banner.showing());
        assertFalse(bridge.retrying());
    }

    @Test
    void theBannerDrawsHtmlLiterally() {
        bridge.deviceLost("<html><b>x");
        assertTrue(banner.text().startsWith("<html><b>x"));
        assertTrue(purist.ui.kit.PlainText.is((javax.swing.JLabel) banner.getComponent(0)));
    }
}
