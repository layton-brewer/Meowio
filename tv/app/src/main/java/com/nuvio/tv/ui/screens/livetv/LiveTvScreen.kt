@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.core.livetv.LiveChannel
import com.nuvio.tv.core.livetv.LiveSource
import com.nuvio.tv.core.livetv.LiveSourceType
import com.nuvio.tv.core.livetv.LiveTvController
import com.nuvio.tv.core.livetv.LiveTvError
import com.nuvio.tv.core.livetv.LiveTvSelection
import com.nuvio.tv.core.livetv.LiveTvState
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val GRID_COLUMNS = 3
private const val GUIDE_REQUEST_DELAY_MS = 450L

@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onPlayChannel: (LiveChannel) -> Unit
) {
    val controller = viewModel.controller
    val state by controller.state.collectAsState()
    var editingSource by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.ensureChannels() }
    BackHandler(enabled = editingSource && state.source != null) { editingSource = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                top = NuvioTheme.spacing.xl
            )
    ) {
        Text(
            text = stringResource(R.string.nav_live_tv),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        when {
            !state.ready -> CenteredLoading()
            state.source == null || editingSource -> SourceForm(
                current = state.source.takeUnless { state.usingDefault },
                canCancel = state.source != null,
                onSave = { source ->
                    editingSource = false
                    controller.setSource(source)
                },
                onCancel = { editingSource = false },
                onRemove = {
                    editingSource = false
                    controller.removeSource()
                }
            )
            else -> ChannelBrowser(
                state = state,
                controller = controller,
                onEditSource = { editingSource = true },
                onPlayChannel = { channel ->
                    controller.markWatched(channel.id)
                    onPlayChannel(channel)
                }
            )
        }
    }
}

