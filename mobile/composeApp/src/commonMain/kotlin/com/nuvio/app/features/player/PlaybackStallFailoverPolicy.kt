package com.nuvio.app.features.player

/**
 * Decides when a source that already started playing has stopped for good: the user wants it
 * to play, the player is waiting for data, and the position has not moved for
 * [STALL_FAILOVER_MS]. Such a source is replaced with the next best one at the same position.
 */
internal object PlaybackStallFailoverPolicy {
    const val STALL_FAILOVER_MS = 30_000L
    const val POLL_INTERVAL_MS = 1_000L

    /** Smaller moves are a player's own nudge past the buffered edge, not real playback. */
    const val MIN_POSITION_ADVANCE_MS = 750L
    const val END_GUARD_MS = 5_000L

    /** Extra time Smart failover gives a source beyond the start-up timeout before moving on. */
    const val EXTRA_STARTUP_WAIT_MS = 5_000L

    data class Sample(
        val wantsToPlay: Boolean,
        val waitingForData: Boolean,
        val positionMs: Long,
        val durationMs: Long,
    )

    /** How long playback has been stuck after [sample], given it was stuck for [stalledMs] before. */
    fun nextStalledMs(stalledMs: Long, lastPositionMs: Long?, sample: Sample, elapsedMs: Long): Long {
        // Live or unknown-length streams are left to the player's own error handling.
        if (sample.durationMs <= 0L) return 0L
        // The last seconds of a file are the end of playback, not a stall.
        if (sample.positionMs >= sample.durationMs - END_GUARD_MS) return 0L
        if (!sample.wantsToPlay || !sample.waitingForData) return 0L
        if (lastPositionMs == null) return 0L
        if (sample.positionMs - lastPositionMs >= MIN_POSITION_ADVANCE_MS) return 0L
        if (lastPositionMs - sample.positionMs >= MIN_POSITION_ADVANCE_MS) return 0L
        return stalledMs + elapsedMs
    }

    fun shouldFailover(stalledMs: Long): Boolean = stalledMs >= STALL_FAILOVER_MS
}
