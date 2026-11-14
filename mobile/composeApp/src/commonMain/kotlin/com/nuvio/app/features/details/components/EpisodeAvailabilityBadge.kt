package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.time.isEpisodeReleaseAired
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.episodes_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun EpisodeAvailabilityBadge(
    video: MetaVideo,
    modifier: Modifier = Modifier,
) {
    val downloads by DownloadsRepository.uiState.collectAsState()
    val isDownloaded = downloads.completedItems.any { it.videoId == video.id && it.isPlayable }
    if (isDownloaded) {
        Text(
            text = "\u2713 DOWNLOADED",
            modifier = modifier
                .background(Color(0xCC1B5E20), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    if (video.available && isEpisodeReleaseAired(video.released) != false) return

    Text(
        text = stringResource(Res.string.episodes_unavailable).uppercase(),
        modifier = modifier
            .background(Color(0xCC5D4037), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
