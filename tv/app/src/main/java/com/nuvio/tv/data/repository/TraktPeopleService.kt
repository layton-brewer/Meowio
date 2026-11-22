package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.trakt.firstTraktImageUrl
import com.nuvio.tv.core.trakt.traktBestBackdropUrl
import com.nuvio.tv.core.trakt.traktBestPosterUrl
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPersonDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PersonDetail
import com.nuvio.tv.domain.model.PosterShape
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val TAG = "TraktPeopleService"
// Talk show and award show guest spots, which TMDB-based pages also leave out.
private val SELF_APPEARANCE = Regex("(?i)^(self|himself|herself|themselves|themself)\\b.*")

/**
 * Cast and crew pages without TMDB. Trakt's people endpoints are public, so the app's built-in
 * Trakt key is enough: the user does not need to be signed in, and no TMDB key is needed.
 * Titles carry IMDb ids, so they open through Cinemeta and the user's add-ons like any other title.
 */
@Singleton
class TraktPeopleService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    private val cache = ConcurrentHashMap<String, PersonDetail>()

    fun isAvailable(): Boolean = traktAuthService.hasRequiredCredentials()

    suspend fun fetchPersonDetail(
        tmdbPersonId: Int,
        personName: String,
        preferCrewCredits: Boolean?
    ): PersonDetail? {
        if (!isAvailable()) return null
        val cacheKey = "$tmdbPersonId:${personName.lowercase()}:${preferCrewCredits ?: "auto"}"
        cache[cacheKey]?.let { return it }
        return try {
            val found = resolvePerson(tmdbPersonId, personName) ?: return null
            val pathId = found.ids?.slug?.takeIf { it.isNotBlank() }
                ?: found.ids?.trakt?.toString()
                ?: return null
            coroutineScope {
                val personDeferred = async {
                    runCatching { traktAuthService.executePublicRequest { traktApi.getPerson(pathId) }?.body() }.getOrNull()
                }
                val moviesDeferred = async {
                    runCatching { traktAuthService.executePublicRequest { traktApi.getPersonMovies(pathId) }?.body() }.getOrNull()
                }
                val showsDeferred = async {
                    runCatching { traktAuthService.executePublicRequest { traktApi.getPersonShows(pathId) }?.body() }.getOrNull()
                }
                val person = personDeferred.await() ?: found
                val movies = moviesDeferred.await()
                val shows = showsDeferred.await()

                val preferCrew = preferCrewCredits ?: isCrewDepartment(person.knownForDepartment)
                val castMovies = movies?.cast.orEmpty().sortedByDescending { it.movie?.votes ?: 0 }.mapNotNull { it.movie?.toPreview(ContentType.MOVIE) }
                val crewMovies = movies?.crew.orEmpty().values.flatten().sortedByDescending { it.movie?.votes ?: 0 }.mapNotNull { it.movie?.toPreview(ContentType.MOVIE) }
                val castShows = shows?.cast.orEmpty()
                    .filterNot { credit -> SELF_APPEARANCE.matches((credit.character ?: credit.characters?.firstOrNull()).orEmpty().trim()) }
                    .sortedByDescending { it.show?.votes ?: 0 }
                    .mapNotNull { it.show?.toPreview(ContentType.SERIES) }
                val crewShows = shows?.crew.orEmpty().values.flatten().sortedByDescending { it.show?.votes ?: 0 }.mapNotNull { it.show?.toPreview(ContentType.SERIES) }

                val movieCredits = pick(preferCrew, castMovies, crewMovies)
                val tvCredits = pick(preferCrew, castShows, crewShows)
                if (movieCredits.isEmpty() && tvCredits.isEmpty() && person.biography.isNullOrBlank()) {
                    return@coroutineScope null
                }
                PersonDetail(
                    tmdbId = person.ids?.tmdb ?: tmdbPersonId,
                    name = person.name?.takeIf { it.isNotBlank() } ?: personName,
                    biography = person.biography?.trim()?.takeIf { it.isNotBlank() },
                    birthday = person.birthday?.takeIf { it.isNotBlank() },
                    deathday = person.death?.takeIf { it.isNotBlank() },
                    placeOfBirth = person.birthplace?.takeIf { it.isNotBlank() },
                    profilePhoto = person.images?.headshot.firstTraktImageUrl()
                        ?: found.images?.headshot.firstTraktImageUrl(),
                    knownFor = person.knownForDepartment?.takeIf { it.isNotBlank() }
                        ?.replaceFirstChar { it.uppercase() },
                    movieCredits = movieCredits,
                    tvCredits = tvCredits
                ).also { cache[cacheKey] = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Trakt person lookup failed for $personName ($tmdbPersonId): ${e.message}")
            null
        }
    }

    private suspend fun resolvePerson(tmdbPersonId: Int, personName: String): TraktPersonDto? {
        if (tmdbPersonId > 0) {
            val byTmdb = runCatching {
                traktAuthService.executePublicRequest { traktApi.searchPersonByTmdbId(tmdbPersonId.toString()) }?.body()
            }.getOrNull().orEmpty().firstNotNullOfOrNull { it.person }
            if (byTmdb != null) return byTmdb
        }
        val name = personName.trim()
        if (name.isBlank()) return null
        val results = runCatching {
            traktAuthService.executePublicRequest { traktApi.searchPeople(name) }?.body()
        }.getOrNull().orEmpty().mapNotNull { it.person }
        return results.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: results.firstOrNull()
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

private fun com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto.toPreview(type: ContentType): MetaPreview? =
    traktCreditPreview(title ?: originalTitle, year, ids, overview, rating, genres, images, type)

private fun com.nuvio.tv.data.remote.dto.trakt.TraktShowDto.toPreview(type: ContentType): MetaPreview? =
    traktCreditPreview(title ?: originalTitle, year, ids, overview, rating, genres, images, type)

private fun traktCreditPreview(
    title: String?,
    year: Int?,
    ids: TraktIdsDto?,
    overview: String?,
    rating: Double?,
    genres: List<String>?,
    images: TraktImagesDto?,
    type: ContentType
): MetaPreview? {
    val name = title?.trim()?.takeIf { it.isNotBlank() } ?: return null
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
        posterShape = PosterShape.POSTER,
        background = images.traktBestBackdropUrl(),
        logo = null,
        description = overview?.trim()?.takeIf { it.isNotBlank() },
        releaseInfo = year?.toString(),
        imdbRating = rating?.takeIf { it > 0 }?.toFloat(),
        genres = genres.orEmpty(),
        imdbId = imdb
    )
}
