# Merging with the original StreamShield

This repository ([somak135/StreamShieldv2](https://github.com/somak135/StreamShieldv2)) started
from [sohamsaha99/StreamShield](https://github.com/sohamsaha99/StreamShield) at commit `574d020`
("Stop PiP stutter"). Its work sits in ordinary commits on top of that shared history, so the two
can be merged with git; see [CHANGELOG.md](CHANGELOG.md) for what this side changed.

## State on 2026-10-24

The original's `main` has five newer commits:

| Commit | Change |
|---|---|
| `84418cd` | Keep live streams loading in PiP; autoplay the next video in PiP/background |
| `df99667` | Docker dev environment and a local test plan for agents |
| `fbc6017` | Rootless Docker helper for the dev container |
| `d4e373e` | Autoplay while hidden: stay in mixes and playlists on m.youtube.com |
| `a6e2bc8` | First on-device test report and CDP probe tools |

A trial merge of those into this repository's `main` conflicts in 8 files (24 places):
`shield.js` (7), `BrowserController.kt` (4), `MainActivity.kt` (3),
`UserPreferencesRepository.kt` (3), `SettingsScreen.kt` (3), `AdBlockShieldEngine.kt` (2),
`StreamModels.kt` (1) and `shield.test.mjs` (1), plus the docs. The Docker, test-plan and
device-probe files merge cleanly. The original's app changes are small (about 225 lines);
most conflicts are both sides adding to the same lists (config keys, preferences, settings rows,
bridge methods), so the resolution is to keep both.

Autoplay while hidden exists on both sides: this side ported the approach of `84418cd` and
`d4e373e` into `shield.js` with the same function names (`captureNextVideo`, `nextByVideo`,
`nextVideoUrl`, `resumeAfterAdvance`), added YouTube Music, and keeps its existing **Autoplay**
setting (`autoplay` in the page config) instead of the original's `autoAdvance` /
"Autoplay next video". When merging, keep this side's version of those functions and map the
original's `autoAdvanceEnabled` preference onto `autoplayEnabled`. The original's other parts of
`84418cd` (live streams in PiP: `resyncPlayer`, the `pipStalls` counter) are not on this side
yet and should be taken.

## How to merge

Either side can do it; keep one person resolving the conflicts.

1. **Bring the original's new commits here first** (recommended):
   ```sh
   git remote add upstream https://github.com/sohamsaha99/StreamShield.git
   git fetch upstream
   git checkout -b merge-upstream
   git merge upstream/main          # resolve the conflicts listed above, keeping both sides
   NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs
   ```
   Push `merge-upstream`, let GitHub Actions build it (unit tests run there), test the APK on a
   phone (see the checklist in [AGENTS.md](AGENTS.md#verifying-changes)), then merge it into
   `main`.
2. **Then open a pull request** from `somak135/StreamShieldv2:main` to
   `sohamsaha99/StreamShield:main`. Because it already contains the original's commits, it merges
   without conflicts. If GitHub does not offer the pull request (the repositories are not linked
   as a fork), the original's owner can merge directly:
   ```sh
   git remote add v2 https://github.com/somak135/StreamShieldv2.git
   git fetch v2 && git merge v2/main
   ```

## Decisions to make before merging

- **Application id.** This version uses `com.aistudio.streamshield.vytkzp`; the original uses
  `…vqxkpl`. Keep `vytkzp` to have two separate apps, or restore `vqxkpl` in
  `app/build.gradle.kts` so the original's installs update in place. On update, the database
  migrations (1 → 4) keep the original's watch history; bookmarks stay in the database and in
  backups, without a screen.
- **Signing key.** An update installs over an existing app only with the same signing key. AI
  Studio projects sign with their own debug keys, and GitHub Actions with the
  `DEBUG_KEYSTORE_BASE64` secret (see the README). Pick one key for the merged app and store it in
  the merged repository's secret.
- **Google AI Studio.** Each side has its own AI Studio project. After merging, update one of them
  from the merged repository (upload the changed files, or connect AI Studio's GitHub sync to a
  repository) and treat that one as the place where builds are tested.
- **Docs.** Both sides rewrote `README.md`, `AGENTS.md` and `ARCHITECTURE.md`. Keep this side's
  structure (it describes the larger app) and add the original's new sections: the Docker
  environment, `docs/LOCAL_TESTING.md`, the device-probe tools and the SABR start-delay note.
