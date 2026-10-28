package com.example.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Home tab's "For you" rule (pure Kotlin, no Android). */
class ForYouRuleTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun video(
        id: String,
        channel: String = "UC$id",
        source: ForYouSource = ForYouSource.RELATED,
        daysOld: Int = 1,
        duration: Int = 600,
        title: String = "Video $id",
        channelName: String = "Channel $channel",
        isShort: Boolean = false
    ) = ForYouVideo(id, title, channel, channelName, duration, 1000, now - daysOld * day, "", isShort, source)

    private fun watch(id: String, channel: String, fraction: Float = 1f, daysAgo: Int = 1, liked: Boolean = false, music: Boolean = false) =
        WatchSignal(id, channel, "Channel $channel", fraction, now - daysAgo * day, music, liked, saved = false)

    @Test
    fun `channel score adds watch time, subscription, star and likes`() {
        val raw = ForYouRule.rawChannelScores(
            listOf(watch("a", "UC1", 1f, 0, liked = true), watch("b", "UC1", 0.5f, 30)),
            subscribed = setOf("UC1", "UC2"),
            starred = setOf("UC2"),
            now = now
        )
        // UC1: 1.0 (today) + 0.5 x 0.5 (30 days) + 1 like + 2 subscribed
        assertEquals(1.0 + 0.25 + 1.0 + 2.0, raw.getValue("UC1"), 1e-9)
        // UC2: subscribed + starred
        assertEquals(4.0, raw.getValue("UC2"), 1e-9)
        val scaled = ForYouRule.channelScores(raw)
        assertEquals(1.0, scaled.getValue("UC1"), 1e-9)
        assertEquals(4.0 / 4.25, scaled.getValue("UC2"), 1e-9)
    }

    @Test
    fun `seeds are recent non-music videos watched at least half, liked or saved`() {
        val seeds = ForYouRule.pickSeeds(
            listOf(
                watch("half", "UC1", 0.6f, 2),
                watch("little", "UC1", 0.2f, 2),
                watch("likedLittle", "UC1", 0.1f, 2, liked = true),
                watch("old", "UC1", 1f, 90),
                watch("song", "UC1", 1f, 1, music = true)
            ),
            now
        ).map { it.first.videoId }
        assertEquals(setOf("half", "likedLittle"), seeds.toSet())
    }

    @Test
    fun `exclusions`() {
        val watched = setOf("seen")
        val notInterested = setOf("nope")
        val hidden = setOf("UChidden")
        fun excluded(v: ForYouVideo) = ForYouRule.isExcluded(v, watched, notInterested, hidden)
        assertTrue(excluded(video("seen")))
        assertTrue(excluded(video("nope")))
        assertTrue(excluded(video("x", channel = "UChidden")))
        assertTrue(excluded(video("short", duration = 45)))
        assertTrue(excluded(video("short2", isShort = true)))
        assertTrue(excluded(video("tag", title = "Funny #Shorts")))
        assertTrue(excluded(video("topic", channelName = "Artist - Topic")))
        assertFalse(excluded(video("ok")))
        assertFalse(excluded(video("unknownLength", duration = 0)))
    }

    @Test
    fun `subscription uploads favour fresh ones and drop old ones`() {
        val ranked = ForYouRule.rankSubscriptionUploads(
            listOf(
                video("week", source = ForYouSource.SUBSCRIPTION, daysOld = 7),
                video("today", source = ForYouSource.SUBSCRIPTION, daysOld = 0),
                video("old", source = ForYouSource.SUBSCRIPTION, daysOld = 40)
            ),
            channel = emptyMap(),
            now = now,
            excluded = { false }
        )
        assertEquals(listOf("today", "week"), ranked.map { it.video.videoId })
        assertEquals(3.0, ranked[0].score, 1e-9)
        assertEquals(1.5, ranked[1].score, 1e-9)
    }

    @Test
    fun `related videos listed by more and stronger seeds rank higher`() {
        val s1 = watch("s1", "UCa", 1f, 0)
        val s2 = watch("s2", "UCb", 0.5f, 0)
        val seeds = listOf(s1 to 1.0, s2 to 0.5)
        val related = mapOf(
            "s1" to listOf(video("both"), video("one")),
            "s2" to listOf(video("both"), video("weak"))
        )
        val ranked = ForYouRule.rankRelated(seeds, related, emptyMap(), excluded = { false })
        assertEquals(listOf("both", "one", "weak"), ranked.map { it.video.videoId })
        assertEquals(2.0, ranked[0].score, 1e-9)            // 1.5 / 1.5 x 2
        assertEquals(2.0 * 1.0 / 1.5, ranked[1].score, 1e-9)
    }

    @Test
    fun `mix keeps the share, the per-channel cap and interleaves`() {
        val subs = (1..20).map { RankedVideo(video("s$it", channel = "UCs${it % 10}", source = ForYouSource.SUBSCRIPTION), 10.0 - it * 0.1) }
        val rel = (1..20).map { RankedVideo(video("r$it", channel = "UCr${it % 10}"), 10.0 - it * 0.1) }
        val mixed = ForYouRule.mix(subs, rel, size = 10, share = 0.5, maxPerChannel = 3)
        assertEquals(10, mixed.size)
        assertEquals(5, mixed.count { it.source == ForYouSource.SUBSCRIPTION })
        // Alternating: S, R, S, R, ...
        assertEquals(ForYouSource.SUBSCRIPTION, mixed[0].source)
        assertEquals(ForYouSource.RELATED, mixed[1].source)
        assertEquals(ForYouSource.SUBSCRIPTION, mixed[2].source)

        val oneChannel = (1..10).map { RankedVideo(video("c$it", channel = "UCsame", source = ForYouSource.SUBSCRIPTION), 1.0) }
        val capped = ForYouRule.mix(oneChannel, emptyList(), size = 10, share = 0.5, maxPerChannel = 3)
        assertEquals(3, capped.size)

        // Related runs short: subscription uploads fill the rest.
        val filled = ForYouRule.mix(subs, rel.take(2), size = 10, share = 0.5, maxPerChannel = 3)
        assertEquals(10, filled.size)
        assertEquals(8, filled.count { it.source == ForYouSource.SUBSCRIPTION })
    }
}
