package com.nuvio.app.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.trakt.TraktPublicListSearchResult
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.trakt.TraktConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal enum class DiscoverListKind(val label: String) {
    POPULAR("Most popular lists"),
    TRENDING("Trending lists"),
}

/**
 * Library "Lists" view: browse the most popular or trending public lists of a tracking service and open
 * one to see its titles right here. Trakt serves these publicly; Simkl has no public lists, and MDBList's
 * top lists need its own account key, so both show as "Not set up yet" for now.
 */
internal class DiscoverListsState(private val scope: CoroutineScope) {
    var kind by mutableStateOf(DiscoverListKind.POPULAR)
        private set
    var lists by mutableStateOf<List<TraktPublicListSearchResult>>(emptyList())
        private set
    var listsLoading by mutableStateOf(false)
        private set
    var listsError by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf<TraktPublicListSearchResult?>(null)
        private set
    var items by mutableStateOf<List<MetaPreview>>(emptyList())
        private set
    var itemsLoading by mutableStateOf(false)
        private set
    var itemsError by mutableStateOf<String?>(null)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    private var started = false
    private var listsJob: Job? = null
    private var itemsJob: Job? = null

    val configured: Boolean get() = TraktListDirectory.isConfigured

    fun ensureStarted() {
        if (started || !configured) return
        started = true
        loadLists()
    }

    fun selectKind(value: DiscoverListKind) {
        if (value == kind && lists.isNotEmpty()) return
        kind = value
        selected = null
        items = emptyList()
        loadLists()
    }

    private fun loadLists() {
        listsJob?.cancel()
        listsLoading = true
        listsError = null
        listsJob = scope.launch {
            try {
                val pages = (1..3).map { page ->
                    when (kind) {
                        DiscoverListKind.POPULAR -> TraktListDirectory.lists("popular", page)
                        DiscoverListKind.TRENDING -> TraktListDirectory.lists("trending", page)
                    }
                }
                lists = pages.flatten().distinctBy { it.traktListId }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                listsError = error.message ?: "Could not load lists."
            } finally {
                listsLoading = false
            }
        }
    }

    fun select(list: TraktPublicListSearchResult) {
        selected = list
        items = emptyList()
        nextPage = null
        loadItems(page = 1)
    }

    fun loadMore() {
        val page = nextPage ?: return
        if (!itemsLoading) loadItems(page)
    }

    private fun loadItems(page: Int) {
        val list = selected ?: return
        itemsJob?.cancel()
        itemsLoading = true
        itemsError = null
        itemsJob = scope.launch {
            try {
                // Movies and shows are two requests: Trakt serves them separately.
                val result = listOf("movie", "tv").map { type ->
                    TraktPublicListSourceResolver.resolve(
                        CollectionSource(
                            provider = "trakt",
                            traktListId = list.traktListId,
                            mediaType = type,
                            sortBy = list.sortBy,
                            sortHow = list.sortHow,
                        ),
                        page,
                    )
                }
                if (selected?.traktListId != list.traktListId) return@launch
                val merged = result.flatMap { it.items }
                items = if (page == 1) merged else (items + merged).distinctBy { "${it.type}:${it.id}" }
                nextPage = result.mapNotNull { it.nextSkip }.maxOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                itemsError = error.message ?: "Could not load this list."
            } finally {
                itemsLoading = false
            }
        }
    }
}

@Composable
internal fun rememberDiscoverListsState(): DiscoverListsState {
    val scope = rememberCoroutineScope()
    return remember { DiscoverListsState(scope) }
}

