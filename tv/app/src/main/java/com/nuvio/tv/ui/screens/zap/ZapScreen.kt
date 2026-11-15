package com.nuvio.tv.ui.screens.zap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.core.zap.ZapEmpty
import com.nuvio.tv.core.zap.ZapFilters
import com.nuvio.tv.core.zap.ZapMediaType
import com.nuvio.tv.core.zap.ZapPerson
import com.nuvio.tv.core.zap.ZapTitle
import com.nuvio.tv.core.zap.zapDecades
import com.nuvio.tv.core.zap.zapGenres
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * "Surprise Me" on Android TV: one random movie or series at a time. D-pad: Play opens the title and
 * starts auto-play, Zap shows another one, Save adds it to the library, Filters narrows the pool.
 */
@Composable
fun ZapScreen(
    viewModel: ZapViewModel = hiltViewModel(),
    onPlay: (ZapTitle) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }

    BackHandler(enabled = showFilters) { showFilters = false }
    LaunchedEffect(state.title?.key, showFilters) {
        if (!showFilters && state.title != null) runCatching { playFocus.requestFocus() }
    }

    if (showFilters) {
        ZapFilterPanel(
            initial = state.filters,
            suggestions = state.suggestions,
            onSearchPeople = viewModel::searchPeople,
            onClearSuggestions = viewModel::clearSuggestions,
            onApply = { next ->
                showFilters = false
                viewModel.applyFilters(next)
            },
            onCancel = { showFilters = false }
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(NuvioTheme.colors.Background)) {
        val title = state.title
        if (title != null) {
            AsyncImage(
                model = title.backdrop ?: title.poster,
                contentDescription = title.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to NuvioTheme.colors.Background,
                        0.55f to NuvioTheme.colors.Background.copy(alpha = 0.75f),
                        1f to Color.Transparent
                    )
                )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(0.55f)
                    .padding(start = 48.dp, end = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SURPRISE ME",
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.Secondary
                )
                Text(
                    text = title.name,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = metaLine(title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                if (title.genres.isNotEmpty()) {
                    Text(
                        text = title.genres.take(4).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
                if (title.overview.isNotBlank()) {
                    Text(
                        text = title.overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (title.cast.isNotEmpty()) {
                    Text(
                        text = "Starring " + title.cast.take(4).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZapButton("Play", Icons.Default.PlayArrow, true, Modifier.focusRequester(playFocus)) { onPlay(title) }
                    ZapButton("Another", Icons.Default.Shuffle, false, enabled = !state.loading) { viewModel.zap() }
                    ZapButton(if (state.saved) "Saved" else "Save", if (state.saved) Icons.Default.Check else Icons.Default.Add, false) {
                        viewModel.toggleSaved()
                    }
                    ZapButton(
                        if (state.filters.activeCount == 0) "Filters" else "Filters (${state.filters.activeCount})",
                        null,
                        false
                    ) { showFilters = true }
                    if (state.canGoBack) ZapButton("Previous", null, false) { viewModel.previous() }
                }
                if (state.empty != null && !state.loading) {
                    Text(
                        text = if (state.empty == ZapEmpty.OFFLINE) "Couldn't load another title." else "That's everything for these filters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            }
        } else if (!state.loading) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = when (state.empty) {
                        ZapEmpty.NO_SOURCES -> "Surprise Me draws from your add-ons' catalogs. Install a catalog add-on and try again."
                        ZapEmpty.OFFLINE -> "Couldn't load titles. Check your connection."
                        else -> "Nothing matches those filters. Try loosening them."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZapButton("Try again", null, true, Modifier.focusRequester(playFocus)) { viewModel.retry() }
                    ZapButton("Filters", null, false) { showFilters = true }
                }
            }
        }
        if (state.loading) {
            Box(modifier = Modifier.align(Alignment.Center)) { LoadingIndicator() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZapFilterPanel(
    initial: ZapFilters,
    suggestions: List<ZapPerson>,
    onSearchPeople: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    onApply: (ZapFilters) -> Unit,
    onCancel: () -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    var actorQuery by remember { mutableStateOf("") }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    LaunchedEffect(actorQuery) {
        if (actorQuery.trim().length < 2) {
            onClearSuggestions()
        } else {
            delay(400)
            onSearchPeople(actorQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Surprise Me filters", style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)

        Label("Show")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ZapMediaType.Both to "Movies & series", ZapMediaType.Movies to "Movies", ZapMediaType.Series to "Series")
                .forEachIndexed { index, (type, label) ->
                    Pill(label, draft.type == type, if (index == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                        draft = draft.copy(type = type)
                    }
                }
        }

        Label("Year")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            zapDecades.forEach { (label, range) ->
                Pill(label, draft.yearFrom == range.first && draft.yearTo == range.second) {
                    draft = draft.copy(yearFrom = range.first, yearTo = range.second)
                }
            }
        }

        Label("Genres (titles must match all you pick)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            zapGenres.forEach { genre ->
                val on = genre.name in draft.genres
                Pill(genre.name, on) {
                    draft = draft.copy(genres = if (on) draft.genres - genre.name else draft.genres + genre.name)
                }
            }
        }

        Label("Actors (any of the ones you add; matched by your add-ons' search)")
        if (draft.people.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                draft.people.forEach { person ->
                    Pill(person.name + "  ✕", true) { draft = draft.copy(people = draft.people - person) }
                }
            }
        }
        InputField(
            value = actorQuery,
            onValueChange = { actorQuery = it },
            placeholder = "Search for an actor",
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        val typed = actorQuery.trim()
        val visible = suggestions.filter { s -> draft.people.none { it.name.equals(s.name, ignoreCase = true) } }
        if (typed.length >= 2 || visible.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (typed.length >= 2 && draft.people.none { it.name.equals(typed, ignoreCase = true) }) {
                    Pill("Add \"$typed\"", false) {
                        draft = draft.copy(people = draft.people + ZapPerson(0, typed))
                        actorQuery = ""
                        onClearSuggestions()
                    }
                }
                visible.forEach { person ->
                    Pill(person.name, false) {
                        draft = draft.copy(people = draft.people + person)
                        actorQuery = ""
                        onClearSuggestions()
                    }
                }
            }
        }

        Label("Minimum rating")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(0f to "Any", 6f to "6+", 7f to "7+", 8f to "8+").forEach { (value, label) ->
                Pill(label, draft.minRating == value) { draft = draft.copy(minRating = value) }
            }
        }

        Label("Already watched")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Hide watched", draft.hideWatched) { draft = draft.copy(hideWatched = true) }
            Pill("Include watched", !draft.hideWatched) { draft = draft.copy(hideWatched = false) }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Apply", true) { onApply(draft) }
            Pill("Reset", false) { draft = ZapFilters() }
            Pill("Cancel", false) { onCancel() }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = NuvioTheme.colors.TextSecondary)
}

@Composable
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
            contentColor = if (selected) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50))
    ) {
        Text(text = label, maxLines = 1)
    }
}

@Composable
private fun ZapButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
            contentColor = if (primary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50))
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.width(6.dp))
        }
        Text(text = label, maxLines = 1)
    }
}

private fun metaLine(title: ZapTitle): String = buildList {
    add(if (title.isSeries) "Series" else "Movie")
    title.year?.let(::add)
    title.runtimeMinutes?.let { add(if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m") }
    title.seasons?.takeIf { title.isSeries && it > 0 }?.let { add(if (it == 1) "1 season" else "$it seasons") }
    title.rating?.let { add("★ " + (Math.round(it * 10) / 10.0)) }
}.joinToString("  ·  ")
