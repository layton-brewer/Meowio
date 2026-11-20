package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val imdbTapframeApi: ImdbTapframeApi,
    private val seriesGraphApi: SeriesGraphApi
) {
    private data class CacheEntry(
        val ratings: Map<Pair<Int, Int>, Double>,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Map<Pair<Int, Int>, Double>>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?
    ): Map<Pair<Int, Int>, Double> {
        val normalizedImdbId = imdbId
            ?.trim()
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?.substringBefore(':')
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 }
        if (normalizedImdbId == null && normalizedTmdbId == null) return emptyMap()

        val cacheKey = when {
            !normalizedImdbId.isNullOrBlank() -> "imdb:$normalizedImdbId"
            else -> "tmdb:$normalizedTmdbId"
        }

        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.ratings
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchEpisodeRatings(
                        imdbId = normalizedImdbId,
                        tmdbId = normalizedTmdbId
                    ).also { result ->
                        cache[cacheKey] = CacheEntry(
                            ratings = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock {
                        inFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    private suspend fun fetchEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?
    ): Map<Pair<Int, Int>, Double> {
        if (!imdbId.isNullOrBlank()) {
            val primary = fetchFromImdbTapframe(imdbId)
            if (primary.isNotEmpty()) return primary
            Log.w(tag, "Primary episode ratings empty for imdbId=$imdbId, trying fallback.")
        }

        if (tmdbId != null && tmdbId > 0) {
            return fetchFromSeriesGraph(tmdbId)
        }

        return emptyMap()
    }

    private suspend fun fetchFromImdbTapframe(imdbId: String): Map<Pair<Int, Int>, Double> {
        return try {
            val response = imdbTapframeApi.getSeasonRatings(imdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed primary season ratings for imdbId=$imdbId (${response.code()})")
                return emptyMap()
            }
            toRatingsMap(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching primary season ratings for imdbId=$imdbId", e)
            emptyMap()
        }
    }

    private suspend fun fetchFromSeriesGraph(tmdbId: Int): Map<Pair<Int, Int>, Double> {
        return try {
            val response = seriesGraphApi.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed fallback season ratings for tmdbId=$tmdbId (${response.code()})")
                return emptyMap()
            }
            toRatingsMap(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching fallback season ratings for tmdbId=$tmdbId", e)
            emptyMap()
        }
    }

    private fun toRatingsMap(payload: List<com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto>): Map<Pair<Int, Int>, Double> {
        return buildMap {
            payload.forEach { season ->
                season.episodes.orEmpty().forEach { episode ->
                    val seasonNumber = episode.seasonNumber ?: return@forEach
                    val episodeNumber = episode.episodeNumber ?: return@forEach
                    val voteAverage = episode.voteAverage ?: return@forEach
                    put(seasonNumber to episodeNumber, voteAverage)
                }
            }
        }
    }
}

/**
 * External ratings (IMDb/TMDB) are keyed by their own season/episode numbers. Some add-ons number
 * episodes differently (anime split into one long season, absolute numbering, merged seasons). When
 * most of the add-on's episodes have no rating at their own number, line the two lists up by order.
 */
fun alignEpisodeRatingsToAddonOrder(
    addonEpisodes: List<Pair<Int, Int>>,
    external: Map<Pair<Int, Int>, Double>,
): Map<Pair<Int, Int>, Double> {
    if (addonEpisodes.isEmpty() || external.isEmpty()) return external
    val addonRegular = addonEpisodes.filter { it.first > 0 && it.second > 0 }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    val externalRegular = external.keys.filter { it.first > 0 && it.second > 0 }.sortedWith(compareBy({ it.first }, { it.second }))
    if (addonRegular.isEmpty() || externalRegular.isEmpty()) return external
    val matched = addonRegular.count { it in external }
    val comparable = minOf(addonRegular.size, externalRegular.size)
    if (matched * 10 >= comparable * 8) return external
    val aligned = external.filterKeys { it.first == 0 }.toMutableMap()
    addonRegular.zip(externalRegular).forEach { (addonKey, externalKey) ->
        external[externalKey]?.let { aligned[addonKey] = it }
    }
    return aligned
}
