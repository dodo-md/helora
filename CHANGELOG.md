# Changelog

All notable changes to Helora will be documented in this file.

## [0.2.0] - 2026-08-22

### Important

- The app is now called Helora everywhere and its package identifier changed to
  `com.dodoznq.helora`. Android treats that as a different app, so this release
  **does not install over 0.1.0**. Export a backup from the old build first, install
  this one, restore, then remove the old app. Music already downloaded to
  `Music/Helora` is on shared storage and is visible to both.

### Added

- Downloaded YouTube tracks are tagged with their real genre, looked up on Deezer and
  written into the file itself, instead of landing in a single placeholder bucket. The
  match has to agree on both artist and title before it is believed, because a wrong
  genre is invisible once it is in the tag.
- A setting, off by default, that does the same lookup for streamed tracks.
- A new launcher icon, including a themed-icon layer for Android 13 and later.
- Complete translations for all eleven shipped languages. The newest surfaces, downloads,
  cloud downloads, MusicBrainz lookup, the radio and half the setup flow, had been falling
  back to English on any phone not set to it.

### Changed

- The radio stays on YouTube's own recommendations for far longer. It used to retire a
  station at the first page that returned nothing new, but page yields swing and recover:
  on one station the new tracks per page ran 50, 21, 14, 13, 3, 8, 2, 12, 0, 2, 15, and it
  kept producing to 176 unique tracks. A third of the station was being thrown away.
- Once a mix really is spent, the station now continues from the mix of a track it already
  played rather than searching for more by the same artist, so it keeps moving through
  related music instead of one back catalogue.
- The "YouTube Music" placeholder genre is gone. Genres now come from local and downloaded
  music only, unless the new setting is on.
- Downloads are fetched in one megabyte ranged chunks rather than a single long request.
  Googlevideo throttles an unranged GET down to roughly the track's own bitrate: measured on
  the same file over the same connection, a plain request moved 3,990,497 bytes in 126.5
  seconds while ranged chunks moved them in 1.7.
- The About screen's sponsor card said "Support Helora" while its button opened the upstream
  author's sponsor page. It now says whose work the money reaches, and the repository's own
  sponsor button is gone rather than pointing somewhere it does not belong.
- The in-app changelog listed PixelPlayer's version history, including a 0.2.0 dated a month
  before this one. It now carries Helora's own releases.

### Fixed

- The radio no longer plays the same song twice under a different upload title, such as a
  track and its "official dance video" re-upload.
- The radio no longer stacks several songs by one artist back to back.
- YouTube tracks are filed under the artist rather than the auto-generated
  "Artist - Topic" channel, which was reaching the queue and the now playing screen and
  splitting one artist into two.
- A crash while downloading, and a radio queue that grew without bound.
- Downloaded songs now appear in the library, and liked songs in the listening stats.
- A YouTube track added to a playlist without being downloaded first now appears in it. The
  playlist stored an id with no library row behind it, so the song was dropped on the way to
  the screen with no error anywhere.
- The song sheet drew two Download buttons on every streaming YouTube track, because such a
  track counts as a cloud song and satisfied both conditions.
- Ringtones, notification tones and alarms no longer reach the library. They arrived with
  opaque URIs for titles, a side effect of ignoring the scanner's music flag, which is itself
  deliberate because some devices leave real songs unflagged.
- Download progress stopped short of full and then jumped to complete, because the tail of a
  file never crossed the reporting step.
- Cancelling a download now actually stops it. Every check resolved to the worker's
  dispatcher rather than the running coroutine, and a dispatcher carries no job to cancel.

## [0.1.0] - 2026-08-21

- First release of Helora, a fork of PixelPlayerOSS with YouTube Music added: search, playback,
  radio stations and downloads.

---

## Upstream history

Everything below belongs to PixelPlayerOSS, the project Helora was forked from. Its version
numbers are its own and do not continue into Helora's, which is why a 0.3.0 sits under a 0.2.0
here.

## PixelPlayerOSS 0.3.0 - 2026-08-15

### Added
- Optional ListenBrainz scrobbling, disabled by default. Connect a ListenBrainz account with a user token from the Accounts screen; listens that reach the ListenBrainz threshold (4 minutes or half the track, whichever is lower) queue offline and submit with retry, with per-source toggles for local files, Subsonic, and Jellyfin playback. Now-playing status is reported while scrobbling is enabled, and disconnecting deletes any queued listens. An optional custom server URL scrobbles to self-hosted ListenBrainz-compatible servers such as Maloja instead of listenbrainz.org.
- Offline downloads for Navidrome/Subsonic and Jellyfin tracks, with per-track progress, retry/removal actions, album downloads, app-private storage, and transparent local playback when a download is available, plus a dedicated download management screen.
- On-demand MusicBrainz enrichment with ranked result selection and local recording, release, and artist identifiers. Existing metadata is preserved except for missing or unknown values.
- Translations for twelve languages with an in-app language picker, including Turkish and Simplified Chinese (`zh-rCN`). The selected language now also applies to the login and external player screens.
- Audio bookmarks for saving your place inside long tracks such as mixes, audiobooks, and DJ sets.
- Offline natural-language playlist creation: describe the playlist you want and it is built locally, with no network calls.
- Passphrase-protected backups.
- Search in Settings, so a toggle can be found without digging through categories.
- Tempo-matched crossfades and smoother play/pause transitions.
- Opt-in performance recorder for debugging lag reports.

