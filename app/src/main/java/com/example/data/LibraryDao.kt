package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Queries for the local library tables in [LibraryEntities.kt]. */
@Dao
abstract class LibraryDao {

    // ---- videos ------------------------------------------------------------------------------

    @Query("SELECT * FROM videos WHERE videoId = :videoId LIMIT 1")
    abstract suspend fun getVideo(videoId: String): VideoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertVideo(video: VideoEntity)

    // ---- watch history -----------------------------------------------------------------------

    @Query("SELECT * FROM watch_entries WHERE videoId = :videoId LIMIT 1")
    abstract suspend fun getWatchEntry(videoId: String): WatchEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertWatchEntry(entry: WatchEntryEntity)

    @Query("DELETE FROM watch_entries WHERE videoId = :videoId")
    abstract suspend fun deleteWatchEntry(videoId: String)

    @Query("DELETE FROM watch_entries")
    abstract suspend fun clearWatchEntries()

    @Query(
        """
        SELECT w.videoId AS videoId,
               COALESCE(v.title, '') AS title,
               COALESCE(v.channelId, '') AS channelId,
               COALESCE(v.channelName, '') AS channelName,
               CASE WHEN w.durationSec > 0 THEN w.durationSec ELSE COALESCE(v.durationSec, 0) END AS durationSec,
               w.positionSec AS positionSec,
               w.watchedFraction AS watchedFraction,
               w.lastWatchedAt AS lastWatchedAt,
               w.playCount AS playCount,
               w.isMusic AS isMusic
        FROM watch_entries w LEFT JOIN videos v ON v.videoId = w.videoId
        ORDER BY w.lastWatchedAt DESC
        LIMIT :limit
        """
    )
    abstract fun observeHistory(limit: Int): Flow<List<WatchedVideo>>

    @Query(
        """
        SELECT w.videoId AS videoId,
               COALESCE(v.title, '') AS title,
               COALESCE(v.channelId, '') AS channelId,
               COALESCE(v.channelName, '') AS channelName,
               CASE WHEN w.durationSec > 0 THEN w.durationSec ELSE COALESCE(v.durationSec, 0) END AS durationSec,
               w.positionSec AS positionSec,
               w.watchedFraction AS watchedFraction,
               w.lastWatchedAt AS lastWatchedAt,
               w.playCount AS playCount,
               w.isMusic AS isMusic
        FROM watch_entries w LEFT JOIN videos v ON v.videoId = w.videoId
        ORDER BY w.lastWatchedAt DESC
        LIMIT :limit
        """
    )
    abstract suspend fun recentHistory(limit: Int): List<WatchedVideo>

