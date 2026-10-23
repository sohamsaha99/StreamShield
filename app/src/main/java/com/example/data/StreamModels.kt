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
    val videoHeight: Int = 0
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
