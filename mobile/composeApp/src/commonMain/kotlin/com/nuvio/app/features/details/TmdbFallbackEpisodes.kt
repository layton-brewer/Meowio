package com.nuvio.app.features.details

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Episode list for a show whose page had to be built from TMDB (every add-on failed). Without it the
 * show would play like a movie and save progress without a season and episode. Ids follow the
 * add-on style "<id>:<season>:<episode>" so stream add-ons and progress work as usual.
 */
internal object TmdbFallbackEpisodes {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(tmdbId: Int, idBase: String, language: String): List<MetaVideo> {
        val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank) ?: return emptyList()
        val lang = language.trim().ifBlank { "en-US" }
        val show = get<TmdbShow>(buildTmdbUrl("tv/$tmdbId", apiKey, mapOf("language" to lang))) ?: return emptyList()
        val seasons = show.seasons.mapNotNull { it.seasonNumber }.filter { it > 0 }.distinct().sorted()
            .ifEmpty { (1..(show.numberOfSeasons ?: 0).coerceIn(0, 60)).toList() }
        if (seasons.isEmpty()) return emptyList()
        val gate = Semaphore(4)
        val bySeason = coroutineScope {
            seasons.map { season ->
                async {
                    gate.withPermit {
                        get<TmdbSeason>(buildTmdbUrl("tv/$tmdbId/season/$season", apiKey, mapOf("language" to lang)))
                    }?.let { season to it }
                }
            }.awaitAll().filterNotNull()
        }
        return bySeason.flatMap { (season, details) ->
            details.episodes.mapNotNull { episode ->
                val number = episode.episodeNumber ?: return@mapNotNull null
                MetaVideo(
                    id = "$idBase:$season:$number",
                    title = episode.name?.trim()?.takeIf(String::isNotBlank) ?: "Episode $number",
                    released = episode.airDate?.trim()?.takeIf(String::isNotBlank),
                    thumbnail = imageUrl(episode.stillPath, "w500"),
                    seasonPoster = imageUrl(details.posterPath, "w500"),
                    season = season,
                    episode = number,
                    overview = episode.overview?.trim()?.takeIf(String::isNotBlank),
                    runtime = episode.runtime,
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private suspend inline fun <reified T> get(url: String): T? =
        try {
            json.decodeFromString<T>(httpGetText(url))
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            null
        }

    private fun imageUrl(path: String?, size: String): String? =
        path?.trim()?.takeIf(String::isNotBlank)?.let { "https://image.tmdb.org/t/p/$size$it" }

    @Serializable
    private data class TmdbShow(
        @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
        val seasons: List<TmdbSeasonRef> = emptyList(),
    )

    @Serializable
    private data class TmdbSeasonRef(
        @SerialName("season_number") val seasonNumber: Int? = null,
    )

    @Serializable
    private data class TmdbSeason(
        @SerialName("poster_path") val posterPath: String? = null,
        val episodes: List<TmdbEpisode> = emptyList(),
    )

    @Serializable
    private data class TmdbEpisode(
        @SerialName("episode_number") val episodeNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("still_path") val stillPath: String? = null,
        @SerialName("air_date") val airDate: String? = null,
        val runtime: Int? = null,
    )
}
