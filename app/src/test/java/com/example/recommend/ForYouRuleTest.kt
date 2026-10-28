package com.example.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.random.Random

/** The Home tab's "For you" rule, version 2 (pure Kotlin, no Android). */
class ForYouRuleTest {
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = 1_000L * day

    private fun video(
        id: String,
        channel: String = "UC$id",
        source: ForYouSource = ForYouSource.RELATED,
        hoursOld: Long = 24,
        duration: Int = 600,
        views: Long = 0,
        title: String = "Video $id",
        channelName: String = "Channel $channel",
        isShort: Boolean = false
    ) = ForYouVideo(id, title, channel, channelName, duration, views, now - hoursOld * hour, "", isShort, source)

    private fun watch(id: String, channel: String, fraction: Float = 1f, hoursAgo: Long = 24, liked: Boolean = false, music: Boolean = false) =
        WatchSignal(id, channel, "Channel $channel", fraction, now - hoursAgo * hour, music, liked, saved = false)

    private fun scored(v: ForYouVideo, score: Double, impressions: Int = 0, starred: Boolean = false) =
        ScoredVideo(v, score, setOf(v.source), starred, impressions)

    // ---- channels and seeds ------------------------------------------------------------------

    @Test
    fun `channel score adds watch time, subscription, star and likes`() {
        val raw = ForYouRule.rawChannelScores(
            listOf(watch("a", "UC1", 1f, 0, liked = true), watch("b", "UC1", 0.5f, 30 * 24)),
            subscribed = setOf("UC1", "UC2"),
            starred = setOf("UC2"),
            now = now
        )
        // UC1: 1.0 (now) + 0.5 x 0.5 (30 days) + 1 like + 2 subscribed
        assertEquals(1.0 + 0.25 + 1.0 + 2.0, raw.getValue("UC1"), 1e-9)
        assertEquals(4.0, raw.getValue("UC2"), 1e-9)
        val scaled = ForYouRule.channelScores(raw)
        assertEquals(1.0, scaled.getValue("UC1"), 1e-9)
        assertEquals(4.0 / 4.25, scaled.getValue("UC2"), 1e-9)
    }

    @Test
    fun `each disliked video lowers the channel by 0_5, within -1 to 1`() {
        val a = ForYouRule.channelAffinity(mapOf("UC1" to 0.8, "UC2" to 0.2), mapOf("UC1" to 1, "UC2" to 5, "UC3" to 1))
        assertEquals(0.3, a.getValue("UC1"), 1e-9)
        assertEquals(-1.0, a.getValue("UC2"), 1e-9)
        assertEquals(-0.5, a.getValue("UC3"), 1e-9)
    }

    @Test
    fun `seed pool keeps the strongest, doubles watches of the last 3 hours, skips music and old`() {
        val watches = listOf(
            watch("now", "UC1", 0.6f, hoursAgo = 1),
            watch("day", "UC1", 1f, hoursAgo = 24),
            watch("half", "UC2", 0.4f, hoursAgo = 24),        // under 50 %, not liked
            watch("old", "UC3", 1f, hoursAgo = 61 * 24),      // older than 60 days
            watch("song", "UC4", 1f, hoursAgo = 2, music = true),
            watch("liked", "UC5", 0.1f, hoursAgo = 48, liked = true)
        )
        val pool = ForYouRule.seedPool(watches, now)
        // "liked": 0.1 x 0.5^(2 / 30) + 1 = 1.10 > "day": 0.98
        assertEquals(listOf("now", "liked", "day"), pool.map { it.first.videoId })
        assertEquals(2.0 * 0.6f * ForYouRule.decay(hour, 30.0), pool[0].second, 1e-9)
    }

    @Test
    fun `seeds to fetch skip seeds with a recent list and favour strong seeds`() {
        val pool = listOf(watch("strong", "UC1") to 4.0, watch("weak", "UC2") to 1.0, watch("cached", "UC3") to 9.0)
        val random = Random(7)
        var strongFirst = 0
        repeat(2000) {
            val picked = ForYouRule.seedsToFetch(pool, hasFresh = { it == "cached" }, count = 1, random = random)
            assertEquals(1, picked.size)
            assertFalse(picked[0].videoId == "cached")
            if (picked[0].videoId == "strong") strongFirst++
        }
        // Probability 4 / (4 + 1) = 0.8.
        assertEquals(0.8, strongFirst / 2000.0, 0.04)
    }

