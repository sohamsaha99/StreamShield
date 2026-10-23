package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val qualityOptionId: String,
    val qualityBadge: String,
    val formatContainer: String, // MP4, WEBM, M4A, OPUS
    val fileExtension: String,
    val isAudioOnly: Boolean,
    val durationSeconds: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val storageSavedBytes: Long,
    val status: String, // DOWNLOADING, PAUSED, COMPLETED, FAILED
    val localFilePath: String,
    val sourceStreamUrl: String,
    val sourcePageUrl: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val url: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoUrl: String,
    val title: String,
    val channelName: String,
    val playbackSpeedUsed: Float,
    val visitedAt: Long = System.currentTimeMillis()
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(entity: DownloadEntity): Long

    @Query(
        "UPDATE downloads SET downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, status = :status WHERE id = :id"
    )
    suspend fun updateProgress(id: Long, downloadedBytes: Long, totalBytes: Long, status: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun countBookmarks(): Int
}

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY visitedAt DESC LIMIT 50")
    fun observeRecentHistory(): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entry: WatchHistoryEntity)

    @Query("DELETE FROM watch_history")
    suspend fun clearAllHistory()
}

@Database(
    entities = [DownloadEntity::class, BookmarkEntity::class, WatchHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StreamShieldDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: StreamShieldDatabase? = null

        fun getInstance(context: Context): StreamShieldDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StreamShieldDatabase::class.java,
                    "streamshield_offline.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
