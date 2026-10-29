package purist.library.art;

/**
 * Identifies one album in the cover cache. The thumbnail on disk is named after all three parts, so two albums
 * in one folder keep their own pictures; the album and album artist also label the placeholder.
 *
 * @param folderPath  the folder holding the album's files, as the library spells it
 * @param album       the album title
 * @param albumArtist the album artist
 */
public record AlbumKey(String folderPath, String album, String albumArtist) {
}
