// Headless-Chromium tests for app/src/main/assets/shield.js against mock YouTube pages.
//
// Run:  NODE_PATH=$(npm root -g) node tools/shield-js-test/shield.test.mjs [path/to/clip.webm]
// Needs the `playwright` package and a Chromium build. If no clip is given, one is generated
// with ffmpeg (a 30 s VP8/Opus test pattern).

import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const here = path.dirname(fileURLToPath(import.meta.url));
const shieldJs = readFileSync(path.join(here, '../../app/src/main/assets/shield.js'), 'utf8');

let clipPath = process.argv[2];
if (!clipPath || !existsSync(clipPath)) {
    clipPath = path.join(mkdtempSync(path.join(tmpdir(), 'shield-')), 'clip.webm');
    execFileSync('ffmpeg', ['-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=size=320x180:rate=15',
        '-f', 'lavfi', '-i', 'sine=frequency=440', '-t', '30', '-c:v', 'libvpx', '-b:v', '200k',
        '-c:a', 'libopus', '-shortest', clipPath]);
}
const clip = readFileSync(clipPath);

const PLAYER_RESPONSE_WITH_ADS = {
    responseContext: { serviceTrackingParams: [] },
    playabilityStatus: { status: 'OK' },
    streamingData: { formats: [] },
    videoDetails: { videoId: 'abc123def45', title: 'Test video' },
    adPlacements: [{ adPlacementRenderer: { kind: 'AD_PLACEMENT_KIND_START' } }],
    playerAds: [{ playerLegacyDesktopWatchAdsRenderer: {} }],
    adSlots: [{ adSlotRenderer: {} }],
    auxiliaryUi: { messageRenderers: { enforcementMessageViewModel: { title: 'Ad blockers are not allowed' } } }
};

const BROWSE_RESPONSE_WITH_ADS = {
    responseContext: {},
    contents: {
        singleColumnBrowseResultsRenderer: {
            tabs: [{
                tabRenderer: {
                    content: {
                        richGridRenderer: {
                            contents: [
                                { richItemRenderer: { content: { videoRenderer: { videoId: 'real1' } } } },
                                { richItemRenderer: { content: { adSlotRenderer: { slotId: 'ad' } } } },
                                { itemSectionRenderer: { contents: [{ promotedSparklesWebRenderer: {} }] } },
                                { itemSectionRenderer: { contents: [{ videoWithContextRenderer: { videoId: 'real2' } }] } },
                                { richItemRenderer: { content: { videoRenderer: { videoId: 'real3' } } } }
                            ]
                        }
                    }
                }
            }]
        }
    }
};

function watchPage() {
    return `<!doctype html><html><head><title>Test video - YouTube</title>
<script>var ytInitialPlayerResponse = ${JSON.stringify(PLAYER_RESPONSE_WITH_ADS)};</script>
<script>
  window.__pageSaw = {
    adPlacements: ytInitialPlayerResponse.adPlacements,
    playerAds: ytInitialPlayerResponse.playerAds,
    enforcement: ytInitialPlayerResponse.auxiliaryUi.messageRenderers.enforcementMessageViewModel,
    videoId: ytInitialPlayerResponse.videoDetails.videoId
  };
  window.__visibilityEvents = 0;
  window.__inputBlurEvents = 0;
  window.__resizeEvents = 0;
  window.__skipAdCalls = 0;
  document.addEventListener('visibilitychange', function () { window.__visibilityEvents++; });
  window.addEventListener('resize', function () { window.__resizeEvents++; });
</script>
</head><body>
<div id="movie_player" class="html5-video-player playing-mode">
  <video src="/clip.webm" playsinline></video>
</div>
<div class="slim-video-information-title">Test video</div>
<div class="slim-owner-icon-and-title"><div class="subhead">Test Channel</div></div>
<ytm-promoted-sparkles-web-renderer id="feedAd">Sponsored</ytm-promoted-sparkles-web-renderer>
<ytm-rich-item-renderer id="realItem">Real video</ytm-rich-item-renderer>
<input id="search"><button id="other">x</button>
<script>document.getElementById('search').addEventListener('blur', function () { window.__inputBlurEvents++; });
document.getElementById('movie_player').skipAd = function () { window.__skipAdCalls++; };</script>
</body></html>`;
}

