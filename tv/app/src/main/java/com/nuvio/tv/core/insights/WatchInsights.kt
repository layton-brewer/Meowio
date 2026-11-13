package com.nuvio.tv.core.insights

/**
 * Watch statistics for one profile, worked out from what is already stored on the device. The
 * phone and TV apps keep identical copies of this file, so both show the same numbers.
 */
data class InsightPlay(
    /** The movie or show. */
    val contentId: String,
    /** The movie, or the single episode. */
    val itemKey: String,
    val title: String,
    val isEpisode: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val atMs: Long,
)

data class InsightWatched(
    val contentId: String,
    val itemKey: String,
    val title: String,
    val isEpisode: Boolean,
    val atMs: Long,
)

data class InsightTopShow(val title: String, val episodes: Int)

data class InsightGenre(val name: String, val titles: Int)

data class WatchInsightsSummary(
    /** Time actually played, from saved positions. An estimate: marking as watched adds nothing. */
    val watchTimeMs: Long = 0,
    val moviesWatched: Int = 0,
    val episodesWatched: Int = 0,
    val showsWatched: Int = 0,
    val inProgress: Int = 0,
    val activeDaysLast30: Int = 0,
    /** Days in a row with something watched, counting today or yesterday as the latest. */
    val streakDays: Int = 0,
    /** 0 is Monday. Null when nothing has been watched. */
    val busiestWeekday: Int? = null,
    val topShows: List<InsightTopShow> = emptyList(),
    val libraryTitles: Int = 0,
    /** From saved library titles only, since watch history does not record genres. */
    val topGenres: List<InsightGenre> = emptyList(),
) {
    val isEmpty: Boolean
        get() = watchTimeMs == 0L && moviesWatched == 0 && episodesWatched == 0 && libraryTitles == 0
}

object WatchInsights {
    private const val DAY_MS = 86_400_000L
    private const val TOP_COUNT = 5

    fun compute(
        plays: List<InsightPlay>,
        watched: List<InsightWatched>,
        libraryGenres: List<List<String>>,
        nowMs: Long,
        utcOffsetMs: Long,
    ): WatchInsightsSummary {
        // The same item can be stored more than once; keep its newest record.
        val latestPlays = plays.groupBy(InsightPlay::itemKey).map { (_, rows) -> rows.maxBy(InsightPlay::atMs) }
        val watchTimeMs = latestPlays.sumOf { play ->
            val duration = play.durationMs.coerceAtLeast(0)
            val position = play.positionMs.coerceAtLeast(0)
            when {
                play.completed && duration > 0 -> duration
                duration > 0 -> minOf(position, duration)
                else -> position
            }
        }

        val finished = HashMap<String, InsightWatched>()
        watched.forEach { finished[it.itemKey] = it }
        latestPlays.filter(InsightPlay::completed).forEach { play ->
            if (play.itemKey !in finished) {
                finished[play.itemKey] = InsightWatched(play.contentId, play.itemKey, play.title, play.isEpisode, play.atMs)
            }
        }
        val episodes = finished.values.filter(InsightWatched::isEpisode)
        val topShows = episodes.groupBy(InsightWatched::contentId)
            .map { (_, rows) -> InsightTopShow(rows.first().title, rows.size) }
            .sortedWith(compareByDescending(InsightTopShow::episodes).thenBy(InsightTopShow::title))
            .take(TOP_COUNT)

        val today = dayIndex(nowMs, utcOffsetMs)
        val activeDays = (latestPlays.map(InsightPlay::atMs) + finished.values.map(InsightWatched::atMs))
            .filter { it > 0 }
            .map { dayIndex(it, utcOffsetMs) }
            .filter { it <= today }
            .toSet()
        var streak = 0
        var day = if (today in activeDays) today else today - 1
        while (day in activeDays) {
            streak++
            day--
        }
        val busiestWeekday = activeDays.groupingBy { weekday(it) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

        val topGenres = libraryGenres.flatMap { genres -> genres.map(String::trim).filter(String::isNotEmpty).distinct() }
            .groupingBy { it }.eachCount()
            .map { (name, count) -> InsightGenre(name, count) }
            .sortedWith(compareByDescending(InsightGenre::titles).thenBy(InsightGenre::name))
            .take(TOP_COUNT)

        return WatchInsightsSummary(
            watchTimeMs = watchTimeMs,
            moviesWatched = finished.values.count { !it.isEpisode },
            episodesWatched = episodes.size,
            showsWatched = episodes.map(InsightWatched::contentId).distinct().size,
            inProgress = latestPlays.filter { !it.completed && it.positionMs > 0 }.map(InsightPlay::contentId).distinct().size,
            activeDaysLast30 = activeDays.count { it > today - 30 },
            streakDays = streak,
            busiestWeekday = busiestWeekday,
            topShows = topShows,
            libraryTitles = libraryGenres.size,
            topGenres = topGenres,
        )
    }

    /** Whole hours and minutes of [ms], for showing a watch time. */
    fun hoursAndMinutes(ms: Long): Pair<Long, Long> {
        val minutes = ms.coerceAtLeast(0) / 60_000L
        return minutes / 60 to minutes % 60
    }

    private fun dayIndex(atMs: Long, utcOffsetMs: Long): Long = floorDiv(atMs + utcOffsetMs, DAY_MS)

    // 1970-01-01 was a Thursday.
    private fun weekday(dayIndex: Long): Int = (((dayIndex + 3) % 7 + 7) % 7).toInt()

    private fun floorDiv(value: Long, by: Long): Long {
        val quotient = value / by
        return if (value % by != 0L && (value < 0) != (by < 0)) quotient - 1 else quotient
    }
}
