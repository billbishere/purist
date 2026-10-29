package purist.ui;

import javax.swing.Timer;
import purist.player.PlayerListener;
import purist.player.PlayerState;
import purist.player.PlayerStatus;
import purist.playlist.TrackRef;

/**
 * Turns Player events into UI (spec §6, §10):
 * <ul>
 *   <li>a skip becomes the toast "Skipped &lt;file&gt;: &lt;reason&gt;";</li>
 *   <li>an error becomes a toast;</li>
 *   <li>device loss raises the banner with RETRY, and retries by itself every 3 s until playback is running again
 *       (a {@code stateChanged} to PLAYING: a {@code trackStarted} that was already queued when the device went
 *       must not count as recovery).</li>
 * </ul>
 * The Player delivers events on the EDT, so everything here runs there.
 */
final class UiPlayerBridge implements PlayerListener {

    static final int AUTO_RETRY_MS = 3000;

    private final Toasts toasts;
    private final Banner banner;
    private final Runnable reopen;
    private final Timer autoRetry;

    /**
     * @param reopen re-creates the sink for the selected driver and resumes playback; a failure shows up as another
     *               {@code deviceLost}
     */
    UiPlayerBridge(Toasts toasts, Banner banner, Runnable reopen) {
        this.toasts = toasts;
        this.banner = banner;
        this.reopen = reopen;
        this.autoRetry = new Timer(AUTO_RETRY_MS, e -> reopen.run());
        autoRetry.setRepeats(true);
    }

    @Override
    public void trackSkipped(int index, TrackRef ref, String reason) {
        toasts.show("Skipped " + ref.path().getFileName() + ": " + reason);
    }

    @Override
    public void error(String message) {
        toasts.show(message);
    }

    @Override
    public void deviceLost(String message) {
        banner.show(withRetryNote(message), reopen);
        if (!autoRetry.isRunning()) {
            autoRetry.start();
        }
    }

    @Override
    public void stateChanged(PlayerStatus s) {
        if (s.state() == PlayerState.PLAYING) {
            autoRetry.stop();
            if (banner.showing()) {
                banner.dismiss();
            }
        }
    }

    /** Stops retrying and removes the banner: the user picked another driver, or pressed STOP. */
    void cancelRetry() {
        autoRetry.stop();
        banner.dismiss();
    }

    boolean retrying() {
        return autoRetry.isRunning();
    }

    /** "Topping USB Audio Device is not available — check it is on and connected. Retrying…" */
    static String withRetryNote(String message) {
        String m = message.strip();
        if (!m.endsWith(".") && !m.endsWith("!") && !m.endsWith("?")) {
            m += ".";
        }
        return m + " Retrying…";
    }
}
