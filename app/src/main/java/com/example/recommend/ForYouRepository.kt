package com.example.recommend

import android.content.Context
import com.example.data.BuiltInPlaylist
import com.example.data.FeedbackType
import com.example.data.StreamShieldDatabase
import com.example.download.NewPipeSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File

/**
 * Builds the Home tab's "For you" list with [ForYouRule]: reads the local library, fetches
 * YouTube's related lists for the seed videos (NewPipe Extractor, cached for a day), and keeps
 * the last result in a file so Home shows it at once on the next start.
 */
class ForYouRepository(private val context: Context) {

    data class State(
        val items: List<ForYouVideo> = emptyList(),
        val refreshedAt: Long = 0L,
        val refreshing: Boolean = false,
        /** A short note when part of the list could not be loaded. */
        val note: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    /** Seed video id -> (fetched at, related videos). */
    private val relatedCache = HashMap<String, Pair<Long, List<ForYouVideo>>>()
    @Volatile
    private var loaded = false

    /** Reads the saved list (once). */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        synchronized(this@ForYouRepository) {
            if (loaded) return@withContext
            loaded = true
        }
        try {
            if (!file.exists()) return@withContext
            val root = JSONObject(file.readText())
            val items = videosFrom(root.optJSONArray("items"))
            root.optJSONObject("related")?.let { related ->
                related.keys().forEach { seed ->
                    val entry = related.optJSONObject(seed) ?: return@forEach
                    relatedCache[seed] = entry.optLong("at") to videosFrom(entry.optJSONArray("items"))
                }
            }
            _state.update { it.copy(items = items, refreshedAt = root.optLong("at")) }
        } catch (_: Exception) {
            // A damaged file only means starting from an empty list.
        }
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        now - _state.value.refreshedAt > REFRESH_AFTER_MS

    /**
     * Rebuilds the list ([force]: even if it is recent). [starred] are the starred channel ids.
     * Returns at once if a refresh is already running.
     */
    suspend fun refresh(starred: Set<String>, force: Boolean) = withContext(Dispatchers.IO) {
        load()
        val now = System.currentTimeMillis()
        if (!force && !isStale(now)) return@withContext
        if (!mutex.tryLock()) return@withContext
        try {
            _state.update { it.copy(refreshing = true) }
            val (items, note) = build(starred, now)
            _state.value = State(items = items, refreshedAt = now, refreshing = false, note = note)
            save(now)
        } catch (_: Exception) {
            _state.update { it.copy(refreshing = false, note = "Could not update this list. Pull down to try again.") }
        } finally {
            mutex.unlock()
        }
    }

    /** "Not interested" from Home: drop it now (the rule leaves it out from then on). */
    suspend fun remove(videoId: String) = withContext(Dispatchers.IO) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.videoId == videoId }) }
        save(_state.value.refreshedAt)
    }

    private suspend fun build(starred: Set<String>, now: Long): Pair<List<ForYouVideo>, String> {
        val dao = StreamShieldDatabase.getInstance(context).libraryDao()
        val videos = dao.allVideos().associateBy { it.videoId }
        val entries = dao.allWatchEntries().associateBy { it.videoId }
        val playlists = dao.allPlaylists()
        val playlistItems = dao.allPlaylistItems()
        val likedId = playlists.firstOrNull { it.builtIn == BuiltInPlaylist.LIKED.name }?.id
        val liked = playlistItems.filter { it.playlistId == likedId }.map { it.videoId }.toSet()
        val saved = playlistItems.filter { it.playlistId != likedId }.map { it.videoId }.toSet()
        val addedAt = playlistItems.groupBy { it.videoId }.mapValues { (_, list) -> list.maxOf { it.addedAt } }
        val subscribed = dao.allSubscriptions().map { it.channelId }.toSet()
        val feedback = dao.allFeedback()
        val notInterested = feedback.filter { it.type == FeedbackType.NOT_INTERESTED.name }.map { it.targetId }.toSet()
        val hiddenChannels = feedback.filter { it.type == FeedbackType.HIDE_CHANNEL.name }.map { it.targetId }.toSet()

        val watches = (entries.keys + liked + saved).mapNotNull { id ->
            val v = videos[id] ?: return@mapNotNull null
            val e = entries[id]
            WatchSignal(
                videoId = id,
                channelId = v.channelId,
                channelName = v.channelName,
                watchedFraction = e?.watchedFraction ?: 0f,
                lastWatchedAt = maxOf(e?.lastWatchedAt ?: 0L, addedAt[id] ?: 0L),
                isMusic = v.isMusic || e?.isMusic == true,
                liked = id in liked,
                saved = id in saved
            )
        }
        val watched = entries.values.filter { it.watchedFraction >= ForYouRule.WATCHED_FRACTION }.map { it.videoId }.toSet()
        val channel = ForYouRule.channelScores(
            ForYouRule.rawChannelScores(watches.filter { !it.isMusic }, subscribed, starred, now)
        )
        val excluded = { v: ForYouVideo -> ForYouRule.isExcluded(v, watched, notInterested, hiddenChannels) }

        val uploads = dao.allFeedItems().map {
            ForYouVideo(
                videoId = it.videoId,
                title = it.title,
                channelId = it.channelId,
                channelName = it.channelName,
                durationSec = 0,
                viewCount = it.viewCount,
                publishedAt = it.publishedAt,
                publishedText = "",
                isShort = it.isShort,
                source = ForYouSource.SUBSCRIPTION
            )
        }
        val rankedUploads = ForYouRule.rankSubscriptionUploads(uploads, channel, now, excluded)

        // Seeds: recently watched / liked / saved videos; a new user's subscriptions instead.
        var seeds = ForYouRule.pickSeeds(watches, now)
        if (seeds.isEmpty()) {
            seeds = rankedUploads.distinctBy { it.video.channelKey }.take(NEW_USER_SEEDS).map { r ->
                WatchSignal(r.video.videoId, r.video.channelId, r.video.channelName, 1f, r.video.publishedAt, false, false, false) to 1.0
            }
        }
        var failed = 0
        var captcha = false
        val semaphore = Semaphore(PARALLEL_FETCHES)
        val related = coroutineScope {
            seeds.map { (seed, _) ->
                async {
                    val cached = synchronized(relatedCache) { relatedCache[seed.videoId] }
                    if (cached != null && now - cached.first < RELATED_TTL_MS) return@async seed.videoId to cached.second
                    semaphore.withPermit {
                        try {
                            val info = NewPipeSupport.streamInfo(seed.videoId)
                            val list = NewPipeSupport.relatedVideos(info).map {
                                ForYouVideo(
                                    videoId = it.videoId,
                                    title = it.title,
                                    channelId = it.channelId,
                                    channelName = it.channelName,
                                    durationSec = it.durationSec,
                                    viewCount = it.viewCount,
                                    publishedAt = it.uploadedAt,
                                    publishedText = it.uploadedText,
                                    isShort = it.isShort,
                                    source = ForYouSource.RELATED
                                )
                            }
                            synchronized(relatedCache) { relatedCache[seed.videoId] = now to list }
                            seed.videoId to list
                        } catch (e: Exception) {
                            synchronized(this@ForYouRepository) {
                                failed++
                                if (e is ReCaptchaException) captcha = true
                            }
                            // An older list is better than none.
                            seed.videoId to (cached?.second ?: emptyList())
                        }
                    }
                }
            }.awaitAll().toMap()
        }
        val rankedRelated = ForYouRule.rankRelated(seeds, related, channel, excluded)
        val items = ForYouRule.mix(rankedUploads, rankedRelated)

        // Keep cached related lists of current seeds only.
        synchronized(relatedCache) {
            val keep = seeds.map { it.first.videoId }.toSet()
            relatedCache.keys.retainAll(keep)
        }
        val note = when {
            seeds.isNotEmpty() && failed == seeds.size && captcha -> "YouTube asked for a check, so related videos could not be loaded."
            seeds.isNotEmpty() && failed == seeds.size -> "Related videos could not be loaded from YouTube right now; showing new uploads."
            else -> ""
        }
        return items to note
    }

    private fun save(at: Long) {
        try {
            val related = JSONObject()
            synchronized(relatedCache) {
                relatedCache.forEach { (seed, entry) ->
                    related.put(seed, JSONObject().put("at", entry.first).put("items", videosJson(entry.second)))
                }
            }
            val root = JSONObject()
                .put("at", at)
                .put("items", videosJson(_state.value.items))
                .put("related", related)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun videosJson(list: List<ForYouVideo>): JSONArray = JSONArray().apply {
        list.forEach { v ->
            put(
                JSONObject()
                    .put("id", v.videoId)
                    .put("title", v.title)
                    .put("channelId", v.channelId)
                    .put("channelName", v.channelName)
                    .put("dur", v.durationSec)
                    .put("views", v.viewCount)
                    .put("pub", v.publishedAt)
                    .put("pubText", v.publishedText)
                    .put("short", v.isShort)
                    .put("src", v.source.name)
            )
        }
    }

    private fun videosFrom(array: JSONArray?): List<ForYouVideo> {
        if (array == null) return emptyList()
        val out = ArrayList<ForYouVideo>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            out.add(
                ForYouVideo(
                    videoId = id,
                    title = o.optString("title"),
                    channelId = o.optString("channelId"),
                    channelName = o.optString("channelName"),
                    durationSec = o.optInt("dur"),
                    viewCount = o.optLong("views"),
                    publishedAt = o.optLong("pub"),
                    publishedText = o.optString("pubText"),
                    isShort = o.optBoolean("short"),
                    source = if (o.optString("src") == ForYouSource.RELATED.name) ForYouSource.RELATED else ForYouSource.SUBSCRIPTION
                )
            )
        }
        return out
    }

    companion object {
        private const val FILE_NAME = "for_you.json"
        /** Opening Home rebuilds the list when it is older than this. */
        const val REFRESH_AFTER_MS = 4L * 60 * 60 * 1000
        private const val RELATED_TTL_MS = 24L * 60 * 60 * 1000
        private const val PARALLEL_FETCHES = 3
        private const val NEW_USER_SEEDS = 5
    }
}
