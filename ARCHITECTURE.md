# Architecture

This document describes how StreamShield is structured, so you can find where a change belongs.
It covers what is stable; details that change often are left to the code.

## Bird's-eye view

StreamShield is a single-activity Android app. The YouTube mobile website runs in **one
WebView** that lives as long as the activity. The app adds features around it in three layers:

1. **Page layer:** a JavaScript file, `app/src/main/assets/shield.js`, injected into every YouTube
   page *before YouTube's own scripts run*. It removes ads from YouTube's data, keeps playback
   going in the background, controls speed and SponsorBlock skipping, and reports playback state.
2. **Network layer:** `WebViewClient.shouldInterceptRequest` refuses ad and tracker requests.
3. **Android layer:** Compose UI, a ViewModel holding app state, and a foreground service with a
   MediaSession for background play. It also handles picture-in-picture and fullscreen.

The app never decodes or downloads video streams itself. YouTube's own player does all playback.

```
 ┌──────────────────────────── MainActivity ─────────────────────────────┐
 │  Compose UI (BrowserScreen, SettingsScreen, PlaybackSpeedSheet)       │
 │        │ reads StateFlows / calls actions                             │
 │        ▼                                                              │
 │  StreamShieldViewModel ──► DataStore prefs, Room (bookmarks, history) │
 │        ▲        │                └► SponsorBlockClient (OkHttp)       │
 │        │        └──► BackgroundPlaybackService (MediaSession, notif.) │
 │        │ onVideoState / onAdBlocked              │                    │
 │  BrowserController ──── owns ────► KeepAliveWebView                   │
 │        │  evaluateJavascript(window.__streamShield.*)  ▲              │
 │        ▼                                               │ PlaybackCommands
 │   shield.js (in page) ── StreamShieldBridge (@JavascriptInterface) ───┘
 └────────────────────────────────────────────────────────────────────────┘
```

## Code map

All Kotlin lives under `app/src/main/java/com/example/` (the namespace is `com.example`; the
application id is `com.aistudio.streamshield.vqxkpl`).

### `MainActivity.kt`
The only activity. It creates the `BrowserController`, hosts the Compose tree
(`StreamShieldApp`) and owns window-level behaviour:
- picture-in-picture: params with the video's aspect ratio, a play/pause `RemoteAction`,
  `setAutoEnterEnabled` on Android 12+ and `onUserLeaveHint` before that;
- keep-screen-on while a video plays;
- notification permission on Android 13+;
- deep links for `youtube.com` / `youtu.be` URLs;
- Back at the root page: moves the task to the background if a video is playing with background
  play on, otherwise finishes.

`configChanges` in the manifest covers rotation, resizing, PiP and dark-mode changes. The activity
is not recreated in those cases, so the WebView keeps playing.

### `browser/BrowserController.kt`
Owns the single `KeepAliveWebView` and everything WebView-specific:
- **Settings:** the user agent is the WebView default with the WebView markers removed, so YouTube
  serves its normal Chrome layout.
- **Script injection:** `shield.js` is registered through
  `WebViewCompat.addDocumentStartJavaScript` for `https://*.youtube.com`. On WebViews without that
  feature it falls back to `evaluateJavascript` in page callbacks.
- **Network filter:** `shouldInterceptRequest` calls `RequestFilter`; main-frame navigations are
  never blocked.
- **Fullscreen:** `onShowCustomView` adds the view to the decor view, hides the system bars, locks
  landscape for landscape videos and registers a Back callback.
- **Bridge and commands:** the `StreamShieldBridge` JavaScript interface receives page events. The
  controller forwards `PlaybackCommands` (notification, lock screen, PiP button) to the page as
  `window.__streamShield.play()/pause()/togglePlay()`.
- **Config push:** observes preferences and PiP state and calls
  `window.__streamShield.setConfig(...)` when they change.

**Rule:** the controller reads settings from the ViewModel *when it needs them*. It never captures
them in a closure; an earlier version did, and toggles silently stopped working.

`KeepAliveWebView` fakes window visibility and focus and ignores `onPause()` while `keepAlive` is
true (background play or PiP), so Chromium keeps decoding media.

### `assets/shield.js`
The page script. It is idempotent: it returns early if `window.__streamShield` exists. Sections:
1. **JSON ad pruning:** wraps `JSON.parse` and `Response.prototype.json`, and puts setters on
   `window.ytInitialPlayerResponse` / `ytInitialData`. It deletes `adPlacements`, `adSlots`,
   `playerAds` and similar keys from player responses, removes ad renderers from
   browse/search/next/Shorts responses, and drops the anti-adblock enforcement dialog. Only objects
   that look like YouTube InnerTube responses (`responseContext`, `playabilityStatus`, …) are walked.
2. **Background play:** overrides `document.hidden` / `visibilityState`, and stops page-level
   `visibilitychange`/`blur`/`pagehide`/`freeze` events with capture listeners registered first.
3. **Cosmetic CSS** for leftover ad slots, plus a PiP layout stylesheet.
4. **Player hooks:**
   - a `MutationObserver` on the player's `class` attribute only; when `ad-showing` appears, it
     mutes the ad, presses skip and seeks to the end, then restores sound and speed;
   - speed enforcement;
   - SponsorBlock segment skipping on `timeupdate`;
   - state reporting (sent only when the state changes);
   - a 1.5 s heartbeat that re-finds the player after single-page navigation.
5. **Native → page API:** `window.__streamShield.setConfig`, `setSponsorSegments`, `play`, `pause`,
   `togglePlay`, `exitFullscreen`, `nudgeLayout`.

Page → native calls go through `window.StreamShieldBridge`: `getConfig()` (called synchronously at
document start), `onAdBlocked(kind)`, `onState(json)`, `onSpeedChanged(rate)` and
`onSponsorSkipped(seconds)`. Bridge methods run on a background thread; the controller posts to the
main thread.

