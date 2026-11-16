package com.nuvio.tv.core.zap

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random

enum class ZapEmpty { NO_SOURCES, NO_MATCHES, OFFLINE }

sealed interface ZapResult {
    data class Found(val title: ZapTitle) : ZapResult
    data class Empty(val reason: ZapEmpty) : ZapResult
}

/** One browsable catalog of one of the user's add-ons. */
private data class ZapSource(
    val addon: Addon,
    val catalogId: String,
    val catalogName: String,
    val type: String,
    val genreOptions: List<String>,
    val supportsSkip: Boolean,
    val supportsSearch: Boolean
) {
    val key: String get() = "${addon.baseUrl}|$type|$catalogId"
}

private const val MAX_PAGE_INDEX = 9

/**
 * A shuffled supply of random titles for one set of [ZapFilters], drawn from the catalogs of the
 * add-ons the user has installed (the same ones Home and Search use). Needs no TMDB key.
 * Make a new one when the filters change; it never repeats a title.
 */
class ZapPool(
    private val filters: ZapFilters,
    private val addons: suspend () -> List<Addon>,
    private val catalogs: CatalogRepository,
    private val isWatched: suspend (String) -> Boolean,
    private val isSaved: suspend (String) -> Boolean = { false }
) {
    private val queue = ArrayDeque<MetaPreview>()
    private val seen = HashSet<String>()
    private val pageSize = HashMap<String, Int>()
    private val triedPages = HashMap<String, MutableSet<Int>>()
    private val lastPage = HashMap<String, Int>()
    private val searched = HashSet<String>()
    private var failures = 0
    private var sources: List<ZapSource>? = null

    suspend fun next(): ZapResult {
        val all = sources ?: loadSources().also { sources = it }
        if (all.isEmpty()) return ZapResult.Empty(ZapEmpty.NO_SOURCES)
        var refills = 0
        while (refills <= 30) {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (!seen.add("${item.rawType}:${item.id}")) continue
                // Surprise Me finds new things: titles already in the library or lists never come up.
                if (runCatching { isSaved(item.id) }.getOrDefault(false)) continue
                if (filters.hideWatched && runCatching { isWatched(item.id) }.getOrDefault(false)) continue
                return ZapResult.Found(item.toZapTitle())
            }
            refills++
            refill(all)
        }
        return ZapResult.Empty(if (failures > 0) ZapEmpty.OFFLINE else ZapEmpty.NO_MATCHES)
    }

    private fun wantedTypes(): Set<String> = when (filters.type) {
        ZapMediaType.Both -> setOf("movie", "series")
        ZapMediaType.Movies -> setOf("movie")
        ZapMediaType.Series -> setOf("series")
    }

    private suspend fun loadSources(): List<ZapSource> {
        val wanted = wantedTypes()
        return addons().filter { it.isActive }.flatMap { addon ->
            addon.catalogs.mapNotNull { catalog ->
                val type = catalog.apiType.lowercase()
                if (type !in wanted) return@mapNotNull null
                if (looksLikeHistory("${catalog.id} ${catalog.name}")) return@mapNotNull null
                if (catalog.isSearchOnlyCatalog()) return@mapNotNull null
                val blocked = catalog.extra.any { e ->
                    e.isRequired && when (e.name.lowercase()) {
                        "genre" -> e.options.isNullOrEmpty()
                        "skip", "search" -> false
                        else -> true
                    }
                }
                if (blocked) return@mapNotNull null
                ZapSource(
                    addon = addon,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = type,
                    genreOptions = catalog.extra.firstOrNull { it.name.equals("genre", true) }?.options.orEmpty(),
                    supportsSkip = catalog.supportsExtra("skip"),
                    supportsSearch = catalog.supportsExtra("search") &&
                        catalog.extra.none { it.isRequired && !it.name.equals("search", true) }
                )
            }
        }.distinctBy { it.key }
    }

    private suspend fun refill(all: List<ZapSource>) {
        val added = ArrayList<MetaPreview>()
        if (filters.people.isNotEmpty()) {
            for (person in filters.people) {
                for (source in all.filter { it.supportsSearch }) {
                    if (!searched.add("${source.key}|${person.name}")) continue
                    added += fetch(source, mapOf("search" to person.name), 0)
                }
            }
        } else {
            // A few random catalogs per round, so many small catalogs cannot drown out the big ones.
            for (source in all.shuffled().take(5)) added += discoverPage(source)
        }
        added.shuffle()
        added.filter { matches(it) && "${it.rawType}:${it.id}" !in seen }.forEach(queue::addLast)
    }

    private suspend fun discoverPage(source: ZapSource): List<MetaPreview> {
        val tried = triedPages.getOrPut(source.key) { mutableSetOf() }
        val limit = minOf(lastPage[source.key] ?: MAX_PAGE_INDEX, MAX_PAGE_INDEX)
        val page = when {
            tried.isEmpty() -> 0
            !source.supportsSkip -> return emptyList()
            else -> (0..limit).filter { it !in tried }.randomOrNull() ?: return emptyList()
        }
        tried += page
        val size = pageSize[source.key]
        val skip = if (page == 0) 0 else (size ?: return emptyList()) * page
        val genre = filters.genres.firstNotNullOfOrNull { wanted ->
            source.genreOptions.firstOrNull { sameGenre(it, wanted) }
        }
        val items = fetch(source, if (genre != null) mapOf("genre" to genre) else emptyMap(), skip)
        if (page == 0 && items.isNotEmpty()) pageSize[source.key] = items.size
        if (items.isEmpty() && page > 0) lastPage[source.key] = page - 1
        return items
    }

    private suspend fun fetch(source: ZapSource, extra: Map<String, String>, skip: Int): List<MetaPreview> =
        runCatching {
            val result = catalogs.getCatalog(
                addonBaseUrl = source.addon.baseUrl,
                addonId = source.addon.id,
                addonName = source.addon.displayName,
                catalogId = source.catalogId,
                catalogName = source.catalogName,
                type = source.type,
                skip = skip,
                extraArgs = extra,
                supportsSkip = source.supportsSkip
            ).first { it !is NetworkResult.Loading }
            when (result) {
                is NetworkResult.Success -> result.data.items
                else -> { failures++; emptyList() }
            }
        }.onFailure {
            failures++
            Log.w("ZapPool", "Zap catalog fetch failed for ${source.key}: ${it.message}")
        }.getOrDefault(emptyList())

    private fun matches(item: MetaPreview): Boolean {
        if (item.poster.isNullOrBlank() && item.background.isNullOrBlank()) return false
        if (filters.minRating > 0f) {
            val rating = item.imdbRating ?: return false
            if (rating < filters.minRating) return false
        }
        if (filters.yearFrom != null || filters.yearTo != null) {
            val year = itemYear(item) ?: return false
            if (filters.yearFrom != null && year < filters.yearFrom) return false
            if (filters.yearTo != null && year > filters.yearTo) return false
        }
        if (filters.genres.isNotEmpty() && item.genres.isNotEmpty()) {
            if (!filters.genres.all { wanted -> item.genres.any { sameGenre(it, wanted) } }) return false
        }
        return true
    }
}

