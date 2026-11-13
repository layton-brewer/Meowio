@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.insights.InsightPlay
import com.nuvio.tv.core.insights.InsightWatched
import com.nuvio.tv.core.insights.WatchInsights
import com.nuvio.tv.core.insights.WatchInsightsSummary
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import javax.inject.Inject

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val watchProgress: WatchProgressPreferences,
    private val watchedItems: WatchedItemsPreferences,
    private val library: LibraryPreferences
) : ViewModel() {
    private val mutableSummary = MutableStateFlow<WatchInsightsSummary?>(null)
    val summary: StateFlow<WatchInsightsSummary?> = mutableSummary.asStateFlow()

    fun load() {
        viewModelScope.launch {
            mutableSummary.value = withContext(Dispatchers.Default) {
                runCatching {
                    val now = System.currentTimeMillis()
                    WatchInsights.compute(
                        plays = watchProgress.getAllRawEntries().values.map { entry ->
                            val isEpisode = entry.season != null && entry.episode != null
                            InsightPlay(
                                contentId = entry.contentId,
                                itemKey = itemKey(entry.contentId, entry.season, entry.episode),
                                title = entry.name,
                                isEpisode = isEpisode,
                                positionMs = entry.position,
                                durationMs = entry.duration,
                                completed = entry.isCompleted(),
                                atMs = entry.lastWatched
                            )
                        },
                        watched = watchedItems.getAllItems().map { item ->
                            InsightWatched(
                                contentId = item.contentId,
                                itemKey = itemKey(item.contentId, item.season, item.episode),
                                title = item.title,
                                isEpisode = item.season != null && item.episode != null,
                                atMs = item.watchedAt
                            )
                        },
                        libraryGenres = library.getAllItems().map { it.genres },
                        nowMs = now,
                        utcOffsetMs = TimeZone.getDefault().getOffset(now).toLong()
                    )
                }.getOrDefault(WatchInsightsSummary())
            }
        }
    }

    private fun itemKey(contentId: String, season: Int?, episode: Int?): String =
        if (season != null && episode != null) "$contentId:$season:$episode" else contentId
}

/** The profile's watch statistics, shown from the Profiles settings page. */
@Composable
internal fun InsightsDialog(onDismiss: () -> Unit, viewModel: InsightsViewModel = hiltViewModel()) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.insights_title),
        subtitle = stringResource(R.string.insights_note),
        width = 640.dpValue()
    ) {
        val stats = summary
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            when {
                stats == null -> InsightLine(stringResource(R.string.live_tv_loading), "")
                stats.isEmpty -> InsightLine(stringResource(R.string.insights_empty), "")
                else -> {
                    val (hours, minutes) = WatchInsights.hoursAndMinutes(stats.watchTimeMs)
                    InsightLine(stringResource(R.string.insights_watch_time), stringResource(R.string.insights_watch_time_value, hours, minutes))
                    InsightLine(stringResource(R.string.insights_movies), stats.moviesWatched.toString())
                    InsightLine(stringResource(R.string.insights_episodes), stats.episodesWatched.toString())
                    InsightLine(stringResource(R.string.insights_shows), stats.showsWatched.toString())
                    InsightLine(stringResource(R.string.insights_in_progress), stats.inProgress.toString())
                    InsightLine(stringResource(R.string.insights_streak), stats.streakDays.toString())
                    InsightLine(stringResource(R.string.insights_active_days), stats.activeDaysLast30.toString())
                    stats.busiestWeekday?.let { InsightLine(stringResource(R.string.insights_busiest_day), stringResource(WEEKDAYS[it])) }
                    InsightLine(stringResource(R.string.insights_library), stats.libraryTitles.toString())
                    if (stats.topShows.isNotEmpty()) {
                        InsightHeading(stringResource(R.string.insights_top_shows))
                        stats.topShows.forEach { InsightLine(it.title, stringResource(R.string.insights_episode_count, it.episodes)) }
                    }
                    if (stats.topGenres.isNotEmpty()) {
                        InsightHeading(stringResource(R.string.insights_top_genres))
                        stats.topGenres.forEach { InsightLine(it.name, it.titles.toString()) }
                    }
                }
            }
        }
        Button(onClick = onDismiss) { Text(stringResource(R.string.insights_close)) }
    }
}

private fun Int.dpValue() = androidx.compose.ui.unit.Dp(this.toFloat())

@Composable
private fun InsightHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = NuvioTheme.colors.TextPrimary
    )
}

@Composable
private fun InsightLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary)
    }
}

private val WEEKDAYS = listOf(
    R.string.insights_day_0, R.string.insights_day_1, R.string.insights_day_2, R.string.insights_day_3,
    R.string.insights_day_4, R.string.insights_day_5, R.string.insights_day_6
)
