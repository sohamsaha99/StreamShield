package com.example.recommend

import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Where a "For you" video came from. */
enum class ForYouSource { SUBSCRIPTION, RELATED, RELATED_DEEP, CHANNEL, SEARCH }

/** A candidate video. 0 / "" = unknown. */
data class ForYouVideo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int,
    val viewCount: Long,
    /** Upload time (ms), when known. */
    val publishedAt: Long,
    /** YouTube's own "2 weeks ago" text, used when [publishedAt] is unknown. */
    val publishedText: String,
    val isShort: Boolean,
    val source: ForYouSource
) {
    /** Key for "same channel": the channel id when known, else the channel name. */
    val channelKey: String get() = channelId.ifBlank { channelName.trim().lowercase() }
}

/** One watched (or saved) video, as the rule sees it. */
data class WatchSignal(
    val videoId: String,
    val channelId: String,
    val channelName: String,
    val watchedFraction: Float,
    val lastWatchedAt: Long,
    val isMusic: Boolean,
    val liked: Boolean,
    val saved: Boolean
) {
    val channelKey: String get() = channelId.ifBlank { channelName.trim().lowercase() }
}

/** How many lists (generations) showed a video on screen without it being opened. */
data class Impression(val count: Int, val lastAt: Long)

/** The candidate videos, by source (see [ForYouRule]). */
data class ForYouCandidates(
    /** New uploads of subscribed channels. */
    val uploads: List<ForYouVideo> = emptyList(),
    /** Seed video id -> YouTube's related videos for it. */
    val related: Map<String, List<ForYouVideo>> = emptyMap(),
    /** Related video id -> its own related videos ("related of related"). */
    val deep: Map<String, List<ForYouVideo>> = emptyMap(),
    /** Recent uploads of watched channels that are not subscribed. */
    val channelUploads: List<ForYouVideo> = emptyList(),
    /** Recent searches, newest first, with their results in YouTube's order. */
    val searches: List<Pair<String, List<ForYouVideo>>> = emptyList()
)

/** What the user did, as the rule needs it. */
data class UserSignals(
    /** Non-music watches, likes and saves. */
    val watches: List<WatchSignal>,
    val subscribed: Set<String>,
    val starred: Set<String>,
    /** Channel key -> number of the channel's videos the user disliked. */
    val dislikedChannels: Map<String, Int> = emptyMap(),
    val impressions: Map<String, Impression> = emptyMap(),
    /** Watched at least [ForYouRule.WATCHED_FRACTION], "Not interested", disliked. */
    val excludedIds: Set<String> = emptySet(),
    val hiddenChannels: Set<String> = emptySet()
)

/** A candidate with its score (after the impression discount). */
data class ScoredVideo(
    val video: ForYouVideo,
    val score: Double,
    val sources: Set<ForYouSource>,
    val starred: Boolean,
    val impressions: Int
) {
    val isSubscriptionUpload: Boolean get() = ForYouSource.SUBSCRIPTION in sources
}