function bridgeInit(config) {
    return `
    window.__bridgeLog = [];
    window.StreamShieldBridge = {
      getConfig: function () { return ${JSON.stringify(JSON.stringify(config))}; },
      onAdBlocked: function (kind) { window.__bridgeLog.push(['ad', kind]); },
      onState: function (json) { window.__bridgeLog.push(['state', JSON.parse(json)]); },
      onSpeedChanged: function (s) { window.__bridgeLog.push(['speed', s]); },
      onSponsorSkipped: function (s) { window.__bridgeLog.push(['sponsor', s]); }
    };`;
}

let failures = 0;
let passes = 0;
function check(name, condition, detail) {
    if (condition) {
        passes++;
        console.log(`  ok   ${name}`);
    } else {
        failures++;
        console.log(`  FAIL ${name}${detail !== undefined ? ' -> ' + JSON.stringify(detail) : ''}`);
    }
}

const browser = await chromium.launch({
    args: ['--autoplay-policy=no-user-gesture-required']
});

async function openWatchPage(config = {}) {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.route('**/*', async (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/clip.webm') {
            // Range support so the media element can seek.
            const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers()['range'] || '');
            if (range) {
                const start = Number(range[1]);
                const end = range[2] ? Number(range[2]) : clip.length - 1;
                return route.fulfill({
                    status: 206,
                    headers: {
                        'Content-Type': 'video/webm',
                        'Accept-Ranges': 'bytes',
                        'Content-Range': `bytes ${start}-${end}/${clip.length}`
                    },
                    body: clip.subarray(start, end + 1)
                });
            }
            return route.fulfill({ status: 200, headers: { 'Content-Type': 'video/webm', 'Accept-Ranges': 'bytes' }, body: clip });
        }
        if (url.pathname === '/youtubei/v1/player') {
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PLAYER_RESPONSE_WITH_ADS) });
        }
        if (url.pathname === '/youtubei/v1/browse') {
            return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(BROWSE_RESPONSE_WITH_ADS) });
        }
        if (url.pathname === '/watch') {
            return route.fulfill({ status: 200, contentType: 'text/html', body: watchPage() });
        }
        return route.fulfill({ status: 404, body: '' });
    });
    await page.addInitScript(bridgeInit({ adBlock: true, bgPlay: true, speed: 1.0, pip: false, sponsorSkip: true, ...config }));
    await page.addInitScript(shieldJs);
    await page.goto('https://m.youtube.com/watch?v=abc123def45');
    await page.waitForFunction(() => {
        const v = document.querySelector('video');
        return v && v.readyState >= 1 && isFinite(v.duration);
    });
    return { context, page };
}

