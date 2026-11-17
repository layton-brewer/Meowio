package com.nuvio.app.features.zap

import co.touchlab.kermit.Logger
import com.nuvio.app.features.search.buildDiscoverSources
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Why [ZapPool.next] returned nothing, so the screen can say something useful. */
enum class ZapEmpty { NoSources, NoMatches, Offline }

sealed interface ZapResult {
    data class Found(val title: ZapTitle) : ZapResult
    data class Empty(val reason: ZapEmpty) : ZapResult
}

/** One browsable catalog of one of the user's add-ons. */
internal data class ZapSource(
    val sourceName: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val genreOptions: List<String>,
    val supportsSkip: Boolean,
    val supportsSearch: Boolean,
    val requiredExtras: Map<String, String>,
) {
    val key: String get() = "$manifestUrl|$type|$catalogId"
}

private data class ZapCandidate(val item: MetaPreview, val sourceName: String)

private const val MAX_PAGES = 10

/**
 * A shuffled supply of random titles for one set of [ZapFilters], drawn from the catalogs of the
 * add-ons the user has installed (the same ones Home and Search use). Needs no TMDB key.
 * Make a new one whenever the filters change; it never repeats a title.
 */
class ZapPool(private val filters: ZapFilters) {
    private val log = Logger.withTag("ZapPool")
    private val queue = ArrayDeque<ZapCandidate>()
    private val seen = HashSet<String>()
    private val nextSkip = HashMap<String, Int>()
    private val pagesFetched = HashMap<String, Int>()
    private val exhausted = HashSet<String>()
    private val sourceTurns = ArrayDeque<ZapSource>()
    private val catalogQueues = HashMap<String, ArrayDeque<MetaPreview>>()
    private val searched = HashSet<String>()
    private var failures = 0
    private var sources: List<ZapSource>? = null

    suspend fun next(): ZapResult {
        val all = sources ?: loadSources().also { sources = it }
        if (all.isEmpty()) return ZapResult.Empty(ZapEmpty.NoSources)
        var refills = 0
        while (refills <= 30) {
            while (queue.isNotEmpty()) {
                val (item, sourceName) = queue.removeFirst()
                if (!seen.add("${item.type}:${item.id}")) continue
                if (isSaved(item)) continue
                if (filters.hideWatched && isWatched(item)) continue
                return ZapResult.Found(item.toZapTitle(sourceName))
            }
            refills++
            refill(all)
        }
        return ZapResult.Empty(if (failures > 0) ZapEmpty.Offline else ZapEmpty.NoMatches)
    }

    /** Surprise Me finds new things: titles already in the user's library or lists never come up. */
    private fun isSaved(item: MetaPreview): Boolean = runCatching {
        LibraryRepository.ensureLoaded()
        LibraryRepository.isSaved(item.id, item.type)
    }.getOrDefault(false)

    private fun isWatched(item: MetaPreview): Boolean = runCatching {
        WatchedRepository.isWatched(id = item.id, type = item.type) ||
            (item.type == "series" && WatchedRepository.isFullyWatchedSeries(item.id, item.type)) ||
            startedIds().contains(item.id)
    }.getOrDefault(false)

    /** Anything with watch progress: continue watching, partly watched or finished. */
    private fun startedIds(): Set<String> {
        val entries = WatchProgressRepository.uiState.value.entries
        return buildSet {
            entries.forEach { add(it.parentMetaId) }
        }
    }

    private suspend fun loadSources(): List<ZapSource> {
        AddonRepository.initialize()
        // A stream-only or personal-list add-on can finish first. Wait for all enabled manifests
        // before freezing the pool's sources, so slower catalog add-ons are included too.
        withTimeoutOrNull(12_000) {
            AddonRepository.uiState.first { state -> state.addons.none { it.enabled && it.isRefreshing } }
        }
        val options = buildDiscoverSources(AddonRepository.uiState.value.addons.filter { it.isActive })
        // Automatic: the catalogs shown on the home screen (those not switched off in Home settings).
        // A custom choice, made in Surprise Me's filters, replaces that. If nothing matches, use everything.
        val homeKeys = HomeCatalogSettingsRepository.uiState.value.items
            .filter { it.enabled && !it.isCollection }
            .map { it.key }
            .toSet()
        val wantedKeys = filters.sourceKeys.ifEmpty { homeKeys }
        val chosen = options.filter { it.key in wantedKeys }.ifEmpty { options }
        val found = chosen.map { catalog ->
            ZapSource(
                sourceName = "${catalog.addonName} · ${catalog.catalogName}",
                manifestUrl = catalog.manifestUrl,
                type = catalog.type,
                catalogId = catalog.catalogId,
                genreOptions = catalog.genreOptions,
                supportsSkip = catalog.supportsPagination,
                supportsSearch = AddonRepository.uiState.value.addons
                    .firstOrNull { it.manifestUrl == catalog.manifestUrl }?.manifest?.catalogs
                    ?.firstOrNull { it.id == catalog.catalogId && it.type == catalog.type }
                    ?.extra?.any { it.name == "search" } == true,
                requiredExtras = if (catalog.genreRequired) mapOf("genre" to catalog.genreOptions.random()) else emptyMap(),
            )
        }
        val wantedTypes = when (filters.type) {
            ZapMediaType.Both -> setOf("movie", "series")
            ZapMediaType.Movies -> setOf("movie")
            ZapMediaType.Series -> setOf("series")
        }
        return found.filter { it.type in wantedTypes }.distinctBy { it.key }
    }

