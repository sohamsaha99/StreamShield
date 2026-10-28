package com.example.download

/** One choice in the download sheet. [height] 0 = audio only. */
data class QualityOption(val height: Int, val label: String, val sizeBytes: Long)

/** Title / channel / length from YouTube, to fill in what the app did not know yet. */
data class StreamMeta(val title: String, val channelName: String, val durationSec: Int)

object DownloadCatalog {
    /** Blocking network call: what this video can be downloaded as (audio first, then heights). */
    fun qualityOptions(videoId: String): Pair<List<QualityOption>, StreamMeta> {
        val info = NewPipeSupport.streamInfo(videoId)
        val duration = info.duration
        val videos = NewPipeSupport.videoCandidates(info)
        val audios = NewPipeSupport.audioCandidates(info)
        val options = mutableListOf<QualityOption>()
        StreamChoice.audioPlan(audios)?.let { plan ->
            options.add(QualityOption(0, "Audio only (" + plan.label.removePrefix("Audio · ").ifBlank { "M4A" } + ")", plan.estimatedBytes(duration)))
        }
        StreamChoice.availableHeights(videos, audios).forEach { height ->
            val plan = StreamChoice.videoPlan(videos, audios, height) ?: return@forEach
            options.add(QualityOption(height, plan.label, plan.estimatedBytes(duration)))
        }
        val meta = StreamMeta(info.name.orEmpty(), info.uploaderName.orEmpty(), duration.toInt())
        return options to meta
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    }
}

/** Short, readable reasons for download failures (YouTube's messages are long). */
object DownloadErrors {
    fun describe(e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("a bot", ignoreCase = true) || message.contains("LOGIN_REQUIRED") ->
                "YouTube is asking this network to prove it isn't a bot. Try again later or on another Wi-Fi."
            message.contains("age-restricted", ignoreCase = true) ->
                "Age-restricted videos can't be downloaded without an account."
            message.contains("private", ignoreCase = true) -> "This video is private."
            e is java.net.UnknownHostException -> "No internet connection."
            else -> message.ifBlank { e.javaClass.simpleName }
        }
    }
}
