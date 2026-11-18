package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStallFailoverPolicyTest {
    private fun sample(
        wantsToPlay: Boolean = true,
        waiting: Boolean = true,
        position: Long = 60_000L,
        duration: Long = 3_600_000L,
    ) = PlaybackStallFailoverPolicy.Sample(wantsToPlay, waiting, position, duration)

    @Test
    fun stuckBufferingWithNoProgressAccumulatesUntilFailover() {
        var stalled = 0L
        repeat(30) {
            stalled = PlaybackStallFailoverPolicy.nextStalledMs(stalled, 60_000L, sample(), 1_000L)
        }
        assertEquals(30_000L, stalled)
        assertTrue(PlaybackStallFailoverPolicy.shouldFailover(stalled))
    }

    @Test
    fun progressPauseLiveAndEndOfFileResetTheStall() {
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(position = 61_000L), 1_000L))
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(wantsToPlay = false), 1_000L))
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(waiting = false), 1_000L))
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 60_000L, sample(duration = 0L), 1_000L))
        assertEquals(0L, PlaybackStallFailoverPolicy.nextStalledMs(10_000L, 3_598_000L, sample(position = 3_598_000L), 1_000L))
        assertFalse(PlaybackStallFailoverPolicy.shouldFailover(29_000L))
    }
}
