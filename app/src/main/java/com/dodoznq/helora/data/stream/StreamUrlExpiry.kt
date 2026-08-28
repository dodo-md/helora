package com.dodoznq.helora.data.stream

import java.net.URI

/**
 * Reads the `expire` query parameter that googlevideo.com stream URLs carry, so callers can
 * cache a URL for as long as it is actually signed to live instead of a fixed guess.
 */
internal object StreamUrlExpiry {

    /**
     * How long [url] remains valid from [nowMs], derived from its own `expire` timestamp.
     * Falls back to [fallbackMs] if the timestamp is missing, unparseable, or already past,
     * and never returns more than [fallbackMs] since the URL is also IP-bound and can die
     * before its signed expiry regardless.
     */
    fun remainingTtlMs(
        url: String,
        nowMs: Long = System.currentTimeMillis(),
        fallbackMs: Long,
        safetyMarginMs: Long = 60_000L
    ): Long {
        val expireSeconds = parseExpireSeconds(url) ?: return fallbackMs
        val remaining = expireSeconds * 1000L - nowMs - safetyMarginMs
        if (remaining <= 0) return fallbackMs
        return minOf(remaining, fallbackMs)
    }

    private fun parseExpireSeconds(url: String): Long? {
        val query = try {
            URI(url).query
        } catch (e: Exception) {
            null
        } ?: return null

        for (param in query.split("&")) {
            val separatorIndex = param.indexOf('=')
            if (separatorIndex <= 0) continue
            if (param.substring(0, separatorIndex) != "expire") continue
            return param.substring(separatorIndex + 1).toLongOrNull()
        }
        return null
    }
}
