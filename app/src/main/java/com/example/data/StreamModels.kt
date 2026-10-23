package com.example.data

import android.net.Uri
import java.util.Locale

enum class AppTab {
    BRAVE_BROWSER,
    DOWNLOADS,
    SHIELDS_SETTINGS
}

enum class DownloadStatus {
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class DownloadFormatOption(
    val id: String,
    val qualityBadge: String,
    val title: String,
    val formatContainer: String, // "MP4", "WEBM", "M4A", "OPUS"
    val fileExtension: String,   // "mp4", "webm", "m4a", "opus"
    val codecDescription: String,
    val bitrateKbps: Int,
    val isAudioOnly: Boolean,
    val storageSavingsPercent: Int, // Compared to 1080p MP4
    val tagLabel: String
) {
    fun estimateSizeBytes(durationSeconds: Int): Long {
        val safeDuration = durationSeconds.coerceAtLeast(30)
        return (safeDuration.toLong() * bitrateKbps.toLong() * 1000L) / 8L
    }
}

object DownloadFormatCatalog {
    val ALL_OPTIONS = listOf(
        DownloadFormatOption(
            id = "1080p_mp4",
            qualityBadge = "1080p FHD",
            title = "Full HD Video",
            formatContainer = "MP4",
            fileExtension = "mp4",
            codecDescription = "H.264 High Profile • 60fps • AAC",
            bitrateKbps = 4200,
            isAudioOnly = false,
            storageSavingsPercent = 0,
            tagLabel = "Max Clarity"
        ),
        DownloadFormatOption(
            id = "720p_mp4",
            qualityBadge = "720p HD",
            title = "Standard HD Video",
            formatContainer = "MP4",
            fileExtension = "mp4",
            codecDescription = "H.264 Main • Universal Compatibility",
            bitrateKbps = 2100,
            isAudioOnly = false,
            storageSavingsPercent = 50,
            tagLabel = "Recommended"
        ),
        DownloadFormatOption(
            id = "480p_webm",
            qualityBadge = "480p VP9",
            title = "Compact WebM Video",
            formatContainer = "WEBM",
            fileExtension = "webm",
            codecDescription = "VP9 + Opus • High Compression Ratio",
            bitrateKbps = 1100,
            isAudioOnly = false,
            storageSavingsPercent = 74,
            tagLabel = "Space Saver"
        ),
        DownloadFormatOption(
            id = "360p_mp4",
            qualityBadge = "360p Eco",
            title = "Ultra-Light Mobile Video",
            formatContainer = "MP4",
            fileExtension = "mp4",
            codecDescription = "H.264 Baseline • Fast Offline Sync",
            bitrateKbps = 650,
            isAudioOnly = false,
            storageSavingsPercent = 85,
            tagLabel = "Low Storage"
        ),
        DownloadFormatOption(
            id = "160k_m4a",
            qualityBadge = "160k M4A",
            title = "Studio Audio Only",
            formatContainer = "M4A",
            fileExtension = "m4a",
            codecDescription = "AAC-LC 160kbps • Music & Background",
            bitrateKbps = 160,
            isAudioOnly = true,
            storageSavingsPercent = 96,
            tagLabel = "Background Audio"
        ),
        DownloadFormatOption(
            id = "64k_opus",
            qualityBadge = "64k OPUS",
            title = "Voice & Podcast Audio",
            formatContainer = "OPUS",
            fileExtension = "opus",
            codecDescription = "Opus VBR 64kbps • Lectures & Talks",
            bitrateKbps = 64,
            isAudioOnly = true,
            storageSavingsPercent = 98,
            tagLabel = "Tiny Footprint"
        )
    )

    val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
}

data class DownloadTargetInfo(
    val videoId: String,
    val title: String,
    val channelName: String,
    val durationSeconds: Int,
    val thumbnailUrl: String,
    val sourcePageUrl: String,
    val directStreamUrl: String = ""
)

data class ActivePlaybackItem(
    val id: String,
    val title: String,
    val channelName: String,
    val mediaUriOrUrl: String,
    val sourcePageUrl: String,
    val isLocalFile: Boolean,
    val isAudioOnly: Boolean,
    val qualityLabel: String,
    val formatContainer: String,
    val durationSeconds: Int,
    val thumbnailUrl: String
)

fun extractYouTubeVideoId(url: String): String? {
    if (url.isBlank()) return null
    return try {
        val uri = Uri.parse(url)
        val vParam = uri.getQueryParameter("v")
        if (!vParam.isNullOrBlank()) {
            return vParam
        }
        val path = uri.path ?: ""
        when {
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore("/")
            path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore("/")
            uri.host?.contains("youtu.be") == true -> path.removePrefix("/").substringBefore("/")
            else -> null
        }?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

fun formatBytesHuman(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 100.0 -> String.format(Locale.US, "%.0f MB", mb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

fun formatDurationSeconds(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatSpeedBadge(speed: Float): String {
    return if (speed * 100f % 10f == 0f) {
        String.format(Locale.US, "%.1fx", speed)
    } else {
        String.format(Locale.US, "%.2fx", speed)
    }
}
