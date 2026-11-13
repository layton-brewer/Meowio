package com.nuvio.app.features.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.insights_active_days
import nuvio.composeapp.generated.resources.insights_busiest_day
import nuvio.composeapp.generated.resources.insights_close
import nuvio.composeapp.generated.resources.insights_day_0
import nuvio.composeapp.generated.resources.insights_day_1
import nuvio.composeapp.generated.resources.insights_day_2
import nuvio.composeapp.generated.resources.insights_day_3
import nuvio.composeapp.generated.resources.insights_day_4
import nuvio.composeapp.generated.resources.insights_day_5
import nuvio.composeapp.generated.resources.insights_day_6
import nuvio.composeapp.generated.resources.insights_empty
import nuvio.composeapp.generated.resources.insights_episode_count
import nuvio.composeapp.generated.resources.insights_episodes
import nuvio.composeapp.generated.resources.insights_in_progress
import nuvio.composeapp.generated.resources.insights_library
import nuvio.composeapp.generated.resources.insights_movies
import nuvio.composeapp.generated.resources.insights_note
import nuvio.composeapp.generated.resources.insights_shows
import nuvio.composeapp.generated.resources.insights_streak
import nuvio.composeapp.generated.resources.insights_title
import nuvio.composeapp.generated.resources.insights_top_genres
import nuvio.composeapp.generated.resources.insights_top_shows
import nuvio.composeapp.generated.resources.insights_watch_time
import nuvio.composeapp.generated.resources.insights_watch_time_value
import nuvio.composeapp.generated.resources.live_tv_loading
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The device's current offset from UTC, so days are counted in local time. */
internal expect fun insightsUtcOffsetMs(nowMs: Long): Long

@OptIn(ExperimentalTime::class)
private fun loadInsights(): WatchInsightsSummary {
    WatchProgressRepository.ensureLoaded()
    WatchedRepository.ensureLoaded()
    LibraryRepository.ensureLoaded()
    val now = Clock.System.now().toEpochMilliseconds()
    return WatchInsights.compute(
        plays = WatchProgressRepository.uiState.value.entries.map { entry ->
            val isEpisode = entry.seasonNumber != null && entry.episodeNumber != null
            InsightPlay(
                contentId = entry.parentMetaId,
                itemKey = itemKey(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber),
                title = entry.title,
                isEpisode = isEpisode,
                positionMs = entry.lastPositionMs,
                durationMs = entry.durationMs,
                completed = entry.isEffectivelyCompleted,
                atMs = entry.lastUpdatedEpochMs,
            )
        },
        watched = WatchedRepository.uiState.value.items.map { item ->
            InsightWatched(
                contentId = item.id,
                itemKey = itemKey(item.id, item.season, item.episode),
                title = item.name,
                isEpisode = item.season != null && item.episode != null,
                atMs = item.markedAtEpochMs,
            )
        },
        libraryGenres = LibraryRepository.uiState.value.items.map { it.genres },
        nowMs = now,
        utcOffsetMs = insightsUtcOffsetMs(now),
    )
}

private fun itemKey(contentId: String, season: Int?, episode: Int?): String =
    if (season != null && episode != null) "$contentId:$season:$episode" else contentId

/** The profile's watch statistics, opened from the settings home. */
@Composable
internal fun InsightsDialog(onDismiss: () -> Unit) {
    var summary by remember { mutableStateOf<WatchInsightsSummary?>(null) }
    LaunchedEffect(Unit) {
        summary = withContext(Dispatchers.Default) { runCatching { loadInsights() }.getOrDefault(WatchInsightsSummary()) }
    }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.insights_title),
        message = stringResource(Res.string.insights_note),
    ) {
        val stats = summary
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                stats == null -> InsightLine(stringResource(Res.string.live_tv_loading), "")
                stats.isEmpty -> InsightLine(stringResource(Res.string.insights_empty), "")
                else -> {
                    val (hours, minutes) = WatchInsights.hoursAndMinutes(stats.watchTimeMs)
                    InsightLine(
                        stringResource(Res.string.insights_watch_time),
                        stringResource(Res.string.insights_watch_time_value, hours.toInt(), minutes.toInt()),
                    )
                    InsightLine(stringResource(Res.string.insights_movies), stats.moviesWatched.toString())
                    InsightLine(stringResource(Res.string.insights_episodes), stats.episodesWatched.toString())
                    InsightLine(stringResource(Res.string.insights_shows), stats.showsWatched.toString())
                    InsightLine(stringResource(Res.string.insights_in_progress), stats.inProgress.toString())
                    InsightLine(stringResource(Res.string.insights_streak), stats.streakDays.toString())
                    InsightLine(stringResource(Res.string.insights_active_days), stats.activeDaysLast30.toString())
                    stats.busiestWeekday?.let {
                        InsightLine(stringResource(Res.string.insights_busiest_day), stringResource(weekdays[it]))
                    }
                    InsightLine(stringResource(Res.string.insights_library), stats.libraryTitles.toString())
                    if (stats.topShows.isNotEmpty()) {
                        InsightHeading(stringResource(Res.string.insights_top_shows))
                        stats.topShows.forEach {
                            InsightLine(it.title, stringResource(Res.string.insights_episode_count, it.episodes))
                        }
                    }
                    if (stats.topGenres.isNotEmpty()) {
                        InsightHeading(stringResource(Res.string.insights_top_genres))
                        stats.topGenres.forEach { InsightLine(it.name, it.titles.toString()) }
                    }
                }
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(Res.string.insights_close))
        }
    }
}

@Composable
private fun InsightHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun InsightLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private val weekdays = listOf(
    Res.string.insights_day_0, Res.string.insights_day_1, Res.string.insights_day_2, Res.string.insights_day_3,
    Res.string.insights_day_4, Res.string.insights_day_5, Res.string.insights_day_6,
)
