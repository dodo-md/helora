package com.dodoznq.helora.data.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StreamUrlExpiryTest {

    private val fallbackMs = 30L * 60 * 1000
    private val safetyMarginMs = 60_000L

    @Test
    fun `remainingTtlMs returns the time left until expire minus the safety margin`() {
        val nowMs = 1_700_000_000_000L
        val expireSeconds = nowMs / 1000 + 300
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=$expireSeconds&ip=1.2.3.4"

        val result = StreamUrlExpiry.remainingTtlMs(
            url = url,
            nowMs = nowMs,
            fallbackMs = fallbackMs,
            safetyMarginMs = safetyMarginMs
        )

        assertEquals(300_000L - safetyMarginMs, result)
    }

    @Test
    fun `remainingTtlMs falls back when expire is already in the past`() {
        val nowMs = 1_700_000_000_000L
        val expireSeconds = nowMs / 1000 - 60
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=$expireSeconds"

        val result = StreamUrlExpiry.remainingTtlMs(
            url = url,
            nowMs = nowMs,
            fallbackMs = fallbackMs
        )

        assertEquals(fallbackMs, result)
    }

    @Test
    fun `remainingTtlMs falls back when the expire parameter is missing`() {
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?ip=1.2.3.4&ei=abc123"

        val result = StreamUrlExpiry.remainingTtlMs(
            url = url,
            nowMs = 1_700_000_000_000L,
            fallbackMs = fallbackMs
        )

        assertEquals(fallbackMs, result)
    }

    @Test
    fun `remainingTtlMs falls back when the expire value is not a number`() {
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=abc"

        val result = StreamUrlExpiry.remainingTtlMs(
            url = url,
            nowMs = 1_700_000_000_000L,
            fallbackMs = fallbackMs
        )

        assertEquals(fallbackMs, result)
    }

    @Test
    fun `remainingTtlMs clamps a far-future expire to the fallback ceiling`() {
        val nowMs = 1_700_000_000_000L
        val expireSeconds = nowMs / 1000 + 6 * 60 * 60
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=$expireSeconds"

        val result = StreamUrlExpiry.remainingTtlMs(
            url = url,
            nowMs = nowMs,
            fallbackMs = fallbackMs
        )

        assertEquals(fallbackMs, result)
    }

    @Test
    fun `remainingTtlMs falls back on a blank or unparseable url without throwing`() {
        val nowMs = 1_700_000_000_000L

        assertEquals(fallbackMs, StreamUrlExpiry.remainingTtlMs("", nowMs, fallbackMs))
        assertEquals(fallbackMs, StreamUrlExpiry.remainingTtlMs("not a url", nowMs, fallbackMs))
    }
}
