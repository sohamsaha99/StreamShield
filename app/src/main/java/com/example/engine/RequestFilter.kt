package com.example.engine

import java.util.Locale

enum class BlockKind { AD, TRACKER }

/**
 * Network-level filter for WebView sub-resource requests. Pure Kotlin (no Android types) so it
 * is cheap to call from WebViewClient.shouldInterceptRequest, which runs for every request.
 *
 * YouTube's video ads are mostly removed by the page script (JSON pruning); this list covers ad
 * and tracking endpoints that the page still calls, plus third-party ad networks.
 */
object RequestFilter {

    private val AD_DOMAINS = hashSetOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "imasdk.googleapis.com",
        "2mdn.net",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "moatads.com",
        "advertising.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "criteo.net",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "adform.net",
        "smartadserver.com",
        "teads.tv"
    )

    private val TRACKER_DOMAINS = hashSetOf(
        "google-analytics.com",
        "googletagservices.com",
        "app-measurement.com",
        "scorecardresearch.com",
        "quantserve.com",
        "doubleverify.com",
        "adsafeprotected.com",
        "imrworldwide.com",
        "chartbeat.com"
    )

    /** First-party hosts whose ad endpoints are matched by path. */
    private val FIRST_PARTY_HOSTS = listOf("youtube.com", "youtube-nocookie.com", "google.com")

    private val AD_PATHS = listOf(
        "/pagead/",
        "/api/stats/ads",
        "/get_midroll_",
        "/pcs/activeview",
        "/ptracking"
    )

    /**
     * Scripts that pages use to detect ad blockers get a harmless stand-in instead of an empty
     * response (as uBlock Origin does). YouTube loads ad_status.js and checks that it set
     * `google_ad_status`; an empty response tells it an ad blocker is active.
     */
    fun surrogateFor(url: String, adBlockEnabled: Boolean): String? {
        if (!adBlockEnabled) return null
        if (hostOf(url) == "static.doubleclick.net" && pathOf(url) == "/instream/ad_status.js") {
            return "window.google_ad_status = 1;"
        }
        return null
    }

    fun evaluate(url: String, adBlockEnabled: Boolean): BlockKind? {
        if (!adBlockEnabled) return null
        val host = hostOf(url) ?: return null

        if (matchesDomain(host, AD_DOMAINS)) return BlockKind.AD
        if (matchesDomain(host, TRACKER_DOMAINS)) return BlockKind.TRACKER

        if (FIRST_PARTY_HOSTS.any { host == it || host.endsWith(".$it") }) {
            val path = pathOf(url)
            if (AD_PATHS.any { path.startsWith(it) }) return BlockKind.AD
        }
        return null
    }

    /** Checks the host and each parent domain against the set ("a.b.example.com" → … → "example.com"). */
    private fun matchesDomain(host: String, domains: Set<String>): Boolean {
        var candidate = host
        while (true) {
            if (candidate in domains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.length - 1) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    internal fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return null
        val start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        var authority = url.substring(start, end)
        authority = authority.substringAfterLast('@')
        if (authority.startsWith("[")) return null // IPv6 literal: never an ad host we list
        val host = authority.substringBefore(':')
        return host.lowercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    internal fun pathOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val slash = url.indexOf('/', schemeEnd + 3)
        if (slash < 0) return "/"
        val query = url.indexOf('?', slash).let { if (it < 0) url.length else it }
        val hash = url.indexOf('#', slash).let { if (it < 0) url.length else it }
        return url.substring(slash, minOf(query, hash))
    }
}