    @Test
    fun `watched channels have a high watch score and are not subscribed, hidden or disliked`() {
        val a = "UC" + "a".repeat(22)
        val b = "UC" + "b".repeat(22)
        val c = "UC" + "c".repeat(22)
        val d = "UC" + "d".repeat(22)
        val watches = listOf(
            watch("1", a), watch("2", a), watch("3", b), watch("4", b),
            watch("5", c), watch("6", c), watch("7", d, 0.5f)   // d: 0.5 < 1.0
        )
        val chosen = ForYouRule.watchedChannels(watches, subscribed = setOf(b), hidden = emptySet(), disliked = mapOf(c to 1), now = now)
        assertEquals(listOf(a), chosen)
    }

    // ---- scores ------------------------------------------------------------------------------

    @Test
    fun `score sums the sources, popularity and channel, and leaves out excluded videos`() {
        val user = UserSignals(
            watches = listOf(watch("s1", "UCx"), watch("s2", "UCy")),
            subscribed = setOf("UCsub"),
            starred = emptySet(),
            excludedIds = setOf("gone")
        )
        val seeds = listOf(watch("s1", "UCx") to 2.0, watch("s2", "UCy") to 1.0)
        val candidates = ForYouCandidates(
            uploads = listOf(video("up", "UCsub", ForYouSource.SUBSCRIPTION, hoursOld = 7 * 24)),
            related = mapOf(
                "s1" to listOf(video("both"), video("one"), video("gone"), video("short", isShort = true)),
                "s2" to listOf(video("both"))
            ),
            deep = mapOf("both" to listOf(video("deep1")), "one" to listOf(video("deep1"), video("deep2"))),
            channelUploads = listOf(video("ch", "UCch", ForYouSource.CHANNEL, hoursOld = 0)),
            searches = listOf("q1" to listOf(video("q0"), video("q1")), "q2" to listOf(video("p0")))
        )
        val result = ForYouRule.score(candidates, user, seeds, now).associateBy { it.video.videoId }
        assertFalse("gone" in result)
        assertFalse("short" in result)
        // Channels: UCsub (2) is the top raw score; UCx and UCy have 1 each -> 0.5 scaled.
        assertEquals(3.0 * 0.5 + 1.0, result.getValue("up").score, 1e-9)
        assertEquals(2.0 * 3.0 / 3.0, result.getValue("both").score, 1e-9)     // r = 2 + 1 = max
        assertEquals(2.0 * 2.0 / 3.0, result.getValue("one").score, 1e-9)
        assertEquals(1.0, result.getValue("deep1").score, 1e-9)                // in 2 of 2 lists
        assertEquals(0.5, result.getValue("deep2").score, 1e-9)
        assertEquals(1.5, result.getValue("ch").score, 1e-9)
        assertEquals(1.0, result.getValue("q0").score, 1e-9)
        assertEquals(0.5, result.getValue("q1").score, 1e-9)
        assertEquals(0.7, result.getValue("p0").score, 1e-9)
        assertEquals(setOf(ForYouSource.RELATED), result.getValue("both").sources)
        assertEquals(ForYouSource.RELATED, result.getValue("both").video.source)
    }

    @Test
    fun `popularity adds at most 0_25, impressions discount, disliked channels sink`() {
        val user = UserSignals(
            watches = emptyList(), subscribed = emptySet(), starred = emptySet(),
            dislikedChannels = mapOf("UCbad" to 1),
            impressions = mapOf(
                "seen2" to Impression(2, now - 2 * day),
                "seenOld" to Impression(5, now - 4 * day)   // older than 3 days: forgotten
            )
        )
        val seeds = listOf(watch("s", "UCs") to 1.0)
        val candidates = ForYouCandidates(
            related = mapOf("s" to listOf(
                video("popular", views = 10_000_000), video("some", views = 1000),
                video("seen2"), video("seenOld"), video("bad", "UCbad")
            ))
        )
        val result = ForYouRule.score(candidates, user, seeds, now).associateBy { it.video.videoId }
        assertEquals(2.0 + 0.25, result.getValue("popular").score, 1e-9)
        assertEquals(2.0 + 0.25 * log10(1001.0) / 7.0, result.getValue("some").score, 1e-9)
        assertEquals(2.0 * 0.36, result.getValue("seen2").score, 1e-9)
        assertEquals(2, result.getValue("seen2").impressions)
        assertEquals(2.0, result.getValue("seenOld").score, 1e-9)
        assertEquals(1.5, result.getValue("bad").score, 1e-9)
    }

    // ---- order -------------------------------------------------------------------------------

