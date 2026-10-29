package purist.library;

import java.nio.file.Path;
import purist.playlist.TrackRef;

/**
 * One album as the browser lists it.
 *
 * @param albumArtist ALBUMARTIST, else ARTIST, else {@code "Unknown artist"}; spelled as the first track has it
 * @param album       ALBUM, else the name of the folder holding the tracks
 * @param year        the first four digits of the first DATE found, or the empty string
 * @param folder      the folder of the album's first track; the cover cache is keyed on it
 * @param trackCount  how many tracks (rows, so every subsong of an SACD ISO) the album holds
 * @param firstTrack  the lowest disc and track number; its art represents the album
 * @param formatLabel the badge shared by the album's playable tracks, such as {@code FLAC 24/96}, or
 *                    {@code MIXED} when they differ
 */
public record AlbumSummary(String albumArtist, String album, String year, Path folder, int trackCount,
        TrackRef firstTrack, String formatLabel) {
}
