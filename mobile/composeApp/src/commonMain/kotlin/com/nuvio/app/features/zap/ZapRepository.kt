package com.nuvio.app.features.zap

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Why [ZapPool.next] returned nothing, so the screen can say something useful. */
enum class ZapEmpty { NoSources, NoMatches, Offline }

sealed interface ZapResult {
    data class Found(val title: ZapTitle) : ZapResult
    data class Empty(val reason: ZapEmpty) : ZapResult
}

/** One browsable catalog of one of the user's add-ons. */
private data class ZapSource(
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val genreOptions: List<String>,
    val supportsSkip: Boolean,
    val supportsSearch: Boolean,
) {
    val key: String get() = "$manifestUrl|$type|$catalogId"
}

private const val CINEMETA_MANIFEST = "https://v3-cinemeta.strem.io/manifest.json"
private const val MAX_PAGE_INDEX = 9

/**
 * A shuffled supply of random titles for one set of [ZapFilters], drawn from the catalogs of the
 * add-ons the user has installed (the same ones Home and Search use). Needs no TMDB key.
 * Make a new one whenever the filters change; it never repeats a title.
 */
class ZapPool(private val filters: ZapFilters) {
    private val log = Logger.withTag("ZapPool")
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
        if (all.isEmpty()) return ZapResult.Empty(ZapEmpty.NoSources)
        var refills = 0
        while (refills <= 14) {
            while (queue.isNotEmpty()) {
                val item = queue.removeFirst()
                if (!seen.add("${item.type}:${item.id}")) continue
                if (filters.hideWatched && isWatched(item)) continue
                return ZapResult.Found(item.toZapTitle())
            }
            refills++
            refill(all)
        }
        return ZapResult.Empty(if (failures > 0) ZapEmpty.Offline else ZapEmpty.NoMatches)
    }

    private fun isWatched(item: MetaPreview): Boolean = runCatching {
        WatchedRepository.isWatched(id = item.id, type = item.type) ||
            (item.type == "series" && WatchedRepository.isFullyWatchedSeries(item.id, item.type))
    }.getOrDefault(false)

    private suspend fun loadSources(): List<ZapSource> {
        AddonRepository.initialize()
        // Manifests load in the background; give them a moment on first use.
        var waited = 0
        while (waited < 12_000 && AddonRepository.uiState.value.addons.none { it.isActive }) {
            delay(500)
            waited += 500
        }
        val found = AddonRepository.uiState.value.addons
            .filter { it.isActive }
            .flatMap { addon ->
                val manifest = addon.manifest ?: return@flatMap emptyList()
                manifest.catalogs.filter { it.type == "movie" || it.type == "series" }.mapNotNull { catalog ->
                    catalog.toSource(manifest.transportUrl)
                }
            }
            .toMutableList()
        // Cinemeta is the app's default metadata provider; fall back to it for a type nothing else lists.
        for (type in listOf("movie", "series")) {
            if (found.none { it.type == type }) {
                found += ZapSource(CINEMETA_MANIFEST, type, "top", genreOptions = emptyList(), supportsSkip = true, supportsSearch = true)
            }
        }
        val wantedTypes = when (filters.type) {
            ZapMediaType.Both -> setOf("movie", "series")
            ZapMediaType.Movies -> setOf("movie")
            ZapMediaType.Series -> setOf("series")
        }
        return found.filter { it.type in wantedTypes }.distinctBy { it.key }
    }

    private fun AddonCatalog.toSource(manifestUrl: String): ZapSource? {
        if (extra.any { it.name == "search" && it.isRequired }) return null
        val blocked = extra.any { property ->
            when (property.name) {
                "genre" -> property.isRequired && property.options.isEmpty()
                "skip", "search" -> false
                else -> property.isRequired
            }
        }
        if (blocked) return null
        return ZapSource(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = id,
            genreOptions = extra.firstOrNull { it.name == "genre" }?.options.orEmpty(),
            supportsSkip = extra.any { it.name.equals("skip", ignoreCase = true) },
            supportsSearch = extra.any { it.name == "search" } &&
                extra.none { it.isRequired && it.name != "search" },
        )
    }

    private suspend fun refill(all: List<ZapSource>) {
        val added = ArrayList<MetaPreview>()
        if (filters.people.isNotEmpty()) {
            for (person in filters.people) {
                for (source in all.filter { it.supportsSearch }) {
                    if (!searched.add("${source.key}|${person.name}")) continue
                    added += fetch(source, search = person.name, skip = null)
                }
            }
        } else {
            for (source in all) added += discoverPage(source)
        }
        added.shuffle()
        added.filter { matches(it) && "${it.type}:${it.id}" !in seen }.forEach(queue::addLast)
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
        val skip = if (page == 0) null else (size ?: return emptyList()) * page
        val genre = filters.genres.firstNotNullOfOrNull { wanted ->
            source.genreOptions.firstOrNull { sameGenre(it, wanted) }
        }
        val items = fetch(source, genre = genre, search = null, skip = skip)
        if (page == 0 && items.isNotEmpty()) pageSize[source.key] = items.size
        if (items.isEmpty() && page > 0) lastPage[source.key] = page - 1
        return items
    }

    private suspend fun fetch(source: ZapSource, genre: String? = null, search: String?, skip: Int?): List<MetaPreview> =
        runCatching {
            fetchCatalogPage(
                manifestUrl = source.manifestUrl,
                type = source.type,
                catalogId = source.catalogId,
                genre = genre,
                search = search,
                skip = skip,
            ).items
        }.onFailure {
            failures++
            log.w { "Zap catalog fetch failed for ${source.key}: ${it.message}" }
        }.getOrDefault(emptyList())

    private fun matches(item: MetaPreview): Boolean {
        if (item.poster.isNullOrBlank() && item.banner.isNullOrBlank()) return false
        if (filters.minRating > 0f) {
            val rating = item.imdbRating?.toDoubleOrNull() ?: return false
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
    (item.releaseInfo ?: item.rawReleaseDate)?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }

private fun normalizeGenre(value: String): String =
    value.lowercase().filter(Char::isLetterOrDigit).replace("scifi", "sciencefiction")

private fun sameGenre(a: String, b: String): Boolean {
    val x = normalizeGenre(a)
    val y = normalizeGenre(b)
    return x == y || (x.length > 3 && y.length > 3 && (x.contains(y) || y.contains(x)))
}

private fun MetaPreview.toZapTitle(): ZapTitle = ZapTitle(
    id = id,
    isSeries = type == "series",
    name = name,
    year = itemYear(this)?.toString(),
    overview = description.orEmpty(),
    poster = poster,
    backdrop = banner ?: landscapePoster ?: poster,
    rating = imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 },
    genres = genres,
    runtimeMinutes = runtime?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
    seasons = null,
    cast = emptyList(),
)

object ZapSearch {
    private val log = Logger.withTag("ZapSearch")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Optional actor-name suggestions from TMDB. Returns nothing when no TMDB key is set; the filter
     * panel then lets the user type a name, which is matched against the add-ons' own search.
     */
    suspend fun people(query: String): List<ZapPerson> {
        val apiKey = runCatching { TmdbSettingsRepository.effectiveApiKey() }.getOrDefault("")
        if (apiKey.isBlank() || query.isBlank()) return emptyList()
        val url = "https://api.themoviedb.org/3/search/person?api_key=${percentEncode(apiKey)}" +
            "&include_adult=false&query=${percentEncode(query.trim())}"
        return runCatching { json.decodeFromString<PersonSearchResponse>(httpGetText(url)) }
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

private fun percentEncode(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (c < 128 && (ch.isLetterOrDigit() || ch in "-_.~")) {
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
private data class PersonSearchResponse(val results: List<PersonItem> = emptyList())

@Serializable
private data class PersonItem(
    val id: Int,
    val name: String = "",
    @SerialName("known_for_department") val knownForDepartment: String? = null,
)