@Composable
private fun CenteredLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LoadingIndicator()
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
            Text(
                text = stringResource(R.string.live_tv_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}

@Composable
private fun SourceForm(
    current: LiveSource?,
    canCancel: Boolean,
    onSave: (LiveSource) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    var type by rememberSaveable { mutableStateOf(current?.type ?: LiveSourceType.M3U) }
    var url by rememberSaveable { mutableStateOf(current?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(current?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(current?.password.orEmpty()) }
    var guideUrl by rememberSaveable { mutableStateOf(current?.guideUrl.orEmpty()) }
    val canSave = url.isNotBlank() &&
        (type == LiveSourceType.M3U || (username.isNotBlank() && password.isNotBlank()))

    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(
            text = stringResource(R.string.live_tv_setup_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(R.string.live_tv_setup_body, stringResource(R.string.app_name)),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            PillButton(
                label = stringResource(R.string.live_tv_type_m3u),
                selected = type == LiveSourceType.M3U,
                onClick = { type = LiveSourceType.M3U }
            )
            PillButton(
                label = stringResource(R.string.live_tv_type_xtream),
                selected = type == LiveSourceType.XTREAM,
                onClick = { type = LiveSourceType.XTREAM }
            )
        }
        InputField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(
                if (type == LiveSourceType.M3U) R.string.live_tv_field_playlist else R.string.live_tv_field_server
            ),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        )
        if (type == LiveSourceType.XTREAM) {
            InputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.live_tv_field_username)
            )
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.live_tv_field_password),
                isPassword = true
            )
        }
        InputField(
            value = guideUrl,
            onValueChange = { guideUrl = it },
            placeholder = stringResource(R.string.live_tv_field_guide),
            keyboardType = KeyboardType.Uri
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            PillButton(
                label = stringResource(R.string.live_tv_save),
                selected = true,
                enabled = canSave,
                onClick = { onSave(LiveSource(type, url, username, password, guideUrl)) }
            )
            if (canCancel) {
                PillButton(label = stringResource(R.string.live_tv_cancel), selected = false, onClick = onCancel)
            }
            if (current != null) {
                PillButton(label = stringResource(R.string.live_tv_remove), selected = false, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun ChannelBrowser(
    state: LiveTvState,
    controller: LiveTvController,
    onEditSource: () -> Unit,
    onPlayChannel: (LiveChannel) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(LiveTvSelection.ALL) }
    val channels = remember(state.channels, state.favorites, state.recent, query, selection) {
        LiveTvController.visibleChannels(state, query, selection)
    }
    val askProviderForGuide = state.source?.type == LiveSourceType.XTREAM
    LaunchedEffect(selection, state.channels) {
        val group = selection
        if (group != LiveTvSelection.ALL && group != LiveTvSelection.FAVORITES && group != LiveTvSelection.RECENT) {
            controller.requestGroupGuide(group)
        }
    }
    var firstGroupChosen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.groups, state.usingDefault) {
        if (firstGroupChosen || state.groups.isEmpty()) return@LaunchedEffect
        firstGroupChosen = true
        LiveTvController.preferredGroup(state, java.util.Locale.getDefault().country)?.let { selection = it }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            InputField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.live_tv_search),
                modifier = Modifier.weight(1f)
            )
            PillButton(label = stringResource(R.string.live_tv_refresh), selected = false, onClick = controller::refresh)
            PillButton(label = stringResource(R.string.live_tv_source), selected = false, onClick = onEditSource)
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            item(key = "all") {
                PillButton(
                    label = stringResource(R.string.live_tv_all),
                    selected = selection == LiveTvSelection.ALL,
                    onClick = { selection = LiveTvSelection.ALL }
                )
            }
            item(key = "favorites") {
                PillButton(
                    label = stringResource(R.string.live_tv_favorites),
                    selected = selection == LiveTvSelection.FAVORITES,
                    onClick = { selection = LiveTvSelection.FAVORITES }
                )
            }
            if (state.recent.isNotEmpty()) {
                item(key = "recent") {
                    PillButton(
                        label = stringResource(R.string.live_tv_recent),
                        selected = selection == LiveTvSelection.RECENT,
                        onClick = { selection = LiveTvSelection.RECENT }
                    )
                }
            }
            items(state.groups, key = { "group:$it" }) { group ->
                PillButton(label = group, selected = selection == group, onClick = { selection = group })
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        when {
            state.loading -> CenteredLoading()
            state.error != null -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(errorMessage(state.error)),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                PillButton(label = stringResource(R.string.live_tv_retry), selected = true, onClick = controller::refresh)
            }
            channels.isEmpty() -> EmptyScreenState(
                title = stringResource(R.string.live_tv_empty),
                icon = Icons.Default.LiveTv
            )
            else -> {
                Text(
                    text = stringResource(R.string.live_tv_favorite_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = NuvioTheme.spacing.xs,
                        bottom = NuvioTheme.spacing.xxl,
                        start = NuvioTheme.spacing.xs,
                        end = NuvioTheme.spacing.xs
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    items(channels, key = LiveChannel::id) { channel ->
                        if (askProviderForGuide) {
                            // Wait a moment so fast scrolling does not ask about every channel passed.
                            LaunchedEffect(channel.id) {
                                delay(GUIDE_REQUEST_DELAY_MS)
                                controller.requestChannelGuide(channel)
                            }
                        }
                        ChannelCard(
                            channel = channel,
                            favorite = channel.id in state.favorites,
                            nowTitle = LiveTvController.nowTitle(state, channel),
                            nextTitle = LiveTvController.nextTitle(state, channel),
                            onClick = { onPlayChannel(channel) },
                            onLongClick = { controller.toggleFavorite(channel.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    channel: LiveChannel,
    favorite: Boolean,
    nowTitle: String?,
    nextTitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1.03f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(channel)
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = nowTitle ?: channel.group.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (nextTitle != null) {
                    Text(
                        text = stringResource(R.string.live_tv_next, nextTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (favorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = stringResource(R.string.live_tv_favorites),
                    tint = NuvioTheme.colors.Secondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelLogo(channel: LiveChannel) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(NuvioTheme.colors.BackgroundElevated),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = channel.name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        if (!channel.logo.isNullOrBlank()) {
            val request = remember(context, channel.logo) {
                ImageRequest.Builder(context).data(channel.logo).crossfade(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .background(NuvioTheme.colors.BackgroundElevated),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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

private fun errorMessage(error: LiveTvError?): Int = when (error) {
    LiveTvError.INVALID_SOURCE -> R.string.live_tv_error_invalid
    LiveTvError.NOT_A_PLAYLIST -> R.string.live_tv_error_not_playlist
    LiveTvError.LOGIN_FAILED -> R.string.live_tv_error_login
    LiveTvError.NO_CHANNELS -> R.string.live_tv_error_no_channels
    LiveTvError.NETWORK, null -> R.string.live_tv_error_network
}
