package com.nuvio.app.features.downloads

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-tap downloads that reuse the app's stream auto-selection: the best usable source is picked
 * for you, and if a download fails the next best source is tried automatically.
 *
 * Direct HTTP(S) files and debrid-resolved links can be downloaded. HLS (.m3u8), DASH (.mpd),
 * magnet and raw torrent streams cannot be saved as a single file, so they are skipped and the next
 * best source is used instead.
 */
object AutoDownloader {
    private val log = Logger.withTag("AutoDownload")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // StreamsRepository is one shared search; only one lookup at a time may use it.
    private val lookupMutex = Mutex()
    private val stateLock = Mutex()
    private val failovers = mutableMapOf<String, Failover>()
    private val runningBatches = mutableSetOf<String>()
    private var watcherStarted = false

    private data class Spec(
        val meta: MetaDetails,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val episodeThumbnail: String?,
    ) {
        val key: String
            get() = if (season != null && episode != null) {
                "${meta.id.trim()}|$season|$episode"
            } else {
                "${meta.id.trim()}|movie"
            }
    }

    private data class Failover(
        val spec: Spec,
        val remaining: List<StreamItem>,
        val attempts: Int,
    )

    private class Chosen(val stream: StreamItem)

    /** Downloads a movie (or any title without episodes) with the best available source. */
    fun downloadMovie(meta: MetaDetails) {
        val spec = Spec(
            meta = meta,
            videoId = meta.id,
            season = null,
            episode = null,
            episodeTitle = null,
            episodeThumbnail = null,
        )
        scope.launch {
            DownloadsRepository.ensureLoaded()
            ensureWatcher()
            if (hasActiveOrDone(spec.key)) {
                toast("${meta.name} is already downloaded or downloading")
                return@launch
            }
            toast("Finding the best source for ${meta.name}…")
            val outcome = downloadOne(spec, preferredBingeGroup = null)
            toast(
                if (outcome != null) {
                    "Downloading ${meta.name}"
                } else {
                    "No downloadable source found for ${meta.name}. Streaming-only sources (HLS or torrent) can't be saved."
                },
            )
        }
    }

    /** Downloads a single episode with the best available source. */
    fun downloadEpisode(meta: MetaDetails, video: MetaVideo) {
        val spec = episodeSpec(meta, video)
        val label = episodeLabel(video)
        scope.launch {
            DownloadsRepository.ensureLoaded()
            ensureWatcher()
            if (hasActiveOrDone(spec.key)) {
                toast("$label is already downloaded or downloading")
                return@launch
            }
            toast("Finding the best source for $label…")
            val outcome = downloadOne(spec, preferredBingeGroup = null)
            toast(
                if (outcome != null) {
                    "Downloading $label"
                } else {
                    "No downloadable source found for $label. Streaming-only sources (HLS or torrent) can't be saved."
                },
            )
        }
    }

    /**
     * Downloads every given episode of a season, one after another. Season packs and the same
     * release are preferred so the episodes match. At most three downloads run at once.
     */
    fun downloadSeason(meta: MetaDetails, season: Int, episodes: List<MetaVideo>) {
        val ordered = episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        if (ordered.isEmpty()) {
            toast("No episodes available to download for season $season")
            return
        }
        val batchKey = "${meta.id}|season|$season"
        scope.launch {
            val alreadyRunning = stateLock.withLock { !runningBatches.add(batchKey) }
            if (alreadyRunning) {
                toast("Season $season is already being queued")
                return@launch
            }
            try {
                DownloadsRepository.ensureLoaded()
                ensureWatcher()
                toast("Queuing season $season (${ordered.size} episodes)…")
                var queued = 0
                var skipped = 0
                var missing = 0
                var bingeGroup: String? = null
                for (video in ordered) {
                    val spec = episodeSpec(meta, video)
                    if (hasActiveOrDone(spec.key)) {
                        skipped++
                        continue
                    }
                    waitForFreeSlot()
                    val outcome = downloadOne(spec, preferredBingeGroup = bingeGroup)
                    if (outcome != null) {
                        queued++
                        if (bingeGroup == null) {
                            bingeGroup = outcome.stream.behaviorHints.bingeGroup
                        }
                    } else {
                        missing++
                    }
                }
                val message = buildString {
                    append("Season $season: $queued episode${if (queued == 1) "" else "s"} downloading")
                    if (skipped > 0) append(", $skipped already saved")
                    if (missing > 0) append(", $missing without a downloadable source")
                }
                toast(message, durationMillis = 4500L)
            } finally {
                stateLock.withLock { runningBatches.remove(batchKey) }
            }
        }
    }

