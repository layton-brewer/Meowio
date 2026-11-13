package com.nuvio.app.features.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.PlatformBackHandler
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.live_tv_all
import nuvio.composeapp.generated.resources.live_tv_cancel
import nuvio.composeapp.generated.resources.live_tv_empty
import nuvio.composeapp.generated.resources.live_tv_error_invalid
import nuvio.composeapp.generated.resources.live_tv_error_login
import nuvio.composeapp.generated.resources.live_tv_error_network
import nuvio.composeapp.generated.resources.live_tv_error_no_channels
import nuvio.composeapp.generated.resources.live_tv_error_not_playlist
import nuvio.composeapp.generated.resources.live_tv_favorites
import nuvio.composeapp.generated.resources.live_tv_field_guide
import nuvio.composeapp.generated.resources.live_tv_field_password
import nuvio.composeapp.generated.resources.live_tv_field_playlist
import nuvio.composeapp.generated.resources.live_tv_field_server
import nuvio.composeapp.generated.resources.live_tv_field_username
import nuvio.composeapp.generated.resources.live_tv_loading
import nuvio.composeapp.generated.resources.live_tv_next
import nuvio.composeapp.generated.resources.live_tv_recent
import nuvio.composeapp.generated.resources.live_tv_refresh
import nuvio.composeapp.generated.resources.live_tv_remove
import nuvio.composeapp.generated.resources.live_tv_retry
import nuvio.composeapp.generated.resources.live_tv_save
import nuvio.composeapp.generated.resources.live_tv_search
import nuvio.composeapp.generated.resources.live_tv_setup_body
import nuvio.composeapp.generated.resources.live_tv_setup_title
import nuvio.composeapp.generated.resources.live_tv_source
import nuvio.composeapp.generated.resources.live_tv_type_m3u
import nuvio.composeapp.generated.resources.live_tv_type_xtream
import nuvio.composeapp.generated.resources.nav_live_tv
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val GUIDE_REQUEST_DELAY_MS = 450L

