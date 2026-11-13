package com.nuvio.tv.core.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchInsightsTest {
    private val day = 86_400_000L
    private val now = 20_000 * day + 12 * 3_600_000L // noon UTC

    private fun play(id: String, key: String, pos: Long, dur: Long, done: Boolean, at: Long, episode: Boolean = false) =
        InsightPlay(id, key, id, episode, pos, dur, done, at)

    @Test
    fun `adds up watch time and counts finished titles once`() {
        val summary = WatchInsights.compute(
            plays = listOf(
                play("movie", "movie", 30 * 60_000L, 100 * 60_000L, false, now),
                play("movie", "movie", 10 * 60_000L, 100 * 60_000L, false, now - day), // older copy, ignored
                play("show", "show:1:1", 40 * 60_000L, 42 * 60_000L, true, now - day, episode = true),
                play("show", "show:1:2", 5 * 60_000L, 0, false, now - 2 * day, episode = true),
            ),
            watched = listOf(
                InsightWatched("show", "show:1:1", "Show", true, now - day),
                InsightWatched("show", "show:1:3", "Show", true, now - 5 * day),
                InsightWatched("film", "film", "Film", false, now - 40 * day),
            ),
            libraryGenres = listOf(listOf("Drama", "Crime"), listOf("Drama"), emptyList()),
            nowMs = now,
            utcOffsetMs = 0,
        )
        assertEquals((30 + 42 + 5) * 60_000L, summary.watchTimeMs)
        assertEquals(1, summary.moviesWatched)
        assertEquals(2, summary.episodesWatched)
        assertEquals(1, summary.showsWatched)
        assertEquals(2, summary.inProgress)
        assertEquals(3, summary.streakDays)
        assertEquals(4, summary.activeDaysLast30)
        assertEquals(listOf(InsightTopShow("Show", 2)), summary.topShows)
        assertEquals(3, summary.libraryTitles)
        assertEquals(InsightGenre("Drama", 2), summary.topGenres.first())
        assertEquals(1L to 17L, WatchInsights.hoursAndMinutes(summary.watchTimeMs))
    }

    @Test
    fun `an empty history gives an empty summary`() {
        val summary = WatchInsights.compute(emptyList(), emptyList(), emptyList(), now, 0)
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.streakDays)
        assertNull(summary.busiestWeekday)
    }

    @Test
    fun `the time zone decides which day something was watched on`() {
        // 23:30 UTC yesterday is today for someone two hours ahead.
        val at = 20_000 * day - 30 * 60_000L
        val plays = listOf(play("m", "m", 1, 10, false, at))
        assertEquals(1, WatchInsights.compute(plays, emptyList(), emptyList(), 20_000 * day + 3_600_000L, 2 * 3_600_000L).streakDays)
        assertEquals(3, WatchInsights.compute(plays, emptyList(), emptyList(), at, 0).busiestWeekday)
    }
}
