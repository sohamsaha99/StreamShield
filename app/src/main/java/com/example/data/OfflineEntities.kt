package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/*
 * Database version 3: downloads, the listening log used by smart downloads, and the songs the
 * user removed from smart downloads. Same conventions as LibraryEntities.kt (plain primary keys,
 * no indices or defaults) so the hand-written migration stays easy to check.
 */

/** A downloaded (or downloading) video or song. */
@Entity(tableName = "offline_items")
data class OfflineItemEntity(
    /** "<videoId>:a" for audio only, "<videoId>:v" for video. */
    @PrimaryKey val id: String,
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val durationSec: Int,
    val isMusic: Boolean,
    val audioOnly: Boolean,
    /** Requested video height (e.g. 720); 0 for audio only. */
    val targetHeight: Int,
    /** What was actually saved, e.g. "720p" or "Audio · 128 kbps". */
    val qualityLabel: String,
    /** [OfflineOrigin] name. */
    val origin: String,
    /** [OfflineStatus] name. */
    val status: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    /** content:// URI (Movies / Music folder) or an absolute file path (inside the app). */
    val location: String,
    val mimeType: String,
    val error: String,
    val createdAt: Long,
    val completedAt: Long,
    /** Smart downloads: [SmartSection] name and rank at the last refresh; empty / 0 otherwise. */
    val smartSection: String,
    val smartRank: Int
) {
    val isDone: Boolean get() = status == OfflineStatus.DONE.name
    val isSmart: Boolean get() = origin == OfflineOrigin.SMART.name
    val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    companion object {
        fun idFor(videoId: String, audioOnly: Boolean) = videoId + if (audioOnly) ":a" else ":v"
    }
}

enum class OfflineOrigin { MANUAL, SMART }

enum class OfflineStatus { QUEUED, DOWNLOADING, DONE, FAILED }

enum class SmartSection { YOUR_MUSIC, DISCOVER }

/** One play of a video or song: when it started and how far it got (listening log). */
@Entity(tableName = "play_events")
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoId: String,
    val startedAt: Long,
    /** Seconds listened in this play (furthest point reached minus the starting point). */
    val listenedSec: Int,
    val durationSec: Int,
    /** Reached the end, or at least 80 % of the duration. */
    val completed: Boolean,
    val isMusic: Boolean
)

/** A song the user deleted from smart downloads; it is not downloaded again for 60 days. */
@Entity(tableName = "smart_removed")
data class SmartRemovedEntity(
    @PrimaryKey val videoId: String,
    val removedAt: Long
)

@Dao
interface OfflineDao {
    @Query("SELECT * FROM offline_items ORDER BY createdAt DESC")
    fun observeItems(): Flow<List<OfflineItemEntity>>

    @Query("SELECT * FROM offline_items WHERE id = :id LIMIT 1")
    suspend fun getItem(id: String): OfflineItemEntity?

    @Query("SELECT * FROM offline_items")
    suspend fun allItems(): List<OfflineItemEntity>

    @Query("SELECT * FROM offline_items WHERE origin = 'SMART'")
    suspend fun smartItems(): List<OfflineItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: OfflineItemEntity)

    @Query("UPDATE offline_items SET status = :status, downloadedBytes = :downloaded, totalBytes = :total WHERE id = :id")
    suspend fun updateProgress(id: String, status: String, downloaded: Long, total: Long)

    @Query("UPDATE offline_items SET status = :status, error = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String)

    @Query(
        "UPDATE offline_items SET status = 'DONE', error = '', location = :location, mimeType = :mimeType, " +
            "qualityLabel = :qualityLabel, totalBytes = :size, downloadedBytes = :size, completedAt = :completedAt, " +
            "title = :title, channelName = :channelName, durationSec = :durationSec WHERE id = :id"
    )
    suspend fun markDone(
        id: String,
        location: String,
        mimeType: String,
        qualityLabel: String,
        size: Long,
        completedAt: Long,
        title: String,
        channelName: String,
        durationSec: Int
    )

    @Query("UPDATE offline_items SET smartSection = :section, smartRank = :rank WHERE id = :id")
    suspend fun updateSmartRank(id: String, section: String, rank: Int)

    @Query("DELETE FROM offline_items WHERE id = :id")
    suspend fun deleteItem(id: String)

    // ---- listening log ----

    @Insert
    suspend fun insertPlay(event: PlayEventEntity): Long

    @Query("UPDATE play_events SET listenedSec = :listenedSec, durationSec = :durationSec, completed = :completed WHERE id = :id")
    suspend fun updatePlay(id: Long, listenedSec: Int, durationSec: Int, completed: Boolean)

    @Query("SELECT * FROM play_events WHERE isMusic = 1")
    suspend fun musicPlays(): List<PlayEventEntity>

    @Query("DELETE FROM play_events")
    suspend fun clearPlays()

    // ---- songs removed from smart downloads ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemoved(entry: SmartRemovedEntity)

    @Query("SELECT videoId FROM smart_removed WHERE removedAt >= :since")
    suspend fun removedSince(since: Long): List<String>
}