private fun itemYear(item: MetaPreview): Int? =
    (item.releaseInfo ?: item.released)?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

private fun normalizeGenre(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit).replace("scifi", "sciencefiction")

private fun sameGenre(a: String, b: String): Boolean {
    val x = normalizeGenre(a)
    val y = normalizeGenre(b)
    return x == y || (x.length > 3 && y.length > 3 && (x.contains(y) || y.contains(x)))
}

private fun looksLikeHistory(text: String): Boolean =
    Regex("watched|history|continue|resume|recently|in.?progress|watching|scrobbl|watchlist|library|favou?rite|my.?list", RegexOption.IGNORE_CASE).containsMatchIn(text)

private fun MetaPreview.toZapTitle(): ZapTitle = ZapTitle(
    id = id,
    isSeries = rawType.equals("series", ignoreCase = true),
    name = name,
    year = itemYear(this)?.toString(),
    overview = description.orEmpty(),
    poster = poster,
    backdrop = background ?: landscapePoster ?: poster,
    rating = imdbRating?.toDouble()?.takeIf { it > 0.0 },
    genres = genres,
    runtimeMinutes = runtime?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
    seasons = null,
    cast = emptyList()
)

/**
 * Optional actor-name suggestions from TMDB. Returns nothing when there is no TMDB key; the panel
 * then lets the user type a name, which the add-ons' own search matches.
 */
suspend fun zapSearchPeople(client: OkHttpClient, apiKey: String, query: String): List<ZapPerson> {
    if (apiKey.isBlank() || query.isBlank()) return emptyList()
    val url = "https://api.themoviedb.org/3/search/person?api_key=" + URLEncoder.encode(apiKey, "UTF-8") +
        "&include_adult=false&query=" + URLEncoder.encode(query.trim(), "UTF-8")
    val body = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    } ?: return emptyList()
    val results = body.optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).map { results.getJSONObject(it) }
        .filter { val dept = it.optString("known_for_department"); dept.isBlank() || dept == "Acting" }
        .take(8)
        .map { ZapPerson(it.getInt("id"), it.optString("name")) }
}
