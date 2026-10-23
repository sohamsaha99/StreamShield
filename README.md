# StreamShield

A personal Android app for watching YouTube without ads. It wraps the YouTube mobile website
(`m.youtube.com`) in a WebView and adds:

- **Ad blocking**: video ads are removed from YouTube's data before the player sees them, ad slots
  are hidden, and ad/tracker requests are blocked.
- **SponsorBlock**: skips sponsor reads, self-promotion and subscribe reminders.
- **Background play**: keeps playing with the screen off or in another app, with notification,
  lock-screen and headset controls.
- **Picture-in-picture**: auto-enters on Home (Android 12+), sized to the video, with a play/pause
  button.
- **Fullscreen video**, a custom **playback speed** (0.25x–3x), bookmarks and watch history.

Offline downloads are **not implemented**. See [Roadmap](#roadmap).

## Requirements

- Android 7.0+ (minSdk 24); picture-in-picture needs Android 8.0+.
- To build: Android Studio (or the Android SDK with platform `android-36.1`) and JDK 17+.
  The build uses AGP 9.1, Gradle 9.3.1 and Kotlin 2.2 (built into AGP).

## Build and run

```sh
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/
./gradlew installDebug             # install on a connected device
./gradlew testDebugUnitTest        # JVM/Robolectric unit tests
```

The repository has `gradle/wrapper/gradle-wrapper.properties` but no `gradlew` script. Run
`gradle wrapper` once, or open the project in Android Studio. Release signing reads
`KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD` from the environment.

### Build on GitHub

`.github/workflows/build-apk.yml` builds a debug APK on every push to `main`, on pull requests,
and on demand (**Actions → Build APK → Run workflow**). Download it from the run page under
**Artifacts → StreamShield-debug-apk** (a zip containing the APK).

To let each new APK install over the previous one, sign them all with the same key. Store a
keystore once as a repository secret: **Settings → Secrets and variables → Actions → New
repository secret**, name `DEBUG_KEYSTORE_BASE64`, value from:

```sh
keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug"
base64 -w0 debug.keystore      # macOS: base64 -i debug.keystore
```

Without the secret, every build gets a new key and you must uninstall before installing.

Tests for the injected page script run in headless Chromium:

```sh
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs
```

See [tools/shield-js-test/README.md](tools/shield-js-test/README.md).

## Project docs

- [ARCHITECTURE.md](ARCHITECTURE.md): how the app is put together and why.
- [AGENTS.md](AGENTS.md): working notes for contributors and AI coding agents.

## Roadmap

- **Offline downloads.** A previous version had a download button that did not really work: it
  saved sample clips or empty files and streamed from the network. It was removed. A real
  implementation needs a maintained YouTube stream extractor
  ([NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)) to decode stream URLs,
  muxing of separate video/audio streams for 720p+, a background download worker and a local
  player. The `downloads` table in the Room database was kept for this.
