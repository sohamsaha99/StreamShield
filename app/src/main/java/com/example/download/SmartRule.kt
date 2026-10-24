package com.example.download

import com.example.data.PlayEventEntity
import com.example.data.SmartSection
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/** A song as smart downloads see it. Duration 0 means unknown. */
data class SongInfo(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int
) {
    /** Key for "same artist": the channel id when known, else the channel name. */
    val artistKey: String get() = channelId.ifBlank { channelName.trim().lowercase() }
}

/** Everything known about one song the user has listened to or saved. */
data class SongSignals(
    val song: SongInfo,
    val plays: List<PlayEventEntity>,
    val liked: Boolean,
    val inPlaylist: Boolean,
    val subscribed: Boolean
)

data class RankedSong(val song: SongInfo, val score: Double, val section: SmartSection)

/** The result of a refresh: songs to keep, to download (in order) and to delete. */
data class SmartPlan(
    val keep: List<RankedSong>,
    val download: List<RankedSong>,
    val remove: Set<String>
) {
    val all: List<RankedSong> get() = keep + download
}

/**
 * The smart-download rule (music only), as reviewed:
 *
 * Play weight: full listen (to the end or at least 80 %) +1.0; partial (at least 30 s) +0.4;
 * skip (under 30 s) -0.5. Each play fades with age: weight x 0.5^(days ago / 30).
 *
 * Song score = sum of faded plays + 0.25 x lifetime full listens (at most 20 counted)
 *              + 3 if liked + 2 if in one of the user's playlists + 1 if subscribed to the channel.
 *
 * Budget: half for "Your music" (highest scores among songs finished at least once, liked or in a
 * playlist), half for "Discover" (unheard songs from YouTube's related lists of the top 10 songs:
 * ranked by how many of them list a song, weighted by their scores, +1 for artists the user
 * already listens to; at most 5 per artist). Space one section cannot use goes to the other.
 *
 * Anti-churn: a song already downloaded stays while it ranks within 1.2 x the number of songs
 * picked in its section.
 */
object SmartRule {
    const val HALF_LIFE_DAYS = 30.0
    const val MIN_SONG_SEC = 60
    const val MAX_SONG_SEC = 600
    val SONG_LENGTH = MIN_SONG_SEC..MAX_SONG_SEC

    /** AAC 128 kbps = 16 000 bytes per second (about 1 MB per minute). */
    const val BYTES_PER_SEC = 16_000L
    const val DEFAULT_SONG_SEC = 240

    const val YOUR_MUSIC_SHARE = 0.5
    const val KEEP_MARGIN = 1.2
    const val MAX_PER_ARTIST_DISCOVER = 5
    const val SEED_COUNT = 10
    const val REMOVED_DAYS = 60
    /** Fewer songs with plays than this: also use recent uploads of subscribed music channels. */
    const val NEW_USER_SONGS = 5

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun isFullListen(play: PlayEventEntity): Boolean =
        play.completed || (play.durationSec > 0 && play.listenedSec >= 0.8 * play.durationSec)

    fun playWeight(play: PlayEventEntity): Double = when {
        isFullListen(play) -> 1.0
        play.listenedSec >= 30 -> 0.4
        else -> -0.5
    }

    fun decay(ageMs: Long): Double = 0.5.pow(ageMs.coerceAtLeast(0L).toDouble() / DAY_MS / HALF_LIFE_DAYS)

    fun fullListens(plays: List<PlayEventEntity>): Int = plays.count { isFullListen(it) }

    fun score(signals: SongSignals, now: Long): Double {
        val faded = signals.plays.sumOf { playWeight(it) * decay(now - it.startedAt) }
        val lifetime = 0.25 * min(fullListens(signals.plays), 20)
        return faded + lifetime +
            (if (signals.liked) 3.0 else 0.0) +
            (if (signals.inPlaylist) 2.0 else 0.0) +
            (if (signals.subscribed) 1.0 else 0.0)
    }

    fun eligibleForYourMusic(signals: SongSignals): Boolean =
        fullListens(signals.plays) >= 1 || signals.liked || signals.inPlaylist

    fun fitsSongLength(durationSec: Int): Boolean = durationSec <= 0 || durationSec in SONG_LENGTH

    fun estimatedBytes(durationSec: Int): Long =
        (if (durationSec > 0) durationSec else DEFAULT_SONG_SEC) * BYTES_PER_SEC

    /** "Your music", best first. */
    fun rankYourMusic(signals: List<SongSignals>, now: Long, excluded: Set<String>): List<RankedSong> =
        signals.asSequence()
            .filter { it.song.videoId !in excluded && fitsSongLength(it.song.durationSec) && eligibleForYourMusic(it) }
            .map { RankedSong(it.song, score(it, now), SmartSection.YOUR_MUSIC) }
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .toList()

    /** Channels with at least two full listens over all their songs. */
    fun knownArtists(signals: List<SongSignals>): Set<String> =
        signals.groupBy { it.song.artistKey }
            .filter { (key, list) -> key.isNotBlank() && list.sumOf { fullListens(it.plays) } >= 2 }
            .keys

