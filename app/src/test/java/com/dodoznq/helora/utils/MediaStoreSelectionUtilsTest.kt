package com.dodoznq.helora.utils

import android.provider.MediaStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaStoreSelectionUtilsTest {

    @Test
    fun `buildLocalAudioSelection never gates on the is music flag alone`() {
        val (selection, selectionArgs) = buildLocalAudioSelection(10_000)

        // Some scanners leave real songs at is_music = 0. Gating on the flag would hide them,
        // which is the bug this selection exists to avoid, so the flag may only ever appear
        // alongside the ringtone exclusion below.
        assertFalse(selection.contains("${MediaStore.Audio.Media.IS_MUSIC} != 0"))
        assertFalse(selection.contains("${MediaStore.Audio.Media.IS_MUSIC} = 1"))
        assertTrue(selection.contains(MediaStore.Audio.Media.DURATION))
        assertTrue(selection.contains(MediaStore.Audio.Media.TITLE))
        assertArrayEquals(
            arrayOf("10000", "audio/midi", "audio/x-midi", "audio/sp-midi", "audio/x-mid", "%.mid", "%.midi"),
            selectionArgs
        )
    }

    @Test
    fun `buildLocalAudioSelection excludes ringtones notifications and alarms`() {
        val (selection, _) = buildLocalAudioSelection(10_000)

        assertTrue(selection.contains(MediaStore.Audio.Media.IS_RINGTONE))
        assertTrue(selection.contains(MediaStore.Audio.Media.IS_NOTIFICATION))
        assertTrue(selection.contains(MediaStore.Audio.Media.IS_ALARM))
        // The exclusion has to be conditional on is_music being unset, otherwise a song the
        // user picked as their ringtone would disappear from the library.
        assertTrue(selection.contains("COALESCE(${MediaStore.Audio.Media.IS_MUSIC}, 0) = 0"))
    }

    @Test
    fun `buildLocalAudioSelection clamps negative durations`() {
        val (_, selectionArgs) = buildLocalAudioSelection(-250)

        assertTrue(selectionArgs.isNotEmpty())
        assertArrayEquals(arrayOf("0"), selectionArgs.take(1).toTypedArray())
    }

    @Test
    fun `buildLocalAudioSelection includes midi duration bypass`() {
        val (selection, _) = buildLocalAudioSelection(10_000)

        assertTrue(selection.contains(MediaStore.Audio.Media.MIME_TYPE))
        assertTrue(selection.contains("audio_media._data") || selection.contains(MediaStore.Audio.Media.DATA))
        assertTrue(selection.contains("LIKE"))
    }
}