### `engine/`
- `RequestFilter.kt`: pure Kotlin. Matches the host (walking up parent domains in hash sets) and,
  for YouTube/Google hosts, the path (`/pagead/`, `/api/stats/ads`, …). It must stay cheap
  because it runs for every request.
- `AdBlockShieldEngine.kt`: the empty 204 response for blocked requests, cached loading of
  `shield.js`, and the config JSON sent to the page.
- `SponsorBlockClient.kt`: fetches skip segments from `sponsor.ajay.app` and parses them. It sends
  only the video ID.

### `ui/`
- `StreamShieldViewModel.kt`: the single source of app state. It holds:
  - **StateFlows:** preferences, browser state, video state, settings/sheet visibility, PiP and
    block counts.
  - **Video-state handling:** reacts to `onVideoState` from the page by recording history,
    fetching SponsorBlock segments, resetting speed when "lock speed" is off, and keeping the
    background service in sync.
  - **Batched counters:** block counts are counted in memory and flushed to DataStore at most every
    5 s and on `onStop`.
- `screens/BrowserScreen.kt`: omnibox, bookmark chips, the WebView (via `AndroidView`, reusing the
  controller's instance) and the bottom control bar. In PiP only the WebView is shown.
- `screens/SettingsScreen.kt`: slides over the browser so the WebView stays alive underneath.
- `components/PlaybackSpeedSheet.kt`: the speed bottom sheet. The slider commits on release.
- `theme/`: colours, typography and the `MyApplicationTheme` light/dark/AMOLED schemes.

### `service/`
- `BackgroundPlaybackService.kt`: a foreground service (`mediaPlayback` type) with a
  `MediaSessionCompat` and a media-style notification (play/pause, stop). It only mirrors the
  page's state; it does not play anything itself.
  - While playing: foreground plus a partial wake lock.
  - While paused: notification detached, wake lock released.
  - Shuts down on Stop, on task removal, or when the activity finishes.
  - `stop()` goes through `onStartCommand(ACTION_SHUTDOWN)` rather than `stopService()`, to honour
    the `startForegroundService` contract.
- `PlaybackCommands.kt`: a process-wide `SharedFlow` carrying media-control commands from the
  service, MediaSession and PiP receiver to the `BrowserController`.

### `data/`
- `UserPreferencesRepository.kt`: DataStore preferences (`ShieldPreferencesState`). Counter keys
  carry a `_v2` suffix because the v1 values were seeded with fake numbers.
- `AppDatabase.kt`: Room database `streamshield_offline.db`, version 1, with tables `bookmarks`,
  `watch_history` and `downloads`. `downloads` is unused and was kept to avoid a migration.
  `fallbackToDestructiveMigration` is on, so **bumping the version wipes bookmarks and history**
  unless you add a migration.
- `StreamModels.kt`: `WebVideoState`, speed presets, URL and format helpers.

## Key flows

**Opening a video.** YouTube navigates within its single-page app. The document-start script has
already hooked `JSON.parse`/`fetch`, so the player response arrives without ad placements. The
script hooks the `<video>` element and reports `onState`. The ViewModel then fetches SponsorBlock
segments, which the controller pushes into the page, and records history.

**Background play.**
1. A state of `playing=true` makes the ViewModel call `BackgroundPlaybackService.update(...)`
   (`startForegroundService` the first time).
2. When the app is hidden, `KeepAliveWebView` and the visibility overrides in `shield.js` keep
   playback going.
3. Notification and lock-screen buttons go to the service, then to `PlaybackCommands`, then to
   `BrowserController`, then to `__streamShield.play()/pause()`.
4. The page reports the new state, which updates the notification.

**Picture-in-picture.**
1. The activity enters PiP (auto-enter, Home, or the button).
2. `isInPipMode=true` hides the Compose chrome and pushes `pip:true`, which applies the
   full-window player CSS.
3. On exit, the CSS is removed and `nudgeLayout()` fires several `resize` events so YouTube
   re-sizes the player. This fixes a cropped player after PiP.
4. Closing the PiP window (rather than expanding it) pauses the video.

**Fullscreen.** YouTube's fullscreen button calls `requestFullscreen`. WebView then calls
`onShowCustomView`, and the controller shows that view over everything. Back, or YouTube's exit
button, calls `onHideCustomView`.

## Architectural invariants

- Exactly one WebView per activity, created in `MainActivity.onCreate` and destroyed in
  `onDestroy`. Never create WebViews inside composables.
- `shield.js` must stay idempotent and must not throw at document start: `document.head` and even
  `document.documentElement` may not exist yet.
- Native code talks to the page only through `window.__streamShield.*`; the page talks to native
  code only through `StreamShieldBridge`. Keep both surfaces small and update both sides together.
- Hot paths stay cheap. That means `shouldInterceptRequest`, the bridge callbacks and anything in
  `shield.js` that runs per mutation, per request or per `timeupdate`. Do not add document-wide
  `MutationObserver`s or fast `setInterval`s, and do not write to disk per event.
- Counters count events once: one ad prevented, or one request blocked.

## Testing

- `app/src/test/.../ExampleRobolectricTest.kt` covers the request filter, SponsorBlock parsing,
  config JSON, URL/format helpers, the user-agent rewrite and PiP aspect-ratio clamping.
- `tools/shield-js-test/shield.test.mjs` runs `shield.js` in headless Chromium against mock
  YouTube pages. It covers pruning, the ad fallback, background-play events, speed, SponsorBlock,
  PiP layout and parse overhead. It cannot catch YouTube changing its markup or JSON shape, so
  verify on a device after YouTube updates.
