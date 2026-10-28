package com.example.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One channel's RSS feed: its name and latest uploads (YouTube returns about 15). */
data class ChannelFeed(val channelId: String, val channelName: String, val items: List<FeedItemEntity>)

/**
 * Reads YouTube's public per-channel Atom feed
 * (https://www.youtube.com/feeds/videos.xml?channel_id=...). No API key or login is needed, but
 * the endpoint sometimes returns 404 for channels that exist, so a failure only marks the channel
 * stale and keeps its previous items.
 */
class SubscriptionFeedClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    /** Fetches and parses one channel's feed, retrying once after a short pause. Null on failure. */
    suspend fun fetch(channelId: String): ChannelFeed? = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            try {
                val request = Request.Builder()
                    .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
                    .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.5")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        return@withContext parse(channelId, body)
                    }
                }
            } catch (_: Exception) {
            }
            if (attempt == 0) delay(1_500L)
        }
        null
    }

    companion object {
        /** Parses the Atom XML. Unknown elements are ignored. */
        fun parse(channelId: String, xml: String): ChannelFeed? {
            if (xml.isBlank()) return null
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var channelName = ""
            val items = ArrayList<FeedItemEntity>()
            var inEntry = false
            var inAuthor = false
            var videoId = ""
            var title = ""
            var published = 0L
            var views = 0L
            var isShort = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "entry" -> {
                            inEntry = true
                            videoId = ""; title = ""; published = 0L; views = 0L; isShort = false
                        }
                        "author" -> inAuthor = true
                        "yt:videoId" -> if (inEntry) videoId = parser.nextText().trim()
                        "title" -> {
                            val text = parser.nextText().trim()
                            if (inEntry) title = text else if (channelName.isEmpty()) channelName = text
                        }
                        "name" -> if (inAuthor && !inEntry && channelName.isEmpty()) channelName = parser.nextText().trim()
                        "published" -> if (inEntry) published = parseIso8601(parser.nextText().trim())
                        "media:statistics" -> if (inEntry) views = parser.getAttributeValue(null, "views")?.toLongOrNull() ?: 0L
                        "link" -> if (inEntry && parser.getAttributeValue(null, "rel") == "alternate") {
                            isShort = parser.getAttributeValue(null, "href").orEmpty().contains("/shorts/")
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "author" -> inAuthor = false
                        "entry" -> {
                            inEntry = false
                            if (videoId.isNotEmpty()) {
                                items += FeedItemEntity(
                                    videoId = videoId,
                                    channelId = channelId,
                                    channelName = channelName,
                                    title = title,
                                    publishedAt = published,
                                    viewCount = views,
                                    isShort = isShort
                                )
                            }
                        }
                    }
                }
                event = parser.next()
            }
            // channelName is known only after the feed header; fill it into all items.
            return ChannelFeed(channelId, channelName, items.map { it.copy(channelName = channelName) })
        }

        private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})?""")

        /** "2026-10-20T15:00:12+00:00" -> epoch ms (UTC). 0 if it cannot be parsed. */
        fun parseIso8601(text: String): Long {
            val m = ISO.find(text) ?: return 0L
            val g = m.groupValues
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            var millis = cal.timeInMillis
            val zone = g[7]
            if (zone.isNotEmpty() && zone != "Z") {
                val sign = if (zone[0] == '-') -1 else 1
                val offsetMin = zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt()
                millis -= sign * offsetMin * 60_000L
            }
            return millis
        }
    }
}

/** Refreshes all subscribed channels, at most four at a time. */
class SubscriptionRefresher(
    private val library: LibraryRepository,
    private val client: SubscriptionFeedClient = SubscriptionFeedClient()
) {
    data class Result(val refreshed: Int, val failed: Int)

    suspend fun refreshAll(): Result = coroutineScope {
        val semaphore = Semaphore(4)
        val outcomes = library.allSubscriptions().map { sub ->
            async {
                semaphore.withPermit {
                    val feed = client.fetch(sub.channelId)
                    if (feed != null) {
                        library.saveChannelFeed(sub.channelId, feed.channelName.ifBlank { sub.name }, feed.items)
                        true
                    } else {
                        library.markChannelStale(sub.channelId)
                        false
                    }
                }
            }
        }.awaitAll()
        Result(refreshed = outcomes.count { it }, failed = outcomes.count { !it })
    }

    /** Refreshes one channel right after subscribing so its videos show up immediately. */
    suspend fun refreshOne(channelId: String, fallbackName: String): Boolean {
        val feed = client.fetch(channelId) ?: return false.also { library.markChannelStale(channelId) }
        library.saveChannelFeed(channelId, feed.channelName.ifBlank { fallbackName }, feed.items)
        return true
    }
}

/**
 * Google Takeout's YouTube subscriptions export (subscriptions.csv). Columns are found by header
 * name; any cell that looks like a channel id ("UC" + 22 characters) is accepted as a fallback.
 */
object TakeoutSubscriptionsParser {
    data class Channel(val channelId: String, val name: String)

    fun parse(csv: String): List<Channel> {
        val rows = csv.lineSequence().map { splitCsvLine(it) }.filter { it.any { cell -> cell.isNotBlank() } }.toList()
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        val idCol = header.indexOfFirst { it.contains("id") }
        val titleCol = header.indexOfFirst { it.contains("title") || it.contains("name") }
        val result = LinkedHashMap<String, Channel>()
        for (row in rows.drop(1)) {
            val id = row.getOrNull(idCol)?.trim()?.takeIf { LibraryRepository.CHANNEL_ID.matches(it) }
                ?: row.map { it.trim() }.firstOrNull { LibraryRepository.CHANNEL_ID.matches(it) }
                ?: continue
            val name = row.getOrNull(titleCol)?.trim().orEmpty()
            result[id] = Channel(id, name)
        }
        return result.values.toList()
    }

    /** Minimal CSV splitting: commas, double-quoted fields and "" escapes. */
    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