const adEvents = (page) => page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'ad').map((e) => e[1]));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------------------------------------
console.log('JSON pruning');
{
    const { context, page } = await openWatchPage();
    const saw = await page.evaluate(() => window.__pageSaw);
    check('inline ytInitialPlayerResponse has no adPlacements when the page reads it', saw.adPlacements === undefined, saw);
    check('inline ytInitialPlayerResponse has no playerAds', saw.playerAds === undefined);
    check('anti-adblock enforcement message removed', saw.enforcement === undefined);
    check('real player data untouched', saw.videoId === 'abc123def45');
    check('prune reported once for the inline response', (await adEvents(page)).filter((k) => k === 'prune').length === 1, await adEvents(page));

    const fetched = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/player', { method: 'POST' });
        const data = await r.json();
        return { ads: data.adPlacements, slots: data.adSlots, status: data.playabilityStatus.status };
    });
    check('fetch().json() player response pruned', fetched.ads === undefined && fetched.slots === undefined && fetched.status === 'OK', fetched);

    const viaText = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/player', { method: 'POST' });
        const data = JSON.parse(await r.text());
        return { ads: data.adPlacements, ads2: data.playerAds };
    });
    check('JSON.parse(text) player response pruned', viaText.ads === undefined && viaText.ads2 === undefined, viaText);

    const feed = await page.evaluate(async () => {
        const r = await fetch('/youtubei/v1/browse', { method: 'POST' });
        const data = await r.json();
        const items = data.contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.richGridRenderer.contents;
        return items.map((i) => JSON.stringify(i));
    });
    check('feed ad cells removed, real videos kept', feed.length === 3 && feed.every((s) => /real\d/.test(s)), feed);

    const shorts = await page.evaluate(() => {
        const text = JSON.stringify({
            responseContext: {},
            entries: [
                { command: { reelWatchEndpoint: { videoId: 'short1' } } },
                { command: { reelWatchEndpoint: { videoId: 'adShort', adClientParams: { isAd: true } } } },
                { command: { reelWatchEndpoint: { videoId: 'short2' } } }
            ]
        });
        return JSON.parse(text).entries.map((e) => e.command.reelWatchEndpoint.videoId);
    });
    check('Shorts sequence ad entries removed', shorts.join(',') === 'short1,short2', shorts);

    const unrelated = await page.evaluate(() => {
        const o = JSON.parse('{"adPlacements":[1],"other":true,"padding":"' + 'x'.repeat(80) + '"}');
        return o.adPlacements;
    });
    check('unrelated JSON (no responseContext) is not modified', Array.isArray(unrelated) && unrelated.length === 1, unrelated);

    const parseString = await page.evaluate(() => JSON.parse('"plain"') === 'plain' && JSON.parse('[1,2]').length === 2);
    check('JSON.parse still behaves for primitives/arrays', parseString);

    const cosmetic = await page.evaluate(() => ({
        ad: getComputedStyle(document.getElementById('feedAd')).display,
        real: getComputedStyle(document.getElementById('realItem')).display
    }));
    check('cosmetic filter hides promoted renderer', cosmetic.ad === 'none', cosmetic);
    check('cosmetic filter leaves real items visible', cosmetic.real !== 'none', cosmetic);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Ad fallback (ad still starts)');
{
    const { context, page } = await openWatchPage({ speed: 1.25 });
    await page.evaluate(() => document.querySelector('video').play());
    await sleep(300);
    const before = await page.evaluate(() => {
        const v = document.querySelector('video');
        return { muted: v.muted, rate: v.playbackRate };
    });
    check('content plays at the configured speed', Math.abs(before.rate - 1.25) < 0.01 && before.muted === false, before);

    await page.evaluate(() => {
        const p = document.getElementById('movie_player');
        p.classList.add('ad-showing');
        // YouTube keeps flipping other classes while an ad plays; must not re-count.
        for (let i = 0; i < 20; i++) p.classList.toggle('ytp-autohide');
    });
    await sleep(150);
    const during = await page.evaluate(() => {
        const v = document.querySelector('video');
        return { muted: v.muted, rate: v.playbackRate, t: v.currentTime, d: v.duration };
    });
    check('ad is muted', during.muted === true, during);
    check("player's own skipAd() is used", (await page.evaluate(() => window.__skipAdCalls)) > 0);
    check('ad is fast-forwarded to its end', during.d - during.t < 0.5, during);
    const skips = (await adEvents(page)).filter((k) => k === 'skip').length;
    check('one ad counts once despite many class mutations', skips === 1, skips);

    await page.evaluate(() => {
        const p = document.getElementById('movie_player');
        p.classList.remove('ad-showing');
        const v = document.querySelector('video');
        v.currentTime = 1;
        v.play();
    });
    await sleep(300);
    const after = await page.evaluate(() => {
        const v = document.querySelector('video');
        return { muted: v.muted, rate: v.playbackRate };
    });
    check('sound restored after the ad', after.muted === false, after);
    check('speed restored after the ad', Math.abs(after.rate - 1.25) < 0.01, after);
    const callsAfterAd = await page.evaluate(() => window.__skipAdCalls);
    await sleep(400);
    check('ad nudging stops once the ad is over', (await page.evaluate(() => window.__skipAdCalls)) === callsAfterAd);
    const adDiag = await page.evaluate(() => { window.__streamShield.reportState(); return window.__bridgeLog.filter((e) => e[0] === 'state').pop()[1]; });
    check('diagnostics record time spent in the ad state', adDiag.adMs >= 100, adDiag);

    await page.evaluate(() => window.__streamShield.setConfig({ adBlock: false }));
    await page.evaluate(() => document.getElementById('movie_player').classList.add('ad-showing'));
    await sleep(150);
    const disabled = await page.evaluate(() => document.querySelector('video').muted);
    check('with ad-block off, ads are left alone', disabled === false, disabled);
    const cosmeticOff = await page.evaluate(() => getComputedStyle(document.getElementById('feedAd')).display);
    check('with ad-block off, cosmetic filter removed', cosmeticOff !== 'none', cosmeticOff);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Background play');
{
    const { context, page } = await openWatchPage({ bgPlay: true });
    const res = await page.evaluate(() => {
        document.dispatchEvent(new Event('visibilitychange'));
        window.dispatchEvent(new Event('blur'));
        const search = document.getElementById('search');
        search.focus();
        document.getElementById('other').focus();
        return {
            hidden: document.hidden,
            state: document.visibilityState,
            vis: window.__visibilityEvents,
            inputBlur: window.__inputBlurEvents
        };
    });
    check('document reports visible', res.hidden === false && res.state === 'visible', res);
    check('visibilitychange swallowed', res.vis === 0, res);
    check('element blur still delivered (search box keeps working)', res.inputBlur === 1, res);

    await page.evaluate(() => window.__streamShield.setConfig({ bgPlay: false }));
    const off = await page.evaluate(() => {
        document.dispatchEvent(new Event('visibilitychange'));
        return window.__visibilityEvents;
    });
    check('with background play off, visibility events pass through', off === 1, off);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Speed, SponsorBlock, state reporting');
{
    const { context, page } = await openWatchPage({ speed: 1.0 });
    await page.evaluate(() => window.__streamShield.setConfig({ speed: 1.5 }));
    check('setConfig speed applies immediately', Math.abs(await page.evaluate(() => document.querySelector('video').playbackRate) - 1.5) < 0.01);

    await page.evaluate(() => document.querySelector('video').play());
    await sleep(1700);
    await page.evaluate(() => { document.querySelector('video').playbackRate = 2; });
    await sleep(100);
    const speedLog = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'speed').map((e) => e[1]));
    check('speed change from YouTube\'s own menu is adopted and reported', speedLog.length === 1 && speedLog[0] === '2', speedLog);

    await page.evaluate(() => {
        window.__streamShield.setSponsorSegments('abc123def45', [[3, 8]]);
        document.querySelector('video').currentTime = 3.2;
    });
    await sleep(700);
    const t = await page.evaluate(() => document.querySelector('video').currentTime);
    check('SponsorBlock segment skipped', t >= 8, t);

    await page.evaluate(() => {
        window.__streamShield.setSponsorSegments('someOtherId', [[10, 20]]);
        document.querySelector('video').currentTime = 10.5;
    });
    await sleep(600);
    const t2 = await page.evaluate(() => document.querySelector('video').currentTime);
    check('segments for a different video are ignored', t2 < 20 && t2 >= 10.5, t2);

    await sleep(2500);
    const states = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'state').map((e) => e[1]));
    const last = states[states.length - 1];
    check('state reports video id, title, channel, dimensions', last.videoId === 'abc123def45' && last.title === 'Test video' &&
        last.channel === 'Test Channel' && last.width === 320 && last.height === 180 && last.playing === true, last);
    check('state is only reported on change (not every tick)', states.length <= 8, states.length);
    check('diagnostics: start time and ads removed are reported', last.startMs >= 0 && last.startMs < 10000 && last.pruned >= 1, last);

    await page.evaluate(() => window.__streamShield.pause());
    await sleep(150);
    const paused = await page.evaluate(() => window.__bridgeLog.filter((e) => e[0] === 'state').pop()[1].playing);
    check('pause command pauses and reports', paused === false);
    await page.evaluate(() => window.__streamShield.togglePlay());
    await sleep(200);
    check('togglePlay resumes', await page.evaluate(() => !document.querySelector('video').paused));
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Picture-in-picture layout');
{
    const { context, page } = await openWatchPage();
    await page.setViewportSize({ width: 400, height: 225 });
    await page.evaluate(() => window.__streamShield.setConfig({ pip: true }));
    const inPip = await page.evaluate(() => {
        const p = document.getElementById('movie_player').getBoundingClientRect();
        return { style: !!document.getElementById('streamshield-pip'), w: p.width, h: p.height, top: p.top };
    });
    check('PiP style fills the window with the player', inPip.style && inPip.w === 400 && inPip.h === 225 && inPip.top === 0, inPip);
    const videoBox = await page.evaluate(() => { const r = document.querySelector('video').getBoundingClientRect(); return { w: r.width, h: r.height }; });
    check('PiP: the video itself fills the window', videoBox.w === 400 && videoBox.h === 225, videoBox);

    await page.evaluate(() => document.querySelector('video').play());
    await sleep(200);
    await page.evaluate(() => document.querySelector('video').pause()); // YouTube/WebView pausing it
    await sleep(300);
    check('PiP: a pause the user did not ask for is undone', await page.evaluate(() => !document.querySelector('video').paused));
    await page.evaluate(() => window.__streamShield.togglePlay()); // the PiP window's pause button
    await sleep(1200);
    check("PiP: the user's own pause is respected", await page.evaluate(() => document.querySelector('video').paused));
    await page.evaluate(() => window.__streamShield.togglePlay());
    await sleep(300);
    check('PiP: play button resumes', await page.evaluate(() => !document.querySelector('video').paused));

    await page.setViewportSize({ width: 412, height: 800 });
    await page.evaluate(() => { window.__resizeEvents = 0; window.__streamShield.setConfig({ pip: false }); });
    await sleep(1200);
    const outPip = await page.evaluate(() => ({ style: !!document.getElementById('streamshield-pip'), resizes: window.__resizeEvents }));
    check('PiP style removed on exit', outPip.style === false, outPip);
    check('resize re-dispatched so the player re-lays out (fixes cropped view)', outPip.resizes >= 5, outPip);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Performance');
{
    const { context, page } = await openWatchPage();
    const perf = await page.evaluate(() => {
        const items = [];
        for (let i = 0; i < 3000; i++) {
            items.push(i % 25 === 0
                ? { richItemRenderer: { content: { adSlotRenderer: { slotId: 'ad' + i, payload: 'x'.repeat(200) } } } }
                : { richItemRenderer: { content: { videoRenderer: { videoId: 'v' + i, title: { runs: [{ text: 'Video ' + i }] },
                    thumbnail: { thumbnails: [{ url: 'https://i.ytimg.com/vi/' + i + '/hq.jpg', width: 480, height: 360 }] },
                    description: 'y'.repeat(400) } } } });
        }
        const text = JSON.stringify({ responseContext: {}, contents: { richGridRenderer: { contents: items } } });
        // Reference: the browser's own parser, captured before any page code ran is not possible
        // here, so compare against parsing a copy without the trigger keyword.
        const plain = text.replace('responseContext', 'responseKontext');
        const runs = 5;
        let hooked = 0, base = 0, kept = 0;
        for (let r = 0; r < runs; r++) {
            let t = performance.now(); JSON.parse(plain); base += performance.now() - t;
            t = performance.now(); const out = JSON.parse(text); hooked += performance.now() - t;
            kept = out.contents.richGridRenderer.contents.length;
        }
        return { mb: (text.length / 1e6).toFixed(2), base: base / runs, hooked: hooked / runs, kept };
    });
    console.log(`       ${perf.mb} MB response: parse ${perf.base.toFixed(1)} ms, parse+prune ${perf.hooked.toFixed(1)} ms`);
    check('pruning keeps all 2880 real items', perf.kept === 2880, perf.kept);
    check('pruning overhead on a large feed response is small (< 25 ms)', perf.hooked - perf.base < 25, perf);
    await context.close();
}

// ---------------------------------------------------------------------------------------------
console.log('Idempotence');
{
    const { context, page } = await openWatchPage();
    const v = await page.evaluate((src) => { (0, eval)(src); return window.__streamShield.version; }, shieldJs);
    check('re-injection is a no-op', v === 2);
    await context.close();
}

await browser.close();
console.log(`\n${passes} passed, ${failures} failed`);
process.exit(failures ? 1 : 0);