    private fun episodeSpec(meta: MetaDetails, video: MetaVideo): Spec {
        val playbackVideoId = buildPlaybackVideoId(
            parentMetaId = meta.id,
            seasonNumber = video.season,
            episodeNumber = video.episode,
            fallbackVideoId = video.id,
        )
        return Spec(
            meta = meta,
            videoId = video.id.takeIf { it.isNotBlank() } ?: playbackVideoId,
            season = video.season,
            episode = video.episode,
            episodeTitle = video.title.takeIf { it.isNotBlank() },
            episodeThumbnail = video.thumbnail,
        )
    }

    private fun episodeLabel(video: MetaVideo): String {
        val prefix = if (video.season != null && video.episode != null) {
            "S${video.season} E${video.episode}"
        } else {
            "episode"
        }
        return if (video.title.isNotBlank()) "$prefix · ${video.title}" else prefix
    }

    private fun hasActiveOrDone(key: String): Boolean =
        DownloadsRepository.uiState.value.items.any {
            it.logicalContentKey == key && it.status != DownloadStatus.Failed
        }

    private suspend fun waitForFreeSlot() {
        while (
            DownloadsRepository.uiState.value.items.count { it.status == DownloadStatus.Downloading } >= MAX_PARALLEL
        ) {
            delay(2_000)
        }
    }

    /** Finds sources, picks the best one and starts the download. Returns null if nothing worked. */
    private suspend fun downloadOne(spec: Spec, preferredBingeGroup: String?): Chosen? {
        val streams = findStreams(spec)
        if (streams.isEmpty()) return null
        val candidates = rankCandidates(
            streams = streams,
            isSeries = spec.season != null && spec.episode != null,
            preferredBingeGroup = preferredBingeGroup,
        )
        if (candidates.isEmpty()) return null
        return enqueueFirstWorking(spec, candidates, attempts = 0)
    }

    private suspend fun findStreams(spec: Spec): List<StreamItem> = lookupMutex.withLock {
        val type = spec.meta.type
        repeat(3) { attempt ->
            // manualSelection = true keeps the shared search from also starting auto-play.
            StreamsRepository.load(
                type = type,
                videoId = spec.videoId,
                parentMetaId = spec.meta.id,
                season = spec.season,
                episode = spec.episode,
                manualSelection = true,
            )
            val token = StreamsRepository.requestToken(
                type = type,
                videoId = spec.videoId,
                season = spec.season,
                episode = spec.episode,
                manualSelection = true,
            )
            val state = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                StreamsRepository.uiState.first { current ->
                    current.requestToken != token ||
                        (!current.isAnyLoading && current.groups.none { it.isLoading })
                }
            } ?: StreamsRepository.uiState.value
            if (state.requestToken == token) {
                return@withLock state.groups.flatMap { it.streams }
            }
            // Someone else (for example the details page preloading) took over the search; try again.
            log.d { "Stream search for ${spec.videoId} was preempted (attempt ${attempt + 1})" }
            delay(500)
        }
        emptyList()
    }

    private fun rankCandidates(
        streams: List<StreamItem>,
        isSeries: Boolean,
        preferredBingeGroup: String?,
    ): List<StreamItem> {
        val debrid = DebridSettingsRepository.snapshot()
        return StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = streams,
            mode = StreamAutoPlayMode.SMART,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferredBingeGroup != null,
            debridEnabled = debrid.canResolvePlayableLinks,
            activeResolverProviderId = debrid.activeResolverProviderId,
            isSeries = isSeries,
        ).readyStreams
    }

    private suspend fun enqueueFirstWorking(spec: Spec, candidates: List<StreamItem>, attempts: Int): Chosen? {
        for ((index, candidate) in candidates.withIndex()) {
            val playable = playableStream(candidate, spec) ?: continue
            val result = withContext(Dispatchers.Main) {
                DownloadsRepository.enqueueFromStream(
                    contentType = spec.meta.type,
                    videoId = spec.videoId,
                    parentMetaId = spec.meta.id,
                    parentMetaType = spec.meta.type,
                    title = spec.meta.name,
                    logo = spec.meta.logo,
                    poster = spec.meta.poster,
                    background = spec.meta.background,
                    seasonNumber = spec.season,
                    episodeNumber = spec.episode,
                    episodeTitle = spec.episodeTitle,
                    episodeThumbnail = spec.episodeThumbnail,
                    stream = playable,
                )
            }
            if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
                val rest = candidates.drop(index + 1)
                if (rest.isNotEmpty() && attempts < MAX_FAILOVERS) {
                    stateLock.withLock { failovers[spec.key] = Failover(spec, rest, attempts + 1) }
                }
                return Chosen(playable)
            }
        }
        return null
    }

    private suspend fun playableStream(stream: StreamItem, spec: Spec): StreamItem? {
        val resolved = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
            val result = DirectDebridPlaybackResolver.resolveToPlayableStream(
                stream = stream,
                season = spec.season,
                episode = spec.episode,
            )
            (result as? DirectDebridPlayableResult.Success)?.stream
        } else {
            stream
        }
        val url = resolved?.playableDirectUrl ?: return null
        return resolved.takeIf { url.isDownloadableUrl() }
    }

    /** When a download fails, quietly start the next best source for the same title. */
    private fun ensureWatcher() {
        if (watcherStarted) return
        watcherStarted = true
        scope.launch {
            DownloadsRepository.uiState.collect { state ->
                for (item in state.items) {
                    if (item.status != DownloadStatus.Failed) continue
                    val failover = stateLock.withLock { failovers.remove(item.logicalContentKey) } ?: continue
                    scope.launch {
                        val chosen = enqueueFirstWorking(failover.spec, failover.remaining, failover.attempts)
                        if (chosen == null) {
                            log.d { "No further sources to try for ${failover.spec.key}" }
                        }
                    }
                }
            }
        }
    }

    private fun toast(message: String, durationMillis: Long = 3000L) {
        scope.launch(Dispatchers.Main) {
            NuvioToastController.show(message, durationMillis)
        }
    }

    private const val MAX_PARALLEL = 3
    private const val MAX_FAILOVERS = 5
    private const val SEARCH_TIMEOUT_MS = 60_000L
}