    private suspend fun refill(all: List<ZapSource>) {
        val added = ArrayList<ZapCandidate>()
        if (filters.people.isNotEmpty()) {
            for (person in filters.people) {
                for (source in all.filter { it.supportsSearch }) {
                    if (!searched.add("${source.key}|${person.name}")) continue
                    added += fetch(source, search = person.name, skip = null).map { ZapCandidate(it, source.sourceName) }
                }
            }
        } else {
            // Visit each installed catalog before revisiting any of them.
            if (sourceTurns.isEmpty()) sourceTurns.addAll(all.filter { it.key !in exhausted || catalogQueues[it.key]?.isNotEmpty() == true }.shuffled())
            repeat(minOf(5, sourceTurns.size)) {
                val source = sourceTurns.removeFirst()
                val candidates = catalogQueues.getOrPut(source.key) { ArrayDeque() }
                if (candidates.isEmpty()) candidates.addAll(discoverPage(source).shuffled())
                while (candidates.isNotEmpty()) {
                    val item = candidates.removeFirst()
                    if (!matches(item) || "${item.type}:${item.id}" in seen || isSaved(item) ||
                        (filters.hideWatched && isWatched(item))) continue
                    added += ZapCandidate(item, source.sourceName)
                    break
                }
            }
        }
        added.shuffle()
        added.filter { matches(it.item) && "${it.item.type}:${it.item.id}" !in seen }.forEach(queue::addLast)
    }

    private suspend fun discoverPage(source: ZapSource): List<MetaPreview> {
        if (source.key in exhausted) return emptyList()
        val skip = nextSkip[source.key] ?: 0
        val genre = filters.genres.firstNotNullOfOrNull { wanted ->
            source.genreOptions.firstOrNull { sameGenre(it, wanted) }
        }
        val page = fetchPage(source, genre = genre, search = null, skip = skip)
        val count = (pagesFetched[source.key] ?: 0) + 1
        pagesFetched[source.key] = count
        val followingSkip = page?.nextSkip
        if (!source.supportsSkip || followingSkip == null || followingSkip <= skip || count >= MAX_PAGES) {
            exhausted += source.key
        } else {
            nextSkip[source.key] = followingSkip
        }
        return page?.items.orEmpty()
    }

    private suspend fun fetch(source: ZapSource, search: String?, skip: Int?): List<MetaPreview> =
        fetchPage(source, search = search, skip = skip ?: 0)?.items.orEmpty()

    private suspend fun fetchPage(source: ZapSource, genre: String? = null, search: String?, skip: Int): CatalogPage? =
        runCatching {
            val extras = source.requiredExtras.toMutableMap()
            if (genre != null) extras["genre"] = genre
            if (search != null) extras["search"] = search
            if (source.supportsSkip && (skip > 0 || "skip" in extras)) extras["skip"] = skip.toString()
            fetchCatalogPage(
                manifestUrl = source.manifestUrl,
                type = source.type,
                catalogId = source.catalogId,
                genre = extras["genre"],
                search = search,
                skip = skip.takeIf { it > 0 },
            )
        }.onFailure {
            if (it is CancellationException) throw it
            failures++
            log.w { "Zap catalog fetch failed: ${it.message}" }
        }.getOrNull()

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

private fun MetaPreview.toZapTitle(sourceName: String): ZapTitle = ZapTitle(
    sourceName = sourceName,
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

/** The filters the user last chose; kept for the life of the app so reopening Surprise Me feels the same. */
object ZapFilterMemory {
    private var current: ZapFilters? = null

    var filters: ZapFilters
        get() = current ?: ZapFilters(sourceKeys = loadSourceKeys()).also { current = it }
        set(value) {
            current = value
            runCatching { ZapSourceStorage.save(value.sourceKeys.joinToString("\n")) }
        }

    private fun loadSourceKeys(): Set<String> =
        runCatching { ZapSourceStorage.load() }.getOrNull().orEmpty()
            .split('\n').filter { it.isNotBlank() }.toSet()
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
