package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.trakt.TraktConfig
import com.nuvio.app.features.trakt.TraktExternalIds
import com.nuvio.app.features.trakt.TraktImagesDto
import com.nuvio.app.features.trakt.firstTraktImageUrl
import com.nuvio.app.features.trakt.traktBestBackdropUrl
import com.nuvio.app.features.trakt.traktBestPosterUrl
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads a cast or crew page. TMDB comes first, like Nuvio. Builds without a TMDB key, users with
 * TMDB enrichment turned off, and cast members without a TMDB id fall back to Trakt's public people
 * pages, which only need the app's built-in Trakt key (no sign-in). Their titles carry IMDb ids, so
 * they open through Cinemeta and the user's add-ons like any other title.
 */
object PersonDetailLoader {
    private val log = Logger.withTag("PersonDetailLoader")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, PersonDetail>()

    suspend fun fetchPersonDetail(
        personId: Int,
        preferCrewCredits: Boolean? = null,
        personName: String = "",
    ): PersonDetail? {
        val tmdbDetail = if (personId > 0) {
            runCatching { TmdbMetadataService.fetchPersonDetail(personId = personId, preferCrewCredits = preferCrewCredits) }
                .getOrNull()
        } else {
            null
        }
        if (tmdbDetail != null && (tmdbDetail.movieCredits.isNotEmpty() || tmdbDetail.tvCredits.isNotEmpty())) {
            return tmdbDetail
        }
        return runCatching { fetchFromTrakt(personId, personName, preferCrewCredits) }
            .onFailure { log.w { "Trakt person lookup failed for $personName ($personId): ${it.message}" } }
            .getOrNull()
            ?: tmdbDetail
    }

    private suspend fun fetchFromTrakt(personId: Int, personName: String, preferCrewCredits: Boolean?): PersonDetail? {
        if (TraktConfig.CLIENT_ID.isBlank()) return null
        val cacheKey = "$personId:${personName.lowercase()}:${preferCrewCredits ?: "auto"}"
        cacheMutex.withLock { cache[cacheKey] }?.let { return it }

        val found = resolvePerson(personId, personName) ?: return null
        val pathId = found.ids?.slug?.takeIf { it.isNotBlank() } ?: found.ids?.trakt?.toString() ?: return null

        val detail = coroutineScope {
            val personDeferred = async { get<TraktPersonDto>("people/$pathId") }
            val moviesDeferred = async { get<TraktPersonCreditsDto>("people/$pathId/movies") }
            val showsDeferred = async { get<TraktPersonCreditsDto>("people/$pathId/shows") }
            val person = personDeferred.await() ?: found
            val movies = moviesDeferred.await()
            val shows = showsDeferred.await()

            val preferCrew = preferCrewCredits ?: isCrewDepartment(person.knownForDepartment)
            val castMovies = movies?.cast.orEmpty()
                .sortedByDescending { it.movie?.votes ?: 0 }
                .mapNotNull { it.movie?.toPreview("movie") }
            val crewMovies = movies?.crew.orEmpty().values.flatten()
                .sortedByDescending { it.movie?.votes ?: 0 }
                .mapNotNull { it.movie?.toPreview("movie") }
            val castShows = shows?.cast.orEmpty()
                .filterNot { SELF_APPEARANCE.matches((it.character ?: it.characters?.firstOrNull()).orEmpty().trim()) }
                .sortedByDescending { it.show?.votes ?: 0 }
                .mapNotNull { it.show?.toPreview("series") }
            val crewShows = shows?.crew.orEmpty().values.flatten()
                .sortedByDescending { it.show?.votes ?: 0 }
                .mapNotNull { it.show?.toPreview("series") }

            val movieCredits = pick(preferCrew, castMovies, crewMovies)
            val tvCredits = pick(preferCrew, castShows, crewShows)
            if (movieCredits.isEmpty() && tvCredits.isEmpty() && person.biography.isNullOrBlank()) {
                return@coroutineScope null
            }
            PersonDetail(
                tmdbId = person.ids?.tmdb ?: personId,
                name = person.name?.takeIf { it.isNotBlank() } ?: personName,
                biography = person.biography?.trim()?.takeIf { it.isNotBlank() },
                birthday = person.birthday?.takeIf { it.isNotBlank() },
                deathday = person.death?.takeIf { it.isNotBlank() },
                placeOfBirth = person.birthplace?.takeIf { it.isNotBlank() },
                profilePhoto = person.images?.headshot.firstTraktImageUrl() ?: found.images?.headshot.firstTraktImageUrl(),
                knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
                movieCredits = movieCredits,
                tvCredits = tvCredits,
            )
        } ?: return null
        cacheMutex.withLock { cache[cacheKey] = detail }
        return detail
    }

