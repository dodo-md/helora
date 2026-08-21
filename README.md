<p align="center">
  <img src="assets/icon.png" alt="Helora" width="120"/>
</p>

<h1 align="center">Helora</h1>

<p align="center">
  A music player for Android that plays <b>your files</b> and <b>YouTube Music</b>, without an account, ads, or tracking.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="GPLv3">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin + Compose">
</p>

<p align="center">
  <img src="assets/screenshot1.jpeg" width="205"/>
  <img src="assets/screenshot2.jpeg" width="205"/>
  <img src="assets/screenshot3.jpeg" width="205"/>
  <img src="assets/screenshot4.jpeg" width="205"/>
</p>

---

## What is this?

Helora is a fork of [**PixelPlayerOSS**](https://github.com/PixelPlayerHQ/PixelPlayerOSS) by [@lostf1sh](https://github.com/lostf1sh) (a genuinely nice offline music player) with one big thing bolted on: **YouTube Music**.

So you get both halves. Your own MP3s and FLACs, scanned from the device like any local player. And a search box that reaches into YouTube Music, plays anything, builds radio stations, and saves tracks to your phone for offline listening.

No login. No API key. Nothing to sign up for.

Everything upstream does (Navidrome, Jellyfin, lyrics, tag editing, widgets, backup) still works exactly as before. This fork adds, it doesn't remove.

---

## For users

### Playing your own music

Same as always: drop files on your phone, grant the audio permission, and they show up. MP3, FLAC, AAC, OGG, WAV, M4A. Albums, artists, genres, folders, playlists, favourites, lyrics, tag editing, sleep timer, crossfade, widgets.

### Playing from YouTube Music

Open **Search**, type something, tap the **YouTube Music** chip. You get songs, albums and artists.

Tap a song and it plays. Nothing else gets queued behind it. That matters, because if you search "snap" you want *that* song, not fourteen other songs also called "snap".

### Radio

When you tap a YouTube track, a **radio station** starts behind it: more songs like that one, forever. It's the real YouTube Music mix (`RDAMVM`), not the "related videos" sidebar, so you get an actual station rather than re-uploads of the same track.

Seed Creep and you get Yellow, Wonderwall, Iris, No Surprises, Nothing Else Matters. That kind of thing.

The station **stays pinned to the song you picked** rather than drifting with whatever's playing now, because "songs like X" should keep meaning X.

You can also start a station from **any song in your own library**: song menu → **Start radio**. It finds the track on YouTube and builds a station from it. It's deliberately picky: artist *and* title *and* duration have to line up, otherwise it tells you it couldn't find a match instead of seeding a station from some random cover.

### Downloads

Song menu → **Download**. Or grab a whole album, a playlist, or all your liked songs at once.

Downloads share the same queue as the Navidrome and Jellyfin ones, so they show up in the same place and behave the same way. YouTube tracks differ in where the finished file lands: **`Music/Helora/<Artist>/<Album>/`**, as proper `.m4a` files with tags and cover art embedded. That means:

- Your other music apps and your file manager can see them
- They survive uninstalling Helora
- Helora's own library scan picks them up as ordinary local songs, so they work offline like any other file

Downloads go over Wi-Fi only by default. Flip that in **Settings → Downloads**, where you can also watch the queue, retry failures, and delete things.

> M4A is picked over the slightly-higher-bitrate WebM/Opus on purpose. Opus in a `.webm` container confuses a lot of car stereos and third-party players, and these files land in your public Music folder where other things have to open them.

### Heads up

- **YouTube extraction breaks sometimes.** It works by scraping, not by an official API. When YouTube changes something, search and playback can break until [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) ships a fix and this app bumps the dependency. That's the deal with this approach.
- **Downloading from YouTube is against YouTube's Terms of Service.** This is a personal-use app; what you do with it is on you.
- **Artist pages are partial.** YouTube Music search returns auto-generated "Topic" channels, which expose no album list at all. You get the artist's tracks, but the albums section will be empty for most artists.
- **Releases here are unsigned.** There's no signing config in the repo, so `assembleRelease` produces an unsigned APK. See below.

---

## For developers

### Build it

Needs **JDK 21** and **Android SDK 37**. Android 11 (API 30) minimum at runtime.

```sh
git clone https://github.com/dodo-md/helora.git
cd helora

# debug APK
./gradlew :app:assembleDebug

# one universal APK instead of per-ABI splits
./gradlew :app:assembleDebug -Ppixelplayer.enableAbiSplits=false

# unit tests
./gradlew :app:testDebugUnitTest
```

If Gradle can't find your SDK, either export `ANDROID_HOME` or drop a `local.properties` with `sdk.dir=...`.

Release builds come out **unsigned**. To install one locally, sign it with your debug key:

```sh
./gradlew :app:assembleRelease
zipalign -f -p 4 app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk aligned.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey --out helora.apk aligned.apk
adb install -r helora.apk
```

**Test release builds, not just debug.** R8 is where the YouTube side breaks, and it breaks silently. See below.

### How the YouTube part works

```
app/src/main/java/com/lostf1sh/pixelplayeross/
├── data/youtube/
│   ├── YouTubeMusicRepository   search, streams, mixes, artist/album lookup, matching
│   ├── NewPipeOkHttpDownloader  NewPipe's Downloader over the shared OkHttp client
│   ├── YouTubeStreamProxy       CloudStreamProxy subclass, scheme "ytmusic"
│   ├── YouTubeIds               deterministic negative ids for ephemeral tracks
│   ├── RemoteTrackCache         in-memory home for tracks not in the database
│   └── YouTubeLibraryWriter     promotes a saved track into the library
├── data/download/               publishes finished downloads into the Music folder
├── data/service/player/
│   └── RadioQueueExtender       keeps the station topped up
└── presentation/screens/search/ the YouTube section of the search screen
```

A YouTube track is a `Song` whose `contentUriString` is `ytmusic://<videoId>`. At playback time that's resolved to a **localhost HTTP proxy** which streams the real audio through, exactly how Navidrome and Jellyfin already worked. `YouTubeStreamProxy` just fills in the blanks of the existing `CloudStreamProxy`.

Search results are **ephemeral**: they never touch Room. A track only earns a database row when you favourite it or add it to a playlist. Because `YouTubeIds` hands out deterministic ids up front, that promotion is a plain upsert and any favourite you saved beforehand already points at the right row.

### Things that will bite you

Four traps, all of which cost real debugging time:

**1. Rhino must survive R8.** NewPipe runs YouTube's player JavaScript through Rhino to deobfuscate stream signatures. Anything less than a full keep gives you streams that work in debug and 403 in release. The rules are in `proguard-rules.pro`; to check they held:

```sh
unzip -p app/.../release.apk classes4.dex | strings | grep -c org/mozilla/javascript
```

**2. Resolution must happen eagerly.** The `ResolvingDataSource` in `DualPlayerEngine` is synchronous and cache-only: it *cannot* resolve. If a `ytmusic://` URI reaches ExoPlayer unresolved, it just fails. The gate is `buildResolvedPlaybackMediaItem` in `PlayerViewModel`, keyed off `CloudStreamSchemes.PROXIED`. Adding a source means adding it there.

**3. Flush the proxy.** `CloudStreamProxy` must `flush()` after each chunk. Without it the opening bytes sit in the response buffer while the player waits for its first byte range, and playback hangs on "loading" instead of failing, which looks like a stall, not an error.

**4. Test methods can't return a value.** A test written as ``fun `x`() = runBlocking { ... }`` whose last expression isn't `Unit` is silently *not discovered* by JUnit: no failure, it just never runs. Use `runBlocking<Unit>`.

### Contributing

PRs welcome. Before opening one:

```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Note that the test suite has **12 failures inherited from upstream**. They fail on a clean checkout too. If your count is 12, you're fine; if it's 13, look at what you did.

---

## Credit

Almost all of this app is [**PixelPlayerOSS**](https://github.com/PixelPlayerHQ/PixelPlayerOSS) by [@lostf1sh](https://github.com/lostf1sh) and its contributors. The player engine, library, UI, self-hosted integrations and general polish are theirs. If you like this, go star the upstream project, and consider [sponsoring them](https://github.com/sponsors/lostf1sh).

YouTube extraction is [**NewPipeExtractor**](https://github.com/TeamNewPipe/NewPipeExtractor) (GPLv3) by Team NewPipe.

## License

GPL-3.0-or-later, same as upstream. See [LICENSE](LICENSE).

```
Helora, a fork of PixelPlayerOSS
Copyright (C) 2026 PixelPlayerOSS contributors and Helora contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) · Dependency licences: [docs/DEPENDENCY_LICENSES.md](docs/DEPENDENCY_LICENSES.md)
