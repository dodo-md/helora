package com.dodoznq.helora.data.youtube

/**
 * Resizes YouTube Music artwork URLs.
 *
 * YouTube hands back a *list* of thumbnails per item, and for songs that list stops at 120x120 —
 * fine in a search row, far too small behind the now-playing controls. The catch is that the
 * small variants are not a smaller asset: the size is a parameter baked into the URL
 * (`...=w120-h120-l90-rj`), and the same base URL serves any size asked of it. Album results
 * prove it, since they list `w544-h544` against a base identical to the song result's.
 *
 * So rather than hunting for a bigger thumbnail that is not there, rewrite the one we have.
 *
 * Measured against the live CDN: 120, 226, 400, 544, 720 and 1080 all return exactly what was
 * asked for. Above that the source clamps — a request for 2000 comes back as 1400 with HTTP 200
 * — so there is no size that 404s and no need to probe for a ceiling at runtime.
 */
object YouTubeArtworkUrls {

    /** Where the source stops growing; larger requests are served clamped to this. */
    const val MAX_SIZE_PX = 1400

    /** Below this the rewrite is not worth the cache churn — that is thumbnail territory. */
    private const val MIN_SIZE_PX = 120

    private const val ARTWORK_HOST_SUFFIX = "googleusercontent.com"

    /**
     * Matches only the leading `w`/`h` pair. Everything after it (`-l90-rj`, or `-p-l90-rj` on
     * artist avatars) is a rendering flag that has to survive untouched.
     */
    private val SIZE_PARAM_REGEX = Regex("=w\\d+-h\\d+")

    /**
     * True for artwork this object can resize. Deliberately narrow: the Coil interceptor runs
     * against every image the app loads, and local, Navidrome and Jellyfin artwork must pass
     * through with the URL they came in with.
     */
    fun isResizable(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':')
        if (!host.endsWith(ARTWORK_HOST_SUFFIX, ignoreCase = true)) return false
        return SIZE_PARAM_REGEX.containsMatchIn(url)
    }

    /**
     * Returns [url] asking for [requestedPx] square, or [url] unchanged when it is not YouTube
     * artwork or carries no size parameter to rewrite.
     */
    fun withSize(url: String?, requestedPx: Int): String? {
        if (url == null || !isResizable(url)) return url
        val size = requestedPx.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        // Lambda form: the replacement is taken literally, so a `$` in a URL cannot be read as
        // a group reference.
        return SIZE_PARAM_REGEX.replace(url) { "=w$size-h$size" }
    }
}