internal fun LazyListScope.libraryDiscoverListsContent(
    state: DiscoverListsState,
    columns: Int,
    onPosterClick: ((MetaPreview) -> Unit)?,
    onPosterLongClick: ((MetaPreview) -> Unit)?,
) {
    item(key = "discover-lists-header") {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val sources = listOf(
                NuvioDropdownOption("trakt", if (state.configured) "Trakt" else "Trakt · Not set up yet"),
                NuvioDropdownOption("simkl", "Simkl · No public lists"),
                NuvioDropdownOption("mdblist", "MDBList · Not set up yet"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NuvioDropdownChip(
                    title = "Service",
                    label = "Trakt",
                    selectedKey = "trakt",
                    options = sources,
                    onSelected = { },
                )
                NuvioDropdownChip(
                    title = "Kind of list",
                    label = state.kind.label,
                    selectedKey = state.kind.name,
                    options = DiscoverListKind.entries.map { NuvioDropdownOption(it.name, it.label) },
                    enabled = state.configured,
                    onSelected = { option ->
                        DiscoverListKind.entries.firstOrNull { it.name == option.key }?.let(state::selectKind)
                    },
                )
            }
            NuvioDropdownChip(
                title = "${state.kind.label} (${state.lists.size})",
                label = state.selected?.title ?: "Choose a list",
                selectedKey = state.selected?.traktListId?.toString(),
                options = state.lists.map { list ->
                    NuvioDropdownOption(
                        list.traktListId.toString(),
                        if (list.subtitle.isBlank()) list.title else "${list.title} · ${list.subtitle}",
                    )
                },
                enabled = state.configured && state.lists.isNotEmpty(),
                onSelected = { option ->
                    state.lists.firstOrNull { it.traktListId.toString() == option.key }?.let(state::select)
                },
            )
        }
    }

    when {
        !state.configured -> item(key = "discover-not-set-up") {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = "Not set up yet",
                message = "Popular lists need this build's Trakt keys, which have not been added yet.",
            )
        }

        state.listsLoading && state.lists.isEmpty() -> item(key = "discover-lists-skeleton") {
            HomeSkeletonRow(horizontalPadding = 16.dp)
        }

        state.listsError != null && state.lists.isEmpty() -> item(key = "discover-lists-error") {
            HomeEmptyStateCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = "Could not load lists",
                message = state.listsError.orEmpty(),
                actionLabel = "Retry",
                onActionClick = { state.selectKind(state.kind) },
            )
        }

        state.selected == null -> item(key = "discover-pick") {
            Text(
                text = "Pick a list above to see its titles here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        else -> {
            if (state.items.isEmpty() && state.itemsLoading) {
                item(key = "discover-items-skeleton") { HomeSkeletonRow(horizontalPadding = 16.dp) }
            } else if (state.items.isEmpty()) {
                item(key = "discover-items-empty") {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = state.itemsError?.let { "Could not load this list" } ?: "This list is empty",
                        message = state.itemsError ?: "Nothing to show from ${state.selected?.title.orEmpty()}.",
                    )
                }
            } else {
                val rows = state.items.chunked(columns.coerceAtLeast(1))
                items(rows.size, key = { index -> "discover-row:${state.selected?.traktListId}:$index" }) { index ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rows[index].forEach { item ->
                            Box(modifier = Modifier.weight(1f)) {
                                HomePosterCard(
                                    item = item,
                                    onClick = onPosterClick?.let { click -> { click(item) } },
                                    onLongClick = onPosterLongClick?.let { click -> { click(item) } },
                                )
                            }
                        }
                        repeat(columns - rows[index].size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
                if (state.nextPage != null) {
                    item(key = "discover-more") {
                        LaunchedEffectLoadMore(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchedEffectLoadMore(state: DiscoverListsState) {
    androidx.compose.runtime.LaunchedEffect(state.nextPage, state.selected?.traktListId) { state.loadMore() }
    HomeSkeletonRow(horizontalPadding = 16.dp)
}

/** Trakt's public popular and trending list directories (100 lists a page). Needs only the app's Trakt key. */
internal object TraktListDirectory {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean
        get() = TraktConfig.CLIENT_ID.isNotBlank()

    suspend fun lists(kind: String, page: Int): List<TraktPublicListSearchResult> = withContext(Dispatchers.Default) {
        val response = httpRequestRaw(
            method = "GET",
            url = "https://api.trakt.tv/lists/$kind?extended=full&page=$page&limit=100",
            headers = mapOf(
                "Accept" to "application/json",
                "trakt-api-version" to "2",
                "trakt-api-key" to TraktConfig.CLIENT_ID,
            ),
            body = "",
        )
        if (response.status !in 200..299) error("Trakt answered ${response.status}.")
        json.decodeFromString<List<DirectoryEntry>>(response.body).mapNotNull { entry ->
            val list = entry.list ?: return@mapNotNull null
            val id = list.ids?.trakt ?: return@mapNotNull null
            val owner = list.user?.username?.takeIf { it.isNotBlank() }
            val stats = listOfNotNull(
                list.itemCount?.let { "$it titles" },
                (entry.likeCount ?: list.likes)?.let { "$it likes" },
            )
            TraktPublicListSearchResult(
                traktListId = id,
                title = list.name?.takeIf { it.isNotBlank() } ?: "List $id",
                subtitle = (listOfNotNull(owner) + stats).joinToString(" \u2022 "),
                sortBy = list.sortBy,
                sortHow = list.sortHow,
            )
        }
    }

    @Serializable
    private data class DirectoryEntry(
        @SerialName("like_count") val likeCount: Int? = null,
        val list: DirectoryList? = null,
    )

    @Serializable
    private data class DirectoryList(
        val name: String? = null,
        @SerialName("item_count") val itemCount: Int? = null,
        val likes: Int? = null,
        @SerialName("sort_by") val sortBy: String? = null,
        @SerialName("sort_how") val sortHow: String? = null,
        val ids: DirectoryIds? = null,
        val user: DirectoryUser? = null,
    )

    @Serializable
    private data class DirectoryIds(val trakt: Long? = null)

    @Serializable
    private data class DirectoryUser(val username: String? = null)
}
