package com.nuvio.app.features.player.skip

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

object PlayerNextEpisodeRules {

    fun resolveNextEpisode(
        videos: List<MetaVideo>,
        currentSeason: Int?,
        currentEpisode: Int?,
    ): MetaVideo? {
        if (currentSeason == null || currentEpisode == null) return null
        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(
                compareBy<MetaVideo> { it.season ?: Int.MAX_VALUE }
                    .thenBy { it.episode ?: Int.MAX_VALUE }
            )

        val currentIndex = sortedEpisodes.indexOfFirst {
            it.season == currentSeason && it.episode == currentEpisode
        }
        if (currentIndex < 0) return null
        return sortedEpisodes.getOrNull(currentIndex + 1)
    }

    fun shouldShowNextEpisodeCard(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Boolean {
        if (isShortPlaceholderDuration(durationMs)) return false
        if (durationMs <= 0L) return false
        val thresholdPositionMs = userThresholdPositionMs(
            durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
        )
        val credits = creditsTiming(durationMs, skipIntervals, thresholdPositionMs)
            ?: return positionMs >= thresholdPositionMs
        return when {
            // A real post-credits scene (marked by the data provider): wait until it has played.
            credits.postCreditsSceneEndMs != null ->
                positionMs >= maxOf(credits.postCreditsSceneEndMs, thresholdPositionMs)
            // Credits near the end of the file: show the card when they begin, or at the user's
            // threshold if that comes first.
            credits.trusted -> positionMs >= minOf(credits.startMs, thresholdPositionMs)
            // Outro times that start well before the end belong to another release (or mark something
            // that is not the end credits): ignore them so the card can't show mid-episode.
            else -> positionMs >= thresholdPositionMs
        }
    }

    /**
     * True when auto-play may switch to the next episode. The card shows when the credits begin, but the
     * switch waits for the credits to finish (or the user's threshold, if that comes first), so it never
     * cuts the episode off while the credits are still rolling over the last scene.
     */
    fun shouldAutoAdvanceToNextEpisode(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Boolean {
        if (!shouldShowNextEpisodeCard(
                positionMs, durationMs, skipIntervals, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
            )
        ) return false
        val thresholdPositionMs = userThresholdPositionMs(
            durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
        )
        val credits = creditsTiming(durationMs, skipIntervals, thresholdPositionMs) ?: return true
        if (credits.postCreditsSceneEndMs != null || !credits.trusted) return true
        return positionMs >= minOf(credits.endMs, thresholdPositionMs)
    }

    private class CreditsTiming(
        val startMs: Long,
        val endMs: Long,
        val postCreditsSceneEndMs: Long?,
        val trusted: Boolean,
    )

    private fun creditsTiming(
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdPositionMs: Long,
    ): CreditsTiming? {
        // Outro times are crowd-sourced (IntroDB) for one release of the episode. An outro that starts
        // at or after this file's end belongs to a longer cut, so it must not hold the card back.
        val outroSegments = skipIntervals.filter {
            it.type in OUTRO_SEGMENT_TYPES && it.startTime.isFinite() && it.endTime.isFinite() &&
                it.endTime > it.startTime && it.startTime * 1_000.0 < durationMs
        }
        // The end credits are the outro that finishes last; an earlier "ed" segment is not the end.
        val latestOutro = outroSegments.maxByOrNull { it.endTime } ?: return null
        val startMs = (latestOutro.startTime * 1_000.0).toLong().coerceAtLeast(0L)
        val endMs = (latestOutro.endTime * 1_000.0).toLong().coerceAtMost(durationMs)
        // Same post-credits detection as the skip-credits button, so the card never covers a real scene.
        val scene = latestOutro.findFollowingPostCreditsScene(skipIntervals, durationMs)
        val sceneEndMs = scene?.takeIf { it.provider != "heuristic" }
            ?.let { (it.endTime * 1_000.0).toLong().coerceAtMost(durationMs) }
        // End credits sit at the end of the file. A start further from the end than this window is from
        // a different cut of the episode, so it is not trusted to show the card early.
        val creditsWindowMs = maxOf(
            durationMs - thresholdPositionMs,
            minOf(MAX_CREDITS_WINDOW_MS, (durationMs * MAX_CREDITS_WINDOW_FRACTION).toLong()),
        )
        return CreditsTiming(
            startMs = startMs,
            endMs = endMs,
            postCreditsSceneEndMs = sceneEndMs,
            trusted = durationMs - startMs <= creditsWindowMs,
        )
    }

    /** End credits (plus any tail after them) start at most this far from the end of the file. */
    private const val MAX_CREDITS_WINDOW_MS = 6 * 60_000L
    private const val MAX_CREDITS_WINDOW_FRACTION = 0.15

    fun hasEpisodeAired(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val dateStr = when {
            value.length >= 10 -> value.substring(0, 10)
            else -> return true
        }
        // Parse YYYY-MM-DD
        val parts = dateStr.split("-")
        if (parts.size != 3) return true
        val year = parts[0].toIntOrNull() ?: return true
        val month = parts[1].toIntOrNull() ?: return true
        val day = parts[2].toIntOrNull() ?: return true

        val today = currentDateComponents()
        return compareDate(year, month, day, today.year, today.month, today.day) <= 0
    }

    private fun compareDate(
        y1: Int, m1: Int, d1: Int,
        y2: Int, m2: Int, d2: Int,
    ): Int {
        if (y1 != y2) return y1.compareTo(y2)
        if (m1 != m2) return m1.compareTo(m2)
        return d1.compareTo(d2)
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    private const val POST_CREDITS_GAP_MS = 5_000L

    private fun SkipInterval.findFollowingPostCreditsScene(
        intervals: List<SkipInterval>,
        durationMs: Long,
    ): SkipInterval? {
        if (type !in OUTRO_SEGMENT_TYPES) return null
        val explicit = intervals.filter {
            it.type.trim().lowercase() == "post-credits" &&
                it.startTime.isFinite() && it.endTime.isFinite() &&
                it.startTime >= endTime && it.endTime > it.startTime &&
                (durationMs <= 0L || it.startTime * 1000.0 < durationMs)
        }.minByOrNull { it.startTime }
        if (explicit != null) return explicit
        if (durationMs > 0L) {
            val creditsEndMs = (endTime * 1000.0).toLong()
            val gapMs = durationMs - creditsEndMs
            if (gapMs > POST_CREDITS_GAP_MS) {
                return SkipInterval(
                    startTime = endTime,
                    endTime = durationMs / 1000.0,
                    type = "post-credits",
                    provider = "heuristic",
                )
            }
        }
        return null
    }

    private fun userThresholdPositionMs(
        durationMs: Long,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float,
    ): Long = when (thresholdMode) {
        NextEpisodeThresholdMode.PERCENTAGE -> {
            val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
            kotlin.math.ceil(durationMs * (clampedPercent / 100.0)).toLong()
        }
        NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
            val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
            durationMs - (clampedMinutes * 60_000f).toLong()
        }
    }
}

internal expect fun currentDateComponents(): DateComponents

data class DateComponents(val year: Int, val month: Int, val day: Int)
