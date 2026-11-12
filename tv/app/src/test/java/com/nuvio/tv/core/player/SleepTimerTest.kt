package com.nuvio.tv.core.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {

    @After
    fun tearDown() {
        SleepTimer.cancel()
    }

    @Test
    fun `cycle walks through every option and back to off`() {
        val seen = (1..7).map { SleepTimer.cycle(nowMs = 0L) }
        assertEquals(
            listOf(15, 30, 45, 60, 90),
            seen.take(5).map { (it as SleepTimer.State.Until).minutes }
        )
        assertEquals(SleepTimer.State.EndOfEpisode, seen[5])
        assertEquals(SleepTimer.State.Off, seen[6])
    }

    @Test
    fun `remaining time counts down and never goes negative`() {
        SleepTimer.start(minutes = 30, nowMs = 1_000L)
        assertEquals(30 * 60_000L, SleepTimer.remainingMs(nowMs = 1_000L))
        assertEquals(60_000L, SleepTimer.remainingMs(nowMs = 1_000L + 29 * 60_000L))
        assertEquals(0L, SleepTimer.remainingMs(nowMs = 1_000L + 31 * 60_000L))
    }

    @Test
    fun `no remaining time without a timed sleep`() {
        assertNull(SleepTimer.remainingMs())
        SleepTimer.endOfEpisode()
        assertNull(SleepTimer.remainingMs())
    }

    @Test
    fun `end of episode stops playback once and clears itself`() {
        SleepTimer.endOfEpisode()
        assertTrue(SleepTimer.consumeEndOfEpisode())
        assertEquals(SleepTimer.State.Off, SleepTimer.state.value)
        assertFalse(SleepTimer.consumeEndOfEpisode())
    }

    @Test
    fun `timed sleep only stops the next episode after the deadline`() {
        SleepTimer.start(minutes = 15, nowMs = 0L)
        assertFalse(SleepTimer.consumeEndOfEpisode(nowMs = 60_000L))
        assertTrue(SleepTimer.consumeEndOfEpisode(nowMs = 15 * 60_000L))
    }

    @Test
    fun `starting with zero minutes turns the timer off`() {
        SleepTimer.start(minutes = 30, nowMs = 0L)
        SleepTimer.start(minutes = 0, nowMs = 0L)
        assertEquals(SleepTimer.State.Off, SleepTimer.state.value)
    }
}