/**
 * The "For you" rule (videos; songs stay in the Music tab), as reviewed (version 2):
 *
 * Seeds: non-music videos of the last 60 days watched at least 50 %, liked or saved; strength =
 * watched fraction x 0.5^(days ago / 30) + 1 if liked + 0.5 if saved, doubled if watched in the
 * last 3 hours. The 30 strongest form the seed pool. Each refresh fetches YouTube's related list
 * for up to 6 pool seeds that have none from the last 24 hours, drawn at random in proportion to
 * strength (the other seeds' lists come from the cache); loading more at the end of the list
 * fetches 4 more.
 *
 * Candidates: A. new uploads of subscribed channels (30 days); B. the seeds' related videos;
 * C. related videos of the 2 best-scoring B videos not expanded in the last 24 hours (2 more when
 * loading more); D. uploads (30 days) of the 5 channels with the highest watch score (sum of
 * watched fraction x 0.5^(days ago / 30), at least 1.0) that are not subscribed, hidden or
 * disliked; E. results of the 2 most recent searches (14 days).
 *
 * Score = A + B + C + D + E + popularity + channel, where A = 3 x 0.5^(age in days / 7);
 * B = 2 x r / max r, r = sum of the strengths of the seeds listing the video; C = number of
 * related-of-related lists with the video / the highest such number; D = 1.5 x 0.5^(age / 7);
 * E = 1 (newest search) or 0.7 (second) x (1 - position / number of results); popularity =
 * 0.25 x min(1, log10(1 + views) / 7); channel = channel score scaled 0..1 (watched fraction x
 * 0.5^(days ago / 30) per watched video, + 2 if subscribed, + 2 more if starred, + 1 per liked
 * video, at most 3) - 0.5 per disliked video of the channel, kept within -1..1. A video shown
 * on screen in k lists of the last 3 days without being opened scores x 0.6^k. Videos scoring
 * 0 or less are left out.
 *
 * Removed: watched at least 50 %, "Not interested", disliked, hidden channels, Shorts, live
 * streams, "- Topic" (auto-generated music) channels.
 *
 * Order: up to 4 uploads of the last 48 hours from subscribed channels first (starred channels
 * first, then newest; not after 2 lists showed them unopened). Then a random order weighted by
 * score: each group (subscription uploads; everything else) is sorted by score / 0.35 + a
 * Gumbel(0, 1) draw, which picks videos in turn with probability proportional to
 * e^(score / 0.35); every third place is a subscription upload (spare places go to the other
 * group). No channel more than twice in any 20 consecutive videos. Up to 200 videos; Home shows
 * 30 and 20 more each time the end is reached.
 */
object ForYouRule {
    const val WATCHED_FRACTION = 0.5f
    /** Below this a known duration is a Short. */
    const val MIN_VIDEO_SEC = 60

    // Seeds and fetching
    const val SEED_DAYS = 60
    const val SEED_POOL = 30
    const val SESSION_HOURS = 3
    const val SESSION_BOOST = 2.0
    const val SEED_FETCHES_PER_REFRESH = 6
    const val SEED_FETCHES_PER_EXTEND = 4
    const val DEEP_FETCHES = 2
    const val WATCHED_CHANNELS = 5
    const val WATCHED_CHANNEL_MIN = 1.0
    const val SEARCH_QUERIES = 2
    const val SEARCH_DAYS = 14

    // Scores
    const val UPLOAD_DAYS = 30
    const val CHANNEL_HALF_LIFE_DAYS = 30.0
    const val FRESH_HALF_LIFE_DAYS = 7.0
    const val IMPRESSION_FACTOR = 0.6
    const val IMPRESSION_DAYS = 3
    const val DISLIKE_PENALTY = 0.5

    // Order
    const val TEMPERATURE = 0.35
    const val PIN_HOURS = 48
    const val MAX_PINNED = 4
    const val PIN_MAX_IMPRESSIONS = 2
    const val SUBSCRIPTION_EVERY = 3
    const val CHANNEL_WINDOW = 20
    const val MAX_PER_WINDOW = 2
    const val MAX_LIST = 200
    const val FIRST_PAGE = 30
    const val PAGE = 20

    private const val HOUR_MS = 60L * 60 * 1000
    private const val DAY_MS = 24 * HOUR_MS

    fun decay(ageMs: Long, halfLifeDays: Double): Double =
        0.5.pow(ageMs.coerceAtLeast(0L).toDouble() / DAY_MS / halfLifeDays)

    // ---- channels -------------------------------------------------------------------------

    /** Raw channel scores (before scaling), keyed by channel key. */
    fun rawChannelScores(
        watches: List<WatchSignal>,
        subscribed: Set<String>,
        starred: Set<String>,
        now: Long
    ): Map<String, Double> {
        val scores = HashMap<String, Double>()
        val likes = HashMap<String, Int>()
        for (w in watches) {
            val key = w.channelKey
            if (key.isBlank()) continue
            scores[key] = (scores[key] ?: 0.0) + w.watchedFraction * decay(now - w.lastWatchedAt, CHANNEL_HALF_LIFE_DAYS)
            if (w.liked) likes[key] = (likes[key] ?: 0) + 1
        }
        likes.forEach { (key, n) -> scores[key] = (scores[key] ?: 0.0) + min(n, 3) }
        subscribed.forEach { id -> scores[id] = (scores[id] ?: 0.0) + 2.0 }
        starred.forEach { id -> if (id in subscribed) scores[id] = (scores[id] ?: 0.0) + 2.0 }
        return scores
    }

