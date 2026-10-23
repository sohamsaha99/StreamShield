# AGENTS.md

Working notes for AI coding agents and contributors. Read [ARCHITECTURE.md](ARCHITECTURE.md)
first for the component map; this file covers commands, conventions and pitfalls.

## Project at a glance

- Android app, Kotlin + Jetpack Compose, single activity (`MainActivity`), package `com.example`.
- YouTube runs in one WebView owned by `browser/BrowserController.kt`. Ad blocking and player
  control are done by `app/src/main/assets/shield.js`, injected at document start.
- State lives in `ui/StreamShieldViewModel.kt` (StateFlows); preferences are in DataStore, and
  bookmarks and history in Room.
- Personal-use app: favour robustness and low battery and CPU use over feature breadth.

## Commands

```sh
./gradlew assembleDebug                 # build
./gradlew testDebugUnitTest             # Robolectric/JVM unit tests
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs   # shield.js browser tests
node --check app/src/main/assets/shield.js                          # quick syntax check
```

- There is no `gradlew` script checked in. Generate it with `gradle wrapper` (Gradle 9.3.1) or use
  Android Studio.
- The build needs Google Maven (`dl.google.com`). In sandboxes without it, AndroidX and AGP cannot
  be resolved. In that case, at minimum run the shield.js tests, and compile the pure-Kotlin files
  (`engine/RequestFilter.kt`, `engine/SponsorBlockClient.kt`) in a plain Kotlin/JVM project.
- The shield.js tests need the `playwright` npm package, Chromium and `ffmpeg` (used to generate a
  test clip).

## Conventions

- Match the surrounding style: four-space indent, KDoc on classes and non-obvious functions, and
  short comments that explain *why*.
- **Read settings live.** Inside WebView callbacks, bridge methods and service code, read
  `viewModel.preferences.value` at call time. Never capture preference values in lambdas created
  once (factory blocks, clients).
- **Main thread.** `@JavascriptInterface` methods run on a background thread: only read StateFlow
  values or parse JSON there, and `post` everything else to the main thread.
- **Bridge changes.** When you add a native → page call, add it to `window.__streamShield` in
  `shield.js` *and* a test in `tools/shield-js-test/shield.test.mjs`. When you add a page → native
  call, add a `@JavascriptInterface` method in `BrowserController.PageBridge`.
- **Batching.** Counters and other high-frequency events are batched in memory; do not write
  DataStore or Room per event.
- **Preferences.** New preferences go in `ShieldPreferencesState` + `UserPreferencesRepository`.
  Toggles are exposed as `toggleX(enabled)` on the ViewModel.
- **Database.** Bumping the Room version wipes user data (`fallbackToDestructiveMigration`). Add a
  `Migration` instead.
- **Gradle.** Keep the `signingConfigs` blocks in `app/build.gradle.kts` unchanged; AI Studio
  needs them. Unused dependencies are commented out rather than deleted.

## Pitfalls (learned the hard way)

- **Injection timing.** Injecting `shield.js` from `onPageStarted`/`onPageFinished` is too late:
  YouTube has already read its ad schedule, so ads start and must be skipped (a 3–4 s delay).
  Keep the document-start registration.
- **Ad-state detection.** Do not watch the whole document with a `MutationObserver`, or poll
  quickly, to detect ads. YouTube mutates the DOM constantly; the old version burned CPU and
  reported hundreds of "ads blocked" for a single ad. Observe only `#movie_player`'s `class`.
- **Restore after a skipped ad.** Anything changed to skip an ad (muted, `playbackRate`) must be
  restored when `ad-showing` goes away.
- **Element blur.** Stop `blur` only when the target is `window`/`document`; element blur events
  (e.g. the search box) must still reach the page.
- **Foreground service rules.** Android 12+ forbids starting foreground services from the
  background. The ViewModel uses `startForegroundService` only for the first start; later updates
  use `startService`. Stopping goes through `ACTION_SHUTDOWN` so a just-started service can still
  call `startForeground` (Android 9–11 crash otherwise).
- **Layout after PiP/fullscreen.** YouTube sizes its player in JavaScript. After PiP or fullscreen
  transitions, call `nudgeLayout()` or the player stays cropped.
- **Downloads.** Downloading needs a real YouTube stream extractor. Do not reintroduce
  placeholder or sample-video fallbacks.

## Verifying changes

1. `./gradlew testDebugUnitTest` and the shield.js tests pass.
2. For page-script or WebView changes, check on a device:
   - a pre-roll ad does not appear and the video starts immediately;
   - feed and Shorts ads are hidden;
   - fullscreen enters and exits;
   - PiP enters on Home, and the player is not cropped after expanding;
   - lock-screen play/pause works;
   - closing the PiP window pauses.
