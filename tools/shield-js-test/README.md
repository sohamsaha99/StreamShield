# Shield script tests

Headless-Chromium tests for `app/src/main/assets/shield.js` (the script injected into YouTube at
document start). They run the script against mock YouTube pages and check ad pruning (inline
`ytInitialPlayerResponse`, `fetch().json()`, `JSON.parse`, feed and Shorts ads), the fallback ad
skipper (mute/fast-forward/restore, counted once per ad), background-play visibility handling,
playback speed sync, SponsorBlock skipping, the PiP layout and a parse-overhead check, plus the
app's additions: player/browse page routing, the background heartbeat, play/pause taps the page
missed, Subscribe buttons (video page, channel page, deeply wrapped buttons), pull-down gestures,
the browse-page fade, and resuming a YouTube Music song after a reload. The mock bridge in
`bridgeInit` mirrors `StreamShieldBridge`; add new bridge methods there too.

```sh
npm install -g playwright          # once; also needs a Chromium build (npx playwright install chromium)
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs [clip.webm]
```

Without a clip argument a 30-second test video is generated with `ffmpeg`.