    /** Channel scores scaled to 0..1 by the highest one. */
    fun channelScores(raw: Map<String, Double>): Map<String, Double> {
        val max = raw.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyMap()
        return raw.mapValues { (_, v) -> (v / max).coerceIn(0.0, 1.0) }
    }

    /** Scaled channel scores minus [DISLIKE_PENALTY] per disliked video, within -1..1. */
    fun channelAffinity(scaled: Map<String, Double>, disliked: Map<String, Int>): Map<String, Double> {
        val out = HashMap(scaled)
        disliked.forEach { (key, n) -> out[key] = ((out[key] ?: 0.0) - DISLIKE_PENALTY * n).coerceIn(-1.0, 1.0) }
        return out
    }

    /**
     * Channels to follow without a subscription (source D): the highest watch scores (watches
     * only, no subscription or like bonus), at least [WATCHED_CHANNEL_MIN].
     */
    fun watchedChannels(
        watches: List<WatchSignal>,
        subscribed: Set<String>,
        hidden: Set<String>,
        disliked: Map<String, Int>,
        now: Long,
        count: Int = WATCHED_CHANNELS
    ): List<String> {
        val scores = HashMap<String, Double>()
        for (w in watches) {
            if (!CHANNEL_ID.matches(w.channelId)) continue
            scores[w.channelId] = (scores[w.channelId] ?: 0.0) +
                w.watchedFraction * decay(now - w.lastWatchedAt, CHANNEL_HALF_LIFE_DAYS)
        }
        return scores.entries
            .filter { (id, s) -> s >= WATCHED_CHANNEL_MIN && id !in subscribed && id !in hidden && (disliked[id] ?: 0) == 0 }
            .sortedByDescending { it.value }
            .take(count)
            .map { it.key }
    }

    private val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")

    // ---- seeds ----------------------------------------------------------------------------

    fun seedStrength(w: WatchSignal, now: Long): Double {
        val base = w.watchedFraction * decay(now - w.lastWatchedAt, CHANNEL_HALF_LIFE_DAYS) +
            (if (w.liked) 1.0 else 0.0) + (if (w.saved) 0.5 else 0.0)
        return if (now - w.lastWatchedAt <= SESSION_HOURS * HOUR_MS) base * SESSION_BOOST else base
    }

    /** The seed pool, strongest first. */
    fun seedPool(watches: List<WatchSignal>, now: Long): List<Pair<WatchSignal, Double>> =
        watches.asSequence()
            .filter { !it.isMusic && now - it.lastWatchedAt <= SEED_DAYS * DAY_MS }
            .filter { it.watchedFraction >= WATCHED_FRACTION || it.liked || it.saved }
            .distinctBy { it.videoId }
            .map { it to seedStrength(it, now) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(SEED_POOL)
            .toList()

    /**
     * Up to [count] pool seeds without a recent related list ([hasFresh]), drawn without
     * replacement with probability proportional to strength (key ln(strength) + Gumbel).
     */
    fun seedsToFetch(
        pool: List<Pair<WatchSignal, Double>>,
        hasFresh: (String) -> Boolean,
        count: Int,
        random: Random
    ): List<WatchSignal> =
        pool.filter { !hasFresh(it.first.videoId) }
            .map { it.first to ln(it.second) + gumbel(random) }
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }

    /** Related videos (B) with the best scores whose own related lists are not recent. */
    fun deepParents(scored: List<ScoredVideo>, hasFresh: (String) -> Boolean, count: Int = DEEP_FETCHES): List<String> =
        scored.asSequence()
            .filter { ForYouSource.RELATED in it.sources && !hasFresh(it.video.videoId) }
            .sortedByDescending { it.score }
            .take(count)
            .map { it.video.videoId }
            .toList()

    // ---- scoring --------------------------------------------------------------------------

    /** Videos that never show up: see the rule above. */
    fun isExcluded(v: ForYouVideo, excludedIds: Set<String>, hiddenChannels: Set<String>): Boolean =
        v.videoId in excludedIds ||
            (v.channelId.isNotBlank() && v.channelId in hiddenChannels) ||
            v.isShort || (v.durationSec in 1 until MIN_VIDEO_SEC) ||
            v.title.contains("#shorts", ignoreCase = true) ||
            v.channelName.trim().endsWith(" - Topic")

