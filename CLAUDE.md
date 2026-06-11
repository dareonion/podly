# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Podly — a native Android podcast player (Kotlin, Jetpack Compose, Media3) with Android Auto support. Single `app` module, package `com.podly`.

## Commands

Gradle needs `JAVA_HOME` pointed at Android Studio's bundled JDK:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug          # build debug APK
./gradlew testDebugUnitTest      # run JVM unit tests
./gradlew testDebugUnitTest --tests "com.podly.RssParserTest"   # single test class
adb install -r app/build/outputs/apk/debug/app-debug.apk        # install to device
```

## Architecture

- **DI**: hand-rolled — `AppGraph` (one instance on `PodlyApp`); access from composables/services via `Context.appGraph`. ViewModels are created with the `appViewModel { ... }` helper in `ui/AppViewModel.kt`.
- **Data** (`data/`): Room database (`PodlyDatabase`) with `podcasts`, `episodes`, `playlists`, `playlist_items` tables. "Library" = episodes of subscribed podcasts + individually saved episodes (`inLibrary`). Feed refresh inserts with `OnConflictStrategy.IGNORE` so it never clobbers download/progress state. Settings live in Preferences DataStore (`SettingsRepository`).
- **Network** (`network/`): plain OkHttp via the `Http` singleton (no Retrofit). iTunes Search (keyless) for search, `RssParser` (XmlPullParser, JVM-testable) for episodes, Apple Marketing Tools JSON for "popular now", PodcastIndex trending (user-supplied key/secret; SHA-1 auth header in `PodcastIndexApi.authHash`). AI recommendations (`network/ai/`) use the official Anthropic Java SDK (`claude-opus-4-8`) or OpenAI via raw HTTP; keys come from Settings.
- **Playback** (`playback/`): `PlaybackService` is a Media3 `MediaLibraryService` used by both the in-app UI and Android Auto. The Android Auto browse tree (Playlists/Library/Downloads) is served from Room in `LibraryCallback`. **Key behavior**: `NudgingPlayer` (a `ForwardingPlayer`) remaps next/previous commands to `seekForward()`/`seekBack()` so car buttons jump within the episode; increments come from Settings and only apply when the service is (re)created. Media IDs use the `ep/<id>` / `playlist/<id>` scheme in `MediaIds`. UI talks to the service through `PlayerConnection` (MediaController wrapper exposing a `StateFlow`).
- **Downloads** (`downloads/`): WorkManager `CoroutineWorker` streams enclosures to `filesDir/episodes/`; status tracked on the episode row. Playback prefers the local file (`MediaItemFactory.localUriOrNull`).
- **Playlists**: per-playlist `sortMode` (MANUAL / CHRONO_ASC / CHRONO_DESC). Chrono sorts are applied in `PlaylistRepository.applySort`; manual order is `playlist_items.manualPosition`, edited via drag handles (`sh.calvin.reorderable`) only when in MANUAL mode.

## Gotchas

- The Anthropic SDK pulls Apache HttpComponents; the `packaging { resources.excludes }` block in `app/build.gradle.kts` resolves META-INF merge conflicts — keep it.
- `RssParser` deliberately uses `XmlPullParserFactory` (not `android.util.Xml`) so unit tests run on the JVM (kxml2 is a test dependency).
- Sideloaded builds only appear in Android Auto after enabling Developer settings → "Unknown sources" in the Android Auto app on the phone.
