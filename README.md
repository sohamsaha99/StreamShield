# StreamShield

A personal Android app for YouTube and YouTube Music without ads, without a Google account, and
with downloads. It shows the YouTube mobile website (`m.youtube.com`, `music.youtube.com`) inside
the app, around native screens laid out like the YouTube app.

## Features

**Watching**
- **Ad blocking:** video ads are removed from YouTube's data before the player sees them, ad
  slots are hidden, and ad/tracker requests are blocked. **SponsorBlock** skips sponsor reads,
  self-promotion and subscribe reminders.
- **Background play** with notification, lock-screen and headset controls; **picture-in-picture**
  (auto-enters on Home, Android 12+); fullscreen; playback speed 0.25x–3x.
- **YouTube-app navigation:** tabs (Home · Music · Subscriptions · Library) on every browsing page,
  a video slides up full screen and minimizes to a mini-player; search results and channels open
  in a second page so Back returns to them with the video still playing.
- **Pull down to refresh** on every page (on a video page, pull down to minimize it).

**Your library (stored on the phone, no account)**
- **Home → For you:** new uploads from your subscriptions mixed with videos related to what you
  watch (rule in `recommend/ForYouRule.kt`). "Not interested" removes a video.
- **Subscriptions:** YouTube's own Subscribe buttons subscribe in the app. Import your real
  subscriptions from Google Takeout (`subscriptions.csv`). Long-press a channel to star it.
- **Library:** history (with resume), Watch later, Liked videos, playlists, downloads.
- **Backups:** export/import everything as one file, optional daily automatic backup, import your
  YouTube watch history from Google Takeout.

**Downloads**
- Any video or song: choose a quality (or audio only); files go to `Movies/StreamShield` and
  `Music/StreamShield` (Android 10+) and play offline in the app.
- **Smart downloads (music):** keeps a chosen amount (default 1 GB) of songs you listen to plus
  new ones you are likely to like, updated daily on Wi-Fi while charging (rule in
  `download/SmartRule.kt`). These stay inside the app.

## Install

Android 7.0 or newer. Picture-in-picture needs Android 8.0; public download folders need
Android 10.

- **From GitHub (no cable):** on the phone, open
  <https://github.com/somak135/StreamShieldv2/releases/download/latest/StreamShield.apk> (or
  **Releases → "StreamShield (latest build)"**) and open the downloaded file. Allow "Install
  unknown apps" for the browser when asked; if Play Protect warns, choose "Install anyway". The
  release is rebuilt on every push to `main` (see [Build on GitHub](#build-on-github)). It is
  marked as a pre-release, so GitHub's `/releases/latest` address does not find it; use the link
  above.
- **From Google AI Studio (USB):** open the project in AI Studio in Chrome or Edge, turn on USB
  debugging on the phone, plug it in and press **Install**. AI Studio's **Export → Download as
  .zip** contains the source only, not the APK or AI Studio's signing key.

An APK installs over an earlier one (keeping your library) only if both were signed with the same
key; see [Signing](#signing). A phone with an AI Studio install cannot update it from GitHub's APK
(different keys): export a backup in Settings, uninstall, install the GitHub APK and import the
backup. This app's id is `com.aistudio.streamshield.vytkzp`, so it installs
alongside the original StreamShield (`…vqxkpl`).

## Build and run

Requirements: Android Studio (or the Android SDK with platform `android-36.1`) and JDK 17+. The
build uses AGP 9.1, Gradle 9.3.1, Kotlin 2.2, and dependencies from Google Maven, Maven Central and
JitPack (NewPipe Extractor).

```sh
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew installDebug             # install on a connected device
./gradlew testDebugUnitTest        # JVM/Robolectric unit tests
```

The repository has `gradle/wrapper/gradle-wrapper.properties` but no `gradlew` script: run
`gradle wrapper` once, or open the project in Android Studio. Release signing reads
`KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` from the environment.

Tests for the injected page script run in headless Chromium:

```sh
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs
```

See [tools/shield-js-test/README.md](tools/shield-js-test/README.md).

### Build on GitHub

`.github/workflows/build-apk.yml` builds a debug APK on every push to `main`, on pull requests,
and on demand (**Actions → Build APK → Run workflow**). Each build:

1. uploads the APK as the run's artifact **StreamShield-debug-apk** (needs a GitHub login), and
2. except for pull requests, replaces the **`latest` release** with the new `StreamShield.apk`.
   Its download link works without a GitHub account if the repository is public; share it with
   friends.

On a fork, GitHub keeps Actions off until you open the **Actions** tab and enable them.

### Signing

Debug builds are signed with `./debug.keystore` (git-ignored). The workflow takes it from the
repository secret `DEBUG_KEYSTORE_BASE64` (**Settings → Secrets and variables → Actions → New
repository secret**); without the secret every build gets a new key, and you must uninstall the
app (losing its library) before installing the next build. To make a key once:

```sh
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug"
base64 -w0 debug.keystore      # macOS: base64 -i debug.keystore
```

Paste the printed text as the `DEBUG_KEYSTORE_BASE64` secret and keep `debug.keystore` somewhere
safe. Do not commit the key to a public repository: anyone with it can sign an APK that installs
as an update of this app.

Google AI Studio signs its builds with its own key, which is not part of its zip export, so AI
Studio installs and GitHub builds do not install over each other.

## Project docs

- [ARCHITECTURE.md](ARCHITECTURE.md): how the app is put together and why.
- [AGENTS.md](AGENTS.md): working notes for contributors and AI coding agents.
- [CHANGELOG.md](CHANGELOG.md): what changed compared with the original StreamShield.
- [MERGING.md](MERGING.md): bringing this version back into the original repository.

## Known limitations

- YouTube changes its pages and data now and then. Ad blocking, the Subscribe button and
  downloads (NewPipe Extractor) can break until the app is updated.
- YouTube can ask for a "confirm you're not a bot" check, typically on cloud and VPN networks;
  playback and downloads do not work then (the Android emulator in AI Studio always gets it).
- No YouTube account: likes, subscriptions and history stay in the app and are not sent to
  YouTube.
- For you and smart downloads only know what you did in this app (plus imported Takeout history).