    /** Scores every candidate (merged across sources); see the rule above. */
    fun score(
        candidates: ForYouCandidates,
        user: UserSignals,
        seeds: List<Pair<WatchSignal, Double>>,
        now: Long
    ): List<ScoredVideo> {
        val channel = channelAffinity(
            channelScores(rawChannelScores(user.watches, user.subscribed, user.starred, now)),
            user.dislikedChannels
        )
        val parts = HashMap<String, DoubleArray>()
        val videos = HashMap<String, ForYouVideo>()
        fun add(v: ForYouVideo, source: ForYouSource, value: Double) {
            if (value <= 0 || isExcluded(v, user.excludedIds, user.hiddenChannels)) return
            videos[v.videoId] = richer(videos[v.videoId], v)
            parts.getOrPut(v.videoId) { DoubleArray(ForYouSource.entries.size) }[source.ordinal] += value
        }
        fun recentUpload(v: ForYouVideo) = v.publishedAt > 0 && now - v.publishedAt <= UPLOAD_DAYS * DAY_MS

        // A. subscription uploads
        for (v in candidates.uploads.distinctBy { it.videoId }) {
            if (recentUpload(v)) add(v, ForYouSource.SUBSCRIPTION, 3.0 * decay(now - v.publishedAt, FRESH_HALF_LIFE_DAYS))
        }
        // B. related videos of the seeds, weighted by seed strength
        val strength = seeds.associate { it.first.videoId to it.second }
        val r = HashMap<String, Double>()
        val relatedVideos = HashMap<String, ForYouVideo>()
        for ((seedId, list) in candidates.related) {
            val w = strength[seedId] ?: continue
            for (v in list.distinctBy { it.videoId }) {
                if (isExcluded(v, user.excludedIds, user.hiddenChannels)) continue
                r[v.videoId] = (r[v.videoId] ?: 0.0) + w
                relatedVideos[v.videoId] = richer(relatedVideos[v.videoId], v)
            }
        }
        val maxR = r.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        r.forEach { (id, value) -> add(relatedVideos.getValue(id), ForYouSource.RELATED, 2.0 * value / maxR) }
        // C. related of related
        val d = HashMap<String, Int>()
        val deepVideos = HashMap<String, ForYouVideo>()
        for ((_, list) in candidates.deep) {
            for (v in list.distinctBy { it.videoId }) {
                d[v.videoId] = (d[v.videoId] ?: 0) + 1
                deepVideos[v.videoId] = richer(deepVideos[v.videoId], v)
            }
        }
        val maxD = d.values.maxOrNull()?.takeIf { it > 0 } ?: 1
        d.forEach { (id, n) -> add(deepVideos.getValue(id), ForYouSource.RELATED_DEEP, n.toDouble() / maxD) }
        // D. watched channels without a subscription
        for (v in candidates.channelUploads.distinctBy { it.videoId }) {
            if (recentUpload(v)) add(v, ForYouSource.CHANNEL, 1.5 * decay(now - v.publishedAt, FRESH_HALF_LIFE_DAYS))
        }
        // E. recent searches
        candidates.searches.take(SEARCH_QUERIES).forEachIndexed { qi, (_, list) ->
            val weight = if (qi == 0) 1.0 else 0.7
            val n = list.size
            list.forEachIndexed { i, v -> add(v, ForYouSource.SEARCH, weight * (1.0 - i.toDouble() / n)) }
        }

        val impressionWindow = IMPRESSION_DAYS * DAY_MS
        return parts.mapNotNull { (id, p) ->
            val v = videos.getValue(id)
            val popularity = 0.25 * min(1.0, log10(1.0 + v.viewCount.coerceAtLeast(0L)) / 7.0)
            val raw = p.sum() + popularity + (channel[v.channelKey] ?: 0.0)
            val k = user.impressions[id]?.takeIf { now - it.lastAt <= impressionWindow }?.count ?: 0
            val discounted = if (raw > 0) raw * IMPRESSION_FACTOR.pow(k) else raw
            if (discounted <= 0) return@mapNotNull null
            val sources = ForYouSource.entries.filter { p[it.ordinal] > 0 }.toSet()
            val main = ForYouSource.entries.maxBy { p[it.ordinal] }
            ScoredVideo(v.copy(source = main), discounted, sources, v.channelKey in user.starred, k)
        }
    }

