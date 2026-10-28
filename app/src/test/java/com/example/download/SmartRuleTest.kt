package com.example.download

import com.example.data.PlayEventEntity
import com.example.data.SmartSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The smart-download rule (pure Kotlin, no Android). */
class SmartRuleTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun play(id: String, daysAgo: Int, listened: Int, duration: Int = 200, completed: Boolean = false) =
        PlayEventEntity(videoId = id, startedAt = now - daysAgo * day, listenedSec = listened, durationSec = duration, completed = completed, isMusic = true)

    private fun song(id: String, channel: String = "Artist $id", duration: Int = 200) = SongInfo(id, "Song $id", "", channel, duration)

    private fun signals(id: String, plays: List<PlayEventEntity>, liked: Boolean = false, channel: String = "Artist $id") =
        SongSignals(song(id, channel), plays, liked = liked, inPlaylist = false, subscribed = false)

    @Test
    fun `play weights and fading`() {
        assertEquals(1.0, SmartRule.playWeight(play("a", 0, 170)), 1e-9)      // 85 % = full listen
        assertEquals(0.4, SmartRule.playWeight(play("a", 0, 60)), 1e-9)       // partial
        assertEquals(-0.5, SmartRule.playWeight(play("a", 0, 10)), 1e-9)      // skip
        assertEquals(0.5, SmartRule.decay(30 * day), 1e-9)                    // half after 30 days
    }

    @Test
    fun `score adds lifetime listens and bonuses`() {
        val s = signals("a", listOf(play("a", 0, 200, completed = true)), liked = true)
        // 1.0 (today) + 0.25 (one lifetime full listen) + 3 (liked)
        assertEquals(4.25, SmartRule.score(s, now), 1e-9)
    }

    @Test
    fun `your music needs a full listen, a like or a playlist`() {
        val skipped = signals("a", listOf(play("a", 0, 40)))
        val finished = signals("b", listOf(play("b", 0, 200, completed = true)))
        val ranked = SmartRule.rankYourMusic(listOf(skipped, finished), now, emptySet())
        assertEquals(listOf("b"), ranked.map { it.song.videoId })
    }

    @Test
    fun `discover skips heard songs and caps each artist`() {
        val seed = RankedSong(song("s"), 2.0, SmartSection.YOUR_MUSIC)
        val related = mapOf("s" to (1..8).map { song("r$it", channel = "Same artist") } + song("heard"))
        val ranked = SmartRule.rankDiscover(listOf(seed), related, emptyList(), heard = setOf("heard"), excluded = emptySet(), knownArtists = emptySet())
        assertEquals(SmartRule.MAX_PER_ARTIST_DISCOVER, ranked.size)
        assertFalse(ranked.any { it.song.videoId == "heard" })
    }

    @Test
    fun `plan splits the budget and gives spare space to the other half`() {
        val your = (1..10).map { RankedSong(song("y$it"), 10.0 - it, SmartSection.YOUR_MUSIC) }
        val budget = 10 * SmartRule.estimatedBytes(200)
        // No discover candidates: all ten "your music" songs fit.
        val plan = SmartRule.plan(your, emptyList(), budget, emptySet())
        assertEquals(10, plan.download.size)
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun `plan keeps downloaded songs just below the cut and removes the rest`() {
        val your = (1..10).map { RankedSong(song("y$it"), 10.0 - it, SmartSection.YOUR_MUSIC) }
        val budget = 4 * SmartRule.estimatedBytes(200)        // 2 songs per half, no discover
        val downloaded = setOf("y5", "y10")
        val plan = SmartRule.plan(your, emptyList(), budget, downloaded)
        val ids = plan.all.map { it.song.videoId }.toSet()
        // 4 picked (y1..y4); y5 is within 1.2 x 4 = 5 and already downloaded -> kept if it fits.
        assertTrue("y10" in plan.remove)
        assertTrue(ids.size <= 5)
    }
}
