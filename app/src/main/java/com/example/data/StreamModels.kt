package com.example.data

import android.net.Uri
import java.util.Locale
import kotlin.math.roundToInt

val PLAYBACK_SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

/** Playback state of the YouTube page, as reported by the injected shield script. */
data class WebVideoState(
    val videoId: String = "",
    val url: String = "",
    val title: String = "",
    val channel: String = "",
    val durationSec: Int = 0,
    val isPlaying: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Milliseconds from navigation until the video's data was loaded (metadata), or -1. */
    val loadMs: Int = -1,
    /** Milliseconds from navigation to the first frame, or -1 until the video has started. */
    val startMs: Int = -1,
    /** Milliseconds the player spent in an ad state while starting this video. */
    val adMs: Int = 0,
    /** Ad entries removed from YouTube's responses for this video. */
    val adsPruned: Int = 0,
    /** In PiP: pause calls from the page that were refused, and pauses that came from the browser. */
    val pipBlockedPauses: Int = 0,
    val pipSystemPauses: Int = 0,
    /** In PiP: times playback stalled waiting for data (live streams run dry when this grows). */
    val pipStalls: Int = 0
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
            path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore("/")
            uri.host?.contains("youtu.be") == true -> path.removePrefix("/").substringBefore("/")
            else -> null
        }?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}

fun formatSpeedBadge(speed: Float): String {
    val hundredths = (speed * 100f).roundToInt()
    return if (hundredths % 10 == 0) {
        String.format(Locale.US, "%.1fx", hundredths / 100f)
    } else {
        String.format(Locale.US, "%.2fx", hundredths / 100f)
    }
}

fun formatCount(count: Long): String = when {
    count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 10_000L -> String.format(Locale.US, "%.0fk", count / 1_000.0)
    count >= 1_000L -> String.format(Locale.US, "%.1fk", count / 1_000.0)
    else -> count.toString()
}
