package com.nuvio.app.features.zap

/** What the Zap pool may contain. */
enum class ZapMediaType { Both, Movies, Series }

/** A genre shown in the filter list, with its TMDB id for movies and for series (they differ). */
data class ZapGenre(val name: String, val movieId: Int?, val tvId: Int?)

data class ZapPerson(val id: Int, val name: String)

/**
 * Everything the user can narrow the Zap pool by. All fields default to "anything".
 * Actors are matched as "any of the chosen actors".
 */
data class ZapFilters(
    val type: ZapMediaType = ZapMediaType.Both,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val genres: Set<String> = emptySet(),
    val people: List<ZapPerson> = emptyList(),
    val minRating: Float = 0f,
    val hideWatched: Boolean = true,
) {
    val activeCount: Int
        get() = listOf(
            type != ZapMediaType.Both,
            yearFrom != null || yearTo != null,
            genres.isNotEmpty(),
            people.isNotEmpty(),
            minRating > 0f,
        ).count { it }
}

/** One title ready to show, taken from an add-on catalog. [id] is the add-on's id, which the title page opens by. */
data class ZapTitle(
    val sourceName: String,
    val id: String,
    val isSeries: Boolean,
    val name: String,
    val year: String?,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val seasons: Int?,
    val cast: List<String>,
) {
    val type: String get() = if (isSeries) "series" else "movie"
    val key: String get() = "$type:$id"
}

internal val zapGenres: List<ZapGenre> = listOf(
    ZapGenre("Action", 28, 10759),
    ZapGenre("Adventure", 12, 10759),
    ZapGenre("Animation", 16, 16),
    ZapGenre("Comedy", 35, 35),
    ZapGenre("Crime", 80, 80),
    ZapGenre("Documentary", 99, 99),
    ZapGenre("Drama", 18, 18),
    ZapGenre("Family", 10751, 10751),
    ZapGenre("Fantasy", 14, 10765),
    ZapGenre("History", 36, null),
    ZapGenre("Horror", 27, null),
    ZapGenre("Kids", null, 10762),
    ZapGenre("Music", 10402, null),
    ZapGenre("Mystery", 9648, 9648),
    ZapGenre("Reality", null, 10764),
    ZapGenre("Romance", 10749, null),
    ZapGenre("Sci-Fi", 878, 10765),
    ZapGenre("Thriller", 53, null),
    ZapGenre("War", 10752, 10768),
    ZapGenre("Western", 37, 37),
)

/** Decades offered as quick year filters: label to (from, to). */
internal val zapDecades: List<Pair<String, Pair<Int?, Int?>>> = listOf(
    "Any year" to (null to null),
    "2020s" to (2020 to null),
    "2010s" to (2010 to 2019),
    "2000s" to (2000 to 2009),
    "1990s" to (1990 to 1999),
    "1980s" to (1980 to 1989),
    "1970s" to (1970 to 1979),
    "Older" to (null to 1969),
)
