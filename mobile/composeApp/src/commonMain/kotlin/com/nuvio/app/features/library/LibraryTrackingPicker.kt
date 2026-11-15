package com.nuvio.app.features.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveLibrarySourceMode
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.launch

private const val NOT_SET_UP = "Not set up yet"

private fun providerName(id: TrackingProviderId): String = when (id) {
    TrackingProviderId.TRAKT -> "Trakt"
    TrackingProviderId.SIMKL -> "Simkl"
    TrackingProviderId.MDBLIST -> "MDBList"
}

/**
 * Library header controls: pick which connected service's lists are shown here and which
 * connected service is the primary one. Watch history is still scrobbled to every connected
 * service at once; unconnected services stay listed as "Not set up yet".
 */
@Composable
internal fun LibraryTrackingPicker(modifier: Modifier = Modifier) {
    val settings by TrackingSettingsRepository.uiState.collectAsStateWithLifecycle()
    val connected by TrackingProviderRegistry.connectedProviderIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val viewing = effectiveLibrarySourceMode(settings.librarySourceMode) { it in connected }
    val primary = effectiveWatchProgressSource(settings.watchProgressSource) { it in connected }

    val viewingOptions = buildList {
        add(NuvioDropdownOption(LibrarySourceMode.LOCAL.name, "Meowio library"))
        TrackingProviderId.entries.forEach { id ->
            val mode = LibrarySourceMode.entries.firstOrNull { it.name == id.name } ?: return@forEach
            val suffix = if (id in connected) "" else " · $NOT_SET_UP"
            add(NuvioDropdownOption(mode.name, providerName(id) + suffix))
        }
    }
    val primaryOptions = buildList {
        add(NuvioDropdownOption(WatchProgressSource.NUVIO_SYNC.name, "Meowio sync"))
        TrackingProviderId.entries.forEach { id ->
            val source = WatchProgressSource.entries.firstOrNull { it.name == id.name } ?: return@forEach
            val suffix = if (id in connected) "" else " · $NOT_SET_UP"
            add(NuvioDropdownOption(source.name, providerName(id) + suffix))
        }
    }
    fun label(options: List<NuvioDropdownOption>, key: String) =
        options.firstOrNull { it.key == key }?.label?.substringBefore(" · ") ?: key

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Show",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NuvioDropdownChip(
            title = "Library source",
            label = label(viewingOptions, viewing.name),
            selectedKey = viewing.name,
            options = viewingOptions,
            onSelected = { option ->
                val mode = LibrarySourceMode.entries.firstOrNull { it.name == option.key } ?: return@NuvioDropdownChip
                val id = TrackingProviderId.entries.firstOrNull { it.name == mode.name }
                if (id == null || id in connected) TrackingSettingsRepository.setLibrarySourceMode(mode)
            },
        )
        Text(
            text = "Primary",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NuvioDropdownChip(
            title = "Primary service (progress). Watching is tracked on every connected service.",
            label = label(primaryOptions, primary.name),
            selectedKey = primary.name,
            options = primaryOptions,
            onSelected = { option ->
                val source = WatchProgressSource.entries.firstOrNull { it.name == option.key } ?: return@NuvioDropdownChip
                val id = source.providerId
                if (id == null || id in connected) {
                    scope.launch {
                        WatchProgressSourceCoordinator.selectSource(ProfileRepository.activeProfileId, source)
                    }
                }
            },
        )
    }
}
