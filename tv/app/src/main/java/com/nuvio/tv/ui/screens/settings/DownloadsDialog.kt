@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.downloads.TvDownload
import com.nuvio.tv.core.downloads.TvDownloadManager
import com.nuvio.tv.core.downloads.TvDownloadPlayback
import com.nuvio.tv.core.downloads.TvDownloadStatus
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    val manager: TvDownloadManager,
    val profileManager: ProfileManager
) : ViewModel()

/** Lists what has been saved for offline viewing. Opened from Profiles settings. */
@Composable
internal fun DownloadsDialog(onDismiss: () -> Unit, viewModel: DownloadsViewModel = hiltViewModel()) {
    val all by viewModel.manager.downloads.collectAsStateWithLifecycle()
    val profileId by viewModel.profileManager.activeProfileId.collectAsStateWithLifecycle()
    val downloads = all.filter { it.profileId == profileId }.sortedByDescending { it.addedAtMs }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.downloads_title),
        subtitle = stringResource(R.string.downloads_note),
        width = 760.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (downloads.isEmpty()) {
                Text(
                    text = stringResource(R.string.downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            downloads.forEach { download ->
                DownloadRow(
                    download = download,
                    onPrimary = {
                        when (download.status) {
                            TvDownloadStatus.DONE -> {
                                TvDownloadPlayback.pending.value = download
                                onDismiss()
                            }
                            TvDownloadStatus.PAUSED, TvDownloadStatus.FAILED -> viewModel.manager.resume(download.id)
                            else -> Unit
                        }
                    },
                    onRemove = { viewModel.manager.remove(download.id) }
                )
            }
        }
        Button(onClick = onDismiss) { Text(stringResource(R.string.insights_close)) }
    }
}

@Composable
private fun DownloadRow(download: TvDownload, onPrimary: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    val status = when (download.status) {
        TvDownloadStatus.DONE -> stringResource(R.string.downloads_status_done, size(download.downloadedBytes))
        TvDownloadStatus.QUEUED -> stringResource(R.string.downloads_status_queued)
        TvDownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        TvDownloadStatus.FAILED -> stringResource(R.string.downloads_status_failed)
        TvDownloadStatus.DOWNLOADING -> download.progressPercent
            ?.let { stringResource(R.string.downloads_status_progress, it) }
            ?: stringResource(R.string.downloads_status_bytes, size(download.downloadedBytes))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            onClick = onPrimary,
            modifier = Modifier.weight(1f),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = CardDefaults.border(
                focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
            ),
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = listOf(download.detail, status).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        Button(onClick = onRemove) { Text(stringResource(R.string.downloads_remove)) }
    }
}
