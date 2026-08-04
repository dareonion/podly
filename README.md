# Podly

A personal Android podcast player with Android Auto support, built with Kotlin,
Jetpack Compose, and Media3.

## Install

Grab the latest debug APK — rebuilt automatically on every push to `main`:

**[podly-debug.apk](https://github.com/dareonion/podly/releases/latest/download/podly-debug.apk)**

Needs Android 10+. It's a debug-signed sideload, so allow "Install unknown
apps" for your browser; for Android Auto, sideloaded apps only appear after
enabling Developer settings → "Unknown sources" in the Android Auto app on the
phone.

## Features

- Subscribe via iTunes search or by pasting an RSS feed URL; OPML import/export
- Downloads with policies: Wi-Fi-only, auto-download the N newest episodes per
  show, auto-delete played downloads
- Streaming with a 1 GB on-disk cache; every episode resumes where you left off
- Playlists with manual (drag-to-reorder) or chronological ordering
- Android Auto: Continue / Playlists / Library / Downloads browse tree, with
  steering-wheel next/previous remapped to in-episode skips
- Listening history and stats
- Discover: Apple charts, PodcastIndex trending, and AI-generated picks
  (acclaimed shows and best-recent-episode lists, pre-generated weekly in CI
  and served as static JSON from GitHub Pages — no AI key needed on-device)
- Optional API keys, entered in Settings: PodcastIndex or Taddy unlock trending
  time windows and an episode-archive fallback for shows whose feeds only
  expose their newest episodes; an Anthropic or OpenAI key enables on-device
  personalized recommendations

## Build

Two modules: `app` (the Android app) and `generator` (JVM-only; produces the
recommendation JSON in CI).

Requires JDK 21 — if your default differs, set `org.gradle.java.home` in
`~/.gradle/gradle.properties` (not the tracked `gradle.properties`).

```sh
./gradlew assembleDebug          # build the APK
./gradlew testDebugUnitTest      # app unit tests
./gradlew :generator:test        # generator tests
./gradlew lintDebug              # Android Lint (baseline: app/lint-baseline.xml)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI builds, lints, and tests every push; pushes to `main` also refresh the
rolling [`latest` release](https://github.com/dareonion/podly/releases/latest).
