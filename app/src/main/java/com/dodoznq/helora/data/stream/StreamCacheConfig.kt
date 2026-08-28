package com.dodoznq.helora.data.stream

object StreamCacheConfig {
    const val DIR_NAME = "media3_stream_cache"
    const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
    const val MIN_MAX_BYTES = 64L * 1024 * 1024
    const val MAX_MAX_BYTES = 4L * 1024 * 1024 * 1024

    /** Sizes offered by the cache size picker in Settings. */
    val SELECTABLE_MAX_BYTES: List<Long> = listOf(
        128L * 1024 * 1024,
        256L * 1024 * 1024,
        512L * 1024 * 1024,
        1024L * 1024 * 1024,
        2048L * 1024 * 1024
    )
}
