# Changelog

Changes in this fork ([somak135/StreamShieldv2](https://github.com/somak135/StreamShieldv2))
compared with the original [StreamShield](https://github.com/sohamsaha99/StreamShield) at commit
`574d020` ("Stop PiP stutter"), where the fork started. Everything from the original is kept
except the address bar and the bookmarks screen.

## Navigation and screens

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

## Library (stored on the phone, no account)

- History with resume (Continue watching, Recently watched), Watch later, Liked videos,
  playlists, "Not interested", search history.
- **Subscriptions** from each channel's RSS feed; import from Google Takeout
  (`subscriptions.csv`); YouTube's own Subscribe buttons subscribe in the app; starred channels.
- **Home → For you:** new uploads from subscriptions mixed 50/50 with videos related to what you
  watch (`recommend/ForYouRule.kt`, reviewed rule).
- **Backups:** export/import of the library and settings as one file, an optional daily backup
  into a folder you pick, and import of YouTube watch history from Google Takeout.

## Music

- YouTube Music in the Music tab, with the app's tabs under it; songs keep playing in the
  background but keep neither the screen on nor open picture-in-picture.
- Pull-to-refresh in the Music tab restarts the song that was playing from the same point.

## Downloads

- **Downloads of any video or song** (NewPipe Extractor): a quality sheet (remembered per kind),
  audio-only for songs by default, H.264+AAC → MP4 or VP9+Opus → WebM, merged on the phone, saved
  to `Movies/StreamShield` and `Music/StreamShield` (Android 10+), with a progress notification
  and retries.
- An **offline player** (Media3 ExoPlayer) with the same mini-player, notification, lock-screen
  controls and picture-in-picture as online playback.
- **Smart downloads (music):** a chosen amount (default 1 GB) of songs you listen to plus new
  ones from YouTube's related lists, refreshed daily on Wi-Fi while charging
  (`download/SmartRule.kt`, reviewed rule); kept inside the app.

## Playback and ad blocking

- Faster starts: pruning also covers batched/wrapped responses and XHR JSON, shortens YouTube's
  17 s player timers, and removes the "Watch in the YouTube app" dialog that held back autoplay.
- Taps on YouTube's play/pause that the page missed are applied by the app and counted in
  Settings → Diagnostics; the page's PiP flag is checked against the app's.
- Battery: the player page is kept awake in the background only while something is loaded; the
  page script's heartbeat slows down in the background; the browse page pauses when hidden; only
  videos (not songs) keep the screen on or enter picture-in-picture.

## Technical

- Application id `com.aistudio.streamshield.vytkzp` (was `…vqxkpl`), so both apps can be
  installed side by side.
- Room database version 4 with migrations from version 1, so an install of the original app keeps
  its watch history if it is updated to this version (same application id and signing key
  required).
- Java 17 and core library desugaring (NewPipe Extractor); JitPack repository; new dependencies:
  WorkManager, Media3 ExoPlayer/UI, NewPipe Extractor; Coil enabled.
- GitHub Actions also publishes each `main` build as the `latest` release.
- Tests: `LibraryTest`, `SmartRuleTest`, `ForYouRuleTest`, and many new shield.js tests (routing,
  heartbeat, tap fixes, Subscribe buttons, pull-down, fade, resume).
