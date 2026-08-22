package com.dodoznq.helora.data.stream

/**
 * Canonical URI schemes for remotely-sourced media.
 *
 * These used to be duplicated between the playback engine and the dispatch layer, which made
 * it easy to add a source that plays in one code path and silently fails in the other. Adding
 * a source means adding it here, once.
 */
object CloudStreamSchemes {
    /** Schemes served by a [CloudStreamProxy] and therefore requiring eager resolution. */
    val PROXIED: Set<String> = setOf(
        "navidrome",
        "jellyfin",
        "ytmusic"
    )

    /** Everything not backed by a local file: the proxied schemes plus plain HTTP(S). */
    val REMOTE: Set<String> = PROXIED + setOf("http", "https")

    val LOCAL: Set<String> = setOf("content", "file", "android.resource")
}
