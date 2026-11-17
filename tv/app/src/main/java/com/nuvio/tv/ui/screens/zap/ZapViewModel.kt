package com.nuvio.tv.ui.screens.zap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.zap.ZapEmpty
import com.nuvio.tv.core.zap.ZapFilters
import com.nuvio.tv.core.zap.ZapPerson
import com.nuvio.tv.core.zap.ZapPool
import com.nuvio.tv.core.zap.ZapResult
import com.nuvio.tv.core.zap.ZapSourceOption
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.core.zap.ZapTitle
import com.nuvio.tv.core.zap.zapSearchPeople
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject

data class ZapUiState(
    val title: ZapTitle? = null,
    val loading: Boolean = true,
    val empty: ZapEmpty? = null,
    val saved: Boolean = false,
    val filters: ZapFilters = ZapFilters(),
    val canGoBack: Boolean = false,
    val suggestions: List<ZapPerson> = emptyList(),
    val sourceOptions: List<ZapSourceOption> = emptyList()
)

@HiltViewModel
class ZapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val client: OkHttpClient,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository
) : ViewModel() {
    private val sourcePrefs = context.getSharedPreferences("meowio_zap_sources", Context.MODE_PRIVATE)
    private val startFilters = ZapFilters(
        sourceKeys = sourcePrefs.getString("keys", "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
    )
    private val _state = MutableStateFlow(ZapUiState(filters = startFilters))
    val state: StateFlow<ZapUiState> = _state.asStateFlow()

    private var pool = newPool(startFilters)
    private val history = ArrayDeque<ZapTitle>()

    init {
        zap()
        viewModelScope.launch {
            val addons = withTimeoutOrNull(8_000) { addonRepository.getInstalledAddons().first { it.isNotEmpty() } }.orEmpty()
            val options = addons.filter { it.isActive }.flatMap { addon ->
                addon.catalogs.filter { !it.isSearchOnlyCatalog() }.mapNotNull { catalog ->
                    val type = catalog.apiType.lowercase()
                    if (!(type == "movie" || type == "series" || type == "tv" || type == "anime" || type.startsWith("anime."))) return@mapNotNull null
                    ZapSourceOption(
                        key = homeCatalogKey(addon.id, type, catalog.id),
                        label = "${addon.displayName} · ${catalog.name} (${if (type.endsWith("movie")) "movies" else "series"})"
                    )
                }
            }.distinctBy { it.key }
            _state.value = _state.value.copy(sourceOptions = options)
        }
    }

    private fun newPool(filters: ZapFilters) = ZapPool(
        filters = filters,
        addons = {
            withTimeoutOrNull(8_000) { addonRepository.getInstalledAddons().first { it.isNotEmpty() } } ?: emptyList()
        },
        catalogs = catalogRepository,
        isWatched = { id ->
            watchProgressRepository.isWatched(id).first() ||
                watchProgressRepository.allProgress.first().any { it.contentId == id }
        },
        isSaved = { id -> libraryRepository.libraryItems.first().any { it.id == id } },
        disabledHomeKeys = { layoutPreferenceDataStore.disabledHomeCatalogKeys.first().toSet() }
    )

    fun zap() {
        if (_state.value.loading && _state.value.title != null) return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val previous = _state.value.title
            when (val result = pool.next()) {
                is ZapResult.Found -> {
                    if (previous != null) {
                        history.addLast(previous)
                        if (history.size > 30) history.removeFirst()
                    }
                    show(result.title)
                }
                is ZapResult.Empty -> _state.value = _state.value.copy(
                    loading = false,
                    empty = result.reason,
                    title = previous
                )
            }
        }
    }

    fun previous() {
        val last = history.removeLastOrNull() ?: return
        viewModelScope.launch { show(last) }
    }

    private suspend fun show(title: ZapTitle) {
        val saved = runCatching { libraryRepository.isInLibrary(title.id, title.type).first() }.getOrDefault(false)
        _state.value = _state.value.copy(
            title = title,
            loading = false,
            empty = null,
            saved = saved,
            canGoBack = history.isNotEmpty()
        )
    }

    fun toggleSaved() {
        val title = _state.value.title ?: return
        viewModelScope.launch {
            runCatching {
                libraryRepository.toggleDefault(
                    LibraryEntryInput(
                        itemId = title.id,
                        itemType = title.type,
                        title = title.name,
                        year = title.year?.toIntOrNull(),
                        imdbId = title.id.takeIf { it.startsWith("tt") },
                        poster = title.poster,
                        background = title.backdrop,
                        description = title.overview,
                        releaseInfo = title.year,
                        imdbRating = title.rating?.toFloat(),
                        genres = title.genres
                    )
                )
            }
            val saved = runCatching { libraryRepository.isInLibrary(title.id, title.type).first() }
                .getOrDefault(_state.value.saved)
            _state.value = _state.value.copy(saved = saved)
        }
    }

    fun applyFilters(filters: ZapFilters) {
        if (filters == _state.value.filters) return
        sourcePrefs.edit().putString("keys", filters.sourceKeys.joinToString("\n")).apply()
        pool = newPool(filters)
        history.clear()
        _state.value = _state.value.copy(filters = filters, empty = null, canGoBack = false, loading = false)
        zap()
    }

    fun retry() {
        pool = newPool(_state.value.filters)
        _state.value = _state.value.copy(loading = false)
        zap()
    }

    fun searchPeople(query: String) {
        viewModelScope.launch {
            val found = zapSearchPeople(client, BuildConfig.TMDB_API_KEY, query)
            _state.value = _state.value.copy(suggestions = found)
        }
    }

    fun clearSuggestions() {
        _state.value = _state.value.copy(suggestions = emptyList())
    }
}
