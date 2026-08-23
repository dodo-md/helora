package com.dodoznq.helora.data.offline

import com.google.common.truth.Truth.assertThat
import com.dodoznq.helora.data.model.Song
import org.junit.jupiter.api.Test

class CloudOfflineRepositoryTest {
    @Test
    fun `provider detection accepts only supported cloud schemes`() {
        assertThat(CloudOfflineRepository.providerFor("navidrome://track_1")).isEqualTo("navidrome")
        assertThat(CloudOfflineRepository.providerFor("jellyfin://ABC123")).isEqualTo("jellyfin")
        assertThat(CloudOfflineRepository.providerFor("https://example.com/song.mp3")).isNull()
        assertThat(CloudOfflineRepository.providerFor("file:///music/song.mp3")).isNull()
    }

    @Test
    fun `download ids are stable and do not expose provider identifiers`() {
        val uri = "navidrome://private-track-id"
        val first = CloudOfflineRepository.downloadId(uri)
        val second = CloudOfflineRepository.downloadId(uri)

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(64)
        assertThat(first).doesNotContain("private-track-id")
        assertThat(first).isNotEqualTo(CloudOfflineRepository.downloadId("navidrome://other"))
    }

    @Test
    fun `cloud song detection uses canonical playback uri`() {
        assertThat(CloudOfflineRepository.isCloudSong(song("navidrome://abc"))).isTrue()
        assertThat(CloudOfflineRepository.isCloudSong(song("jellyfin://ABC123"))).isTrue()
        assertThat(CloudOfflineRepository.isCloudSong(song("content://media/audio/1"))).isFalse()
    }

    /**
     * A track still streaming from YouTube counts as a cloud song, which is easy to forget
     * because it has nothing to do with a self-hosted server. The song sheet drew a download
     * button for YouTube tracks and a second one for cloud songs, so every such track showed
     * two of them until the first was made conditional on this being false.
     */
    @Test
    fun `a streaming youtube track counts as a cloud song`() {
        assertThat(CloudOfflineRepository.isCloudSong(song("ytmusic://dQw4w9WgXcQ"))).isTrue()
        assertThat(CloudOfflineRepository.providerFor("ytmusic://dQw4w9WgXcQ"))
            .isEqualTo(CloudOfflineRepository.PROVIDER_YOUTUBE)
    }

    /** Once downloaded the same track is a local file again, and stops being a cloud song. */
    @Test
    fun `a downloaded youtube track is no longer a cloud song`() {
        assertThat(CloudOfflineRepository.isCloudSong(song("file:///Music/track.m4a"))).isFalse()
    }

    @Test
    fun `separate attempts cannot share temporary or final file names`() {
        val downloadId = CloudOfflineRepository.downloadId("navidrome://track_1")

        val first = CloudOfflineRepository.attemptFileStem(downloadId, "attempt-a")
        val second = CloudOfflineRepository.attemptFileStem(downloadId, "attempt-b")

        assertThat(first).isNotEqualTo(second)
        assertThat("$first.part").isNotEqualTo("$second.part")
        assertThat("$first.flac").isNotEqualTo("$second.flac")
    }

    private fun song(uri: String) = Song.emptySong().copy(contentUriString = uri)
}
