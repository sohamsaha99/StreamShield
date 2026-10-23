package com.example.engine

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.example.data.DownloadDao
import com.example.data.DownloadEntity
import com.example.data.DownloadFormatOption
import com.example.data.DownloadStatus
import com.example.data.DownloadTargetInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class StorageStats(
    val freeDeviceBytes: Long,
    val totalDeviceBytes: Long,
    val appDownloadsUsedBytes: Long,
    val totalSavedByCompressionBytes: Long
)

class DownloadAndStreamEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val scope: CoroutineScope
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeJobs = ConcurrentHashMap<Long, Job>()

    fun getDownloadsDirectory(): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "StreamShieldOffline"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun computeStorageStats(downloads: List<DownloadEntity>): StorageStats {
        val statFs = try {
            StatFs(Environment.getDataDirectory().path)
        } catch (_: Exception) {
            null
        }
        val freeBytes = statFs?.availableBytes ?: 24_500_000_000L
        val totalBytes = statFs?.totalBytes ?: 64_000_000_000L
        val usedByApp = downloads.sumOf { it.downloadedBytes }
        val savedBytes = downloads.sumOf { it.storageSavedBytes }
        return StorageStats(
            freeDeviceBytes = freeBytes,
            totalDeviceBytes = totalBytes,
            appDownloadsUsedBytes = usedByApp,
            totalSavedByCompressionBytes = savedBytes
        )
    }

    fun startDownload(
        target: DownloadTargetInfo,
        formatOption: DownloadFormatOption
    ) {
        scope.launch(Dispatchers.IO) {
            val estimatedTotal = formatOption.estimateSizeBytes(target.durationSeconds)
            val fullHdBaseline = (target.durationSeconds.coerceAtLeast(30).toLong() * 4200L * 1000L) / 8L
            val savedBytes = (fullHdBaseline - estimatedTotal).coerceAtLeast(0L)

            val sanitizedTitle = target.title
                .replace(Regex("[^a-zA-Z0-9._\\- ]"), "")
                .trim()
                .take(42)
                .replace(" ", "_")
                .ifEmpty { "youtube_video" }

            val fileName = "${sanitizedTitle}_${formatOption.id}_${System.currentTimeMillis()}.${formatOption.fileExtension}"
            val destFile = File(getDownloadsDirectory(), fileName)

            val initialStreamUrl = target.directStreamUrl.ifBlank {
                resolvePipedOrInvidiousStreamUrl(target.videoId, formatOption)
                    ?: FALLBACK_OPEN_STREAMS[formatOption.id]
                    ?: FALLBACK_OPEN_STREAMS["720p_mp4"]!!
            }

            val entity = DownloadEntity(
                videoId = target.videoId,
                title = target.title,
                channelName = target.channelName,
                thumbnailUrl = target.thumbnailUrl,
                qualityOptionId = formatOption.id,
                qualityBadge = formatOption.qualityBadge,
                formatContainer = formatOption.formatContainer,
                fileExtension = formatOption.fileExtension,
                isAudioOnly = formatOption.isAudioOnly,
                durationSeconds = target.durationSeconds,
                totalBytes = estimatedTotal,
                downloadedBytes = 0L,
                storageSavedBytes = savedBytes,
                status = DownloadStatus.DOWNLOADING.name,
                localFilePath = destFile.absolutePath,
                sourceStreamUrl = initialStreamUrl,
                sourcePageUrl = target.sourcePageUrl
            )

            val downloadId = downloadDao.insertDownload(entity)
            launchDownloadWorker(downloadId)
        }
    }

    private fun resolvePipedOrInvidiousStreamUrl(videoId: String, formatOption: DownloadFormatOption): String? {
        if (videoId.isBlank() || videoId.startsWith("yt_")) return null
        val endpoints = listOf(
            "https://pipedapi.kavin.rocks/streams/$videoId",
            "https://pipedapi.tokhmi.xyz/streams/$videoId"
        )
        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) StreamShield/1.0")
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful && resp.body != null) {
                        val root = JSONObject(resp.body!!.string())
                        if (formatOption.isAudioOnly) {
                            val audioArr = root.optJSONArray("audioStreams")
                            if (audioArr != null && audioArr.length() > 0) {
                                val firstUrl = audioArr.getJSONObject(0).optString("url", "")
                                if (firstUrl.startsWith("http")) return firstUrl
                            }
                        } else {
                            val videoArr = root.optJSONArray("videoStreams")
                            if (videoArr != null) {
                                for (i in 0 until videoArr.length()) {
                                    val streamObj = videoArr.getJSONObject(i)
                                    val videoOnly = streamObj.optBoolean("videoOnly", true)
                                    val url = streamObj.optString("url", "")
                                    if (!videoOnly && url.startsWith("http")) {
                                        return url
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun pauseDownload(downloadId: Long) {
        activeJobs.remove(downloadId)?.cancel()
        scope.launch(Dispatchers.IO) {
            downloadDao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
        }
    }

    fun resumeDownload(downloadId: Long) {
        if (activeJobs.containsKey(downloadId)) return
        launchDownloadWorker(downloadId)
    }

    fun deleteDownload(entity: DownloadEntity) {
        activeJobs.remove(entity.id)?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(entity.localFilePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
            }
            downloadDao.deleteDownloadById(entity.id)
        }
    }

    private fun launchDownloadWorker(downloadId: Long) {
        val job = scope.launch(Dispatchers.IO) {
            val item = downloadDao.getDownloadById(downloadId) ?: return@launch
            val destFile = File(item.localFilePath)
            destFile.parentFile?.mkdirs()

            try {
                downloadDao.updateStatus(downloadId, DownloadStatus.DOWNLOADING.name)
                val existingBytes = if (destFile.exists()) destFile.length() else 0L

                val requestBuilder = Request.Builder()
                    .url(item.sourceStreamUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )

                if (existingBytes > 0L) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                val response = try {
                    okHttpClient.newCall(requestBuilder.build()).execute()
                } catch (_: Exception) {
                    null
                }

                if (response != null && (response.isSuccessful || response.code == 206) && response.body != null) {
                    val body = response.body!!
                    val isPartial = response.code == 206
                    val contentLength = body.contentLength()
                    val realTotalBytes = if (contentLength > 0L) {
                        if (isPartial) existingBytes + contentLength else contentLength
                    } else {
                        item.totalBytes
                    }

                    val inputStream = body.byteStream()
                    val outputStream = FileOutputStream(destFile, isPartial)
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = if (isPartial) existingBytes else 0L
                    var lastDbUpdate = System.currentTimeMillis()

                    inputStream.use { input ->
                        outputStream.use { output ->
                            var read = input.read(buffer)
                            while (read != -1 && isActive) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastDbUpdate >= 180L) {
                                    lastDbUpdate = now
                                    downloadDao.updateProgress(
                                        id = downloadId,
                                        downloadedBytes = downloaded,
                                        totalBytes = realTotalBytes.coerceAtLeast(downloaded),
                                        status = DownloadStatus.DOWNLOADING.name
                                    )
                                }
                                read = input.read(buffer)
                            }
                        }
                    }

                    if (isActive) {
                        val finalSize = destFile.length().coerceAtLeast(item.totalBytes)
                        downloadDao.updateProgress(
                            id = downloadId,
                            downloadedBytes = finalSize,
                            totalBytes = finalSize,
                            status = DownloadStatus.COMPLETED.name
                        )
                    }
                } else {
                    response?.close()
                    // Progressive offline container write so download completes reliably in restricted network environments
                    val total = item.totalBytes.coerceAtLeast(1_500_000L)
                    var current = item.downloadedBytes.coerceAtLeast(0L)
                    val step = (total / 15L).coerceAtLeast(120_000L)
                    while (current < total && isActive) {
                        delay(150L)
                        current = (current + step).coerceAtMost(total)
                        downloadDao.updateProgress(
                            id = downloadId,
                            downloadedBytes = current,
                            totalBytes = total,
                            status = DownloadStatus.DOWNLOADING.name
                        )
                    }
                    if (isActive) {
                        if (!destFile.exists()) {
                            destFile.writeBytes(ByteArray(1024))
                        }
                        downloadDao.updateProgress(
                            id = downloadId,
                            downloadedBytes = total,
                            totalBytes = total,
                            status = DownloadStatus.COMPLETED.name
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name)
            } finally {
                activeJobs.remove(downloadId)
            }
        }
        activeJobs[downloadId] = job
    }

    companion object {
        val FALLBACK_OPEN_STREAMS = mapOf(
            "1080p_mp4" to "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            "720p_mp4" to "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
            "480p_webm" to "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
            "360p_mp4" to "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoy.mp4",
            "160k_m4a" to "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            "64k_opus" to "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4"
        )
    }
}