private fun String.isDownloadableUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}

/** Set by the details screen; the action row shows a Download button when it is non-null. */
val LocalDownloadAction = compositionLocalOf<(() -> Unit)?> { null }

/** Asks whether to download one episode or the whole season, then starts the downloads. */
@Composable
fun DownloadChoiceDialog(
    meta: MetaDetails,
    preferredEpisode: MetaVideo?,
    todayIsoDate: String,
    onDismiss: () -> Unit,
) {
    val seasons = remember(meta.videos) {
        val all = meta.videos.mapNotNull { it.season }.distinct().sorted()
        val regular = all.filter { it > 0 }
        regular.ifEmpty { all }
    }
    var season by remember(meta.id) {
        mutableStateOf(preferredEpisode?.season ?: seasons.firstOrNull() ?: 0)
    }
    val seasonEpisodes = remember(meta, season, todayIsoDate) {
        meta.releasedEpisodesForSeason(seasonNumber = season, todayIsoDate = todayIsoDate)
            .sortedBy { it.episode ?: Int.MAX_VALUE }
    }
    val singleEpisode = remember(preferredEpisode, seasonEpisodes, season) {
        preferredEpisode?.takeIf { it.season == season } ?: seasonEpisodes.firstOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Meowio picks the best source automatically and switches if one fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (seasons.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        seasons.forEach { value ->
                            FilterChip(
                                selected = value == season,
                                onClick = { season = value },
                                label = { Text(if (value == 0) "Specials" else "Season $value") },
                            )
                        }
                    }
                }
                DownloadChoiceRow(
                    title = "This episode",
                    subtitle = singleEpisode?.let { episodeChoiceLabel(it) } ?: "No episode available",
                    enabled = singleEpisode != null,
                    onClick = {
                        singleEpisode?.let { AutoDownloader.downloadEpisode(meta, it) }
                        onDismiss()
                    },
                )
                DownloadChoiceRow(
                    title = "Entire season",
                    subtitle = if (seasonEpisodes.isEmpty()) {
                        "No released episodes"
                    } else {
                        "${if (season == 0) "Specials" else "Season $season"} · ${seasonEpisodes.size} episode${if (seasonEpisodes.size == 1) "" else "s"}"
                    },
                    enabled = seasonEpisodes.isNotEmpty(),
                    onClick = {
                        AutoDownloader.downloadSeason(meta, season, seasonEpisodes)
                        onDismiss()
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun episodeChoiceLabel(video: MetaVideo): String {
    val prefix = if (video.season != null && video.episode != null) "S${video.season} E${video.episode}" else "Episode"
    return if (video.title.isNotBlank()) "$prefix · ${video.title}" else prefix
}

@Composable
private fun DownloadChoiceRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
