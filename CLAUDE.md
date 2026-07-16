# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Podly — a native Android podcast player (Kotlin, Jetpack Compose, Media3) with Android Auto support. Two modules: the `app` Android module (package `com.podly`) and a JVM-only `generator` module that pre-computes AI recommendation JSON in CI (published to GitHub Pages at dareonion.github.io/podly; the app just downloads the static files).

## Commands

Gradle needs JDK 21. Machine-specific JDK paths live in `~/.gradle/gradle.properties` (`org.gradle.java.home`) — never in the tracked `gradle.properties`:

- Mac: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` (or set the user-level property once)
- Linux box: already configured (Temurin 21); plain `./gradlew` works

```sh
./gradlew assembleDebug          # build debug APK
./gradlew testDebugUnitTest      # run JVM unit tests
./gradlew testDebugUnitTest --tests "com.podly.RssParserTest"   # single test class
./gradlew :generator:test        # generator module tests
adb install -r app/build/outputs/apk/debug/app-debug.apk        # install to device
```

CI: `.github/workflows/android.yml` runs the build plus all unit tests on every push/PR to main; pushes to main also publish the debug APK to the rolling `latest` GitHub release (stable URL: `releases/latest/download/podly-debug.apk`). `recommendations.yml` regenerates the AI picks JSON weekly (Mondays 08:00 UTC) and deploys it to GitHub Pages.

## Architecture

- **DI**: hand-rolled — `AppGraph` (one instance on `PodlyApp`); access from composables/services via `Context.appGraph`. ViewModels are created with the `appViewModel { ... }` helper in `ui/AppViewModel.kt`. `AppGraph.messages` (`UiMessages`) is an app-wide one-shot snackbar channel rendered by `MainActivity`; `EpisodeActions` reports its failures through it — don't add silent fire-and-forget launches.
- **Data** (`data/`): Room database (`PodlyDatabase`, version 6, `exportSchema = true`) with `podcasts`, `episodes`, `playlists`, `playlist_items`, `listening_segments` tables. "Library" = episodes of subscribed podcasts + individually saved episodes (`inLibrary`). Feed refresh uses conditional GET (per-podcast `etag`/`lastModified`, 304 short-circuits) and runs 4 feeds concurrently; episode rows are upserted via `EpisodeDao.upsertFromFeed` — insert with `OnConflictStrategy.IGNORE` plus a metadata-only UPDATE, so download/progress/library state is never clobbered and `pubDateMs` never churns (undated episodes get a first-seen timestamp). Settings live in Preferences DataStore (`SettingsRepository`).
- **Network** (`network/`): plain OkHttp via the `Http` singleton (no Retrofit); `http://` URLs are retried as `https://` first (Android blocks cleartext, and iTunes still returns http feed URLs). iTunes Search (keyless) for search — pasting a feed URL into Discover search offers it as a direct subscribe result. `RssParser` (XmlPullParser, JVM-testable) for episodes, Apple Marketing Tools JSON for "popular now", PodcastIndex trending (user-supplied key/secret; SHA-1 auth header in `PodcastIndexApi.authHash`). The picks-playlist importer (`PicksImporter`, Settings → Import picks JSON) falls back to PodcastIndex's episode archive for picks that have rolled off a short feed window, inserting the episode row directly. AI recommendations (`network/ai/`) use the official Anthropic Java SDK (`claude-opus-4-8`) or OpenAI via raw HTTP; keys come from Settings. Acclaimed/recent-episode lists come pre-generated from `RemoteRecsApi` + `AiPicksCache` (cached with `generatedAtMs` shown in the UI).
- **Playback** (`playback/`): `PlaybackService` is a Media3 `MediaLibraryService` used by both the in-app UI and Android Auto. The Android Auto browse tree (Continue/Playlists/Library/Downloads) is served from Room in `LibraryCallback`, every list capped at 100. **Key behavior**: `NudgingPlayer` (a `ForwardingPlayer`) remaps `seekToNext()`/`seekToPrevious()` (car/headset buttons) to in-episode nudges, but leaves `seekToNext/PreviousMediaItem` as real queue skips — the in-app skip buttons and Up Next sheet rely on that split. Nudge increments are mutable fields fed live from Settings (no service restart). Queue transitions (skip/jump/auto-advance) restore each episode's saved position; a `pendingResumeEpisodeId` guard stops the 5-second progress saver from clobbering it. Media IDs use the `ep/<id>` / `playlist/<id>` scheme in `MediaIds`. UI talks to the service through `PlayerConnection` (MediaController wrapper exposing a `StateFlow` incl. queue + player error).
- **Downloads** (`downloads/`): WorkManager `CoroutineWorker` streams enclosures to `filesDir/episodes/`; status tracked on the episode row. Policies in Settings: Wi-Fi-only constraint, auto-download N newest per subscribed show, auto-delete played downloads — applied by `Downloader.applyPolicies()` after every refresh. `episodes.autoDownloadBlocked` marks manually removed downloads so auto-download won't re-fetch them (cleared by an explicit re-download). Playback prefers the local file (`MediaItemFactory.localUriOrNull`).
- **Streaming cache**: `MediaCache` is a process-wide `SimpleCache` singleton (1 GB LRU in `cacheDir/media`) wrapped around the OkHttp data source in `PlaybackService`; never construct a second instance on the same directory. Downloads (file:// URIs) bypass it.
- **Playlists**: per-playlist `sortMode` (MANUAL / CHRONO_ASC / CHRONO_DESC). Chrono sorts are applied in `PlaylistRepository.applySort`; manual order is `playlist_items.manualPosition`, edited via drag handles (`sh.calvin.reorderable`) only when in MANUAL mode. AI-saved playlists are named with the picks' generation date.

## Gotchas

- The Anthropic SDK pulls Apache HttpComponents; the `packaging { resources.excludes }` block in `app/build.gradle.kts` resolves META-INF merge conflicts — keep it.
- `RssParser` deliberately uses `XmlPullParserFactory` (not `android.util.Xml`) so unit tests run on the JVM (kxml2 is a test dependency).
- Room schema export is on: bumping the DB version regenerates `app/schemas/.../<N>.json` on the next build — commit it. Hand-written migration index names must match Room's generated names (`index_<table>_<column>`).
- API keys are deliberately stored unencrypted in DataStore (androidx security-crypto is deprecated); the mitigation is the backup rules in `res/xml/` that exclude the settings DataStore and downloaded audio from cloud backups. Keep those rules in sync if DataStore file names change.
- `gradle.properties` is machine-neutral (configuration cache enabled). Don't re-add `org.gradle.java.home` there.
- Sideloaded builds only appear in Android Auto after enabling Developer settings → "Unknown sources" in the Android Auto app on the phone.
