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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Only debrid-resolved and direct HTTP(S) links are downloaded; every other kind of stream is
 * skipped and the next best source is used instead.
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

    /** Asks the app to open the source list for a title so the user can pick a source to download by hand. */
    data class ManualSourceRequest(
        val type: String,
        val videoId: String,
        val parentMetaId: String,
        val parentMetaType: String,
        val title: String,
        val logo: String?,
        val poster: String?,
        val background: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val episodeTitle: String?,
        val episodeThumbnail: String?,
    )

    private val _manualSourceRequests = MutableSharedFlow<ManualSourceRequest>(extraBufferCapacity = 1)
    val manualSourceRequests: SharedFlow<ManualSourceRequest> = _manualSourceRequests.asSharedFlow()

    private fun requestManualSources(spec: Spec) {
        _manualSourceRequests.tryEmit(
            ManualSourceRequest(
                type = spec.meta.type,
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
            ),
        )
    }

    private data class Spec(
        val meta: MetaDetails,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val episodeThumbnail: String?,
        val options: DownloadOptions,
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

    private class Chosen(val stream: StreamItem) {
        val summary: String get() = describeStream(stream)
    }

    /** Downloads a movie (or any title without episodes) with the best available source. */
    fun downloadMovie(meta: MetaDetails, options: DownloadOptions = DownloadPrefs.options()) {
        val spec = Spec(
            meta = meta,
            videoId = meta.id,
            season = null,
            episode = null,
            episodeTitle = null,
            episodeThumbnail = null,
            options = options,
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
            if (outcome != null) {
                toast("Downloading ${meta.name} (${outcome.summary})")
            } else {
                toast("No debrid or direct link found for ${meta.name}. Pick a source from the list.", durationMillis = 4500L)
                requestManualSources(spec)
            }
        }
    }

    /** Downloads a single episode with the best available source. */
    fun downloadEpisode(meta: MetaDetails, video: MetaVideo, options: DownloadOptions = DownloadPrefs.options()) {
        val spec = episodeSpec(meta, video, options)
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
            if (outcome != null) {
                toast("Downloading $label (${outcome.summary})")
            } else {
                toast("No debrid or direct link found for $label. Pick a source from the list.", durationMillis = 4500L)
                requestManualSources(spec)
            }
        }
    }

    /**
     * Downloads every given episode of a season, one after another. Season packs and the same
     * release are preferred so the episodes match. At most three downloads run at once.
     */
    fun downloadSeason(
        meta: MetaDetails,
        season: Int,
        episodes: List<MetaVideo>,
        options: DownloadOptions = DownloadPrefs.options(),
    ) {
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
                    val spec = episodeSpec(meta, video, options)
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
                    if (missing > 0) append(", $missing without a debrid or direct link")
                }
                toast(message, durationMillis = 4500L)
            } finally {
                stateLock.withLock { runningBatches.remove(batchKey) }
            }
        }
    }

    private fun episodeSpec(meta: MetaDetails, video: MetaVideo, options: DownloadOptions): Spec {
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
            options = options,
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
            options = spec.options,
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
        options: DownloadOptions,
    ): List<StreamItem> {
        val debrid = DebridSettingsRepository.snapshot()
        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
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
        )
        val ready = evaluation.readyStreams
        if (ready.isEmpty()) return ready
        return applyQuality(ready, options)
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

/**
 * The download dialog: quality and size limit for everything, plus This episode / Entire season
 * for series. The choices are remembered as the default for next time.
 */