    @Test
    fun `fresh subscription uploads come first, starred first, at most 4, not after 2 impressions`() {
        val list = listOf(
            scored(video("old", "UC1", ForYouSource.SUBSCRIPTION, hoursOld = 72), 3.0),
            scored(video("new1", "UC2", ForYouSource.SUBSCRIPTION, hoursOld = 1), 1.0),
            scored(video("new2", "UC3", ForYouSource.SUBSCRIPTION, hoursOld = 5), 1.0, starred = true),
            scored(video("new3", "UC4", ForYouSource.SUBSCRIPTION, hoursOld = 10), 1.0),
            scored(video("new4", "UC5", ForYouSource.SUBSCRIPTION, hoursOld = 20), 1.0),
            scored(video("new5", "UC6", ForYouSource.SUBSCRIPTION, hoursOld = 30), 1.0),
            scored(video("seen", "UC7", ForYouSource.SUBSCRIPTION, hoursOld = 2), 1.0, impressions = 2),
            scored(video("rel"), 9.0)
        )
        val out = ForYouRule.order(list, now, Random(1)).map { it.videoId }
        assertEquals(listOf("new2", "new1", "new3", "new4"), out.take(4))
        assertTrue(out.containsAll(listOf("old", "new5", "seen", "rel")))
        assertEquals(8, out.size)
    }

    @Test
    fun `every third place is a subscription upload while both groups last`() {
        val subs = (1..10).map { scored(video("s$it", "UCs$it", ForYouSource.SUBSCRIPTION, hoursOld = 100), 1.0) }
        val rel = (1..20).map { scored(video("r$it", "UCr$it"), 1.0) }
        val out = ForYouRule.order(subs + rel, now, Random(3))
        out.forEachIndexed { i, v ->
            val expectSub = i % 3 == 2
            assertEquals("place $i", expectSub, v.source == ForYouSource.SUBSCRIPTION)
        }
    }

    @Test
    fun `no channel more than twice in 20 consecutive videos, also across listed ones`() {
        val many = (1..10).map { scored(video("a$it", "UCa"), 5.0) } + (1..100).map { scored(video("o$it", "UCo$it"), 1.0) }
        val out = ForYouRule.order(many, now, Random(5))
        for (i in out.indices) {
            val window = out.subList(maxOf(0, i - 19), i + 1)
            assertTrue("window ending at $i", window.count { it.channelKey == "UCa" } <= 2)
        }
        // Continuing a list: the last listed videos count.
        val listed = out.take(20)
        val more = ForYouRule.order(many, now, Random(6), listed = listed, pin = false)
        assertTrue(more.none { v -> listed.any { it.videoId == v.videoId } })
        val joined = listed + more
        for (i in joined.indices) {
            val window = joined.subList(maxOf(0, i - 19), i + 1)
            assertTrue("joined window ending at $i", window.count { it.channelKey == "UCa" } <= 2)
        }
    }

    @Test
    fun `refreshes differ, but a clearly better video usually stays near the top`() {
        val best = scored(video("best", "UCbest"), 4.0)
        val rest = (1..40).map { scored(video("v$it", "UCv$it"), 1.0 + (it % 5) * 0.1) }
        var bestInTop5 = 0
        val firsts = HashSet<List<String>>()
        repeat(200) { seed ->
            val out = ForYouRule.order(listOf(best) + rest, now, Random(seed)).map { it.videoId }
            if (out.indexOf("best") < 5) bestInTop5++
            firsts.add(out.take(10))
        }
        assertTrue("best in the top 5 in $bestInTop5 of 200", bestInTop5 >= 190)
        assertTrue("first 10 should vary between refreshes", firsts.size > 150)
    }

    @Test
    fun `an impression discount lets unseen videos overtake`() {
        val user = UserSignals(emptyList(), emptySet(), emptySet(), impressions = mapOf("shown" to Impression(1, now - hour)))
        val seeds = listOf(watch("s", "UCs") to 1.0, watch("t", "UCt") to 1.0)
        val candidates = ForYouCandidates(related = mapOf(
            "s" to listOf(video("shown"), video("fresh")),
            "t" to listOf(video("shown"), video("fresh"))
        ))
        val s = ForYouRule.score(candidates, user, seeds, now)
        var freshFirst = 0
        repeat(500) { seed -> if (ForYouRule.order(s, now, Random(seed)).first().videoId == "fresh") freshFirst++ }
        // Scores 2.0 vs 1.2: P(fresh first) = 1 / (1 + e^(-0.8 / 0.35)) = 0.908.
        assertEquals(0.908, freshFirst / 500.0, 0.04)
    }
}
