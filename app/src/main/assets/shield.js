/*
 * StreamShield page script for m.youtube.com / www.youtube.com.
 *
 * Injected at document start (WebViewCompat.addDocumentStartJavaScript) so the hooks are in place
 * before YouTube's own scripts run. Falls back to evaluateJavascript on older WebViews, which is
 * why everything below is idempotent.
 *
 * Responsibilities:
 *   1. Remove ad payloads from YouTube JSON (player response, feed/search/watch-next renderers) so
 *      the player never schedules an ad. This is the same technique uBlock Origin / Brave use.
 *   2. Fallback: if an ad still starts, mute it, press skip, fast-forward to the end, then restore.
 *   3. Cosmetic CSS for leftover ad slots.
 *   4. Background play: keep the page "visible" so YouTube does not pause when the app is hidden.
 *   5. Playback speed, SponsorBlock segment skipping, PiP layout, play/pause commands.
 *   6. Report compact playback state to the app (only when it changes).
 *
 * Native side talks to this script through window.__streamShield.* and receives events through
 * window.StreamShieldBridge (an @JavascriptInterface object).
 */
(function () {
    'use strict';
    if (window.__streamShield) {
        return;
    }

    var bridge = window.StreamShieldBridge || null;

    var config = { adBlock: true, bgPlay: true, speed: 1.0, pip: false, sponsorSkip: true };
    try {
        if (bridge && bridge.getConfig) {
            var initial = JSON.parse(String(bridge.getConfig()));
            for (var k in initial) {
                if (Object.prototype.hasOwnProperty.call(initial, k)) config[k] = initial[k];
            }
        }
    } catch (e) {}

    function report(method, arg) {
        try {
            if (bridge && typeof bridge[method] === 'function') bridge[method](arg);
        } catch (e) {}
    }

    // ---------------------------------------------------------------------------------------
    // 1. JSON ad pruning
    // ---------------------------------------------------------------------------------------
    var PLAYER_AD_KEYS = ['adPlacements', 'adSlots', 'playerAds', 'adBreakHeartbeatParams', 'adBreakParams'];
    // Renderers that only ever wrap ads in browse/search/next responses.
    var AD_RENDERER_KEYS = {
        adSlotRenderer: 1,
        promotedSparklesWebRenderer: 1,
        promotedSparklesTextSearchRenderer: 1,
        promotedVideoRenderer: 1,
        compactPromotedVideoRenderer: 1,
        compactPromotedItemRenderer: 1,
        inFeedAdLayoutRenderer: 1,
        displayAdRenderer: 1,
        searchPyvRenderer: 1,
        bannerPromoRenderer: 1,
        statementBannerRenderer: 1,
        brandVideoSingletonRenderer: 1,
        brandVideoShelfRenderer: 1,
        companionAdRenderer: 1,
        actionCompanionAdRenderer: 1,
        mealbarPromoRenderer: 1,
        adBreakServiceRenderer: 1,
        reelShelfAdRenderer: 1
    };
    var MAX_WALK_DEPTH = 40;

    function isAdItem(item) {
        if (!item || typeof item !== 'object') return false;
        for (var key in item) {
            if (AD_RENDERER_KEYS[key]) return true;
        }
        // Shorts feed: ads are ordinary reel entries flagged in their endpoint.
        var reel = item.command && item.command.reelWatchEndpoint;
        if (reel && reel.adClientParams && reel.adClientParams.isAd) return true;
        // Feed cells wrap the real renderer: {richItemRenderer: {content: {adSlotRenderer: ...}}}
        var rich = item.richItemRenderer;
        if (rich && rich.content && typeof rich.content === 'object') {
            for (var k2 in rich.content) {
                if (AD_RENDERER_KEYS[k2]) return true;
            }
        }
        // Mobile section items: {itemSectionRenderer: {contents: [{adSlotRenderer: ...}]}}
        var section = item.itemSectionRenderer;
        if (section && Array.isArray(section.contents) && section.contents.length > 0) {
            for (var i = 0; i < section.contents.length; i++) {
                if (!isAdItem(section.contents[i])) return false;
            }
            return true;
        }
        return false;
    }

    // Returns the number of ad entries removed.
    function stripAdRenderers(node, depth) {
        if (!node || typeof node !== 'object' || depth > MAX_WALK_DEPTH) return 0;
        var removed = 0;
        if (Array.isArray(node)) {
            for (var i = node.length - 1; i >= 0; i--) {
                if (isAdItem(node[i])) {
                    node.splice(i, 1);
                    removed++;
                } else {
                    removed += stripAdRenderers(node[i], depth + 1);
                }
            }
            return removed;
        }
        for (var key in node) {
            if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
            var value = node[key];
            if (value && typeof value === 'object') {
                if (AD_RENDERER_KEYS[key]) {
                    delete node[key];
                    removed++;
                } else {
                    removed += stripAdRenderers(value, depth + 1);
                }
            }
        }
        return removed;
    }

    function stripPlayerAds(obj) {
        if (!obj || typeof obj !== 'object') return 0;
        var removed = 0;
        for (var i = 0; i < PLAYER_AD_KEYS.length; i++) {
            var key = PLAYER_AD_KEYS[i];
            if (Object.prototype.hasOwnProperty.call(obj, key)) {
                if (Array.isArray(obj[key]) ? obj[key].length > 0 : !!obj[key]) removed++;
                delete obj[key];
            }
        }
        // Anti-adblock enforcement dialog ("Ad blockers are not allowed on YouTube").
        var aux = obj.auxiliaryUi;
        if (aux && aux.messageRenderers && aux.messageRenderers.enforcementMessageViewModel) {
            delete aux.messageRenderers.enforcementMessageViewModel;
        }
        return removed;
    }

    var prunedObjects = typeof WeakSet === 'function' ? new WeakSet() : null;

    // Prunes a parsed YouTube InnerTube response in place. Only touches objects that look like
    // YouTube responses so unrelated JSON is never walked.
    function pruneResponse(obj) {
        if (!config.adBlock || !obj || typeof obj !== 'object' || Array.isArray(obj)) return obj;
        if (prunedObjects) {
            if (prunedObjects.has(obj)) return obj;
        }
        var looksLikePlayer = 'playabilityStatus' in obj || 'streamingData' in obj || 'videoDetails' in obj;
        var looksLikeInnerTube = looksLikePlayer || 'responseContext' in obj || 'playerResponse' in obj;
        if (!looksLikeInnerTube) return obj;
        if (prunedObjects) prunedObjects.add(obj);

        var adsRemoved = stripPlayerAds(obj);
        if (obj.playerResponse && typeof obj.playerResponse === 'object') {
            adsRemoved += stripPlayerAds(obj.playerResponse);
        }
        if (!looksLikePlayer) {
            adsRemoved += stripAdRenderers(obj, 0);
        }
        if (adsRemoved > 0) {
            report('onAdBlocked', 'prune');
        }
        return obj;
    }

    // Expose pure helpers for tests.
    var internals = { pruneResponse: pruneResponse, isAdItem: isAdItem };

    (function installJsonHooks() {
        var nativeParse = JSON.parse;
        JSON.parse = function (text, reviver) {
            var result = nativeParse.apply(this, arguments);
            if (config.adBlock && result && typeof result === 'object' && typeof text === 'string' &&
                text.length > 64 && text.indexOf('responseContext') !== -1) {
                try { pruneResponse(result); } catch (e) {}
            }
            return result;
        };
        try { JSON.parse.toString = function () { return nativeParse.toString(); }; } catch (e) {}

        if (window.Response && Response.prototype && Response.prototype.json) {
            var nativeJson = Response.prototype.json;
            Response.prototype.json = function () {
                return nativeJson.apply(this, arguments).then(function (data) {
                    try { pruneResponse(data); } catch (e) {}
                    return data;
                });
            };
        }

        // Inline <script> globals. They are declared with `var ytInitialPlayerResponse = {...}`,
        // which goes through this setter because the property already exists on window.
        ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
            var stored = window[name];
            try {
                Object.defineProperty(window, name, {
                    configurable: true,
                    enumerable: true,
                    get: function () { return stored; },
                    set: function (value) {
                        try { pruneResponse(value); } catch (e) {}
                        stored = value;
                    }
                });
                if (stored) pruneResponse(stored);
            } catch (e) {}
        });
    })();

    // ---------------------------------------------------------------------------------------
    // 2. Background play: keep the page visible and swallow visibility/pause triggers.
    // ---------------------------------------------------------------------------------------
    function keepAlive() {
        return !!(config.bgPlay || config.pip);
    }

    (function installVisibilityHooks() {
        function override(target, prop, fakeValue) {
            var proto = Object.getPrototypeOf(target);
            var desc = null;
            while (proto && !desc) {
                desc = Object.getOwnPropertyDescriptor(proto, prop);
                proto = Object.getPrototypeOf(proto);
            }
            if (!desc || !desc.get) return;
            try {
                Object.defineProperty(target, prop, {
                    configurable: true,
                    get: function () { return keepAlive() ? fakeValue : desc.get.call(target); }
                });
            } catch (e) {}
        }
        override(document, 'hidden', false);
        override(document, 'webkitHidden', false);
        override(document, 'visibilityState', 'visible');
        override(document, 'webkitVisibilityState', 'visible');

        var nativeHasFocus = document.hasFocus;
        document.hasFocus = function () {
            return keepAlive() ? true : nativeHasFocus.call(document);
        };

        // Registered at document start, so these capture listeners run before any of YouTube's.
        var swallowed = ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide', 'freeze'];
        swallowed.forEach(function (type) {
            window.addEventListener(type, function (event) {
                // Only page-level events; element blur (e.g. the search box) must still work.
                var t = event.target;
                if (keepAlive() && (t === window || t === document)) event.stopImmediatePropagation();
            }, true);
        });
    })();

    // ---------------------------------------------------------------------------------------
    // 3. Cosmetic filtering
    // ---------------------------------------------------------------------------------------
    var COSMETIC_CSS = [
        'ytm-promoted-sparkles-web-renderer',
        'ytm-promoted-sparkles-text-search-renderer',
        'ytm-promoted-video-renderer',
        'ytm-companion-ad-renderer',
        'ytm-companion-slot',
        'ytm-in-feed-ad-layout-renderer',
        'ytm-ad-slot-renderer',
        'ad-slot-renderer',
        'ytm-rich-item-renderer:has(> ad-slot-renderer)',
        'ytm-rich-item-renderer:has(ytm-ad-slot-renderer)',
        'ytm-item-section-renderer:has(> ytm-ad-slot-renderer)',
        'ytm-mealbar-promo-renderer',
        'ytm-statement-banner-renderer',
        'ytm-brand-video-singleton-renderer',
        'ytm-paid-content-overlay-renderer',
        'ytm-search-pyv-renderer',
        'ytd-ad-slot-renderer',
        'ytd-in-feed-ad-layout-renderer',
        'ytd-banner-promo-renderer',
        'ytd-promoted-sparkles-web-renderer',
        'ytd-player-legacy-desktop-watch-ads-renderer',
        'ytd-enforcement-message-view-model',
        '#player-ads',
        '#masthead-ad',
        '.ytp-ad-overlay-container',
        '.ytp-ad-overlay-slot',
        '.ytp-ad-image-overlay',
        '.ytp-ad-text-overlay',
        '.ytp-ad-action-interstitial',
        '.ytp-featured-product',
        '.ytm-promoted-sparkles-web-renderer'
    ].join(',\n') + ' { display: none !important; }';

    var PIP_CSS = [
        'html, body { overflow: hidden !important; background: #000 !important; }',
        'ytm-mobile-topbar-renderer, #header-bar, ytm-pivot-bar-renderer, .mobile-topbar-header,',
        'ytm-single-column-watch-next-results-renderer, ytm-watch-metadata-app-promo-renderer,',
        '.player-controls-content, #player-control-overlay, .ytp-chrome-top, .ytp-chrome-bottom',
        '{ display: none !important; }',
        '#player-container-id, #player, #movie_player, .html5-video-player {',
        '  position: fixed !important; inset: 0 !important; width: 100vw !important;',
        '  height: 100vh !important; max-height: 100vh !important; margin: 0 !important;',
        '  padding: 0 !important; z-index: 2147483646 !important; background: #000 !important; }',
        '#movie_player video, .html5-video-player video, .html5-main-video {',
        '  position: absolute !important; inset: 0 !important; width: 100% !important;',
        '  height: 100% !important; left: 0 !important; top: 0 !important;',
        '  object-fit: contain !important; }'
    ].join('\n');

    function ensureStyle(id, css, enabled) {
        var root = document.head || document.documentElement;
        var el = document.getElementById(id);
        if (!enabled) {
            if (el) el.remove();
            return;
        }
        if (!root) return;
        if (!el) {
            el = document.createElement('style');
            el.id = id;
            el.textContent = css;
            root.appendChild(el);
        } else if (el.parentNode !== root && root === document.head) {
            root.appendChild(el);
        }
    }

    function applyStyles() {
        ensureStyle('streamshield-cosmetic', COSMETIC_CSS, config.adBlock);
        ensureStyle('streamshield-pip', PIP_CSS, config.pip);
    }

    // ---------------------------------------------------------------------------------------
    // 4. Player hooks: ad fallback, speed, SponsorBlock, state reporting
    // ---------------------------------------------------------------------------------------
    var hookedPlayer = null;
    var hookedVideo = null;
    var playerObserver = null;
    var adActive = false;
    var savedMuted = false;
    var settingRate = false;
    var lastLoadAt = 0;
    var sponsor = { videoId: '', segments: [] };
    var lastStateJson = '';

    function currentVideoId() {
        try {
            var url = new URL(location.href);
            var v = url.searchParams.get('v');
            if (v) return v;
            var m = url.pathname.match(/^\/(?:shorts|embed|live)\/([A-Za-z0-9_-]{6,})/);
            return m ? m[1] : '';
        } catch (e) {
            return '';
        }
    }

    function findPlayer() {
        return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
    }

    function findVideo() {
        var player = hookedPlayer || findPlayer();
        return (player && player.querySelector('video')) || document.querySelector('video');
    }

    function isAdShowing(player) {
        if (!player || !player.classList) return false;
        return player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
    }

    function setRate(video, rate) {
        if (!video || Math.abs(video.playbackRate - rate) < 0.01) return;
        settingRate = true;
        try { video.playbackRate = rate; } catch (e) {}
        settingRate = false;
    }

    function clickSkipButtons() {
        var buttons = document.querySelectorAll(
            '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
            '.ytp-ad-skip-button-slot button, .ytp-ad-overlay-close-button'
        );
        for (var i = 0; i < buttons.length; i++) {
            try { buttons[i].click(); } catch (e) {}
        }
    }

    function fastForwardAd() {
        var video = findVideo();
        clickSkipButtons();
        if (!video) return;
        video.muted = true;
        setRate(video, 16);
        var d = video.duration;
        if (isFinite(d) && d > 0 && video.currentTime < d - 0.1) {
            try { video.currentTime = d; } catch (e) {}
        }
    }

    function onAdStateChanged() {
        var showing = config.adBlock && isAdShowing(hookedPlayer);
        if (showing && !adActive) {
            adActive = true;
            var video = findVideo();
            savedMuted = video ? video.muted : false;
            report('onAdBlocked', 'skip');
            fastForwardAd();
        } else if (showing) {
            fastForwardAd();
        } else if (!showing && adActive) {
            adActive = false;
            var v = findVideo();
            if (v) {
                v.muted = savedMuted;
                setRate(v, config.speed);
            }
        }
    }

    function applySpeed() {
        if (adActive) return;
        setRate(findVideo(), config.speed);
    }

    function checkSponsorSegments(video) {
        if (!config.sponsorSkip || adActive || !sponsor.segments.length) return;
        if (sponsor.videoId !== currentVideoId()) return;
        var t = video.currentTime;
        for (var i = 0; i < sponsor.segments.length; i++) {
            var seg = sponsor.segments[i];
            if (t >= seg[0] && t < seg[1] - 0.5) {
                try { video.currentTime = seg[1]; } catch (e) {}
                report('onSponsorSkipped', String(Math.round(seg[1] - seg[0])));
                return;
            }
        }
    }

    function textOf(selector) {
        var el = document.querySelector(selector);
        var text = el && (el.textContent || '').trim();
        return text || '';
    }

    function reportState(force) {
        var video = hookedVideo;
        var playing = !!(video && !video.paused && !video.ended && !adActive);
        var title = textOf('.slim-video-information-title, ytm-slim-video-metadata-section-renderer h2, h1.title') ||
            (document.title || '').replace(/ - YouTube$/, '');
        var channel = textOf('ytm-slim-owner-renderer .slim-owner-channel-name, .slim-owner-icon-and-title .subhead, #channel-name a');
        var state = {
            videoId: currentVideoId(),
            url: location.href,
            title: title,
            channel: channel,
            duration: video && isFinite(video.duration) ? Math.round(video.duration) : 0,
            playing: playing,
            width: video ? video.videoWidth : 0,
            height: video ? video.videoHeight : 0
        };
        var json = JSON.stringify(state);
        if (!force && json === lastStateJson) return;
        lastStateJson = json;
        report('onState', json);
    }

    function hookVideo(video) {
        if (!video || video === hookedVideo) return;
        hookedVideo = video;
        video.addEventListener('loadedmetadata', function () {
            lastLoadAt = Date.now();
            applySpeed();
            onAdStateChanged();
            reportState();
        });
        video.addEventListener('durationchange', function () { if (adActive) fastForwardAd(); });
        video.addEventListener('playing', function () { applySpeed(); reportState(); });
        video.addEventListener('pause', function () { reportState(); });
        video.addEventListener('ended', function () { reportState(); });
        video.addEventListener('ratechange', function () {
            if (settingRate || adActive || Math.abs(video.playbackRate - config.speed) < 0.01) return;
            var rate = video.playbackRate;
            if (!video.paused && Date.now() - lastLoadAt > 1500 && rate >= 0.25 && rate <= 3) {
                // Changed from YouTube's own speed menu: adopt it so the app stays in sync.
                config.speed = rate;
                report('onSpeedChanged', String(rate));
            } else {
                // YouTube resetting the rate while loading a new video: keep the user's speed.
                setRate(video, config.speed);
            }
        });
        video.addEventListener('timeupdate', function () {
            if (adActive) fastForwardAd();
            else checkSponsorSegments(video);
        });
        applySpeed();
        reportState();
    }

    function hookPlayer() {
        var player = findPlayer();
        if (player && player !== hookedPlayer) {
            if (playerObserver) playerObserver.disconnect();
            hookedPlayer = player;
            adActive = false;
            if (typeof MutationObserver === 'function') {
                // Only the player's own class attribute: cheap, and it is where YouTube flags ads.
                playerObserver = new MutationObserver(onAdStateChanged);
                playerObserver.observe(player, { attributes: true, attributeFilter: ['class'] });
            }
            onAdStateChanged();
        }
        var video = findVideo();
        if (video) hookVideo(video);
    }

    function tick() {
        applyStyles();
        hookPlayer();
        reportState();
    }

    function start() {
        tick();
        // YouTube is a single-page app: re-check after its own navigation events and on a slow
        // heartbeat for title changes and player re-creation. All work in tick() is O(1) lookups.
        ['yt-navigate-finish', 'state-navigateend', 'yt-page-data-updated', 'popstate'].forEach(function (type) {
            window.addEventListener(type, function () { setTimeout(tick, 50); });
        });
        setInterval(tick, 1500);
    }

    if (document.readyState === 'loading') {
        // Styles as soon as <head> exists (avoids ad slots flashing in), the rest once parsed.
        if (document.head) {
            applyStyles();
        } else if (typeof MutationObserver === 'function') {
            var headWatcher = new MutationObserver(function () {
                if (document.head) {
                    headWatcher.disconnect();
                    applyStyles();
                }
            });
            headWatcher.observe(document, { childList: true, subtree: true });
        }
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }

    // ---------------------------------------------------------------------------------------
    // 5. Native -> page API
    // ---------------------------------------------------------------------------------------
    function nudgeLayout() {
        // YouTube sizes the player from window size in JS; after PiP / fullscreen transitions the
        // WebView settles over a few frames, so re-dispatch resize until it has.
        [0, 120, 300, 600, 1000].forEach(function (delay) {
            setTimeout(function () { window.dispatchEvent(new Event('resize')); }, delay);
        });
    }

    window.__streamShield = {
        version: 2,
        internals: internals,
        setConfig: function (next) {
            var wasPip = config.pip;
            var wasAdBlock = config.adBlock;
            for (var key in next) {
                if (Object.prototype.hasOwnProperty.call(next, key)) config[key] = next[key];
            }
            applyStyles();
            if (wasAdBlock !== config.adBlock) onAdStateChanged();
            applySpeed();
            if (wasPip !== config.pip) {
                if (config.pip) {
                    var v = findVideo();
                    if (v && v.paused && !v.ended && !adActive) v.play().catch(function () {});
                }
                nudgeLayout();
            }
        },
        getConfig: function () { return JSON.parse(JSON.stringify(config)); },
        setSponsorSegments: function (videoId, segments) {
            sponsor = { videoId: String(videoId || ''), segments: Array.isArray(segments) ? segments : [] };
        },
        play: function () {
            var v = findVideo();
            if (v) v.play().catch(function () {});
        },
        pause: function () {
            var v = findVideo();
            if (v) v.pause();
        },
        togglePlay: function () {
            var v = findVideo();
            if (!v) return;
            if (v.paused) v.play().catch(function () {}); else v.pause();
        },
        exitFullscreen: function () {
            try {
                if (document.fullscreenElement) document.exitFullscreen();
                else if (document.webkitFullscreenElement) document.webkitExitFullscreen();
            } catch (e) {}
        },
        nudgeLayout: nudgeLayout,
        reportState: function () { reportState(true); }
    };
})();
