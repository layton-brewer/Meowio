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
        // Outro times are crowd-sourced (IntroDB) for one release of the episode. An outro that starts
        // at or after this file's end belongs to a longer cut, so it must not hold the card back.
        val outroSegments = skipIntervals.filter {
            it.type in OUTRO_SEGMENT_TYPES && (durationMs <= 0L || it.startTime * 1_000.0 < durationMs)
        }

        if (outroSegments.isNotEmpty()) {
            if (durationMs <= 0L) return false

            // Use the same post-credits detection as the skip button so the
            // next-episode card never appears over a post-credits scene.
            val latestOutro = outroSegments.maxByOrNull { it.endTime }
            val postCreditsScene = latestOutro?.findFollowingPostCreditsScene(skipIntervals, durationMs)

            if (postCreditsScene != null) {
                val sceneEndMs = (postCreditsScene.endTime * 1_000.0).toLong()
                    .coerceAtMost(durationMs)
                val userTriggerMs = userThresholdPositionMs(
                    durationMs, thresholdMode, thresholdPercent, thresholdMinutesBeforeEnd
                )
                val isHeuristic = postCreditsScene.provider == "heuristic"
                val sceneDurationMs = sceneEndMs - (postCreditsScene.startTime * 1_000.0).toLong()
                val userThresholdFromEndMs = durationMs - userTriggerMs
                // A guessed "scene" shorter than the user's window is just the few seconds of logos after
                // the credits, not a real post-credits scene: handle it like credits that run to the end
                // (below). It used to return false here, so the Next Episode card never appeared at all
                // for those episodes.
                val shortGuessedScene = isHeuristic && sceneDurationMs < userThresholdFromEndMs
                if (!shortGuessedScene) {
                    return if (isHeuristic) {
                        positionMs >= userTriggerMs
                    } else {
                        positionMs >= maxOf(sceneEndMs, userTriggerMs)
                    }
                }
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

            return if (postOutroGapMs > userThresholdMs) {
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
