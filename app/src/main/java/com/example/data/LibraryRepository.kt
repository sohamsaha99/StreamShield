package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local library: history, subscriptions, playlists, likes, feedback and searches. All writes are
 * suspend functions; callers run them off the main thread (Room requires it).
 */
class LibraryRepository(
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis
) {

    val subscriptions: Flow<List<SubscriptionEntity>> = dao.observeSubscriptions()
    val subscribedIds: Flow<Set<String>> = subscriptions.map { list -> list.mapTo(HashSet()) { it.channelId } }
    val playlists: Flow<List<PlaylistSummary>> = dao.observePlaylistSummaries()

    fun history(limit: Int = 300): Flow<List<WatchedVideo>> = dao.observeHistory(limit)
    fun feed(limit: Int = 150): Flow<List<FeedItemEntity>> = dao.observeFeed(limit)
    fun playlistVideos(playlistId: Long): Flow<List<PlaylistVideo>> = dao.observePlaylistVideos(playlistId)
    fun playlistsContaining(videoId: String): Flow<List<Long>> = dao.observePlaylistsContaining(videoId)
    fun recentSearches(limit: Int = 8): Flow<List<SearchHistoryEntity>> = dao.observeRecentSearches(limit)
    fun feedbackTargets(type: FeedbackType): Flow<List<String>> = dao.observeFeedbackTargets(type.name)

    // ---- videos & history --------------------------------------------------------------------

    /** Stores what is known about a video, keeping earlier values where the new ones are empty. */
    suspend fun rememberVideo(
        videoId: String,
        title: String,
        channelId: String,
        channelName: String,
        durationSec: Int,
        viewCount: Long,
        isMusic: Boolean
    ) {
        if (videoId.isBlank()) return
        val old = dao.getVideo(videoId)
        // "YouTube" / "YouTube Music" are page titles shown before the real title is known.
        val cleanTitle = title.takeUnless { it == "YouTube" || it == "YouTube Music" }.orEmpty()
        dao.upsertVideo(
            VideoEntity(
                videoId = videoId,
                title = cleanTitle.ifBlank { old?.title.orEmpty() },
                channelId = channelId.ifBlank { old?.channelId.orEmpty() },
                channelName = channelName.ifBlank { old?.channelName.orEmpty() },
                durationSec = if (durationSec > 0) durationSec else old?.durationSec ?: 0,
                viewCount = if (viewCount > 0) viewCount else old?.viewCount ?: 0L,
                isMusic = isMusic || old?.isMusic == true,
                updatedAt = now()
            )
        )
    }

    /**
     * Records the playback position. [newSession] is true for the first write after the video
     * started playing; it increments the play count.
     */
    suspend fun recordProgress(videoId: String, positionSec: Int, durationSec: Int, isMusic: Boolean, newSession: Boolean) {
        if (videoId.isBlank()) return
        val time = now()
        val old = dao.getWatchEntry(videoId)
        val duration = if (durationSec > 0) durationSec else old?.durationSec ?: 0
        val fraction = if (duration > 0) (positionSec.toFloat() / duration).coerceIn(0f, 1f) else 0f
        dao.upsertWatchEntry(
            WatchEntryEntity(
                videoId = videoId,
                positionSec = positionSec.coerceAtLeast(0),
                durationSec = duration,
                watchedFraction = maxOf(fraction, old?.watchedFraction ?: 0f),
                firstWatchedAt = old?.firstWatchedAt ?: time,
                lastWatchedAt = time,
                playCount = (old?.playCount ?: 0) + if (newSession) 1 else 0,
                isMusic = isMusic || old?.isMusic == true
            )
        )
    }

    suspend fun recentHistory(limit: Int): List<WatchedVideo> = dao.recentHistory(limit)

    suspend fun deleteHistoryEntry(videoId: String) = dao.deleteWatchEntry(videoId)

    suspend fun clearHistory() = dao.clearWatchEntries()

    // ---- subscriptions -----------------------------------------------------------------------

    suspend fun isSubscribed(channelId: String): Boolean = dao.getSubscription(channelId) != null

    suspend fun subscriptionName(channelId: String): String? = dao.getSubscription(channelId)?.name

    /** Returns true if this was a new subscription. */
    suspend fun subscribe(channelId: String, name: String): Boolean {
        if (!CHANNEL_ID.matches(channelId)) return false
        return dao.insertSubscription(
            SubscriptionEntity(channelId = channelId, name = name, subscribedAt = now(), lastRefreshedAt = 0L, stale = false)
        ) != -1L
    }

    suspend fun unsubscribe(channelId: String) = dao.unsubscribe(channelId)

    suspend fun allSubscriptions(): List<SubscriptionEntity> = dao.allSubscriptions()

    suspend fun saveChannelFeed(channelId: String, channelName: String, items: List<FeedItemEntity>) {
        dao.replaceFeedForChannel(channelId, items)
        dao.markRefreshed(channelId, channelName, now(), stale = false)
    }

    suspend fun markChannelStale(channelId: String) = dao.markStale(channelId)

    // ---- playlists ---------------------------------------------------------------------------

    /** Creates the built-in lists on first use. */
    suspend fun ensureBuiltInPlaylists() {
        for (builtIn in BuiltInPlaylist.values()) {
            if (dao.getBuiltInPlaylist(builtIn.name) == null) {
                dao.insertPlaylist(
                    PlaylistEntity(name = builtIn.title, kind = PlaylistKind.VIDEO.name, builtIn = builtIn.name, createdAt = now())
                )
            }
        }
    }

    suspend fun builtInPlaylistId(builtIn: BuiltInPlaylist): Long {
        ensureBuiltInPlaylists()
        return dao.getBuiltInPlaylist(builtIn.name)?.id ?: -1L
    }

    /** Adds or removes [videoId] from a built-in list; returns true if it is now in the list. */
    suspend fun toggleBuiltIn(builtIn: BuiltInPlaylist, videoId: String, currentlyIn: Boolean): Boolean {
        val id = builtInPlaylistId(builtIn)
        if (id < 0) return false
        return if (currentlyIn) {
            dao.removeFromPlaylist(id, videoId)
            false
        } else {
            dao.appendToPlaylist(id, videoId, now())
            true
        }
    }

    suspend fun createPlaylist(name: String, kind: PlaylistKind = PlaylistKind.VIDEO): Long =
        dao.insertPlaylist(PlaylistEntity(name = name.trim().take(80), kind = kind.name, builtIn = null, createdAt = now()))

    suspend fun renamePlaylist(id: Long, name: String) = dao.renamePlaylist(id, name.trim().take(80))

    suspend fun deletePlaylist(id: Long) = dao.deletePlaylist(id)

    suspend fun addToPlaylist(playlistId: Long, videoId: String) = dao.appendToPlaylist(playlistId, videoId, now())

    suspend fun removeFromPlaylist(playlistId: Long, videoId: String) = dao.removeFromPlaylist(playlistId, videoId)

    /** Moves an entry one step up (-1) or down (+1) by swapping positions with its neighbour. */
    suspend fun moveInPlaylist(items: List<PlaylistVideo>, index: Int, delta: Int) {
        val other = index + delta
        if (index !in items.indices || other !in items.indices) return
        val a = items[index]
        val b = items[other]
        dao.setItemPosition(a.playlistId, a.videoId, b.position)
        dao.setItemPosition(b.playlistId, b.videoId, a.position)
    }

    // ---- feedback & search -------------------------------------------------------------------

    suspend fun setFeedback(targetId: String, type: FeedbackType, enabled: Boolean) {
        if (enabled) {
            val targetType = if (type == FeedbackType.HIDE_CHANNEL) "CHANNEL" else "VIDEO"
            dao.insertFeedback(FeedbackEntity(targetId, type.name, targetType, now()))
        } else {
            dao.deleteFeedback(targetId, type.name)
        }
    }

    suspend fun allFeedback(): List<FeedbackEntity> = dao.allFeedback()

    suspend fun recordSearch(query: String) {
        val q = query.trim()
        if (q.isNotEmpty()) dao.upsertSearch(SearchHistoryEntity(q.take(120), now()))
    }

    suspend fun deleteSearch(query: String) = dao.deleteSearch(query)

    companion object {
        /** YouTube channel ids: "UC" + 22 characters. */
        val CHANNEL_ID = Regex("^UC[A-Za-z0-9_-]{22}$")
    }
}
