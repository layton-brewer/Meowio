package com.nuvio.app.features.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadLocationRow
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.DownloadsUiState
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow

/** One poster in the Downloads grid: a movie, or a show with all its downloaded episodes. */
internal data class DownloadPosterEntry(
    val preview: MetaPreview,
    val items: List<DownloadItem>,
    val addedAtEpochMs: Long,
)

internal enum class DownloadSort(val label: String) {
    AddedDesc("Recently added"),
    AddedAsc("Oldest first"),
    TitleAsc("Title A to Z"),
    TitleDesc("Title Z to A"),
}

internal fun buildDownloadPosterEntries(
    state: DownloadsUiState,
    type: String?,
    sort: DownloadSort,
): List<DownloadPosterEntry> {
    val entries = state.completedItems
        .groupBy { item -> if (item.isEpisode) "series:${item.parentMetaId}" else "movie:${item.parentMetaId}" }
        .values
        .map { group ->
            val first = group.first()
            val isShow = first.isEpisode
            DownloadPosterEntry(
                preview = MetaPreview(
                    id = first.parentMetaId,
                    type = if (isShow) "series" else "movie",
                    name = first.title,
                    poster = first.poster,
                    banner = first.background,
                    logo = first.logo,
                    releaseInfo = if (isShow) "${group.size} episode" + if (group.size == 1) "" else "s" else null,
                ),
                items = group,
                addedAtEpochMs = group.maxOf { it.updatedAtEpochMs },
            )
        }
        .filter { type == null || it.preview.type == type }
    return when (sort) {
        DownloadSort.AddedDesc -> entries.sortedByDescending { it.addedAtEpochMs }
        DownloadSort.AddedAsc -> entries.sortedBy { it.addedAtEpochMs }
        DownloadSort.TitleAsc -> entries.sortedBy { it.preview.name.lowercase() }
        DownloadSort.TitleDesc -> entries.sortedByDescending { it.preview.name.lowercase() }
    }
}

@Composable
internal fun LibraryDownloadsControls(
    selectedType: String?,
    sort: DownloadSort,
    onTypeSelected: (String?) -> Unit,
    onSortSelected: (DownloadSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val typeOptions = listOf(
            NuvioDropdownOption(key = "", label = "All types"),
            NuvioDropdownOption(key = "movie", label = localizedMediaTypeLabel("movie")),
            NuvioDropdownOption(key = "series", label = localizedMediaTypeLabel("series")),
        )
        NuvioDropdownChip(
            title = "Type",
            label = selectedType?.let(::localizedMediaTypeLabel) ?: "All types",
            selectedKey = selectedType.orEmpty(),
            options = typeOptions,
            enabled = true,
            onSelected = { option -> onTypeSelected(option.key.ifBlank { null }) },
        )
        NuvioDropdownChip(
            title = "Sort",
            label = sort.label,
            selectedKey = sort.name,
            options = DownloadSort.entries.map { NuvioDropdownOption(key = it.name, label = it.label) },
            enabled = true,
            onSelected = { option ->
                DownloadSort.entries.firstOrNull { it.name == option.key }?.let(onSortSelected)
            },
        )
    }
}

internal fun LazyListScope.libraryDownloadsContent(
    state: DownloadsUiState,
    entries: List<DownloadPosterEntry>,
    selectedType: String?,
    sort: DownloadSort,
    columns: Int,
    onTypeSelected: (String?) -> Unit,
    onSortSelected: (DownloadSort) -> Unit,
    onManageClick: (() -> Unit)?,
    onOpenDownload: ((DownloadItem) -> Unit)?,
    onOpenShow: ((showId: String, title: String) -> Unit)?,
    onLongPress: (DownloadPosterEntry) -> Unit,
) {
    item(key = "downloads-controls") {
        LibraryDownloadsControls(
            selectedType = selectedType,
            sort = sort,
            onTypeSelected = onTypeSelected,
            onSortSelected = onSortSelected,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    item(key = "downloads-summary") {
        val activeCount = state.activeItems.size
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (activeCount > 0) {
                    "$activeCount downloading or waiting · ${state.completedItems.size} saved"
                } else {
                    "${state.completedItems.size} saved for offline viewing"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onManageClick != null) {
                TextButton(onClick = onManageClick) { Text("Manage") }
            }
        }
    }
    item(key = "downloads-location") { DownloadLocationRow() }

    if (entries.isEmpty()) {
        item(key = "downloads-empty") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
                Text(
                    text = "Nothing downloaded yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Press Download on a movie or episode and it will show up here for offline viewing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    items(
        items = entries.chunked(columns),
        key = { row -> "downloads-row:${row.first().preview.type}:${row.first().preview.id}" },
    ) { rowEntries ->
        PosterGridRow(
            items = rowEntries.map { it.preview },
            columns = columns,
            modifier = libraryContentTransitionModifier().padding(horizontal = 16.dp),
            onPosterClick = { preview ->
                val entry = rowEntries.firstOrNull { it.preview.id == preview.id && it.preview.type == preview.type }
                if (entry != null) {
                    if (entry.preview.type == "series") {
                        onOpenShow?.invoke(entry.preview.id, entry.preview.name)
                    } else {
                        entry.items.firstOrNull { it.isPlayable }?.let { onOpenDownload?.invoke(it) }
                    }
                }
            },
            onPosterLongClick = { preview ->
                rowEntries.firstOrNull { it.preview.id == preview.id && it.preview.type == preview.type }
                    ?.let(onLongPress)
            },
        )
    }
}

/** Asks before deleting a downloaded movie, or every downloaded episode of a show. */
@Composable
internal fun DownloadDeleteDialog(entry: DownloadPosterEntry?, onDismiss: () -> Unit) {
    if (entry == null) return
    val isShow = entry.preview.type == "series"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete download?") },
        text = {
            Text(
                if (isShow) {
                    "This removes ${entry.items.size} downloaded episode(s) of ${entry.preview.name} from this device."
                } else {
                    "This removes ${entry.preview.name} from this device."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    entry.items.forEach { DownloadsRepository.cancelDownload(it.id) }
                    onDismiss()
                },
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
