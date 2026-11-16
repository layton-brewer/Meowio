package com.nuvio.app.features.zap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Surprise Me": one random movie or series at a time. Tap Another for the next one, open the title to watch it
 * (the normal title page then finds a source and auto-plays), save it, or narrow the pool by
 * type, year, genre, actor and rating. Titles already marked watched are skipped.
 *
 * [onOpenTitle] opens the title page for the given preview, [onClose] leaves Zap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ZapScreen(
    onClose: () -> Unit,
    onOpenTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var filters by remember { mutableStateOf(ZapFilterMemory.filters) }
    var pool by remember { mutableStateOf(ZapPool(filters)) }
    var current by remember { mutableStateOf<ZapTitle?>(null) }
    var empty by remember { mutableStateOf<ZapEmpty?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showFilters by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<ZapTitle>() }
    var saved by remember { mutableStateOf(false) }

    fun zap(fromPool: ZapPool = pool) {
        if (loading && current != null) return
        loading = true
        scope.launch {
            val previous = current
            when (val result = fromPool.next()) {
                is ZapResult.Found -> {
                    if (previous != null) history += previous
                    if (history.size > 30) history.removeAt(0)
                    current = result.title
                    empty = null
                }
                is ZapResult.Empty -> {
                    empty = result.reason
                    if (previous == null) current = null
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loading = false
        zap()
    }
    LaunchedEffect(current?.key) {
        val title = current ?: return@LaunchedEffect
        saved = runCatching { LibraryRepository.isSaved(title.id, title.type) }.getOrDefault(false)
    }

    PlatformBackHandler(enabled = true) {
        if (showFilters) showFilters = false else onClose()
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val background = MaterialTheme.colorScheme.background

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .pointerInput(Unit) {
                var travel = 0f
                detectVerticalDragGestures(
                    onDragStart = { travel = 0f },
                    onDragEnd = { if (travel < -160f) zap() },
                ) { _, dy -> travel += dy }
            },
    ) {
        val title = current
        if (title != null) {
            AsyncImage(
                model = title.backdrop ?: title.poster,
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.30f to Color.Transparent,
                            0.50f to Color.Black.copy(alpha = 0.55f),
                            0.72f to Color.Black.copy(alpha = 0.88f),
                            1f to Color.Black,
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = nuvioSafeBottomPadding(20.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              CompositionLocalProvider(LocalContentColor provides Color.White) {
                Text(
                    text = title.name,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Catalog: ${title.sourceName}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = zapMetaLine(title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (title.genres.isNotEmpty()) {
                    Text(
                        text = title.genres.take(4).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (title.overview.isNotBlank()) {
                    Text(
                        text = title.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (title.cast.isNotEmpty()) {
                    Text(
                        text = "Starring " + title.cast.take(4).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { onOpenTitle(title.toPreview()) }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text("Play")
                    }
                    FilledTonalButton(onClick = { zap() }, enabled = !loading) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text("Another")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching { LibraryRepository.toggleSaved(title.toLibraryItem()) }
                                saved = runCatching { LibraryRepository.isSaved(title.id, title.type) }
                                    .getOrDefault(saved)
                            }
                        },
                    ) {
                        Icon(if (saved) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text(if (saved) "Saved" else "Save")
                    }
                }
                if (history.isNotEmpty()) {
                    Text(
                        text = "Previous title",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable {
                                if (history.isNotEmpty()) current = history.removeAt(history.lastIndex)
                            },
                    )
                }
              }
            }
        }

        // Empty and error states.
        if (title == null && !loading) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = when (empty) {
                        ZapEmpty.NoSources -> "Surprise Me draws from your add-ons' catalogs. Install a catalog add-on in Settings and try again."
                        ZapEmpty.Offline -> "Couldn't load titles. Check your connection."
                        else -> "Nothing matches those filters. Try loosening them."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = {
                        pool = ZapPool(filters)
                        zap(pool)
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 2.dp))
                        Text("Try again")
                    }
                    OutlinedButton(onClick = { showFilters = true }) { Text("Filters") }
                }
            }
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
        if (title != null && empty != null && !loading) {
            Text(
                text = if (empty == ZapEmpty.Offline) "Couldn't load another title." else "That's everything for these filters.",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop + 64.dp),
            )
        }

        // Top bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusTop + 8.dp, start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
            Text(
                text = "Surprise Me",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Chip(
                label = if (filters.activeCount == 0) "Filters" else "Filters (${filters.activeCount})",
                selected = filters.activeCount > 0,
                onClick = { showFilters = true },
            )
        }

        if (showFilters) {
            ZapFilterPanel(
                initial = filters,
                onApply = { next ->
                    showFilters = false
                    if (next != filters) {
                        filters = next
                        ZapFilterMemory.filters = next
                        pool = ZapPool(next)
                        history.clear()
                        empty = null
                        zap(pool)
                    }
                },
                onCancel = { showFilters = false },
            )
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZapFilterPanel(
    initial: ZapFilters,
    onApply: (ZapFilters) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var fromText by remember { mutableStateOf(initial.yearFrom?.toString().orEmpty()) }
    var toText by remember { mutableStateOf(initial.yearTo?.toString().orEmpty()) }
    var actorQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<ZapPerson>>(emptyList()) }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LaunchedEffect(actorQuery) {
        if (actorQuery.trim().length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        suggestions = ZapSearch.people(actorQuery)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) { detectVerticalDragGestures { _, _ -> } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = statusTop + 12.dp, bottom = nuvioSafeBottomPadding(96.dp)),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Surprise Me filters", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            FilterLabel("Show")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ZapMediaType.Both to "Movies & series",
                    ZapMediaType.Movies to "Movies",
                    ZapMediaType.Series to "Series",
                ).forEach { (type, label) ->
                    Chip(label = label, selected = draft.type == type, onClick = { draft = draft.copy(type = type) })
                }
            }

            FilterLabel("Year")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                zapDecades.forEach { (label, range) ->
                    Chip(
                        label = label,
                        selected = draft.yearFrom == range.first && draft.yearTo == range.second,
                        onClick = {
                            draft = draft.copy(yearFrom = range.first, yearTo = range.second)
                            fromText = range.first?.toString().orEmpty()
                            toText = range.second?.toString().orEmpty()
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { text ->
                        val clean = text.filter(Char::isDigit).take(4)
                        fromText = clean
                        draft = draft.copy(yearFrom = clean.toIntOrNull()?.takeIf { clean.length == 4 })
                    },
                    label = { Text("From year") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { text ->
                        val clean = text.filter(Char::isDigit).take(4)
                        toText = clean
                        draft = draft.copy(yearTo = clean.toIntOrNull()?.takeIf { clean.length == 4 })
                    },
                    label = { Text("To year") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            FilterLabel("Genres (titles must match all you pick)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                zapGenres.forEach { genre ->
                    val on = genre.name in draft.genres
                    Chip(
                        label = genre.name,
                        selected = on,
                        onClick = {
                            draft = draft.copy(genres = if (on) draft.genres - genre.name else draft.genres + genre.name)
                        },
                    )
                }
            }

            FilterLabel("Actors (any of the ones you add; matched by your add-ons' search)")
            if (draft.people.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.people.forEach { person ->
                        Chip(
                            label = person.name + "  ✕",
                            selected = true,
                            onClick = { draft = draft.copy(people = draft.people - person) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = actorQuery,
                onValueChange = { actorQuery = it },
                label = { Text("Search for an actor") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val typed = actorQuery.trim()
            if (typed.length >= 2 || suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (typed.length >= 2 && draft.people.none { it.name.equals(typed, ignoreCase = true) }) {
                        Chip(
                            label = "Add \"$typed\"",
                            selected = false,
                            onClick = {
                                draft = draft.copy(people = draft.people + ZapPerson(0, typed))
                                actorQuery = ""
                                suggestions = emptyList()
                            },
                        )
                    }
                    suggestions.filter { s -> draft.people.none { it.name.equals(s.name, ignoreCase = true) } }.forEach { person ->
                        Chip(
                            label = person.name,
                            selected = false,
                            onClick = {
                                draft = draft.copy(people = draft.people + person)
                                actorQuery = ""
                                suggestions = emptyList()
                            },
                        )
                    }
                }
            }

            FilterLabel("Minimum rating")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0f to "Any", 6f to "6+", 7f to "7+", 8f to "8+").forEach { (value, label) ->
                    Chip(label = label, selected = draft.minRating == value, onClick = { draft = draft.copy(minRating = value) })
                }
            }

            FilterLabel("Already watched")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(label = "Hide watched", selected = draft.hideWatched, onClick = { draft = draft.copy(hideWatched = true) })
                Chip(label = "Include watched", selected = !draft.hideWatched, onClick = { draft = draft.copy(hideWatched = false) })
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = nuvioSafeBottomPadding(14.dp)),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { draft = ZapFilters() ; fromText = ""; toText = "" }) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onApply(draft) }) { Text("Apply") }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun zapMetaLine(title: ZapTitle): String = buildList {
    add(if (title.isSeries) "Series" else "Movie")
    title.year?.let(::add)
    title.runtimeMinutes?.let { add(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m") }
    title.seasons?.takeIf { title.isSeries && it > 0 }?.let { add(if (it == 1) "1 season" else "$it seasons") }
    title.rating?.let { add("★ " + (kotlin.math.round(it * 10) / 10.0)) }
}.joinToString("  ·  ")

private fun ZapTitle.toPreview(): MetaPreview = MetaPreview(
    id = id,
    type = type,
    name = name,
    poster = poster,
    banner = backdrop,
    description = overview,
    releaseInfo = year,
    imdbRating = rating?.let { (kotlin.math.round(it * 10) / 10.0).toString() },
    genres = genres,
)

private fun ZapTitle.toLibraryItem(): LibraryItem = LibraryItem(
    id = id,
    type = type,
    name = name,
    poster = poster,
    banner = backdrop,
    description = overview,
    releaseInfo = year,
    imdbRating = rating?.let { (kotlin.math.round(it * 10) / 10.0).toString() },
    genres = genres,
    imdbId = id.takeIf { it.startsWith("tt") },
    savedAtEpochMs = 0L,
)
