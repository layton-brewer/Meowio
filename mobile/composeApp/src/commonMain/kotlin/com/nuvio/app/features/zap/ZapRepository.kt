package com.nuvio.app.features.zap

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Why [ZapPool.next] returned nothing, so the screen can say something useful. */
enum class ZapEmpty { NoKey, NoMatches, Offline }

sealed interface ZapResult {
    data class Found(val title: ZapTitle) : ZapResult
    data class Empty(val reason: ZapEmpty) : ZapResult
}

private data class ZapCandidate(val tmdbId: Int, val isSeries: Boolean) {
    val key: String get() = "${if (isSeries) "tv" else "movie"}:$tmdbId"
}

/**
 * A shuffled supply of random titles for one set of [ZapFilters]. Create a new one whenever the
 * filters change. It pulls random pages from TMDB discover (or from the chosen actors' credits),
 * hands out one title at a time and never repeats a title within its own lifetime.
 */
class ZapPool(private val filters: ZapFilters) {
    private val log = Logger.withTag("ZapPool")
    private val queue = ArrayDeque<ZapCandidate>()
    private val seen = HashSet<String>()
    private val totalPages = HashMap<Boolean, Int>()
    private val triedPages = HashMap<Boolean, MutableSet<Int>>()
    private var creditsLoaded = false
    private var failures = 0

    suspend fun next(): ZapResult {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        if (apiKey.isBlank()) return ZapResult.Empty(ZapEmpty.NoKey)
        var attempts = 0
        while (attempts < 40) {
            if (queue.isEmpty()) {
                refill(apiKey)
                if (queue.isEmpty()) {
                    return ZapResult.Empty(if (failures > 0) ZapEmpty.Offline else ZapEmpty.NoMatches)
                }
            }
            val candidate = queue.removeFirst()
            if (!seen.add(candidate.key)) continue
            attempts++
            val title = loadTitle(candidate, apiKey) ?: continue
            if (filters.minRating > 0f && (title.rating ?: 0.0) < filters.minRating) continue
            if (filters.hideWatched && isWatched(title)) continue
            return ZapResult.Found(title)
        }
        return ZapResult.Empty(if (failures > 0) ZapEmpty.Offline else ZapEmpty.NoMatches)
    }

    private fun isWatched(title: ZapTitle): Boolean = runCatching {
        WatchedRepository.isWatched(id = title.imdbId, type = title.type) ||
            (title.isSeries && WatchedRepository.isFullyWatchedSeries(title.imdbId, title.type))
    }.getOrDefault(false)

    private fun activeTypes(): List<Boolean> {
        val wanted = when (filters.type) {
            ZapMediaType.Both -> listOf(false, true)
            ZapMediaType.Movies -> listOf(false)
            ZapMediaType.Series -> listOf(true)
        }
        val chosen = zapGenres.filter { it.name in filters.genres }
        // Genres are an AND filter, so a type that has no id for one of them can never match.
        return wanted.filter { isSeries ->
            chosen.all { (if (isSeries) it.tvId else it.movieId) != null }
        }
    }

    private suspend fun refill(apiKey: String) {
        val added = ArrayList<ZapCandidate>()
        if (filters.people.isNotEmpty()) {
            if (!creditsLoaded) {
                creditsLoaded = true
                added += loadCredits(apiKey)
            }
        } else {
            for (isSeries in activeTypes()) {
                added += discoverPage(isSeries, apiKey)
            }
        }
        added.shuffle()
        added.filter { it.key !in seen }.forEach(queue::addLast)
    }

    private suspend fun discoverPage(isSeries: Boolean, apiKey: String): List<ZapCandidate> {
        val tried = triedPages.getOrPut(isSeries) { mutableSetOf() }
        val known = totalPages[isSeries]
        val page = if (known == null) 1 else {
            val limit = minOf(known, 25)
            val free = (1..limit).filter { it !in tried }
            if (free.isEmpty()) return emptyList()
            free[Random.nextInt(free.size)]
        }
        tried += page
        val body = fetch<DiscoverResponse>(
            endpoint = if (isSeries) "discover/tv" else "discover/movie",
            apiKey = apiKey,
            query = discoverQuery(isSeries, page),
        ) ?: return emptyList()
        totalPages[isSeries] = body.totalPages.coerceIn(1, 500)
        return body.results.map { ZapCandidate(it.id, isSeries) }
    }

    private fun discoverQuery(isSeries: Boolean, page: Int): Map<String, String> {
        val chosen = zapGenres.filter { it.name in filters.genres }
        val ids = chosen.mapNotNull { if (isSeries) it.tvId else it.movieId }.distinct()
        val dateKey = if (isSeries) "first_air_date" else "primary_release_date"
        return buildMap {
            put("sort_by", "popularity.desc")
            put("include_adult", "false")
            put("page", page.toString())
            put("vote_count.gte", if (filters.yearTo != null && filters.yearTo < 2000) "80" else "150")
            if (filters.minRating > 0f) put("vote_average.gte", filters.minRating.toString())
            if (ids.isNotEmpty()) put("with_genres", ids.joinToString(","))
            filters.yearFrom?.let { put("$dateKey.gte", "$it-01-01") }
            filters.yearTo?.let { put("$dateKey.lte", "$it-12-31") }
            val language = TmdbSettingsRepository.snapshot().language
            if (language.isNotBlank()) put("language", language)
        }
    }

