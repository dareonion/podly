# Repository Guidelines

## Project Structure & Module Organization

Podly is a single-module Android app in `app/`, written in Kotlin with Jetpack Compose, Media3, Room, WorkManager, and DataStore. App source lives under `app/src/main/java/com/podly/`: `ui/` contains Compose screens and view-model helpers, `data/` contains repositories and Room database code, `network/` contains OkHttp-based APIs and RSS parsing, `playback/` contains Media3 service/controller code, `downloads/` contains download workers, and `work/` contains background refresh work. Android resources are in `app/src/main/res/`. JVM unit tests are in `app/src/test/java/com/podly/`.

## Build, Test, and Development Commands

Gradle expects Java 17; on this machine use Android Studio's bundled JDK:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew testDebugUnitTest --tests "com.podly.RssParserTest"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`assembleDebug` builds the debug APK. `testDebugUnitTest` runs JVM unit tests. The `--tests` form runs one test class. The `adb install` command sideloads the built APK to a connected device or emulator.

## Coding Style & Naming Conventions

Use Kotlin style consistent with the existing code: 4-space indentation, trailing commas in multiline declarations where already used, and clear package-level organization by feature. Compose UI functions and screens use PascalCase names such as `LibraryScreen`; repositories, workers, and services use descriptive suffixes such as `PodcastRepository`, `FeedRefreshWorker`, and `PlaybackService`. Prefer existing helpers such as `Context.appGraph` and `appViewModel { ... }` over adding a new dependency injection pattern.

## Testing Guidelines

Tests use JUnit under `app/src/test/java/com/podly/` and follow the `*Test.kt` naming pattern. Keep parsing and business logic JVM-testable when practical; for example, `RssParser` uses `XmlPullParserFactory` instead of Android-only XML APIs. Add focused tests for repository sorting, parser changes, auth/header logic, and AI response parsing. Run `./gradlew testDebugUnitTest` before submitting changes.

## Commit & Pull Request Guidelines

Recent commits use short, imperative subject lines, for example `Add playback resumption, continue listening, and resolved AI picks` and `Avoid layout shift when opening podcasts`. Keep commits focused on one behavior change. Pull requests should describe the user-visible change, list tests run, mention Android Auto or playback impact when relevant, and include screenshots for UI changes.

## Security & Configuration Tips

Do not commit local secrets or signing material. Release signing is read from `local.properties` keys such as `PODLY_RELEASE_STORE_FILE` and passwords; API keys are user settings, not source constants. Keep the existing packaging excludes in `app/build.gradle.kts` because the Anthropic SDK introduces META-INF conflicts.
