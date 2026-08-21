package com.lostf1sh.pixelplayeross.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Returns true if [table] already has a column named [column].
 *
 * Platform Auto Backup can restore a database that reports the right schema version but whose
 * columns have drifted, so migrations guard each `ALTER` with this check instead of assuming a
 * bare `ADD COLUMN` is safe (see the migration notes in CLAUDE.md / CONTRIBUTING).
 */
private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return false
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
    }
    return false
}

private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, ddl: String) {
    if (!hasColumn(table, column)) {
        execSQL("ALTER TABLE `$table` ADD COLUMN $ddl")
    }
}

/**
 * v1 -> v2: album-artist support for the unified library (issue #8).
 *
 * - `songs.album_artist_id`: id of the *effective* album artist (the song's `album_artist` when
 *   present, otherwise its primary track artist). Source-independent, so the "Group by Album
 *   Artist" Artists tab can collapse on it at runtime without forcing a re-sync.
 * - `navidrome_songs.album_artist` / `jellyfin_songs.album_artist`: carry the server's
 *   album-artist tag through the cloud cache so the unified projection can populate the above.
 *
 * Additive and idempotent — each column is added only when missing. `album_artist_id` is then
 * backfilled to the existing primary artist so the collapsed Artists tab is populated before the
 * next library sync recomputes precise values (e.g. collapsing compilations under one artist).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.addColumnIfMissing("songs", "album_artist_id", "`album_artist_id` INTEGER NOT NULL DEFAULT 0")
        db.addColumnIfMissing("navidrome_songs", "album_artist", "`album_artist` TEXT")
        db.addColumnIfMissing("jellyfin_songs", "album_artist", "`album_artist` TEXT")

        db.execSQL("UPDATE songs SET album_artist_id = artist_id WHERE album_artist_id = 0")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_album_artist_id` ON `songs` (`album_artist_id`)")
    }
}

/**
 * v2 -> v3: opt-in ListenBrainz scrobbling + MusicBrainz identifier storage.
 *
 * - `listenbrainz_pending_listens`: offline scrobble queue. Rows snapshot track metadata at
 *   enqueue time and are deleted on successful submission or permanent rejection.
 * - `songs.mb_recording_id` / `mb_release_id` / `mb_artist_id`: MusicBrainz identifiers, read
 *   from embedded file tags or applied via tag lookup. Scrobbles carry the recording MBID when
 *   known for better server-side matching.
 *
 * Additive and idempotent, per the Auto Backup drift guard above.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `listenbrainz_pending_listens` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `listened_at_ms` INTEGER NOT NULL,
                    `track_name` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `release_name` TEXT,
                    `duration_ms` INTEGER,
                    `recording_mbid` TEXT,
                    `source` TEXT NOT NULL,
                    `attempts` INTEGER NOT NULL DEFAULT 0,
                    `created_at_ms` INTEGER NOT NULL
                )
            """.trimIndent()
        )

        db.addColumnIfMissing("songs", "mb_recording_id", "`mb_recording_id` TEXT")
        db.addColumnIfMissing("songs", "mb_release_id", "`mb_release_id` TEXT")
        db.addColumnIfMissing("songs", "mb_artist_id", "`mb_artist_id` TEXT")
    }
}

/**
 * v3 -> v4: audio bookmarks — named position markers inside a track (audiobooks, DJ sets,
 * podcasts), grouped per song on the Bookmarks screen.
 *
 * `audio_bookmarks` snapshots song metadata at save time so a bookmark stays presentable even
 * if the underlying song leaves the library. Additive and idempotent, per the Auto Backup
 * drift guard above.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `audio_bookmarks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `song_title` TEXT NOT NULL,
                    `artist_name` TEXT NOT NULL,
                    `album_art_uri` TEXT,
                    `title` TEXT NOT NULL,
                    `timestamp_ms` INTEGER NOT NULL,
                    `created_time` INTEGER NOT NULL
                )
            """.trimIndent()
        )
    }
}

/** v4 -> v5: app-private offline copies of Navidrome and Jellyfin tracks. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS `offline_tracks` (
                    `download_id` TEXT NOT NULL,
                    `attempt_id` TEXT NOT NULL,
                    `song_id` TEXT NOT NULL,
                    `source_uri` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `mime_type` TEXT,
                    `local_path` TEXT,
                    `state` TEXT NOT NULL,
                    `bytes_downloaded` INTEGER NOT NULL,
                    `total_bytes` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    `error_message` TEXT,
                    PRIMARY KEY(`download_id`)
                )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_offline_tracks_source_uri` " +
                "ON `offline_tracks` (`source_uri`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_song_id` " +
                "ON `offline_tracks` (`song_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_offline_tracks_state` " +
                "ON `offline_tracks` (`state`)"
        )
    }
}

/** Adds the link between a downloaded file and the YouTube track it came from. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE IF NOT EXISTS downloaded_tracks (
                    video_id TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    album_art_uri TEXT,
                    duration INTEGER NOT NULL DEFAULT 0,
                    media_store_uri TEXT,
                    file_path TEXT,
                    state INTEGER NOT NULL DEFAULT 0,
                    total_bytes INTEGER NOT NULL DEFAULT 0,
                    error_message TEXT,
                    created_at INTEGER NOT NULL,
                    completed_at INTEGER
                )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_downloaded_tracks_state ON downloaded_tracks(state)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_downloaded_tracks_media_store_uri " +
                "ON downloaded_tracks(media_store_uri)"
        )
    }
}

/**
 * Folds the fork's parallel download stack back into the shared one.
 *
 * `downloaded_tracks` belonged to a second, YouTube-only download mechanism that duplicated
 * what `offline_tracks` already did. YouTube is now a provider inside the shared queue, so the
 * table goes and `offline_tracks` gains the one thing it was missing: somewhere to record the
 * MediaStore entry, since those downloads are published to the shared Music folder rather than
 * written into the app's own directory.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE offline_tracks ADD COLUMN media_store_uri TEXT")
        // Needed to build a real folder and embed tags when a download is published outside the
        // app's own directory.
        db.execSQL("ALTER TABLE offline_tracks ADD COLUMN artist TEXT")
        db.execSQL("ALTER TABLE offline_tracks ADD COLUMN album TEXT")
        db.execSQL("ALTER TABLE offline_tracks ADD COLUMN album_art_uri TEXT")
        db.execSQL("DROP TABLE IF EXISTS downloaded_tracks")
    }
}

/**
 * Clears the "YouTube Music" placeholder genre off tracks that were only streamed.
 *
 * The placeholder is gone from the writer, but rows written before that keep it, and one of
 * them is enough to leave the whole bucket standing in the genre list. Restricted to
 * source_type 7 so a downloaded track, which is published as an ordinary local song, is never
 * touched, and to rows the user has not edited themselves.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE songs
            SET genre = NULL
            WHERE genre = 'YouTube Music'
            AND source_type = 7
            AND genre_user_edited = 0
            """.trimIndent()
        )
    }
}