    /** Of two copies of a video from different lists, the one that knows more. */
    private fun richer(a: ForYouVideo?, b: ForYouVideo): ForYouVideo {
        if (a == null) return b
        fun known(v: ForYouVideo) = (if (v.durationSec > 0) 1 else 0) + (if (v.viewCount > 0) 1 else 0) +
            (if (v.publishedAt > 0) 1 else 0) + (if (v.channelId.isNotBlank()) 1 else 0)
        return if (known(b) > known(a)) b else a
    }

    // ---- order ----------------------------------------------------------------------------

    /** A Gumbel(0, 1) draw. */
    fun gumbel(random: Random): Double {
        val u = random.nextDouble().coerceIn(1e-12, 1 - 1e-12)
        return -ln(-ln(u))
    }

    /**
     * Orders [scored] as the rule says. [listed] are videos already in the list (left out; the
     * last of them count for the channel spacing); [pin] puts fresh subscription uploads first.
     */
    fun order(
        scored: List<ScoredVideo>,
        now: Long,
        random: Random,
        listed: List<ForYouVideo> = emptyList(),
        pin: Boolean = true,
        max: Int = MAX_LIST
    ): List<ForYouVideo> {
        val listedIds = listed.mapTo(HashSet()) { it.videoId }
        val pool = scored.filter { it.video.videoId !in listedIds }

        val out = ArrayList<ForYouVideo>()
        val placed = ArrayList<ForYouVideo>(listed.takeLast(CHANNEL_WINDOW - 1))
        fun place(v: ForYouVideo) {
            out.add(v)
            placed.add(v)
        }
        fun allowed(v: ForYouVideo): Boolean {
            val key = v.channelKey
            if (key.isBlank()) return true
            val window = placed.subList((placed.size - (CHANNEL_WINDOW - 1)).coerceAtLeast(0), placed.size)
            return window.count { it.channelKey == key } < MAX_PER_WINDOW
        }
        fun takeAllowed(queue: ArrayDeque<ForYouVideo>): ForYouVideo? {
            val i = queue.indexOfFirst { allowed(it) }
            return if (i >= 0) queue.removeAt(i) else null
        }

        // Fresh subscription uploads first (also within the channel spacing).
        val pinnedIds = HashSet<String>()
        if (pin) {
            pool.filter {
                it.isSubscriptionUpload && it.video.publishedAt > 0 &&
                    now - it.video.publishedAt <= PIN_HOURS * HOUR_MS && it.impressions < PIN_MAX_IMPRESSIONS
            }
                .sortedWith(compareByDescending<ScoredVideo> { it.starred }.thenByDescending { it.video.publishedAt })
                .forEach { p ->
                    if (pinnedIds.size < MAX_PINNED && out.size < max && allowed(p.video)) {
                        place(p.video)
                        pinnedIds.add(p.video.videoId)
                    }
                }
        }
        fun weightedOrder(list: List<ScoredVideo>): ArrayDeque<ForYouVideo> =
            ArrayDeque(list.map { it to it.score / TEMPERATURE + gumbel(random) }.sortedByDescending { it.second }.map { it.first.video })
        val rest = pool.filter { it.video.videoId !in pinnedIds }
        val subs = weightedOrder(rest.filter { it.isSubscriptionUpload })
        val others = weightedOrder(rest.filter { !it.isSubscriptionUpload })

        var slot = 0
        while (out.size < max && (subs.isNotEmpty() || others.isNotEmpty())) {
            val subscriptionTurn = slot % SUBSCRIPTION_EVERY == SUBSCRIPTION_EVERY - 1
            val first = if (subscriptionTurn) subs else others
            val second = if (subscriptionTurn) others else subs
            // Nothing fits the spacing (only one or two channels left): take the next anyway.
            val next = takeAllowed(first) ?: takeAllowed(second) ?: first.removeFirstOrNull() ?: second.removeFirst()
            place(next)
            slot++
        }
        return out
    }
}