### Changed
- Listening-stats hour labels follow the system 12/24-hour clock format.
- Cached album art is size-capped and artwork extraction is skipped during library scans, reducing memory use and scan time.
- Alpha releases are skipped for docs-only and CI-only changes; the standalone phone APK workflows were dropped in favour of alpha releases.

### Fixed
- Cloud artwork now reaches media notifications, the lock screen, and external media controllers.
- Offline download cancellation races that could leave partial files or stuck progress.
- Restored queues no longer keep dead local proxy links.
- Cloud streams that cannot be resolved now surface a clear error instead of failing silently.
- Synced embedded lyrics are preferred, and the lyrics API rate limiter no longer throttles valid requests.
- Shuffle changes stay in sync with the media session.
- Folder filters stay in sync with the library, and comma-separated genre tags are split correctly.
- The song info sheet no longer overflows on small screens.
- Bookmark buttons that were still hardcoded English are localized.
- The themed launcher icon is restored.

### Security
- Credentials are redacted from debug network logs.

### Removed
- Android Auto media-library browsing and discovery. Standard MediaSession playback controls for notifications, lock screen, Bluetooth devices, and other system surfaces remain available.

## PixelPlayerOSS 0.2.0 - 2026-07-17

### Added
- Navidrome library selector for servers that expose more than one music library.
- The Artists tab and cloud albums are grouped by album artist.
- Source code and F-Droid links in the About screen, plus GitHub Sponsors metadata.

### Changed
- Material 3 Expressive pass: motion scheme, wavy progress indicators, and shape morphs.
- New app icon and redesigned header.
- Leaner R8 rules and regenerated baseline profiles.
- Coroutines, Flow, and Compose hygiene pass, plus dead-code and deprecated-API cleanup.
- Dependency bumps across Material 3, Compose, OkHttp, core-ktx, and lifecycle.

### Fixed
- Local server connections on Android 17.
- Plain HTTP is allowed for local Navidrome and Jellyfin servers, including Tailscale and VPN hosts.
- GitLab mirror repository guard after the organisation move.

### Security
- User-installed CAs are trusted so self-signed cloud servers work without disabling verification.
- State-changing media session commands are restricted to trusted clients, and artwork sharing stays private behind explicit URI grants.

## PixelPlayerOSS 0.1.0 - 2026-06-09

### Initial release
- First public FOSS release of PixelPlayerOSS, an OSS-focused Android music player.
- Includes local music playback, playlists, favorites, lyrics, listening stats, dynamic Material 3 theming, widgets, and backup/restore.
- Keeps self-hosted library support for Navidrome/Subsonic and Jellyfin, plus optional LRCLIB lyrics and Deezer artist artwork lookups.

### Removed for FOSS
- Removed non-FOSS and Google Play oriented integrations: Telegram, NetEase, QQ Music, Google Drive, Gemini, Cast, Wear OS, Play Store billing, Firebase, Crashlytics, and Google Play Services runtime dependencies.
- Removed public scrobbling integrations such as Last.fm and ListenBrainz; self-hosted Navidrome/Subsonic playback reporting remains scoped to the user's own server.
- Removed bundled translations and the in-app language selector for the first FOSS release; the initial source release ships with English resources only.
- Removed release paths that depended on local/private signing artifacts, dummy signing values, or app-store-only assumptions.

### Release readiness
- Added F-Droid metadata, Fastlane store metadata, dependency/license documentation, privacy notes, security notes, and contributor guidance.
- Release builds now stay unsigned when local signing keys are absent, and `pixelplayer.disableReleaseSigning=true` forces unsigned verification builds even on a maintainer machine.
- Documented third-party asset and dependency licenses, including native/binary Maven artifacts and JitPack source trails.

### Security and privacy
- The loopback cloud-stream proxy now requires a per-session token so other apps on the device cannot stream the user's cloud library by guessing local proxy URLs.
- Backup restore now ignores preference keys owned by dedicated module handlers, preventing crafted global-settings payloads from bypassing module validation.
- Release logging is tightened so HTTP request headers and remaining raw Android logs do not bypass the Timber release filter.

### App polish included in this FOSS release
- Added smart playlist persistence, duplicate-track scanning, playback speed control, clearer playback/sync failure messages, and retry actions on album/artist detail failures.
- Improved accessibility for toggle states and song row actions.
