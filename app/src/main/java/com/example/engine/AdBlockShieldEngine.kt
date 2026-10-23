package com.example.engine

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

data class BlockDecision(
    val blocked: Boolean,
    val isVideoAd: Boolean = false,
    val matchedRule: String = ""
)

object AdBlockShieldEngine {

    private val BLOCKED_AD_DOMAINS = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "imasdk.googleapis.com",
        "2mdn.net",
        "adnxs.com",
        "adsrvr.org",
        "moatads.com",
        "advertising.com",
        "taboola.com",
        "outbrain.com"
    )

    private val BLOCKED_TRACKER_DOMAINS = setOf(
        "google-analytics.com",
        "ssl.google-analytics.com",
        "scorecardresearch.com",
        "quantserve.com",
        "criteo.com",
        "rubiconproject.com"
    )

    private val YOUTUBE_AD_PATH_PATTERNS = listOf(
        "/api/stats/ads",
        "/pagead/",
        "/ptracking",
        "/get_midroll_info",
        "adformat=",
        "ad_type=",
        "ad_break",
        "/pcs/activeview",
        "/generate_204?ad"
    )

    fun evaluateRequest(url: String, adBlockEnabled: Boolean): BlockDecision {
        if (!adBlockEnabled) return BlockDecision(blocked = false)
        val lowerUrl = url.lowercase(Locale.US)

        val host = try {
            Uri.parse(lowerUrl).host ?: ""
        } catch (_: Exception) {
            ""
        }

        // Check YouTube-specific video ad endpoints
        if (host.contains("youtube.com") || host.contains("googlevideo.com")) {
            for (pattern in YOUTUBE_AD_PATH_PATTERNS) {
                if (lowerUrl.contains(pattern)) {
                    return BlockDecision(blocked = true, isVideoAd = true, matchedRule = pattern)
                }
            }
        }

        // Check ad domains
        for (domain in BLOCKED_AD_DOMAINS) {
            if (host == domain || host.endsWith(".$domain")) {
                return BlockDecision(blocked = true, isVideoAd = true, matchedRule = domain)
            }
        }

        // Check tracker domains
        for (domain in BLOCKED_TRACKER_DOMAINS) {
            if (host == domain || host.endsWith(".$domain")) {
                return BlockDecision(blocked = true, isVideoAd = false, matchedRule = domain)
            }
        }

        return BlockDecision(blocked = false)
    }

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Builds the Brave-style Shield + Background Play + PiP Pinning + Speed Control JS script.
     *
     * Key techniques for zero-delay ad elimination (no "Skip Ad" fixed screen):
     * 1. Object.prototype property traps on `adPlacements`, `playerAds`, `adSlots`, and
     *    `adBreakHeartbeatParams` so YouTube's player payload never contains scheduled ads.
     * 2. JSON.parse & Response.prototype.json pruning of ad arrays before player initialization.
     * 3. 50ms + MutationObserver instant ad-state neutralizer that forces immediate playback of
     *    the main video without waiting for a skip countdown.
     * 4. Full-bleed Picture-in-Picture CSS & playback watchdog so the video fills 100% of the
     *    minimized PiP window and never pauses when losing focus.
     */
    fun buildShieldInjectionJs(
        adBlockEnabled: Boolean,
        sponsorSkipEnabled: Boolean,
        backgroundPlayEnabled: Boolean,
        playbackSpeed: Float,
        isInPipMode: Boolean
    ): String {
        val safeSpeed = playbackSpeed.coerceIn(0.25f, 3.0f)
        return """
        (function() {
            window.__streamShieldTargetSpeed = $safeSpeed;
            window.__streamShieldAdBlock = $adBlockEnabled;
            window.__streamShieldSponsorSkip = $sponsorSkipEnabled;
            window.__streamShieldBgPlay = $backgroundPlayEnabled;
            window.__streamShieldInPip = $isInPipMode;

            // 1. BRAVE-STYLE JSON AD PRUNING (Prevents pre-roll & "Skip Ad" screens from ever initializing)
            if (window.__streamShieldAdBlock && !window.__streamShieldJsonPruneInstalled) {
                window.__streamShieldJsonPruneInstalled = true;
                try {
                    const adProps = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
                    adProps.forEach(function(prop) {
                        try {
                            Object.defineProperty(Object.prototype, prop, {
                                get: function() { return undefined; },
                                set: function(val) {
                                    if (window.StreamShieldBridge && window.StreamShieldBridge.onAdSkipped) {
                                        window.StreamShieldBridge.onAdSkipped();
                                    }
                                },
                                configurable: true
                            });
                        } catch (e) {}
                    });

                    // Clean existing ytInitialPlayerResponse if already present
                    if (window.ytInitialPlayerResponse) {
                        delete window.ytInitialPlayerResponse.adPlacements;
                        delete window.ytInitialPlayerResponse.playerAds;
                        delete window.ytInitialPlayerResponse.adSlots;
                        delete window.ytInitialPlayerResponse.adBreakHeartbeatParams;
                    }

                    // Wrap JSON.parse to strip ad payloads from /youtubei/v1/player responses
                    const origJsonParse = JSON.parse;
                    JSON.parse = function(text, reviver) {
                        const data = origJsonParse.call(this, text, reviver);
                        if (data && typeof data === 'object' && window.__streamShieldAdBlock) {
                            delete data.adPlacements;
                            delete data.playerAds;
                            delete data.adSlots;
                            delete data.adBreakHeartbeatParams;
                        }
                        return data;
                    };

                    // Wrap Response.prototype.json for Fetch API player requests
                    if (window.Response && Response.prototype.json) {
                        const origRespJson = Response.prototype.json;
                        Response.prototype.json = function() {
                            return origRespJson.call(this).then(function(data) {
                                if (data && typeof data === 'object' && window.__streamShieldAdBlock) {
                                    delete data.adPlacements;
                                    delete data.playerAds;
                                    delete data.adSlots;
                                    delete data.adBreakHeartbeatParams;
                                }
                                return data;
                            });
                        };
                    }
                } catch (e) {}
            }

            // 2. BACKGROUND PLAYBACK & FOCUS LOCK (Keeps video playing in PiP and when screen is off)
            if (!window.__streamShieldBgInstalled) {
                window.__streamShieldBgInstalled = true;
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                    document.hasFocus = function() { return true; };

                    const blockedEvents = ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide', 'freeze'];
                    const origDocAdd = document.addEventListener.bind(document);
                    document.addEventListener = function(type, listener, options) {
                        if ((window.__streamShieldBgPlay || window.__streamShieldInPip) && blockedEvents.indexOf(type) !== -1) {
                            return;
                        }
                        return origDocAdd(type, listener, options);
                    };
                    const origWinAdd = window.addEventListener.bind(window);
                    window.addEventListener = function(type, listener, options) {
                        if ((window.__streamShieldBgPlay || window.__streamShieldInPip) && blockedEvents.indexOf(type) !== -1) {
                            return;
                        }
                        return origWinAdd(type, listener, options);
                    };
                } catch (e) {}
            }

            // 3. INJECT AD-BLOCKING CSS (Hides all ad overlays, endcaps, and skip-ad interstitial screens)
            if (window.__streamShieldAdBlock && !document.getElementById('streamshield-adblock-style')) {
                const style = document.createElement('style');
                style.id = 'streamshield-adblock-style';
                style.textContent = `
                    ytm-promoted-sparkles-web-renderer,
                    ytm-promoted-video-renderer,
                    ytm-companion-ad-renderer,
                    ytm-in-feed-ad-layout-renderer,
                    ytd-ad-slot-renderer,
                    ytd-in-feed-ad-layout-renderer,
                    ytd-banner-promo-renderer,
                    .ytp-ad-overlay-container,
                    .ytp-ad-message-container,
                    .ytp-ad-player-overlay,
                    .ytp-ad-player-overlay-layout,
                    .ytp-ad-action-interstitial,
                    .ytp-ad-action-interstitial-slot,
                    .ytp-ad-image-overlay,
                    .ytp-ad-text-overlay,
                    .ad-showing .ytp-ad-player-overlay,
                    .ad-showing .ytp-ad-player-overlay-layout,
                    .ad-showing .ytp-ad-action-interstitial,
                    ytm-mealbar-promo-renderer,
                    ytm-statement-banner-renderer,
                    .mealbar-promo-renderer {
                        display: none !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                        height: 0 !important;
                        width: 0 !important;
                        visibility: hidden !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);
            }

            // 4. PICTURE-IN-PICTURE FULL-BLEED PINNING & PLAYBACK KEEPER
            window.setStreamShieldPipMode = function(inPip) {
                window.__streamShieldInPip = !!inPip;
                let pipStyle = document.getElementById('streamshield-pip-style');
                if (inPip) {
                    if (!pipStyle) {
                        pipStyle = document.createElement('style');
                        pipStyle.id = 'streamshield-pip-style';
                        (document.head || document.documentElement).appendChild(pipStyle);
                    }
                    pipStyle.textContent = `
                        html, body {
                            overflow: hidden !important;
                            background: #000 !important;
                            margin: 0 !important;
                            padding: 0 !important;
                        }
                        ytm-mobile-topbar-renderer,
                        #header-bar,
                        .player-controls-content,
                        #player-control-overlay,
                        .ytp-chrome-top,
                        .ytp-chrome-bottom,
                        ytm-single-column-watch-next-results-renderer,
                        ytm-item-section-renderer,
                        ytm-comments-entry-point-header-renderer {
                            display: none !important;
                            opacity: 0 !important;
                        }
                        #player-container-id,
                        #player,
                        #movie_player,
                        .html5-video-player,
                        .html5-main-video,
                        video {
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            right: 0 !important;
                            bottom: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            max-width: 100vw !important;
                            max-height: 100vh !important;
                            z-index: 2147483647 !important;
                            background: #000 !important;
                            object-fit: contain !important;
                        }
                    `;
                    const v = document.querySelector('video');
                    if (v && v.paused && !v.ended) {
                        v.play().catch(function(){});
                    }
                } else {
                    if (pipStyle) {
                        pipStyle.remove();
                    }
                }
            };

            window.setStreamShieldPipMode(window.__streamShieldInPip);

            // 5. GLOBAL PLAYBACK SPEED CONTROLLER
            window.setStreamShieldSpeed = function(speed) {
                window.__streamShieldTargetSpeed = speed;
                const videos = document.querySelectorAll('video');
                videos.forEach(function(v) {
                    if (Math.abs(v.playbackRate - speed) > 0.01) {
                        v.playbackRate = speed;
                    }
                });
            };

            window.setStreamShieldSpeed($safeSpeed);

            // 6. FAST 60ms AD-ELIMINATOR & PIP WATCHDOG
            function neutralizeAdsAndSync() {
                try {
                    const player = document.querySelector('#movie_player, .html5-video-player');
                    const video = document.querySelector('video');

                    if (window.__streamShieldAdBlock) {
                        // Call internal YouTube player skipAd API if available
                        if (player && typeof player.skipAd === 'function') {
                            try { player.skipAd(); } catch (e) {}
                        }

                        // Click any skip or interstitial dismiss button immediately
                        const skipSelectors = [
                            '.ytp-ad-skip-button',
                            '.ytp-ad-skip-button-modern',
                            '.ytp-skip-ad-button',
                            '.ytp-ad-skip-button-slot button',
                            '.ytp-ad-overlay-close-button',
                            '.videoAdUiSkipButton'
                        ];
                        for (let i = 0; i < skipSelectors.length; i++) {
                            const btn = document.querySelector(skipSelectors[i]);
                            if (btn) {
                                btn.click();
                                if (window.StreamShieldBridge && window.StreamShieldBridge.onAdSkipped) {
                                    window.StreamShieldBridge.onAdSkipped();
                                }
                            }
                        }

                        // If player is in ad-showing or action-interstitial state, jump straight to end
                        const isAdShowing = (player && (
                            player.classList.contains('ad-showing') ||
                            player.classList.contains('ad-interrupting') ||
                            player.classList.contains('ytp-ad-action-interstitial')
                        )) || document.querySelector('.ad-showing, .ytp-ad-action-interstitial-slot');

                        if (isAdShowing && video) {
                            video.muted = true;
                            video.playbackRate = 16.0;
                            if (!isNaN(video.duration) && video.duration > 0 && isFinite(video.duration)) {
                                video.currentTime = video.duration + 1;
                            } else {
                                video.currentTime = 9999;
                            }
                            if (window.StreamShieldBridge && window.StreamShieldBridge.onAdSkipped) {
                                window.StreamShieldBridge.onAdSkipped();
                            }
                        } else if (video) {
                            // Restore normal unmuted state and custom playback speed for actual content
                            const target = window.__streamShieldTargetSpeed || 1.0;
                            if (Math.abs(video.playbackRate - target) > 0.02) {
                                video.playbackRate = target;
                            }
                        }
                    }

                    // Keep video playing if in Picture-in-Picture mode
                    if (window.__streamShieldInPip && video && video.paused && !video.ended && video.readyState >= 2) {
                        video.play().catch(function(){});
                    }
                } catch (err) {}
            }

            if (!window.__streamShieldFastLoopInstalled) {
                window.__streamShieldFastLoopInstalled = true;
                setInterval(neutralizeAdsAndSync, 80);

                // Also attach MutationObserver to #movie_player / body for 0ms reaction
                try {
                    const observer = new MutationObserver(function() {
                        neutralizeAdsAndSync();
                    });
                    if (document.documentElement) {
                        observer.observe(document.documentElement, {
                            childList: true,
                            subtree: true,
                            attributes: true,
                            attributeFilter: ['class', 'src']
                        });
                    }
                } catch (e) {}

                // Metadata & Direct Stream Extractor every 800ms
                setInterval(function() {
                    try {
                        if (!window.StreamShieldBridge || !window.StreamShieldBridge.onVideoStateUpdate) return;
                        const video = document.querySelector('video');
                        const titleEl = document.querySelector(
                            '.slim-video-information-title, h1.title, .ytm-video-meta-block .slim-video-information-title'
                        );
                        const channelEl = document.querySelector(
                            '.slim-owner-icon-and-title .subhead, .ytm-channel-name, #channel-name'
                        );
                        let rawTitle = (titleEl && titleEl.innerText) ? titleEl.innerText.trim() : document.title.replace(' - YouTube', '');
                        let rawChannel = (channelEl && channelEl.innerText) ? channelEl.innerText.trim() : 'YouTube';
                        let dur = (video && !isNaN(video.duration) && video.duration > 0) ? Math.round(video.duration) : 240;
                        const playing = video ? (!video.paused && !video.ended) : false;

                        // Extract direct stream URL from ytInitialPlayerResponse if present
                        let directStreamUrl = '';
                        try {
                            const pr = window.ytInitialPlayerResponse;
                            if (pr && pr.videoDetails) {
                                if (!rawTitle || rawTitle === 'YouTube') rawTitle = pr.videoDetails.title || rawTitle;
                                if (!rawChannel || rawChannel === 'YouTube') rawChannel = pr.videoDetails.author || rawChannel;
                                const lenSec = parseInt(pr.videoDetails.lengthSeconds || '0', 10);
                                if (lenSec > 0) dur = lenSec;
                            }
                            if (pr && pr.streamingData && pr.streamingData.formats && pr.streamingData.formats.length > 0) {
                                for (let i = 0; i < pr.streamingData.formats.length; i++) {
                                    const f = pr.streamingData.formats[i];
                                    if (f.url && f.url.startsWith('http')) {
                                        directStreamUrl = f.url;
                                        break;
                                    }
                                }
                            }
                        } catch (e) {}

                        if (!directStreamUrl && video && video.currentSrc && video.currentSrc.startsWith('http') && !video.currentSrc.startsWith('blob:')) {
                            directStreamUrl = video.currentSrc;
                        }

                        window.StreamShieldBridge.onVideoStateUpdate(
                            rawTitle || 'YouTube Stream',
                            rawChannel || 'YouTube',
                            window.location.href,
                            dur,
                            playing,
                            directStreamUrl || ''
                        );
                    } catch (err) {}
                }, 800);
            }
        })();
        """.trimIndent()
    }
}