    // ---- subscriptions -----------------------------------------------------------------------

    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE ASC")
    abstract fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    abstract suspend fun allSubscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE channelId = :channelId LIMIT 1")
    abstract suspend fun getSubscription(channelId: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Query("UPDATE subscriptions SET name = :name, lastRefreshedAt = :at, stale = :stale WHERE channelId = :channelId")
    abstract suspend fun markRefreshed(channelId: String, name: String, at: Long, stale: Boolean)

    @Query("UPDATE subscriptions SET stale = 1 WHERE channelId = :channelId")
    abstract suspend fun markStale(channelId: String)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    abstract suspend fun deleteSubscriptionRow(channelId: String)

    @Query("DELETE FROM feed_items WHERE channelId = :channelId")
    abstract suspend fun deleteFeedForChannel(channelId: String)

    @Transaction
    open suspend fun unsubscribe(channelId: String) {
        deleteSubscriptionRow(channelId)
        deleteFeedForChannel(channelId)
    }

    // ---- subscription feed -------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFeedItems(items: List<FeedItemEntity>)

    /** Replaces one channel's feed in a single transaction (the RSS feed holds its latest ~15). */
    @Transaction
    open suspend fun replaceFeedForChannel(channelId: String, items: List<FeedItemEntity>) {
        deleteFeedForChannel(channelId)
        insertFeedItems(items)
    }

    @Query(
        """
        SELECT f.* FROM feed_items f INNER JOIN subscriptions s ON s.channelId = f.channelId
        ORDER BY f.publishedAt DESC
        LIMIT :limit
        """
    )
    abstract fun observeFeed(limit: Int): Flow<List<FeedItemEntity>>

    // ---- playlists ---------------------------------------------------------------------------

    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.kind AS kind, p.builtIn AS builtIn,
               (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS itemCount,
               (SELECT i.videoId FROM playlist_items i WHERE i.playlistId = p.id
                ORDER BY i.position ASC LIMIT 1) AS firstVideoId
        FROM playlists p
        ORDER BY (p.builtIn IS NULL) ASC, p.createdAt ASC
        """
    )
    abstract fun observePlaylistSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE builtIn = :builtIn LIMIT 1")
    abstract suspend fun getBuiltInPlaylist(builtIn: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    abstract suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id AND builtIn IS NULL")
    abstract suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id AND builtIn IS NULL")
    abstract suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    abstract suspend fun clearPlaylistItems(playlistId: Long)

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        val playlist = getPlaylist(id) ?: return
        if (playlist.builtIn != null) return
        clearPlaylistItems(id)
        deletePlaylistRow(id)
    }

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlaylistItem(item: PlaylistItemEntity): Long

    @Transaction
    open suspend fun appendToPlaylist(playlistId: Long, videoId: String, now: Long) {
        insertPlaylistItem(PlaylistItemEntity(playlistId, videoId, maxPosition(playlistId) + 1, now))
    }

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND videoId = :videoId")
    abstract suspend fun removeFromPlaylist(playlistId: Long, videoId: String)

    @Query("UPDATE playlist_items SET position = :position WHERE playlistId = :playlistId AND videoId = :videoId")
    abstract suspend fun setItemPosition(playlistId: Long, videoId: String, position: Int)

    @Query(
        """
        SELECT i.playlistId AS playlistId, i.videoId AS videoId, i.position AS position,
               COALESCE(v.title, '') AS title,
               COALESCE(v.channelId, '') AS channelId,
               COALESCE(v.channelName, '') AS channelName,
               COALESCE(v.durationSec, 0) AS durationSec
        FROM playlist_items i LEFT JOIN videos v ON v.videoId = i.videoId
        WHERE i.playlistId = :playlistId
        ORDER BY i.position ASC
        """
    )
    abstract fun observePlaylistVideos(playlistId: Long): Flow<List<PlaylistVideo>>

    @Query("SELECT playlistId FROM playlist_items WHERE videoId = :videoId")
    abstract fun observePlaylistsContaining(videoId: String): Flow<List<Long>>

    // ---- feedback ----------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFeedback(feedback: FeedbackEntity)

    @Query("DELETE FROM feedback WHERE targetId = :targetId AND type = :type")
    abstract suspend fun deleteFeedback(targetId: String, type: String)

    @Query("SELECT * FROM feedback")
    abstract suspend fun allFeedback(): List<FeedbackEntity>

    @Query("SELECT targetId FROM feedback WHERE type = :type")
    abstract fun observeFeedbackTargets(type: String): Flow<List<String>>

    // ---- search history ----------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSearch(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history ORDER BY lastUsedAt DESC LIMIT :limit")
    abstract fun observeRecentSearches(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("DELETE FROM search_history WHERE query = :query")
    abstract suspend fun deleteSearch(query: String)

    // ---- backup / restore --------------------------------------------------------------------

    @Query("SELECT * FROM videos")
    abstract suspend fun allVideos(): List<VideoEntity>

    @Query("SELECT * FROM watch_entries")
    abstract suspend fun allWatchEntries(): List<WatchEntryEntity>

    @Query("SELECT * FROM playlists")
    abstract suspend fun allPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId ASC, position ASC")
    abstract suspend fun allPlaylistItems(): List<PlaylistItemEntity>

    @Query("SELECT * FROM search_history")
    abstract suspend fun allSearches(): List<SearchHistoryEntity>

    @Query("SELECT * FROM feed_items")
    abstract suspend fun allFeedItems(): List<FeedItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSubscriptions(items: List<SubscriptionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertVideos(items: List<VideoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertWatchEntries(items: List<WatchEntryEntity>)

    @Query("DELETE FROM videos")
    abstract suspend fun clearVideos()

    @Query("DELETE FROM subscriptions")
    abstract suspend fun clearSubscriptions()

    @Query("DELETE FROM feed_items")
    abstract suspend fun clearFeed()

    @Query("DELETE FROM playlist_items")
    abstract suspend fun clearAllPlaylistItems()

    @Query("DELETE FROM playlists")
    abstract suspend fun clearPlaylists()

    @Query("DELETE FROM feedback")
    abstract suspend fun clearFeedback()

    @Query("DELETE FROM search_history")
    abstract suspend fun clearSearches()

    /** Empties every library table (used by "Replace" when restoring a backup). */
    @Transaction
    open suspend fun clearLibrary() {
        clearVideos()
        clearWatchEntries()
        clearSubscriptions()
        clearFeed()
        clearAllPlaylistItems()
        clearPlaylists()
        clearFeedback()
        clearSearches()
    }
}