    private suspend fun resolvePerson(personId: Int, personName: String): TraktPersonDto? {
        if (personId > 0) {
            get<List<TraktPersonSearchResultDto>>("search/tmdb/$personId", mapOf("type" to "person"))
                ?.firstNotNullOfOrNull { it.person }
                ?.let { return it }
        }
        val name = personName.trim()
        if (name.isBlank()) return null
        val results = get<List<TraktPersonSearchResultDto>>("search/person", mapOf("query" to name, "limit" to "5"))
            .orEmpty()
            .mapNotNull { it.person }
        return results.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: results.firstOrNull()
    }

    private suspend inline fun <reified T> get(endpoint: String, query: Map<String, String> = emptyMap()): T? {
        val params = (query + ("extended" to "full,images")).entries.joinToString("&") { (k, v) ->
            "${k.encodeURLParameter()}=${v.encodeURLParameter()}"
        }
        val response = httpRequestRaw(
            method = "GET",
            url = "https://api.trakt.tv/$endpoint?$params",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "trakt-api-version" to "2",
                "trakt-api-key" to TraktConfig.CLIENT_ID,
            ),
            body = "",
            maxResponseBodyBytes = 8 * 1024 * 1024,
        )
        if (response.status !in 200..299) return null
        return runCatching { json.decodeFromString<T>(response.body) }.getOrNull()
    }

    private fun pick(preferCrew: Boolean, cast: List<MetaPreview>, crew: List<MetaPreview>): List<MetaPreview> {
        val chosen = when {
            preferCrew && crew.isNotEmpty() -> crew
            cast.isNotEmpty() -> cast
            else -> crew
        }
        return chosen.distinctBy { it.id }
    }

    private fun isCrewDepartment(department: String?): Boolean {
        val d = department?.trim()?.lowercase().orEmpty()
        return d.isNotBlank() && d != "acting" && d != "actors"
    }
}

// Talk show and award show guest spots, which TMDB-based pages also leave out.
private val SELF_APPEARANCE = Regex("(?i)^(self|himself|herself|themselves|themself)\\b.*")

private fun TraktCreditTitleDto.toPreview(type: String): MetaPreview? {
    val name = (title ?: originalTitle)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val imdb = ids?.imdb?.takeIf { it.startsWith("tt") }
    val id = imdb ?: ids?.tmdb?.let { "tmdb:$it" } ?: return null
    val poster = images.traktBestPosterUrl()
        ?: imdb?.let { "https://images.metahub.space/poster/medium/$it/img" }
        ?: return null
    return MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = images.traktBestBackdropUrl(),
        posterShape = PosterShape.Poster,
        description = overview?.trim()?.takeIf { it.isNotBlank() },
        releaseInfo = year?.toString(),
        imdbRating = rating?.takeIf { it > 0 }?.let { ((it * 10).toInt() / 10.0).toString() },
        genres = genres.orEmpty(),
    )
}

@Serializable
private data class TraktPersonImagesDto(
    val headshot: List<String>? = null,
)

@Serializable
private data class TraktPersonDto(
    val name: String? = null,
    val ids: TraktExternalIds? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val death: String? = null,
    val birthplace: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    val images: TraktPersonImagesDto? = null,
)

@Serializable
private data class TraktPersonSearchResultDto(
    val type: String? = null,
    val person: TraktPersonDto? = null,
)

@Serializable
private data class TraktCreditTitleDto(
    val title: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    val year: Int? = null,
    val ids: TraktExternalIds? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val votes: Int? = null,
    val genres: List<String>? = null,
    val images: TraktImagesDto? = null,
)

@Serializable
private data class TraktPersonCastDto(
    val character: String? = null,
    val characters: List<String>? = null,
    val movie: TraktCreditTitleDto? = null,
    val show: TraktCreditTitleDto? = null,
)

@Serializable
private data class TraktPersonCrewDto(
    val job: String? = null,
    val jobs: List<String>? = null,
    val movie: TraktCreditTitleDto? = null,
    val show: TraktCreditTitleDto? = null,
)

@Serializable
private data class TraktPersonCreditsDto(
    val cast: List<TraktPersonCastDto>? = null,
    val crew: Map<String, List<TraktPersonCrewDto>>? = null,
)
