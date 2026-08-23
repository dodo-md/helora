package com.dodoznq.helora.utils

import android.provider.MediaStore

private val MIDI_MIME_SELECTION_ARGS = arrayOf(
    "audio/midi",
    "audio/x-midi",
    "audio/sp-midi",
    "audio/x-mid"
)
private val MIDI_EXTENSION_SELECTION_ARGS = arrayOf(
    "%.mid",
    "%.midi"
)

/**
 * Builds the baseline MediaStore selection for user-facing local audio.
 *
 * We intentionally do not rely on [MediaStore.Audio.Media.IS_MUSIC] here because some devices
 * and scanners leave valid songs flagged as non-music, which makes library sync and folder
 * browsing appear to "cap out" below the real file count for specific users.
 *
 * MIDI files may be indexed with incomplete duration metadata, so explicit MIDI MIME/path
 * matches bypass the duration floor and are left to playback capability checks.
 *
 * Ignoring [MediaStore.Audio.Media.IS_MUSIC] does let the system sound pool in: ringtones,
 * notification tones and alarms are audio rows too, and they arrive with opaque titles. They
 * are excluded here only when the scanner both flagged them as one of those and did not flag
 * them as music, which is a combination a real song never has. A song wrongly left at
 * is_music = 0 carries none of the other flags, so it still comes through.
 */
fun buildLocalAudioSelection(minDurationMs: Int): Pair<String, Array<String>> {
    val clampedMinDurationMs = minDurationMs.coerceAtLeast(0)
    val midiMimePlaceholders = MIDI_MIME_SELECTION_ARGS.joinToString(",") { "?" }
    val midiExtensionSelection = MIDI_EXTENSION_SELECTION_ARGS.joinToString(" OR ") {
        "LOWER(${MediaStore.Audio.Media.DATA}) LIKE ?"
    }
    val selection = buildString {
        append("(")
        append("${MediaStore.Audio.Media.DURATION} >= ?")
        append(" OR LOWER(COALESCE(${MediaStore.Audio.Media.MIME_TYPE}, '')) IN ($midiMimePlaceholders)")
        append(" OR $midiExtensionSelection")
        append(")")
        append(" AND COALESCE(${MediaStore.Audio.Media.TITLE}, '') != ''")
        append(" AND ${MediaStore.Audio.Media.DATA} IS NOT NULL")
        append(" AND NOT (")
        append("COALESCE(${MediaStore.Audio.Media.IS_MUSIC}, 0) = 0")
        append(" AND (")
        append("COALESCE(${MediaStore.Audio.Media.IS_RINGTONE}, 0) != 0")
        append(" OR COALESCE(${MediaStore.Audio.Media.IS_NOTIFICATION}, 0) != 0")
        append(" OR COALESCE(${MediaStore.Audio.Media.IS_ALARM}, 0) != 0")
        append(")")
        append(")")
    }
    return selection to arrayOf(clampedMinDurationMs.toString()) +
        MIDI_MIME_SELECTION_ARGS +
        MIDI_EXTENSION_SELECTION_ARGS
}
