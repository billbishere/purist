package purist.player;

import purist.audio.SignalPath;
import purist.decode.TrackInfo;
import purist.playlist.TrackRef;

/** Player events. Every method is called through the player's {@code events} executor (the EDT in the app). */
public interface PlayerListener {

    /** The state, the track or the pause changed. */
    default void stateChanged(PlayerStatus s) {
    }

    /** The output reached the first frame of a track (at a gapless join, within one 50 ms tick of it). */
    default void trackStarted(int index, TrackInfo t, SignalPath p) {
    }

    /** A track could not be played and was passed over; {@code reason} says why (never converted, §5). */
    default void trackSkipped(int index, TrackRef ref, String reason) {
    }

    /** Something went wrong that the user should hear about, such as a damaged file that stopped mid-track. */
    default void error(String message) {
    }

    /** The output device stopped working; playback has stopped. */
    default void deviceLost(String message) {
    }
}
