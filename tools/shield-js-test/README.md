# Shield script tests

Headless-Chromium tests for `app/src/main/assets/shield.js` (the script injected into YouTube at
document start). They run the script against mock YouTube pages and check ad pruning (inline
`ytInitialPlayerResponse`, `fetch().json()`, `JSON.parse`, feed and Shorts ads), the fallback ad
skipper (mute/fast-forward/restore, counted once per ad), background-play visibility handling,
playback speed sync, SponsorBlock skipping, the PiP layout and a parse-overhead check.

```sh
npm install -g playwright          # once; also needs a Chromium build (npx playwright install chromium)
NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs [clip.webm]
```

Without a clip argument a 30-second test video is generated with `ffmpeg`.
