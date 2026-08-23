package com.dodoznq.helora.data.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudStreamSecurityTest {

    @Test
    fun `totalLengthFromContentRange reads the size after the slash`() {
        assertEquals(9999L, CloudStreamSecurity.totalLengthFromContentRange("bytes 0-1023/9999"))
        assertEquals(9999L, CloudStreamSecurity.totalLengthFromContentRange("  bytes 1024-2047/9999  "))
        assertEquals(1L, CloudStreamSecurity.totalLengthFromContentRange("BYTES 0-0/1"))
    }

    @Test
    fun `totalLengthFromContentRange gives up rather than guessing`() {
        // A server is allowed to send * when it does not know the total, and the download then
        // has to fall back to reading until a short chunk arrives.
        assertNull(CloudStreamSecurity.totalLengthFromContentRange("bytes 0-1023/*"))
        assertNull(CloudStreamSecurity.totalLengthFromContentRange(null))
        assertNull(CloudStreamSecurity.totalLengthFromContentRange(""))
        assertNull(CloudStreamSecurity.totalLengthFromContentRange("items 0-1023/9999"))
        assertNull(CloudStreamSecurity.totalLengthFromContentRange("bytes 0-1023/abc"))
        assertNull(CloudStreamSecurity.totalLengthFromContentRange("bytes 0-1023"))
        assertNull(CloudStreamSecurity.totalLengthFromContentRange("bytes 0-1023/-5"))
    }

    @Test
    fun `validateRangeHeader accepts standard ranges`() {
        val validation = CloudStreamSecurity.validateRangeHeader("bytes=0-1023")

        assertTrue(validation.isValid)
        assertTrue(validation.normalizedHeader == "bytes=0-1023")
        assertTrue(validation.startInclusive == 0L)
        assertTrue(validation.endInclusive == 1023L)
        assertFalse(validation.isSuffixRange)
    }

    @Test
    fun `validateRangeHeader accepts suffix ranges`() {
        val validation = CloudStreamSecurity.validateRangeHeader("bytes=-4096")

        assertTrue(validation.isValid)
        assertTrue(validation.isSuffixRange)
        assertTrue(validation.startInclusive == null)
        assertTrue(validation.endInclusive == 4096L)
    }

    @Test
    fun `validateRangeHeader rejects invalid formats`() {
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=1-2,4-5").isValid)
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=10-2").isValid)
        assertFalse(CloudStreamSecurity.validateRangeHeader("bytes=-").isValid)
    }

    @Test
    fun `isSafeRemoteStreamUrl blocks localhost`() {
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://127.0.0.1:8000/audio.mp3",
                allowedHostSuffixes = setOf("127.0.0.1"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isSafeRemoteStreamUrl enforces host allowlist and scheme rules`() {
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "https://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net")
            )
        )
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://evil.example.com/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isSupportedAudioContentType only accepts audio-safe types`() {
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("audio/mpeg"))
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("application/octet-stream"))
        assertFalse(CloudStreamSecurity.isSupportedAudioContentType("text/html"))
    }
}
