package com.example.recommend

import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Where a "For you" video came from. */
enum class ForYouSource { SUBSCRIPTION, RELATED }

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

data class RankedVideo(val video: ForYouVideo, val score: Double)

/**
 * The "For you" rule (videos; songs stay in the Music tab), as reviewed:
 *
 * Candidates: A. new uploads from subscribed channels (last 30 days); B. related videos
 * (YouTube's up-next list) of up to 8 seed videos: watched at least 50 %, liked or saved in the
 * last 60 days.
 *
 * Channel score (scaled 0..1 by the highest channel): sum over the channel's watched videos of
 * watched fraction x 0.5^(days ago / 30), + 2 if subscribed (+2 more if starred), + 1 per liked
 * video (at most 3).
 *
 * Video score: A = 3 x 0.5^(age in days / 7) + channel score; B = how many seeds list it, each
 * weighted by the seed's strength (scaled 0..2) + channel score.
 *
 * Removed: watched at least 50 %, "Not interested", hidden channels, Shorts, live streams,
 * "- Topic" (auto-generated music) channels. At most 3 per channel, 30 videos, 50 % A / 50 % B
 * (spare slots go to the other side), interleaved.
 */
object ForYouRule {
    const val SIZE = 30
    const val SUBSCRIPTION_SHARE = 0.5
    const val MAX_PER_CHANNEL = 3
    const val SEED_COUNT = 8
    const val SEED_DAYS = 60
    const val UPLOAD_DAYS = 30
    const val CHANNEL_HALF_LIFE_DAYS = 30.0
    const val FRESH_HALF_LIFE_DAYS = 7.0
    const val WATCHED_FRACTION = 0.5f
    /** Below this a known duration is a Short. */
    const val MIN_VIDEO_SEC = 60

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun decay(ageMs: Long, halfLifeDays: Double): Double =
        0.5.pow(ageMs.coerceAtLeast(0L).toDouble() / DAY_MS / halfLifeDays)

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

    fun seedStrength(w: WatchSignal, now: Long): Double =
        w.watchedFraction * decay(now - w.lastWatchedAt, CHANNEL_HALF_LIFE_DAYS) +
            (if (w.liked) 1.0 else 0.0) + (if (w.saved) 0.5 else 0.0)

    /** The seed videos, strongest first. */
    fun pickSeeds(watches: List<WatchSignal>, now: Long): List<Pair<WatchSignal, Double>> =
        watches.asSequence()
            .filter { !it.isMusic && now - it.lastWatchedAt <= SEED_DAYS * DAY_MS }
            .filter { it.watchedFraction >= WATCHED_FRACTION || it.liked || it.saved }
            .map { it to seedStrength(it, now) }
            .sortedByDescending { it.second }
            .take(SEED_COUNT)
            .toList()

    /** Videos that never show up: see the rule above. */
    fun isExcluded(
        v: ForYouVideo,
        watched: Set<String>,
        notInterested: Set<String>,
        hiddenChannels: Set<String>
    ): Boolean =
        v.videoId in watched || v.videoId in notInterested ||
            (v.channelId.isNotBlank() && v.channelId in hiddenChannels) ||
            v.isShort || (v.durationSec in 1 until MIN_VIDEO_SEC) ||
            v.title.contains("#shorts", ignoreCase = true) ||
            v.channelName.trim().endsWith(" - Topic")

    fun rankSubscriptionUploads(
        uploads: List<ForYouVideo>,
        channel: Map<String, Double>,
        now: Long,
        excluded: (ForYouVideo) -> Boolean
    ): List<RankedVideo> =
        uploads.asSequence()
            .filter { it.publishedAt > 0 && now - it.publishedAt <= UPLOAD_DAYS * DAY_MS && !excluded(it) }
            .distinctBy { it.videoId }
            .map { RankedVideo(it, 3.0 * decay(now - it.publishedAt, FRESH_HALF_LIFE_DAYS) + (channel[it.channelKey] ?: 0.0)) }
            .sortedByDescending { it.score }
            .toList()

    /** [related] maps each seed's video id to the videos YouTube lists next to it. */
    fun rankRelated(
        seeds: List<Pair<WatchSignal, Double>>,
        related: Map<String, List<ForYouVideo>>,
        channel: Map<String, Double>,
        excluded: (ForYouVideo) -> Boolean
    ): List<RankedVideo> {
        val maxSeed = seeds.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
        val raw = LinkedHashMap<String, Double>()
        val videos = HashMap<String, ForYouVideo>()
        for ((seed, strength) in seeds) {
            for (v in related[seed.videoId].orEmpty().distinctBy { it.videoId }) {
                if (excluded(v)) continue
                videos.putIfAbsent(v.videoId, v)
                raw[v.videoId] = (raw[v.videoId] ?: 0.0) + strength / maxSeed
            }
        }
        val maxRaw = raw.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return raw.entries
            .map { (id, r) ->
                val v = videos.getValue(id)
                RankedVideo(v, 2.0 * r / maxRaw + (channel[v.channelKey] ?: 0.0))
            }
            .sortedByDescending { it.score }
    }

    /**
     * Picks [size] videos: [share] of them from [subscriptions], the rest from [related] (spare
     * slots go to the other list), at most [maxPerChannel] per channel, no duplicates, then
     * interleaves the two lists in that proportion.
     */
    fun mix(
        subscriptions: List<RankedVideo>,
        related: List<RankedVideo>,
        size: Int = SIZE,
        share: Double = SUBSCRIPTION_SHARE,
        maxPerChannel: Int = MAX_PER_CHANNEL
    ): List<ForYouVideo> {
        val perChannel = HashMap<String, Int>()
        val taken = HashSet<String>()
        fun take(list: List<RankedVideo>, limit: Int, into: MutableList<ForYouVideo>) {
            for (r in list) {
                if (into.size >= limit) return
                val v = r.video
                if (v.videoId in taken) continue
                val key = v.channelKey
                val n = perChannel.getOrDefault(key, 0)
                if (key.isNotBlank() && n >= maxPerChannel) continue
                taken.add(v.videoId)
                if (key.isNotBlank()) perChannel[key] = n + 1
                into.add(v)
            }
        }
        val subTarget = (size * share).roundToInt()
        val subs = mutableListOf<ForYouVideo>()
        val rel = mutableListOf<ForYouVideo>()
        take(subscriptions, subTarget, subs)
        take(related, size - subs.size, rel)
        // Related could not fill its share: more subscription uploads.
        if (subs.size + rel.size < size) take(subscriptions, size - rel.size, subs)

        // Interleave so the list keeps the chosen proportion all the way down.
        val out = ArrayList<ForYouVideo>(subs.size + rel.size)
        var i = 0
        var j = 0
        while (i < subs.size || j < rel.size) {
            val subsDue = j >= rel.size || (i < subs.size && i * (rel.size + subs.size) <= (i + j) * subs.size)
            if (subsDue) out.add(subs[i++]) else out.add(rel[j++])
        }
        return out
    }
}
