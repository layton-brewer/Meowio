package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.Video
import java.time.Clock
import java.time.LocalDate

object PlayerNextEpisodeRules {
    fun resolveNextEpisode(
        videos: List<Video>,
        currentSeason: Int?,
        currentEpisode: Int
    ): Video? {
        // Absolute-numbered content (e.g. some anime via Kitsu) carries no season; order by episode
        // number alone and advance to the next one.
        if (currentSeason == null) {
            val sorted = videos
                .filter { it.episode != null }
                .sortedWith(compareBy<Video>({ it.season ?: 0 }, { it.episode ?: 0 }))
            val index = sorted.indexOfFirst { it.episode == currentEpisode }
            return if (index < 0) null else sorted.getOrNull(index + 1)
        }

        val sortedEpisodes = videos
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy<Video> { it.season ?: Int.MAX_VALUE }.thenBy { it.episode ?: Int.MAX_VALUE })

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
        // A duration below the current position is not a valid end-of-video signal.
        if (durationMs > 0L && positionMs > durationMs + END_OF_VIDEO_EPSILON_MS) return false
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
        val scene = latestOutro.followingPostCreditsScene(skipIntervals, durationMs)
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

    /** True when a reading is clearly before the end and outside the next-episode window. */
    fun isAwayFromEnd(
        positionMs: Long,
        durationMs: Long,
        skipIntervals: List<SkipInterval>,
        thresholdMode: NextEpisodeThresholdMode,
        thresholdPercent: Float,
        thresholdMinutesBeforeEnd: Float
    ): Boolean =
        durationMs > 0L &&
            positionMs < durationMs - NEAR_END_MS &&
            !shouldShowNextEpisodeCard(
                positionMs = positionMs,
                durationMs = durationMs,
                skipIntervals = skipIntervals,
                thresholdMode = thresholdMode,
                thresholdPercent = thresholdPercent,
                thresholdMinutesBeforeEnd = thresholdMinutesBeforeEnd
            )

    fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
        return parseEpisodeReleaseLocalDate(raw)
    }

    fun hasEpisodeAired(raw: String?, clock: Clock = Clock.systemDefaultZone()): Boolean {
        return isEpisodeReleaseAired(raw, clock) ?: true
    }

    val OUTRO_SEGMENT_TYPES = setOf("outro", "ed", "mixed-ed")

    const val POST_OUTRO_AUTOPLAY_GAP_MS = 5_000L

    const val END_OF_VIDEO_EPSILON_MS = 1_000L

    /** How close to the duration MPV treats as the end of the file. */
    const val NEAR_END_MS = 500L

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