    private suspend fun loadCredits(apiKey: String): List<ZapCandidate> {
        val types = activeTypes()
        val chosen = zapGenres.filter { it.name in filters.genres }
        val out = LinkedHashMap<String, ZapCandidate>()
        for (person in filters.people) {
            val body = fetch<CreditsResponse>(
                endpoint = "person/${person.id}/combined_credits",
                apiKey = apiKey,
            ) ?: continue
            for (item in body.cast) {
                val isSeries = when (item.mediaType) {
                    "tv" -> true
                    "movie" -> false
                    else -> continue
                }
                if (isSeries !in types) continue
                val genreIds = item.genreIds.toSet()
                if (chosen.isNotEmpty() && chosen.any { g ->
                        val id = if (isSeries) g.tvId else g.movieId
                        id == null || id !in genreIds
                    }
                ) continue
                val year = (item.releaseDate ?: item.firstAirDate)?.take(4)?.toIntOrNull()
                if (filters.yearFrom != null && (year == null || year < filters.yearFrom)) continue
                if (filters.yearTo != null && (year == null || year > filters.yearTo)) continue
                if (filters.minRating > 0f && item.voteAverage < filters.minRating) continue
                if (item.voteCount < 20) continue
                val candidate = ZapCandidate(item.id, isSeries)
                out[candidate.key] = candidate
            }
        }
        return out.values.toList()
    }

    private suspend fun loadTitle(candidate: ZapCandidate, apiKey: String): ZapTitle? {
        val body = fetch<DetailResponse>(
            endpoint = "${if (candidate.isSeries) "tv" else "movie"}/${candidate.tmdbId}",
            apiKey = apiKey,
            query = buildMap {
                put("append_to_response", "external_ids,credits")
                val language = TmdbSettingsRepository.snapshot().language
                if (language.isNotBlank()) put("language", language)
            },
        ) ?: return null
        val imdb = (body.imdbId ?: body.externalIds?.imdbId)?.takeIf { it.startsWith("tt") } ?: return null
        val name = (body.title ?: body.name)?.takeIf { it.isNotBlank() } ?: return null
        val date = body.releaseDate ?: body.firstAirDate
        return ZapTitle(
            tmdbId = candidate.tmdbId,
            isSeries = candidate.isSeries,
            imdbId = imdb,
            name = name,
            year = date?.take(4)?.takeIf { it.length == 4 },
            overview = body.overview.orEmpty(),
            poster = body.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdrop = (body.backdropPath ?: body.posterPath)?.let { "https://image.tmdb.org/t/p/w1280$it" },
            rating = body.voteAverage.takeIf { it > 0.0 },
            genres = body.genres.map { it.name },
            runtimeMinutes = body.runtime?.takeIf { it > 0 } ?: body.episodeRunTime.firstOrNull()?.takeIf { it > 0 },
            seasons = body.numberOfSeasons,
            cast = body.credits?.cast.orEmpty().take(6).map { it.name },
        )
    }

    private suspend inline fun <reified T> fetch(
        endpoint: String,
        apiKey: String,
        query: Map<String, String> = emptyMap(),
    ): T? {
        val url = zapUrl(endpoint, apiKey, query)
        return runCatching { zapJson.decodeFromString<T>(httpGetText(url)) }
            .onFailure { error ->
                failures++
                log.w { "Zap request failed for $endpoint: ${error.message}" }
            }
            .getOrNull()
    }
}

object ZapSearch {
    private val log = Logger.withTag("ZapSearch")

    /** Looks up actors by name for the filter panel. */
    suspend fun people(query: String): List<ZapPerson> {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        if (apiKey.isBlank() || query.isBlank()) return emptyList()
        val url = zapUrl("search/person", apiKey, mapOf("query" to query.trim(), "include_adult" to "false"))
        return runCatching { zapJson.decodeFromString<PersonSearchResponse>(httpGetText(url)) }
            .onFailure { log.w { "Actor search failed: ${it.message}" } }
            .getOrNull()
            ?.results
            ?.filter { it.knownForDepartment == null || it.knownForDepartment == "Acting" }
            ?.take(8)
            ?.map { ZapPerson(it.id, it.name) }
            .orEmpty()
    }
}

/** The filters the user last chose; kept for the life of the app so reopening Zap feels the same. */
object ZapFilterMemory {
    var filters: ZapFilters = ZapFilters()
}

private val zapJson = Json { ignoreUnknownKeys = true }

private fun zapUrl(endpoint: String, apiKey: String, query: Map<String, String>): String = buildString {
    append("https://api.themoviedb.org/3/")
    append(endpoint)
    append("?api_key=").append(percentEncode(apiKey))
    query.forEach { (key, value) ->
        if (value.isNotBlank()) append('&').append(key).append('=').append(percentEncode(value))
    }
}

private fun percentEncode(value: String): String {
    val unreserved = "-_.~"
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 128 && (ch.isLetterOrDigit() || ch in unreserved)) {
            out.append(ch)
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[c shr 4])
            out.append("0123456789ABCDEF"[c and 0xF])
        }
    }
    return out.toString()
}

@Serializable
private data class DiscoverResponse(
    val results: List<DiscoverItem> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
private data class DiscoverItem(val id: Int)

@Serializable
private data class CreditsResponse(val cast: List<CreditItem> = emptyList())

@Serializable
private data class CreditItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)

@Serializable
private data class GenreItem(val id: Int = 0, val name: String = "")

@Serializable
private data class CastItem(val name: String = "")

@Serializable
private data class CreditsBlock(val cast: List<CastItem> = emptyList())

@Serializable
private data class ExternalIds(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
private data class DetailResponse(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val genres: List<GenreItem> = emptyList(),
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("external_ids") val externalIds: ExternalIds? = null,
    val credits: CreditsBlock? = null,
)

@Serializable
private data class PersonSearchResponse(val results: List<PersonItem> = emptyList())

@Serializable
private data class PersonItem(
    val id: Int,
    val name: String = "",
    @SerialName("known_for_department") val knownForDepartment: String? = null,
)
