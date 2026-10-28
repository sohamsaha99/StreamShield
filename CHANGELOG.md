# Changelog

Releases are published from version tags: <https://github.com/streamshield-app/StreamShield/releases>.

## Unreleased

- Ad blocking: if YouTube re-created its player while an ad was showing, the ad skipper kept
  running and skipped and muted the following videos; it now restores sound and speed and stops.
- Autoplay: a pause from the notification or lock screen after a video ended on screen no longer
  lets the app open the next video a few seconds later.
- Library and downloads: subscriptions no longer refresh in full on every start when one channel's
  feed keeps failing; backup and Takeout imports run in one transaction (all or nothing, faster);
  backups include "Show start time"; a download deleted while it was being saved no longer leaves
  its file behind; smart downloads are no longer scheduled when turned off, and finished songs
  whose file is gone are cleaned up; play-progress writes no longer race.
- Build: unit tests run before an APK is published; GitHub Actions updated to their Node 24
  versions.
- Clean-up: unused code, resources (about 1.2 MB of images), dependencies and the template unit
  test removed; documentation brought up to date.

## 1.0.0 (2026-10-28)

The first release from [streamshield-app/StreamShield](https://github.com/streamshield-app/StreamShield):
the original StreamShield merged with [somak135/StreamShieldv2](https://github.com/somak135/StreamShieldv2),
a fork started from the original's commit `574d020` ("Stop PiP stutter"). Everything from the
original is kept except the address bar and the bookmarks screen.

- Autoplay while hidden: the fork's version is kept (it covers YouTube Music and diagnostics).
  The original's "Autoplay next video" setting is folded into **Autoplay**; a value saved under
  its old key (`auto_advance`) is used until Autoplay is changed.
- From the original's later commits (`84418cd` to `a6e2bc8`): live streams keep loading in PiP
  (`resyncPlayer()`), the PiP stall counter in Diagnostics, WebView debugging in debug builds, the
  Docker dev environment, `docs/LOCAL_TESTING.md` and `tools/device-probe`.
- The application id is the original's `com.aistudio.streamshield.vqxkpl`, so the app updates an
  install of the original in place (the database migrations keep its watch history).

What the fork added:

### Navigation and screens

- **YouTube-app layout:** native tabs Home · Music · Subscriptions · Library, shown on every
  browsing page. A video fills the screen with the app's player controls (Minimize, Save,
  Download, speed, Pop out, Settings) and minimizes to a mini-player.
- **Second WebView (browse page)** for search results, channels and playlists: tapping a video
  there plays it in the player page, and Back returns to the same results with the video still
  playing. Searches typed into YouTube's own search box open there too.
- The YouTube logo opens the app's Home; m.youtube's own bottom bar is hidden; no Shorts tab.
- **Transitions:** the player slides up from the mini-player and back down; while a video loads,
  its thumbnail and title cover the page; deeper screens slide in, tabs cross-fade; predictive
  Back on Android 14+; a short fade while YouTube swaps pages.
- **Pull down to refresh** on every page; on a video page, pull down to minimize.
- Links opened with the app or shared to it ("Share → StreamShield"), including pasted links in
  the search box.
- Removed: the address bar and the bookmarks screen (bookmark data stays in the database and in
  backups).

### Library (stored on the phone, no account)

- History with resume (Continue watching, Recently watched), Watch later, Liked videos,
  playlists, "Not interested", search history.
- **Subscriptions** from each channel's RSS feed; import from Google Takeout
  (`subscriptions.csv`); YouTube's own Subscribe buttons subscribe in the app; starred channels.
- **Home → For you:** new uploads from subscriptions mixed 50/50 with videos related to what you
  watch (`recommend/ForYouRule.kt`, reviewed rule).
- **Backups:** export/import of the library and settings as one file, an optional daily backup
  into a folder you pick, and import of YouTube watch history from Google Takeout.

### Music

- YouTube Music in the Music tab, with the app's tabs under it; songs keep playing in the
  background but keep neither the screen on nor open picture-in-picture.
- Pull-to-refresh in the Music tab restarts the song that was playing from the same point.

### Downloads

- **Downloads of any video or song** (NewPipe Extractor): a quality sheet (remembered per kind),
  audio-only for songs by default, H.264+AAC → MP4 or VP9+Opus → WebM, merged on the phone, saved
  to `Movies/StreamShield` and `Music/StreamShield` (Android 10+), with a progress notification
  and retries.
- An **offline player** (Media3 ExoPlayer) with the same mini-player, notification, lock-screen
  controls and picture-in-picture as online playback.
- **Smart downloads (music):** a chosen amount (default 1 GB) of songs you listen to plus new
  ones from YouTube's related lists, refreshed daily on Wi-Fi while charging
  (`download/SmartRule.kt`, reviewed rule); kept inside the app.

### Playback and ad blocking

- Faster starts: pruning also covers batched/wrapped responses and XHR JSON, shortens YouTube's
  17 s player timers, and removes the "Watch in the YouTube app" dialog that held back autoplay.
- **Autoplay with the screen off and in picture-in-picture**, for videos and YouTube Music: the
  page moves on itself when YouTube's countdown cannot run, and the notification stays in the
  foreground between two videos so Android does not suspend the app in the gap. Mixes,
  playlists and the Music queue always continue; suggestions follow the Autoplay setting.
  (The video part follows the original's commits `84418cd` and `d4e373e`.)
- **No muted starts:** videos YouTube starts muted (the first one after opening the app, links
  opened in the app, autoplay with the screen off) play with sound; a mute chosen on the page
  is kept.
- Taps on YouTube's play/pause that the page missed are applied by the app and counted in
  Settings → Diagnostics; the page's PiP flag is checked against the app's.
- Battery: the player page is kept awake in the background only while something is loaded; the
  page script's heartbeat slows down in the background; the browse page pauses when hidden; only
  videos (not songs) keep the screen on or enter picture-in-picture.

### Technical

- New launcher icon (red shield with a play symbol), including a themed-icon layer for
  Android 13+; built from `tools/icons/streamshield-logo.png` by `tools/icons/make_icons.py`.
- Room database version 4 with migrations from version 1, so an install of the original app keeps
  its watch history if it is updated to this version (same application id and signing key
  required).
- Java 17 and core library desugaring (NewPipe Extractor); JitPack repository; new dependencies:
  WorkManager, Media3 ExoPlayer/UI, NewPipe Extractor; Coil enabled.
- GitHub Actions also publishes each `main` build as the `latest` release.
- Tests: `LibraryTest`, `SmartRuleTest`, `ForYouRuleTest`, and many new shield.js tests (routing,
  heartbeat, tap fixes, Subscribe buttons, pull-down, fade, resume).