@Composable
fun DownloadChoiceDialog(
    meta: MetaDetails,
    preferredEpisode: MetaVideo?,
    todayIsoDate: String,
    onDismiss: () -> Unit,
) {
    val isSeries = meta.type == "series" || meta.videos.any { it.season != null || it.episode != null }
    val saved = remember { DownloadPrefs.current }
    var quality by remember { mutableStateOf(saved.quality) }
    var maxSizeGb by remember { mutableStateOf(saved.maxSizeGb) }
    var askEachTime by remember { mutableStateOf(saved.askEachTime) }

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

    fun options(): DownloadOptions {
        DownloadPrefs.save(quality, maxSizeGb, askEachTime)
        return DownloadOptions(quality, maxSizeGb)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Meowio picks the best debrid source for your choice and switches if one fails.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipGroup(title = "Quality") {
                    DownloadQuality.entries.forEach { value ->
                        FilterChip(
                            selected = value == quality,
                            onClick = { quality = value },
                            label = { Text(value.label) },
                        )
                    }
                }
                Text(
                    text = if (quality == DownloadQuality.SMALLEST_GOOD) {
                        "Up to 1080p, no remuxes, smaller file preferred."
                    } else {
                        "Picks ${quality.label} when a source has it, otherwise the closest smaller one."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipGroup(title = "Size limit per file") {
                    listOf(0 to "No limit", 2 to "2 GB", 5 to "5 GB", 10 to "10 GB").forEach { (gb, label) ->
                        FilterChip(
                            selected = gb == maxSizeGb,
                            onClick = { maxSizeGb = gb },
                            label = { Text(label) },
                        )
                    }
                }
                if (!isSeries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { askEachTime = !askEachTime },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = !askEachTime, onCheckedChange = { askEachTime = !it })
                        Text(
                            text = "Don't ask again for movies (use these choices)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    DownloadChoiceRow(
                        title = "Download movie",
                        subtitle = "${quality.label}${if (maxSizeGb > 0) " · up to $maxSizeGb GB" else ""}",
                        enabled = true,
                        onClick = {
                            AutoDownloader.downloadMovie(meta, options())
                            onDismiss()
                        },
                    )
                } else {
                    if (seasons.size > 1) {
                        ChipGroup(title = "Season") {
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
                            singleEpisode?.let { AutoDownloader.downloadEpisode(meta, it, options()) }
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
                            AutoDownloader.downloadSeason(meta, season, seasonEpisodes, options())
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChipGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
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

// ---------------------------------------------------------------------------------------------
// Quality choice
// ---------------------------------------------------------------------------------------------

/** What the user wants to store. The default is deliberately not the biggest file. */
enum class DownloadQuality(val label: String, val maxHeight: Int?) {
    /** Up to 1080p, never a remux, and the smaller file when several look equally good. */
    SMALLEST_GOOD("Auto: smallest good", 1080),
    UHD_2160("4K", 2160),
    FHD_1080("1080p", 1080),
    HD_720("720p", 720),
    SD_480("480p", 480),
}

/** [maxSizeGb] of 0 means no size limit. */
data class DownloadOptions(
    val quality: DownloadQuality = DownloadQuality.SMALLEST_GOOD,
    val maxSizeGb: Int = 0,
)

/** The user's saved download choices. When [askEachTime] is off a movie downloads without a dialog. */
object DownloadPrefs {
    data class State(
        val quality: DownloadQuality = DownloadQuality.SMALLEST_GOOD,
        val maxSizeGb: Int = 0,
        val askEachTime: Boolean = true,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(load())
    val state: kotlinx.coroutines.flow.StateFlow<State> = _state

    val current: State get() = _state.value

    fun options(): DownloadOptions = DownloadOptions(current.quality, current.maxSizeGb)

    fun save(quality: DownloadQuality, maxSizeGb: Int, askEachTime: Boolean) {
        val next = State(quality, maxSizeGb, askEachTime)
        _state.value = next
        runCatching { DownloadPrefsStorage.save("${next.quality.name}|${next.maxSizeGb}|${next.askEachTime}") }
    }

    private fun load(): State = runCatching {
        val parts = DownloadPrefsStorage.load()?.split("|") ?: return@runCatching State()
        State(
            quality = DownloadQuality.entries.firstOrNull { it.name == parts.getOrNull(0) }
                ?: DownloadQuality.SMALLEST_GOOD,
            maxSizeGb = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            askEachTime = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: true,
        )
    }.getOrDefault(State())
}

private val REMUX_REGEX = Regex("remux", RegexOption.IGNORE_CASE)
private val SIZE_REGEX = Regex("(\\d+(?:[.,]\\d+)?)\\s?(GB|GiB|MB|MiB)", RegexOption.IGNORE_CASE)

private fun StreamItem.qualityText(): String = listOfNotNull(
    name,
    title,
    description,
    behaviorHints.filename,
    clientResolve?.filename,
).joinToString("\n")

private fun StreamItem.heightOrNull(): Int? =
    com.nuvio.app.features.streams.StreamSmartRanker.parseHeightText(qualityText())

/** File size in bytes when the source reports it (add-on hint or a size printed in its text). */
internal fun StreamItem.reportedSizeBytes(): Long? {
    behaviorHints.videoSize?.takeIf { it > 0L }?.let { return it }
    val match = SIZE_REGEX.find(qualityText()) ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val unit = match.groupValues[2].lowercase()
    val multiplier = if (unit.startsWith("g")) 1_073_741_824.0 else 1_048_576.0
    return (value * multiplier).toLong().takeIf { it > 0L }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1.0) {
        "${(gb * 10).toLong() / 10.0} GB"
    } else {
        "${bytes / 1_048_576} MB"
    }
}

private fun describeStream(stream: StreamItem): String {
    val height = stream.heightOrNull()?.let { if (it >= 2160) "4K" else "${it}p" }
    val size = stream.reportedSizeBytes()?.let(::formatSize)
    return listOfNotNull(height, size).joinToString(" · ").ifBlank { "best available source" }
}

/**
 * Reorders already-ranked candidates for the chosen quality and size limit. Streams that break a
 * limit are dropped while any that fit remain; if none fit, the smallest ones are tried instead of
 * failing outright.
 */
private fun applyQuality(ranked: List<StreamItem>, options: DownloadOptions): List<StreamItem> {
    val maxHeight = options.quality.maxHeight ?: 2160
    val sizeCap = options.maxSizeGb.takeIf { it > 0 }?.let { it * 1_073_741_824L }
    val smart = options.quality == DownloadQuality.SMALLEST_GOOD

    var pool = ranked
    // Remuxes are huge; skip them unless nothing else exists (or 4K was asked for explicitly).
    if (smart || options.quality != DownloadQuality.UHD_2160) {
        pool.filterNot { REMUX_REGEX.containsMatchIn(it.qualityText()) }.takeIf { it.isNotEmpty() }?.let { pool = it }
    } else {
        pool = pool.sortedBy { if (REMUX_REGEX.containsMatchIn(it.qualityText())) 1 else 0 }
    }

    val withinHeight = pool.filter { (it.heightOrNull() ?: 0) <= maxHeight }
    pool = if (withinHeight.isNotEmpty()) {
        withinHeight
    } else {
        pool.sortedBy { it.heightOrNull() ?: Int.MAX_VALUE }
    }

    if (sizeCap != null) {
        val withinSize = pool.filter { (it.reportedSizeBytes() ?: 0L) <= sizeCap }
        pool = if (withinSize.isNotEmpty()) {
            withinSize
        } else {
            pool.sortedBy { it.reportedSizeBytes() ?: Long.MAX_VALUE }
        }
    }

    if (smart && pool.size > 1) {
        // Same resolution as the best pick: prefer the smaller file.
        val bestHeight = pool.first().heightOrNull()
        val (sameHeight, others) = pool.partition { it.heightOrNull() == bestHeight }
        pool = sameHeight.sortedBy { it.reportedSizeBytes() ?: Long.MAX_VALUE } + others
    }
    return pool
}
