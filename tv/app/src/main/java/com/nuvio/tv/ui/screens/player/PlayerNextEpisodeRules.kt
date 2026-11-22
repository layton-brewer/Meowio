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
        thresholdMinutesBeforeEnd: Float
    ): Boolean {
        // A duration below the current position is not a valid end-of-video signal.
        if (durationMs > 0L && positionMs > durationMs + END_OF_VIDEO_EPSILON_MS) return false

        // Outro times are crowd-sourced (IntroDB) for one release of the episode. An outro that starts
        // at or after this file's end belongs to a longer cut, so it must not hold the card back.
        val outroSegments = skipIntervals.filter {
            it.type in OUTRO_SEGMENT_TYPES && (durationMs <= 0L || it.startTime * 1_000.0 < durationMs)
        }

        if (outroSegments.isNotEmpty()) {
            if (durationMs <= 0L) return false

            // Use the same post-credits detection as the skip-credits button so
            // the next-episode card never appears over a post-credits scene.
            val latestOutro = outroSegments.maxByOrNull { it.endTime }
            val postCreditsScene = latestOutro?.followingPostCreditsScene(skipIntervals, durationMs)

            var guessedScene = false
            if (postCreditsScene != null) {
                val sceneEndMs = (postCreditsScene.endTime * 1_000.0).toLong()
                    .coerceAtMost(durationMs)
                val userTriggerMs = userThresholdPositionMs(
                    durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
                )
                // Only a real post-credits scene (marked by the data provider) holds the card back. A
                // guessed one is just the gap between the credits' end and the file's end, so the card
                // shows when the credits begin, like upstream Nuvio does for outro times.
                if (postCreditsScene.provider != "heuristic") {
                    return positionMs >= maxOf(sceneEndMs, userTriggerMs)
                }
                guessedScene = true
            }

            val latestOutroEndMs = (outroSegments.maxOf { it.endTime } * 1_000.0).toLong()
            val postOutroGapMs = durationMs - latestOutroEndMs

            // Calculate the user's configured threshold as milliseconds from end.
            val userThresholdMs = when (thresholdMode) {
                NextEpisodeThresholdMode.PERCENTAGE -> {
                    val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                    ((1.0 - clampedPercent / 100.0) * durationMs).toLong()
                }
                NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                    val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                    (clampedMinutes * 60_000f).toLong()
                }
            }

            return if (!guessedScene && postOutroGapMs > userThresholdMs) {
                when (thresholdMode) {
                    NextEpisodeThresholdMode.PERCENTAGE -> {
                        val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                        (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
                    }
                    NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                        val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                        val remainingMs = durationMs - positionMs
                        remainingMs <= (clampedMinutes * 60_000f).toLong()
                    }
                }
            } else {
                // Outro ends close to the file end: fire at the earliest outro start, and at the user's
                // threshold at the latest, so outro times from another release can't skip the card.
                val outroStartMs = (outroSegments.minOf { it.startTime } * 1_000.0).toLong()
                positionMs >= minOf(outroStartMs, durationMs - userThresholdMs)
            }
        }

        // Fallback to the settings threshold when no outro data exists.
        if (durationMs <= 0L) return false
        return when (thresholdMode) {
            NextEpisodeThresholdMode.PERCENTAGE -> {
                val clampedPercent = thresholdPercent.coerceIn(97f, 100f)
                (positionMs.toDouble() / durationMs.toDouble()) >= (clampedPercent / 100.0)
            }
            NextEpisodeThresholdMode.MINUTES_BEFORE_END -> {
                val clampedMinutes = thresholdMinutesBeforeEnd.coerceIn(0f, 3.5f)
                val remainingMs = durationMs - positionMs
                remainingMs <= (clampedMinutes * 60_000f).toLong()
            }
        }
    }

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
