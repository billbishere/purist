package purist.player;

import java.util.Optional;
import purist.audio.SignalPath;
import purist.decode.TrackInfo;

/**
 * A snapshot of the player for the UI.
 *
 * @param state               what the player is doing
 * @param index               the queue index of the track being heard (or last heard, when stopped); -1 for none
 * @param track               that track, or null when there is none
 * @param positionSeconds     how far into the track the output is
 * @param durationSeconds     the track's length; 0 when unknown
 * @param signalPath          what happens between file and DAC for this track; null when there is no track
 * @param dropoutsThisTrack   underruns since this track started
 * @param notBitPerfectReason why the output is not bit-perfect right now, or empty when it is
 */
public record PlayerStatus(PlayerState state, int index, TrackInfo track, double positionSeconds,
        double durationSeconds, SignalPath signalPath, long dropoutsThisTrack, Optional<String> notBitPerfectReason) {
}