@Composable
fun LiveTvScreen(
    modifier: Modifier = Modifier,
    onPlayChannel: (LiveChannel) -> Unit,
) {
    val controller = LiveTvRepository.controller
    val state by controller.state.collectAsStateWithLifecycle()
    var editingSource by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selection by rememberSaveable { mutableStateOf(LiveTvSelection.ALL) }

    LaunchedEffect(controller) { controller.ensureChannels() }
    PlatformBackHandler(enabled = editingSource && state.source != null) { editingSource = false }

    val showForm = state.ready && (state.source == null || editingSource)
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
        LiveTvController.preferredGroup(state, liveTvCountryCode())?.let { selection = it }
    }

    NuvioScreen(modifier = modifier) {
        item(key = "header") {
            NuvioScreenHeader(
                title = stringResource(Res.string.nav_live_tv),
                includeStatusBarPadding = false,
            ) {
                if (!showForm && state.source != null) {
                    IconButton(onClick = controller::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.live_tv_refresh))
                    }
                    IconButton(onClick = { editingSource = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(Res.string.live_tv_source))
                    }
                }
            }
        }
        when {
            !state.ready -> item(key = "starting") { LoadingRow() }
            showForm -> item(key = "form") {
                SourceForm(
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
                    },
                )
            }
            else -> {
                item(key = "search") {
                    NuvioInputField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(Res.string.live_tv_search),
                    )
                }
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "all") {
                            Chip(
                                label = stringResource(Res.string.live_tv_all),
                                selected = selection == LiveTvSelection.ALL,
                                onClick = { selection = LiveTvSelection.ALL },
                            )
                        }
                        item(key = "favorites") {
                            Chip(
                                label = stringResource(Res.string.live_tv_favorites),
                                selected = selection == LiveTvSelection.FAVORITES,
                                onClick = { selection = LiveTvSelection.FAVORITES },
                            )
                        }
                        if (state.recent.isNotEmpty()) {
                            item(key = "recent") {
                                Chip(
                                    label = stringResource(Res.string.live_tv_recent),
                                    selected = selection == LiveTvSelection.RECENT,
                                    onClick = { selection = LiveTvSelection.RECENT },
                                )
                            }
                        }
                        items(state.groups, key = { "group:$it" }) { group ->
                            Chip(label = group, selected = selection == group, onClick = { selection = group })
                        }
                    }
                }
                when {
                    state.loading -> item(key = "loading") { LoadingRow() }
                    state.error != null -> item(key = "error") {
                        NuvioSurfaceCard {
                            Text(
                                text = stringResource(errorMessage(state.error)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            NuvioPrimaryButton(
                                text = stringResource(Res.string.live_tv_retry),
                                onClick = controller::refresh,
                            )
                        }
                    }
                    channels.isEmpty() -> item(key = "empty") {
                        Text(
                            text = stringResource(Res.string.live_tv_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        )
                    }
                    else -> items(channels, key = { "channel:${it.id}" }) { channel ->
                        if (askProviderForGuide) {
                            // Wait a moment so fast scrolling does not ask about every channel passed.
                            LaunchedEffect(channel.id) {
                                delay(GUIDE_REQUEST_DELAY_MS)
                                controller.requestChannelGuide(channel)
                            }
                        }
                        ChannelRow(
                            channel = channel,
                            favorite = channel.id in state.favorites,
                            nowTitle = LiveTvController.nowTitle(state, channel),
                            nextTitle = LiveTvController.nextTitle(state, channel),
                            onClick = {
                                controller.markWatched(channel.id)
                                onPlayChannel(channel)
                            },
                            onToggleFavorite = { controller.toggleFavorite(channel.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NuvioLoadingIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.live_tv_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceForm(
    current: LiveSource?,
    canCancel: Boolean,
    onSave: (LiveSource) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    var xtream by rememberSaveable { mutableStateOf(current?.type == LiveSourceType.XTREAM) }
    var url by rememberSaveable { mutableStateOf(current?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(current?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(current?.password.orEmpty()) }
    var guideUrl by rememberSaveable { mutableStateOf(current?.guideUrl.orEmpty()) }
    val canSave = url.isNotBlank() && (!xtream || (username.isNotBlank() && password.isNotBlank()))

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.live_tv_setup_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.live_tv_setup_body, stringResource(Res.string.app_brand_name)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                label = stringResource(Res.string.live_tv_type_m3u),
                selected = !xtream,
                onClick = { xtream = false },
            )
            Chip(
                label = stringResource(Res.string.live_tv_type_xtream),
                selected = xtream,
                onClick = { xtream = true },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        NuvioInputField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(
                if (xtream) Res.string.live_tv_field_server else Res.string.live_tv_field_playlist,
            ),
        )
        if (xtream) {
            Spacer(modifier = Modifier.height(12.dp))
            NuvioInputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(Res.string.live_tv_field_username),
            )
            Spacer(modifier = Modifier.height(12.dp))
            NuvioInputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(Res.string.live_tv_field_password),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        NuvioInputField(
            value = guideUrl,
            onValueChange = { guideUrl = it },
            placeholder = stringResource(Res.string.live_tv_field_guide),
        )
        Spacer(modifier = Modifier.height(16.dp))
        NuvioPrimaryButton(
            text = stringResource(Res.string.live_tv_save),
            enabled = canSave,
            onClick = {
                onSave(
                    LiveSource(
                        type = if (xtream) LiveSourceType.XTREAM else LiveSourceType.M3U,
                        url = url,
                        username = username,
                        password = password,
                        guideUrl = guideUrl,
                    ),
                )
            },
        )
        if (canCancel) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.live_tv_cancel)) }
                if (current != null) {
                    TextButton(onClick = onRemove) { Text(stringResource(Res.string.live_tv_remove)) }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    favorite: Boolean,
    nowTitle: String?,
    nextTitle: String?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(channel)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = nowTitle ?: channel.group.orEmpty()
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nextTitle != null) {
                Text(
                    text = stringResource(Res.string.live_tv_next, nextTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(Res.string.live_tv_favorites),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChannelLogo(channel: LiveChannel) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = channel.name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!channel.logo.isNullOrBlank()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun errorMessage(error: LiveTvError?): StringResource = when (error) {
    LiveTvError.INVALID_SOURCE -> Res.string.live_tv_error_invalid
    LiveTvError.NOT_A_PLAYLIST -> Res.string.live_tv_error_not_playlist
    LiveTvError.LOGIN_FAILED -> Res.string.live_tv_error_login
    LiveTvError.NO_CHANNELS -> Res.string.live_tv_error_no_channels
    LiveTvError.NETWORK, null -> Res.string.live_tv_error_network
}
