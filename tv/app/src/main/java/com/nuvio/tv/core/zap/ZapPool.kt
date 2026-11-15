package com.nuvio.tv.core.zap

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random

enum class ZapEmpty { NO_KEY, NO_MATCHES, OFFLINE }

sealed interface ZapResult {
    data class Found(val title: ZapTitle) : ZapResult
    data class Empty(val reason: ZapEmpty) : ZapResult
}

private data class ZapCandidate(val tmdbId: Int, val isSeries: Boolean) {
    val key: String get() = "${if (isSeries) "tv" else "movie"}:$tmdbId"
}

/**
 * A shuffled supply of random titles for one set of [ZapFilters]. Make a new one when the filters
 * change. Pulls random pages from TMDB discover (or the chosen actors' credits), hands out one
 * title at a time and never repeats a title. Same behavior as the phone and desktop apps.
 */
class ZapPool(
    private val filters: ZapFilters,
    private val apiKey: String,
    private val client: OkHttpClient,
    private val isWatched: suspend (ZapTitle) -> Boolean
) {
    private val queue = ArrayDeque<ZapCandidate>()
    private val seen = HashSet<String>()
    private val totalPages = HashMap<Boolean, Int>()
    private val triedPages = HashMap<Boolean, MutableSet<Int>>()
    private var creditsLoaded = false
    private var failures = 0

    suspend fun next(): ZapResult {
        if (apiKey.isBlank()) return ZapResult.Empty(ZapEmpty.NO_KEY)
        var attempts = 0
        while (attempts < 40) {
            if (queue.isEmpty()) {
                refill()
                if (queue.isEmpty()) return ZapResult.Empty(if (failures > 0) ZapEmpty.OFFLINE else ZapEmpty.NO_MATCHES)
            }
            val candidate = queue.removeFirst()
            if (!seen.add(candidate.key)) continue
            attempts++
            val title = loadTitle(candidate) ?: continue
            if (filters.minRating > 0f && (title.rating ?: 0.0) < filters.minRating) continue
            if (filters.hideWatched && runCatching { isWatched(title) }.getOrDefault(false)) continue
            return ZapResult.Found(title)
        }
        return ZapResult.Empty(if (failures > 0) ZapEmpty.OFFLINE else ZapEmpty.NO_MATCHES)
    }

    private fun activeTypes(): List<Boolean> {
        val wanted = when (filters.type) {
            ZapMediaType.Both -> listOf(false, true)
            ZapMediaType.Movies -> listOf(false)
            ZapMediaType.Series -> listOf(true)
        }
        val chosen = zapGenres.filter { it.name in filters.genres }
        // Genres are an AND filter, so a type with no id for one of them can never match.
        return wanted.filter { isSeries -> chosen.all { (if (isSeries) it.tvId else it.movieId) != null } }
    }

    private suspend fun refill() {
        val added = ArrayList<ZapCandidate>()
        if (filters.people.isNotEmpty()) {
            if (!creditsLoaded) {
                creditsLoaded = true
                added += loadCredits()
            }
        } else {
            for (isSeries in activeTypes()) added += discoverPage(isSeries)
        }
        added.shuffle()
        added.filter { it.key !in seen }.forEach(queue::addLast)
    }

    private suspend fun discoverPage(isSeries: Boolean): List<ZapCandidate> {
        val tried = triedPages.getOrPut(isSeries) { mutableSetOf() }
        val known = totalPages[isSeries]
        val page = if (known == null) 1 else {
            val free = (1..minOf(known, 25)).filter { it !in tried }
            if (free.isEmpty()) return emptyList()
            free[Random.nextInt(free.size)]
        }
        tried += page
        val body = fetch(if (isSeries) "discover/tv" else "discover/movie", discoverQuery(isSeries, page)) ?: return emptyList()
        totalPages[isSeries] = body.optInt("total_pages", 1).coerceIn(1, 500)
        val results = body.optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).map { ZapCandidate(results.getJSONObject(it).getInt("id"), isSeries) }
    }

    private fun discoverQuery(isSeries: Boolean, page: Int): Map<String, String> {
        val chosen = zapGenres.filter { it.name in filters.genres }
        val ids = chosen.mapNotNull { if (isSeries) it.tvId else it.movieId }.distinct()
        val dateKey = if (isSeries) "first_air_date" else "primary_release_date"
        return buildMap {
            put("sort_by", "popularity.desc")
            put("include_adult", "false")
            put("page", page.toString())
            put("language", "en-US")
            put("vote_count.gte", if (filters.yearTo != null && filters.yearTo < 2000) "80" else "150")
            if (filters.minRating > 0f) put("vote_average.gte", filters.minRating.toString())
            if (ids.isNotEmpty()) put("with_genres", ids.joinToString(","))
            filters.yearFrom?.let { put("$dateKey.gte", "$it-01-01") }
            filters.yearTo?.let { put("$dateKey.lte", "$it-12-31") }
        }
    }

    private suspend fun loadCredits(): List<ZapCandidate> {
        val types = activeTypes()
        val chosen = zapGenres.filter { it.name in filters.genres }
        val out = LinkedHashMap<String, ZapCandidate>()
        for (person in filters.people) {
            val cast = fetch("person/${person.id}/combined_credits")?.optJSONArray("cast") ?: continue
            for (i in 0 until cast.length()) {
                val item = cast.getJSONObject(i)
                val isSeries = when (item.str("media_type")) {
                    "tv" -> true
                    "movie" -> false
                    else -> continue
                }
                if (isSeries !in types) continue
                val genreIds = item.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map(a::getInt) }.orEmpty().toSet()
                if (chosen.isNotEmpty() && chosen.any { g ->
                        val id = if (isSeries) g.tvId else g.movieId
                        id == null || id !in genreIds
                    }
                ) continue
                val date = item.str("release_date").ifBlank { item.str("first_air_date") }
                val year = date.take(4).toIntOrNull()
                if (filters.yearFrom != null && (year == null || year < filters.yearFrom)) continue
                if (filters.yearTo != null && (year == null || year > filters.yearTo)) continue
                if (filters.minRating > 0f && item.optDouble("vote_average", 0.0) < filters.minRating) continue
                if (item.optInt("vote_count", 0) < 20) continue
                val candidate = ZapCandidate(item.getInt("id"), isSeries)
                out[candidate.key] = candidate
            }
        }
        return out.values.toList()
    }

    private suspend fun loadTitle(candidate: ZapCandidate): ZapTitle? {
        val kind = if (candidate.isSeries) "tv" else "movie"
        val body = fetch(
            "$kind/${candidate.tmdbId}",
            mapOf("append_to_response" to "external_ids,credits", "language" to "en-US")
        ) ?: return null
        val imdb = body.str("imdb_id").ifBlank { body.optJSONObject("external_ids")?.str("imdb_id").orEmpty() }
        if (!imdb.startsWith("tt")) return null
        val name = body.str("title").ifBlank { body.str("name") }
        if (name.isBlank()) return null
        val date = body.str("release_date").ifBlank { body.str("first_air_date") }
        val poster = body.str("poster_path").takeIf { it.isNotBlank() && it != "null" }
        val backdrop = body.str("backdrop_path").takeIf { it.isNotBlank() && it != "null" } ?: poster
        val genres = body.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).str("name") } }.orEmpty()
        val cast = body.optJSONObject("credits")?.optJSONArray("cast")
            ?.let { a -> (0 until minOf(a.length(), 6)).map { a.getJSONObject(it).str("name") } }.orEmpty()
        val runtime = body.optInt("runtime", 0).takeIf { it > 0 }
            ?: body.optJSONArray("episode_run_time")?.let { a -> if (a.length() > 0) a.getInt(0).takeIf { it > 0 } else null }
        return ZapTitle(
            tmdbId = candidate.tmdbId,
            isSeries = candidate.isSeries,
            imdbId = imdb,
            name = name,
            year = date.take(4).takeIf { it.length == 4 },
            overview = body.str("overview"),
            poster = poster?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdrop = backdrop?.let { "https://image.tmdb.org/t/p/w1280$it" },
            rating = body.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            genres = genres,
            runtimeMinutes = runtime,
            seasons = if (body.has("number_of_seasons")) body.optInt("number_of_seasons") else null,
            cast = cast
        )
    }

    private suspend fun fetch(endpoint: String, query: Map<String, String> = emptyMap()): JSONObject? =
        zapFetch(client, apiKey, endpoint, query).also { if (it == null) failures++ }
}

internal suspend fun zapFetch(
    client: OkHttpClient,
    apiKey: String,
    endpoint: String,
    query: Map<String, String> = emptyMap()
): JSONObject? = withContext(Dispatchers.IO) {
    val url = buildString {
        append("https://api.themoviedb.org/3/").append(endpoint)
        append("?api_key=").append(URLEncoder.encode(apiKey, "UTF-8"))
        query.forEach { (k, v) -> if (v.isNotBlank()) append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8")) }
    }
    runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }.onFailure { Log.w("ZapPool", "TMDB request failed for $endpoint: ${it.message}") }.getOrNull()
}

/** Actor lookup for the filter panel. */
suspend fun zapSearchPeople(client: OkHttpClient, apiKey: String, query: String): List<ZapPerson> {
    if (apiKey.isBlank() || query.isBlank()) return emptyList()
    val results = zapFetch(client, apiKey, "search/person", mapOf("query" to query.trim(), "include_adult" to "false"))
        ?.optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).map { results.getJSONObject(it) }
        .filter { val dept = it.str("known_for_department"); dept.isBlank() || dept == "Acting" }
        .take(8)
        .map { ZapPerson(it.getInt("id"), it.str("name")) }
}

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
