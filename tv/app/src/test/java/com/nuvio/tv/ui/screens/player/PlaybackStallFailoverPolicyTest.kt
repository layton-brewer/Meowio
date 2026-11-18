package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallFailoverPolicyTest {
    private fun sample(
        wantsToPlay: Boolean = true,
        waiting: Boolean = true,
        position: Long = 60_000L,
        duration: Long = 3_600_000L,
    ) = PlaybackStallFailoverPolicy.Sample(wantsToPlay, waiting, position, duration)

    @Test
    fun `stuck buffering with no progress accumulates until failover`() {
        var stalled = 0L
        repeat(30) {
            stalled = PlaybackStallFailoverPolicy.nextStalledMs(stalled, 60_000L, sample(), 1_000L)
        }
        assertEquals(30_000L, stalled)
        assertTrue(PlaybackStallFailoverPolicy.shouldFailover(stalled))
    }

    @Test
    fun `tiny watchdog seek does not reset the stall`() {
        val stalled = PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(position = 60_250L), 1_000L)
        assertEquals(11_000L, stalled)
    }

    @Test
    fun `real progress, pause, live and end of file reset it`() {
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(position = 61_000L), 1_000L))
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(wantsToPlay = false), 1_000L))
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(waiting = false), 1_000L))
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(duration = -1L), 1_000L))
        assertEquals(
            0L,
            PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 3_598_000L, sample(position = 3_598_000L), 1_000L)
        )
        assertFalse(PlaybackStallFailoverPolicy.shouldFailover(29_000L))
    }
}