    /**
     * "Discover", best first. [related] maps each seed's video id to the songs YouTube lists next
     * to it; [extra] are fallback candidates (recent uploads of subscribed music channels) that rank
     * after every related song.
     */
    fun rankDiscover(
        seeds: List<RankedSong>,
        related: Map<String, List<SongInfo>>,
        extra: List<SongInfo>,
        heard: Set<String>,
        excluded: Set<String>,
        knownArtists: Set<String>
    ): List<RankedSong> {
        val maxSeed = seeds.maxOfOrNull { it.score }?.takeIf { it > 0 } ?: 1.0
        val scores = LinkedHashMap<String, Double>()
        val songs = HashMap<String, SongInfo>()
        for (seed in seeds) {
            val list = related[seed.song.videoId].orEmpty().distinctBy { it.videoId }
            for (song in list) {
                if (!candidateOk(song, heard, excluded)) continue
                songs.putIfAbsent(song.videoId, song)
                scores[song.videoId] = (scores[song.videoId] ?: 0.0) + seed.score / maxSeed
            }
        }
        val ranked = scores.entries.map { (id, s) ->
            val song = songs.getValue(id)
            val bonus = if (song.artistKey in knownArtists) 1.0 else 0.0
            RankedSong(song, s + bonus, SmartSection.DISCOVER)
        }.sortedByDescending { it.score }.toMutableList()
        // Fallback candidates rank below every related song.
        extra.filter { candidateOk(it, heard, excluded) && it.videoId !in scores }
            .distinctBy { it.videoId }
            .forEach { ranked.add(RankedSong(it, 0.0, SmartSection.DISCOVER)) }
        // At most N songs per artist.
        val perArtist = HashMap<String, Int>()
        return ranked.filter { r ->
            val key = r.song.artistKey
            if (key.isBlank()) return@filter true
            val n = perArtist.getOrDefault(key, 0)
            if (n >= MAX_PER_ARTIST_DISCOVER) false else {
                perArtist[key] = n + 1
                true
            }
        }
    }

    private fun candidateOk(song: SongInfo, heard: Set<String>, excluded: Set<String>): Boolean =
        song.videoId !in heard && song.videoId !in excluded && fitsSongLength(song.durationSec)

    /**
     * Fits the ranked lists into [budgetBytes] (half each, spare space to the other section),
     * keeps already-downloaded songs within the anti-churn margin, and lists what to download and
     * what to delete. [downloaded] are the video ids already saved as smart downloads.
     */
    fun plan(
        yourMusic: List<RankedSong>,
        discover: List<RankedSong>,
        budgetBytes: Long,
        downloaded: Set<String>
    ): SmartPlan {
        if (budgetBytes <= 0) return SmartPlan(emptyList(), emptyList(), downloaded)
        val yourBudget = (budgetBytes * YOUR_MUSIC_SHARE).toLong()
        var yourPick = fill(yourMusic, yourBudget)
        val discoverPick = fill(discover, budgetBytes - size(yourPick))
        // Discover could not use its share: give the rest back to "Your music".
        if (size(yourPick) + size(discoverPick) < budgetBytes) {
            yourPick = fill(yourMusic, budgetBytes - size(discoverPick))
        }

        val selected = LinkedHashMap<String, RankedSong>()
        (yourPick + discoverPick).forEach { selected.putIfAbsent(it.song.videoId, it) }

        // Anti-churn: downloaded songs ranked just below the cut stay.
        val extras = mutableListOf<RankedSong>()
        for ((list, pick) in listOf(yourMusic to yourPick, discover to discoverPick)) {
            val margin = ceil(pick.size * KEEP_MARGIN).toInt()
            list.take(margin).forEach { r ->
                if (r.song.videoId in downloaded && r.song.videoId !in selected) extras.add(r)
            }
        }
        val final = (selected.values + extras).distinctBy { it.song.videoId }.toMutableList()
        // Over budget because of the kept songs: drop the lowest-ranked songs not yet downloaded,
        // then (if still needed) the lowest-ranked kept ones.
        var total = size(final)
        if (total > budgetBytes) {
            val notDownloaded = final.filter { it.song.videoId !in downloaded }.reversed()
            for (r in notDownloaded) {
                if (total <= budgetBytes) break
                final.remove(r)
                total -= estimatedBytes(r.song.durationSec)
            }
            val keptLowFirst = extras.reversed()
            for (r in keptLowFirst) {
                if (total <= budgetBytes) break
                if (final.remove(r)) total -= estimatedBytes(r.song.durationSec)
            }
        }
        val finalIds = final.map { it.song.videoId }.toSet()
        return SmartPlan(
            keep = final.filter { it.song.videoId in downloaded },
            download = final.filter { it.song.videoId !in downloaded },
            remove = downloaded - finalIds
        )
    }

    /** Takes songs in rank order while they fit (a song that does not fit is skipped). */
    private fun fill(ranked: List<RankedSong>, budget: Long): List<RankedSong> {
        val out = mutableListOf<RankedSong>()
        var used = 0L
        for (r in ranked) {
            val bytes = estimatedBytes(r.song.durationSec)
            if (used + bytes > budget) continue
            out.add(r)
            used += bytes
        }
        return out
    }

    private fun size(list: List<RankedSong>): Long = list.sumOf { estimatedBytes(it.song.durationSec) }

    fun removedCutoff(now: Long): Long = now - REMOVED_DAYS * DAY_MS
}
