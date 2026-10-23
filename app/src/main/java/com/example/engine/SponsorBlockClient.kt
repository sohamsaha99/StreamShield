package com.example.engine

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** Fetches crowd-sourced skip segments from SponsorBlock (https://sponsor.ajay.app). */
class SponsorBlockClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {

    /** Blocking; call from Dispatchers.IO. Returns [start, end] pairs in seconds, empty if none. */
    fun fetchSegments(videoId: String): List<Pair<Double, Double>> {
        if (!VIDEO_ID.matches(videoId)) return emptyList()
        val url = "https://sponsor.ajay.app/api/skipSegments".toHttpUrl().newBuilder()
            .addQueryParameter("videoID", videoId)
            .addQueryParameter("categories", CATEGORIES)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "StreamShield/1.0").build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                // 404 means "no segments for this video".
                if (!response.isSuccessful) return emptyList()
                parseSegments(response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        private const val CATEGORIES = "[\"sponsor\",\"selfpromo\",\"interaction\"]"

        fun parseSegments(json: String): List<Pair<Double, Double>> {
            if (json.isBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                val result = ArrayList<Pair<Double, Double>>(array.length())
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optString("actionType", "skip") != "skip") continue
                    val segment = item.optJSONArray("segment") ?: continue
                    val start = segment.optDouble(0, Double.NaN)
                    val end = segment.optDouble(1, Double.NaN)
                    if (start.isNaN() || end.isNaN() || end - start < 1.0) continue
                    result += start to end
                }
                result.sortedBy { it.first }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun segmentsToJson(segments: List<Pair<Double, Double>>): String {
            val array = JSONArray()
            segments.forEach { (start, end) -> array.put(JSONArray().put(start).put(end)) }
            return array.toString()
        }
    }
}
