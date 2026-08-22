package com.dodoznq.helora.data.download

/**
 * Builds where a downloaded track lands in shared storage.
 *
 * Files go to the public Music folder so other players, file managers and car head units can
 * see them, and so the app's own MediaStore scan picks them up as ordinary local songs.
 */
object DownloadPaths {
    const val ROOT_FOLDER = "Helora"

    /**
     * Literal rather than [android.os.Environment.DIRECTORY_MUSIC]: MediaStore's RELATIVE_PATH
     * wants exactly this string, and the constant resolves to null off-device, which quietly
     * produced paths beginning "null/".
     */
    const val MUSIC_FOLDER = "Music"

    private const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val UNKNOWN_ALBUM = "Unknown Album"
    private const val UNKNOWN_TITLE = "Unknown Title"

    /** Longest a single path segment may be; well under the 255-byte limit once encoded. */
    private const val MAX_SEGMENT_LENGTH = 60

    // Characters that are illegal or troublesome on FAT32/exFAT SD cards, not just ext4.
    private val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Makes one path segment safe. Beyond stripping illegal characters this also refuses names
     * that are only dots: "." and ".." would escape the directory, and a trailing dot is
     * silently dropped by some filesystems, which would make two tracks collide.
     */
    fun sanitizeSegment(raw: String, fallback: String): String {
        val cleaned = ILLEGAL_CHARS.replace(raw, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .trimEnd('.')
            .trim()
        if (cleaned.isBlank() || cleaned.all { it == '.' }) return fallback
        return if (cleaned.length > MAX_SEGMENT_LENGTH) {
            cleaned.take(MAX_SEGMENT_LENGTH).trim().trimEnd('.').ifBlank { fallback }
        } else {
            cleaned
        }
    }

    /** `Music/Helora/<Artist>/<Album>/`, as MediaStore's RELATIVE_PATH wants it. */
    fun relativePath(artist: String, album: String): String {
        val safeArtist = sanitizeSegment(artist, UNKNOWN_ARTIST)
        val safeAlbum = sanitizeSegment(album, UNKNOWN_ALBUM)
        return "$MUSIC_FOLDER/$ROOT_FOLDER/$safeArtist/$safeAlbum/"
    }

    /** `07 - Paranoid Android.m4a`, or just the title when there is no track number. */
    fun fileName(title: String, trackNumber: Int, extension: String): String {
        val safeTitle = sanitizeSegment(title, UNKNOWN_TITLE)
        val prefix = if (trackNumber > 0) "%02d - ".format(trackNumber) else ""
        return "$prefix$safeTitle.$extension"
    }
}
