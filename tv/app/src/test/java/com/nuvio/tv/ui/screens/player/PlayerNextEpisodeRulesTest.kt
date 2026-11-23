package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Video
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextEpisodeRulesTest {

    private fun ep(season: Int?, episode: Int?, id: String = "s${season}e${episode}") =
        Video(
            id = id,
            title = id,
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null
        )

    @Test
    fun `advances to next episode in same season`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s1e3", next?.id)
    }

    @Test
    fun `crosses into next season after the season finale`() {
        val videos = listOf(ep(1, 1), ep(1, 2), ep(2, 1))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertEquals("s2e1", next?.id)
    }

    @Test
    fun `returns null after the very last episode`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 1, currentEpisode = 2)
        assertNull(next)
    }

    @Test
    fun `returns null when the current episode is not in the list`() {
        val videos = listOf(ep(1, 1), ep(1, 2))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = 3, currentEpisode = 9)
        assertNull(next)
    }

    @Test
    fun `absolute numbering advances by episode when the caller has no season`() {
        // Season-less anime: the caller has no season but the meta videos still carry one.
        val videos = listOf(ep(1, 5), ep(1, 6), ep(1, 7))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("s1e7", next?.id)
    }

    @Test
    fun `absolute numbering advances when the meta videos also lack a season`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"), ep(null, 7, "e7"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertEquals("e7", next?.id)
    }

    @Test
    fun `absolute numbering returns null after the last episode`() {
        val videos = listOf(ep(null, 5, "e5"), ep(null, 6, "e6"))
        val next = PlayerNextEpisodeRules.resolveNextEpisode(videos, currentSeason = null, currentEpisode = 6)
        assertNull(next)
    }

    @Test
    fun `timestamped episode does not air early on its local release day`() {
        val eastern = ZoneId.of("America/Detroit")
        val before = Clock.fixed(Instant.parse("2026-07-15T14:59:59Z"), eastern)
        val exact = Clock.fixed(Instant.parse("2026-07-15T15:00:00Z"), eastern)

        assertFalse(PlayerNextEpisodeRules.hasEpisodeAired("2026-07-15T15:00:00Z", before))
        assertTrue(PlayerNextEpisodeRules.hasEpisodeAired("2026-07-15T15:00:00Z", exact))
    }

    private fun shouldShow(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval> = emptyList(),
        mode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
        percent: Float = 97f,
        minutesBeforeEnd: Float = 2f
    ) = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
        positionMs = positionMs,
        durationMs = durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = mode,
        thresholdPercent = percent,
        thresholdMinutesBeforeEnd = minutesBeforeEnd
    )

    private fun outro(startSec: Double, endSec: Double) =
        SkipInterval(startTime = startSec, endTime = endSec, type = "outro", provider = "introdb")

    @Test
    fun `percentage mode fires past the threshold`() {
        assertTrue(shouldShow(positionMs = 44 * 60_000L, durationMs = 45 * 60_000L))
    }

    @Test
    fun `a duration below the position does not fire in percentage mode`() {
        // 25 minutes into a 45 minute episode, player transiently reports 20 minutes.
        assertFalse(shouldShow(positionMs = 25 * 60_000L, durationMs = 20 * 60_000L))
    }

    @Test
    fun `a duration below the position does not fire in minutes mode`() {
        assertFalse(
            shouldShow(
                positionMs = 25 * 60_000L,
                durationMs = 20 * 60_000L,
                mode = NextEpisodeThresholdMode.MINUTES_BEFORE_END
            )
        )
    }

    @Test
    fun `a duration below the position does not fire with outro segments`() {
        assertFalse(
            shouldShow(
                positionMs = 25 * 60_000L,
                durationMs = 20 * 60_000L,
                skipIntervals = listOf(outro(startSec = 1_180.0, endSec = 1_200.0))
            )
        )
    }

    @Test
    fun `credits followed by a few seconds of logos still show the card`() {
        // 45 min episode, credits end 15 s before the file does (shorter than the 97% window).
        val durationMs = 45 * 60_000L
        val skip = listOf(outro(startSec = 2_640.0, endSec = 2_685.0))
        assertFalse(shouldShow(positionMs = 2_600_000L, durationMs = durationMs, skipIntervals = skip))
        assertTrue(shouldShow(positionMs = 2_641_000L, durationMs = durationMs, skipIntervals = skip))
    }

    @Test
    fun `credits that end well before the file does still show the card when they begin`() {
        // 45 min episode, credits run 40:00-43:00, then 2 min of extras: card at the credits start.
        val durationMs = 45 * 60_000L
        val skip = listOf(outro(startSec = 2_400.0, endSec = 2_580.0))
        assertFalse(shouldShow(positionMs = 2_390_000L, durationMs = durationMs, skipIntervals = skip))
        assertTrue(shouldShow(positionMs = 2_401_000L, durationMs = durationMs, skipIntervals = skip))
    }

    private fun shouldAdvance(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval> = emptyList(),
        percent: Float = 99f,
    ) = PlayerNextEpisodeRules.shouldAutoAdvanceToNextEpisode(
        positionMs = positionMs,
        durationMs = durationMs,
        skipIntervals = skipIntervals,
        thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
        thresholdPercent = percent,
        thresholdMinutesBeforeEnd = 2f
    )

    @Test
    fun `auto-play waits for the credits to finish instead of switching when the card shows`() {
        // 45 min episode, credits 40:00-43:00, 2 min tail: card at 40:00, switch at 43:00.
        val durationMs = 45 * 60_000L
        val skip = listOf(outro(startSec = 2_400.0, endSec = 2_580.0))
        assertTrue(shouldShow(positionMs = 2_401_000L, durationMs = durationMs, skipIntervals = skip, percent = 99f))
        assertFalse(shouldAdvance(positionMs = 2_401_000L, durationMs = durationMs, skipIntervals = skip))
        assertFalse(shouldAdvance(positionMs = 2_570_000L, durationMs = durationMs, skipIntervals = skip))
        assertTrue(shouldAdvance(positionMs = 2_580_000L, durationMs = durationMs, skipIntervals = skip))
    }

    @Test
    fun `credits that run to the end switch at the user threshold`() {
        // Credits 43:30-45:00 on a 45 min file, 99% threshold = 44:33.
        val durationMs = 45 * 60_000L
        val skip = listOf(outro(startSec = 2_610.0, endSec = 2_700.0))
        assertTrue(shouldShow(positionMs = 2_611_000L, durationMs = durationMs, skipIntervals = skip, percent = 99f))
        assertFalse(shouldAdvance(positionMs = 2_650_000L, durationMs = durationMs, skipIntervals = skip))
        assertTrue(shouldAdvance(positionMs = 2_674_000L, durationMs = durationMs, skipIntervals = skip))
    }

    @Test
    fun `outro times far from the end do not show the card mid-episode`() {
        // Outro from another cut starts 15 min before this file ends: ignore it, use the threshold.
        val durationMs = 45 * 60_000L
        val skip = listOf(outro(startSec = 1_800.0, endSec = 1_890.0))
        assertFalse(shouldShow(positionMs = 1_801_000L, durationMs = durationMs, skipIntervals = skip, percent = 99f))
        assertFalse(shouldShow(positionMs = 2_600_000L, durationMs = durationMs, skipIntervals = skip, percent = 99f))
        assertTrue(shouldShow(positionMs = 2_674_000L, durationMs = durationMs, skipIntervals = skip, percent = 99f))
        assertTrue(shouldAdvance(positionMs = 2_674_000L, durationMs = durationMs, skipIntervals = skip))
    }

    @Test
    fun `an earlier ending segment does not pull the card forward`() {
        // Anime: an "ed" segment mid-episode and the real ending at 21:30-23:00 of 24 min.
        val durationMs = 24 * 60_000L
        val skip = listOf(
            SkipInterval(startTime = 600.0, endTime = 690.0, type = "ed", provider = "aniskip"),
            SkipInterval(startTime = 1_290.0, endTime = 1_380.0, type = "ed", provider = "aniskip"),
        )
        assertFalse(shouldShow(positionMs = 700_000L, durationMs = durationMs, skipIntervals = skip, percent = 99f))
        assertTrue(shouldShow(positionMs = 1_291_000L, durationMs = durationMs, skipIntervals = skip, percent = 99f))
        assertFalse(shouldAdvance(positionMs = 1_291_000L, durationMs = durationMs, skipIntervals = skip))
        assertTrue(shouldAdvance(positionMs = 1_380_000L, durationMs = durationMs, skipIntervals = skip))
    }

    @Test
    fun `no outro data switches when the card shows`() {
        val durationMs = 45 * 60_000L
        assertFalse(shouldAdvance(positionMs = 2_600_000L, durationMs = durationMs))
        assertTrue(shouldAdvance(positionMs = 2_674_000L, durationMs = durationMs))
    }

    @Test
    fun `outro times from a longer release do not hide the card`() {
        // Outro starts after this file ends: fall back to the normal threshold.
        val durationMs = 40 * 60_000L
        val skip = listOf(outro(startSec = 2_500.0, endSec = 2_560.0))
        assertTrue(shouldShow(positionMs = 39 * 60_000L, durationMs = durationMs, skipIntervals = skip))
    }

    @Test
    fun `outro times past the card threshold still show the card at the threshold`() {
        // Outro starts inside the file but only 10 s before the end: the 97% threshold comes first.
        val durationMs = 40 * 60_000L
        val skip = listOf(outro(startSec = 2_390.0, endSec = 2_400.0))
        assertTrue(shouldShow(positionMs = 2_330_000L, durationMs = durationMs, skipIntervals = skip))
    }

    @Test
    fun `the end of the episode still fires within the epsilon`() {
        val durationMs = 45 * 60_000L
        assertTrue(shouldShow(positionMs = durationMs, durationMs = durationMs))
        assertTrue(shouldShow(positionMs = durationMs + 500L, durationMs = durationMs))
    }

    @Test
    fun `position beyond the epsilon does not fire`() {
        val durationMs = 45 * 60_000L
        assertFalse(shouldShow(positionMs = durationMs + 1_001L, durationMs = durationMs))
    }

    @Test
    fun `minutes mode fires inside the window`() {
        assertTrue(
            shouldShow(
                positionMs = 44 * 60_000L,
                durationMs = 45 * 60_000L,
                mode = NextEpisodeThresholdMode.MINUTES_BEFORE_END
            )
        )
    }

    @Test
    fun `an unknown duration never fires`() {
        assertFalse(shouldShow(positionMs = 25 * 60_000L, durationMs = 0L))
    }

    private fun awayFromEnd(positionMs: Long, durationMs: Long) =
        PlayerNextEpisodeRules.isAwayFromEnd(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = emptyList(),
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 97f,
            thresholdMinutesBeforeEnd = 2f
        )

    @Test
    fun `the start of a stream is away from the end`() {
        assertTrue(awayFromEnd(positionMs = 0L, durationMs = 22 * 60_000L))
    }

    @Test
    fun `mid-episode is away from the end`() {
        assertTrue(awayFromEnd(positionMs = 11 * 60_000L, durationMs = 22 * 60_000L))
    }

    @Test
    fun `the previous file's end position is not away from the end`() {
        // Stale reading after a switch: previous episode's end against a similar duration.
        assertFalse(awayFromEnd(positionMs = 22 * 60_000L, durationMs = 22 * 60_000L))
    }

    @Test
    fun `a stale position inside the card threshold is not away from the end`() {
        // Previous episode ended at 22:00; the new one reports 22:20. Before the file end, but
        // past 97%, so it must not arm.
        assertFalse(awayFromEnd(positionMs = 22 * 60_000L, durationMs = 22 * 60_000L + 20_000L))
    }

    @Test
    fun `a resume inside the card window but below completion is not away from the end`() {
        // 17:30 of a 20 minute episode is 87.5%, so it resumes, but it is inside a 3 minute window.
        assertFalse(
            PlayerNextEpisodeRules.isAwayFromEnd(
                positionMs = 17 * 60_000L + 30_000L,
                durationMs = 20 * 60_000L,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.MINUTES_BEFORE_END,
                thresholdPercent = 97f,
                thresholdMinutesBeforeEnd = 3f
            )
        )
    }

    @Test
    fun `an unknown duration is not away from the end`() {
        assertFalse(awayFromEnd(positionMs = 0L, durationMs = 0L))
    }
}
