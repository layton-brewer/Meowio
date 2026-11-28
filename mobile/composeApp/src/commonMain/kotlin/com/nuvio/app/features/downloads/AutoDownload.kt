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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.streams.PlaceholderVideoDetector
import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
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
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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

    /** Every title this downloader queued, so a final failure can go back to the waiting list. */
    private val managed = mutableMapOf<String, Spec>()
    private val runningBatches = mutableSetOf<String>()
    private var watcherStarted = false

    /** Episodes that had no working source yet; tried again every few minutes. */
    private val waiting = mutableMapOf<String, Waiting>()
    private var retryLoopRunning = false

    /** Last time each running download received bytes, to spot downloads that stopped moving. */
    private val progressMarks = mutableMapOf<String, ProgressMark>()
    private var watchdogStarted = false

    private class ProgressMark(val bytes: Long, val at: TimeMark, val restarts: Int = 0)
    private class Waiting(val spec: Spec, val since: TimeMark, val preferredBingeGroup: String?)

    /**
     * True on Android: every episode of a batch is handed to the system download scheduler right
     * away, while the app is open, because Android only lets an app start a new background download
     * while it is on screen. The scheduler itself runs a few at a time. On other platforms the
     * queue here starts the next download whenever a slot frees up.
     */
    var enqueueAllAtOnce: Boolean = false

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
                toast(
                    "No ready debrid link for $label yet. It will download by itself once one is ready, " +
                        "or pick a source from the list.",
                    durationMillis = 5000L,
                )
                addWaiting(spec, preferredBingeGroup = null)
                requestManualSources(spec)
            }
        }
    }

    /** Downloads every given episode of a season. See [downloadEpisodes]. */
    fun downloadSeason(
        meta: MetaDetails,
        season: Int,
        episodes: List<MetaVideo>,
        options: DownloadOptions = DownloadPrefs.options(),
    ) = downloadEpisodes(meta, episodes.filter { it.season == null || it.season == season }, options)

    /**
     * Downloads the chosen episodes, one after another, each as its own file. A season pack is
     * never downloaded whole: debrid hands out every episode of a pack as a separate file, so for
     * each episode only that one file is resolved and saved, and the Downloads screen groups them
     * back into their season. The same release is preferred within a season so the episodes match,
     * a failed episode switches to the next best source, and at most three downloads run at once.
     */
    fun downloadEpisodes(
        meta: MetaDetails,
        episodes: List<MetaVideo>,
        options: DownloadOptions = DownloadPrefs.options(),
    ) {
        val ordered = episodes
            .distinctBy { "${it.season}|${it.episode}|${it.id}" }
            .sortedWith(compareBy<MetaVideo>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
        if (ordered.isEmpty()) {
            toast("No episodes selected to download")
            return
        }
        val seasons = ordered.mapNotNull { it.season }.distinct()
        val what = when {
            seasons.size == 1 && ordered.size > 1 -> "${seasonName(seasons.single())} (${ordered.size} episodes)"
            ordered.size == 1 -> episodeLabel(ordered.single())
            else -> "${ordered.size} episodes"
        }
        val batchKey = "${meta.id}|batch|" + ordered.joinToString(",") { "${it.season}x${it.episode}" }
        scope.launch {
            val alreadyRunning = stateLock.withLock { !runningBatches.add(batchKey) }
            if (alreadyRunning) {
                toast("$what is already being queued")
                return@launch
            }
            try {
                DownloadsRepository.ensureLoaded()
                ensureWatcher()
                toast("Queuing $what…")
                var queued = 0
                var skipped = 0
                var missing = 0
                val bingeGroups = mutableMapOf<Int?, String>()
                for (video in ordered) {
                    val spec = episodeSpec(meta, video, options)
                    if (hasActiveOrDone(spec.key)) {
                        skipped++
                        continue
                    }
                    if (!enqueueAllAtOnce) waitForFreeSlot()
                    val outcome = downloadOne(spec, preferredBingeGroup = bingeGroups[video.season])
                    if (outcome != null) {
                        queued++
                        outcome.stream.behaviorHints.bingeGroup?.let { group ->
                            bingeGroups.getOrPut(video.season) { group }
                        }
                    } else {
                        missing++
                        addWaiting(spec, preferredBingeGroup = bingeGroups[video.season])
                    }
                }
                val message = buildString {
                    append("$queued episode${if (queued == 1) "" else "s"} downloading")
                    if (skipped > 0) append(", $skipped already saved")
                    if (missing > 0) append(", $missing waiting for a ready debrid link (they start by themselves)")
                }
                toast(message, durationMillis = 4500L)
            } finally {
                stateLock.withLock { runningBatches.remove(batchKey) }
            }
        }
    }

    private fun seasonName(season: Int): String = if (season == 0) "Specials" else "Season $season"

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

    /**
     * Waits until fewer than [MAX_PARALLEL] downloads are actually moving. A download that has not
     * received any bytes for a while does not hold a slot, so one stuck download never stops the
     * rest of the queue (the watchdog restarts it separately).
     */
    private suspend fun waitForFreeSlot() {
        while (activeDownloadCount() >= MAX_PARALLEL) {
            delay(2_000)
        }
    }

    private suspend fun activeDownloadCount(): Int {
        val running = DownloadsRepository.uiState.value.items.filter { it.status == DownloadStatus.Downloading }
        return stateLock.withLock {
            running.count { item ->
                val mark = progressMarks[item.id]
                mark == null || mark.at.elapsedNow().inWholeMilliseconds < SLOT_STALL_MS
            }
        }
    }

    /**
     * Keeps the download queue moving on its own, so it never needs a manual pause and resume:
     *  - a running download that receives no bytes for a few minutes is paused and resumed, which
     *    gets a fresh connection (and a fresh scheduler job on Android), and
     *  - when a download fails with no other source left, the episode is put back in the waiting
     *    list and tried again later.
     * Safe to call more than once; the app calls it at start so downloads from earlier are watched too.
     */
    fun startWatchdog() {
        scope.launch {
            val first = stateLock.withLock { if (watchdogStarted) false else { watchdogStarted = true; true } }
            if (!first) return@launch
            DownloadsRepository.ensureLoaded()
            ensureWatcher()
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                runCatching { checkStalledDownloads() }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.d { "Download watchdog: ${error.message}" }
                }
            }
        }
    }

    private suspend fun checkStalledDownloads() {
        // Android's download jobs already retry a stalled connection themselves, and restarting
        // one from the background is not allowed there.
        if (enqueueAllAtOnce) return
        val items = DownloadsRepository.uiState.value.items
        val running = items.filter { it.status == DownloadStatus.Downloading }
        val toRestart = mutableListOf<String>()
        stateLock.withLock {
            progressMarks.keys.retainAll(running.map { it.id }.toSet())
            for (item in running) {
                val mark = progressMarks[item.id]
                if (mark == null || mark.bytes != item.downloadedBytes) {
                    progressMarks[item.id] = ProgressMark(item.downloadedBytes, TimeSource.Monotonic.markNow(), mark?.restarts ?: 0)
                } else if (mark.at.elapsedNow().inWholeMilliseconds >= RESTART_STALL_MS) {
                    toRestart += item.id
                    progressMarks[item.id] = ProgressMark(item.downloadedBytes, TimeSource.Monotonic.markNow(), mark.restarts + 1)
                }
            }
        }
        for (id in toRestart) {
            log.d { "Download $id stopped receiving data; restarting it" }
            withContext(Dispatchers.Main) {
                DownloadsRepository.pauseDownload(id)
                DownloadsRepository.resumeDownload(id)
            }
        }
        // Downloads paused by the system rather than the user (for example after the network
        // dropped) are left alone: resuming those is the user's choice.
    }

    private suspend fun addWaiting(spec: Spec, preferredBingeGroup: String?) {
        stateLock.withLock {
            if (spec.key !in waiting) waiting[spec.key] = Waiting(spec, TimeSource.Monotonic.markNow(), preferredBingeGroup)
            if (retryLoopRunning) return
            retryLoopRunning = true
        }
        scope.launch { retryWaitingLoop() }
    }

    /** Tries every waiting episode again every few minutes until it downloads or gives up after a day. */
    private suspend fun retryWaitingLoop() {
        try {
            while (true) {
                delay(RETRY_INTERVAL_MS)
                val batch = stateLock.withLock {
                    waiting.values.removeAll { it.since.elapsedNow().inWholeMilliseconds > MAX_WAIT_MS }
                    waiting.values.toList()
                }
                if (batch.isEmpty()) return
                for (entry in batch) {
                    if (hasActiveOrDone(entry.spec.key)) {
                        stateLock.withLock { waiting.remove(entry.spec.key) }
                        continue
                    }
                    if (!enqueueAllAtOnce) waitForFreeSlot()
                    val outcome = runCatching { downloadOne(entry.spec, entry.preferredBingeGroup) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    if (outcome != null) {
                        stateLock.withLock { waiting.remove(entry.spec.key) }
                        log.d { "Waiting download ${entry.spec.key} found a ready source" }
                    }
                }
            }
        } finally {
            stateLock.withLock { retryLoopRunning = false }
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
            sizeOf = fileSizeEstimator(spec),
        )
        if (candidates.isEmpty()) return null
        // First only files that respect the size limit once their real size is known; if none
        // does, the smallest sources are tried rather than failing outright.
        return enqueueFirstWorking(spec, candidates, attempts = 0, strictSize = true)
            ?: enqueueFirstWorking(spec, candidates, attempts = 0, strictSize = false)
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
        sizeOf: (StreamItem) -> Long?,
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
        return applyQuality(ready, options, sizeOf)
    }

    /**
     * Size of the one file that would be saved. For an episode inside a pack this is the episode
     * file's own size when the add-on reports it; when only the pack's total is printed, the total
     * is split over the episodes the pack holds (one season, a season range such as S01-S05, or
     * the whole show), so a huge multi-season pack of small episode files still counts as small.
     */
    private fun fileSizeEstimator(spec: Spec): (StreamItem) -> Long? {
        val season = spec.season
        if (season == null || spec.episode == null) return { it.reportedSizeBytes() }
        val episodesPerSeason = spec.meta.videos
            .filter { (it.season ?: 0) > 0 && it.episode != null }
            .groupBy { it.season!! }
            .mapValues { (_, videos) -> videos.distinctBy { it.episode }.size }
        return { stream ->
            stream.reportedSizeBytes(isEpisode = true) ?: run {
                val total = stream.printedSizeBytes() ?: return@run null
                val seasons = stream.packSeasons(episodesPerSeason.keys) ?: setOf(season)
                val episodes = seasons.sumOf { episodesPerSeason[it] ?: 0 }
                if (episodes > 0) total / episodes else null
            }
        }
    }

    private suspend fun enqueueFirstWorking(
        spec: Spec,
        candidates: List<StreamItem>,
        attempts: Int,
        strictSize: Boolean = false,
    ): Chosen? {
        for ((index, candidate) in candidates.withIndex()) {
            val playable = playableStream(candidate, spec) ?: continue
            val rejected = rejectReason(playable, spec, strictSize)
            if (rejected != null) {
                log.d { "Skipping a source for ${spec.key}: $rejected" }
                continue
            }
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
                stateLock.withLock { managed[spec.key] = spec }
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
            // Same steps as on the debrid site: add the torrent, pick this title's file from the
            // pack, and get that one file's download link.
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
        if (!url.isDownloadableUrl()) return null
        return checkLink(resolved, url)
    }

    /**
     * Opens the link before it is queued, following the add-on's redirects to the debrid file,
     * the same way the player would. A dead link, a "torrent is downloading" stand-in clip or a
     * tiny file is skipped here so the next source is tried at once instead of saving a clip.
     * The real file name and size are filled in so the episode and size checks see the actual file
     * from the pack. If the check itself cannot finish (slow server), the source is kept.
     */
    private suspend fun checkLink(stream: StreamItem, url: String): StreamItem? {
        if (PlaceholderVideoDetector.isPlaceholderUrl(url)) return null
        val response = try {
            withTimeoutOrNull(LINK_CHECK_TIMEOUT_MS) {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = stream.behaviorHints.proxyHeaders?.request.orEmpty() + ("Range" to "bytes=0-0"),
                    body = "",
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            log.d { "Link check failed for a source: ${error.message}" }
            return null
        } ?: return stream
        if (response.status >= 400) {
            log.d { "Skipping a source: HTTP ${response.status}" }
            return null
        }
        val finalUrl = response.url.ifBlank { url }
        if (PlaceholderVideoDetector.isPlaceholderUrl(finalUrl)) {
            log.d { "Skipping a source: it is a stand-in clip ($finalUrl)" }
            return null
        }
        val contentType = response.headers["content-type"].orEmpty().lowercase()
        if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("mpegurl")) {
            log.d { "Skipping a source: not a video file ($contentType)" }
            return null
        }
        val size = response.headers["content-range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
            ?: response.headers["content-length"]?.trim()?.toLongOrNull()?.takeIf { response.status == 200 }
        if (size != null && size in 1 until MIN_VIDEO_FILE_BYTES) {
            log.d { "Skipping a source: file is only $size bytes, a stand-in clip" }
            return null
        }
        val fileName = response.headers["content-disposition"]?.let(::contentDispositionFileName)
            ?: finalUrl.substringBefore('?').substringAfterLast('/').decodeUrlPart()
                .takeIf { it.contains('.') && it.length > 4 }
        val hints = stream.behaviorHints
        return stream.copy(
            behaviorHints = hints.copy(
                filename = hints.filename?.takeIf { it.isNotBlank() } ?: fileName,
                videoSize = size?.takeIf { it > 0L } ?: hints.videoSize,
            ),
        )
    }

    /**
     * Checks the resolved file before it is downloaded. For an episode this is what keeps a whole
     * season pack (or a zip of it) from ever being saved: only a single video file for exactly
     * that episode is accepted.
     */
    private fun rejectReason(resolved: StreamItem, spec: Spec, strictSize: Boolean): String? {
        val url = resolved.playableDirectUrl ?: return "no direct link"
        val fileName = resolved.behaviorHints.filename?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('?').substringAfterLast('/')
        if (ARCHIVE_REGEX.containsMatchIn(fileName) || ARCHIVE_REGEX.containsMatchIn(url.substringBefore('?'))) {
            return "archive, not a single video file"
        }
        val size = resolved.behaviorHints.videoSize?.takeIf { it > 0L }
        val season = spec.season
        val episode = spec.episode
        if (season != null && episode != null) {
            val tags = EPISODE_TAG_REGEX.findAll(fileName).mapNotNull { match ->
                val s = match.groupValues[1].ifEmpty { match.groupValues[3] }.toIntOrNull()
                val e = match.groupValues[2].ifEmpty { match.groupValues[4] }.toIntOrNull()
                if (s != null && e != null) s to e else null
            }.toList()
            if (tags.isNotEmpty() && tags.none { it.first == season && it.second == episode }) {
                return "file is a different episode ($fileName)"
            }
            if (size != null && size > MAX_EPISODE_FILE_BYTES) {
                return "file is too big to be one episode"
            }
        }
        val cap = spec.options.sizeCapBytes
        if (strictSize && cap != null && size != null && size > cap) {
            return "over the size limit"
        }
        return null
    }

    /** When a download fails, quietly start the next best source for the same title. */
    private fun ensureWatcher() {
        if (watcherStarted) return
        watcherStarted = true
        scope.launch {
            DownloadsRepository.uiState.collect { state ->
                for (item in state.items) {
                    if (item.status != DownloadStatus.Failed) continue
                    val (failover, spec) = stateLock.withLock {
                        failovers.remove(item.logicalContentKey) to managed.remove(item.logicalContentKey)
                    }
                    if (failover == null) {
                        // Every source failed: look again later instead of leaving it failed.
                        if (spec != null) scope.launch { addWaiting(spec, preferredBingeGroup = null) }
                        continue
                    }
                    scope.launch {
                        val chosen = enqueueFirstWorking(failover.spec, failover.remaining, failover.attempts, strictSize = true)
                            ?: enqueueFirstWorking(failover.spec, failover.remaining, failover.attempts, strictSize = false)
                        if (chosen == null) {
                            log.d { "No further sources to try for ${failover.spec.key}; trying again later" }
                            addWaiting(failover.spec, preferredBingeGroup = null)
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

    /** A download with no new bytes for this long no longer holds a queue slot. */
    private const val SLOT_STALL_MS = 90_000L

    /** A download with no new bytes for this long is restarted (pause + resume). */
    private const val RESTART_STALL_MS = 3L * 60_000L
    private const val WATCHDOG_INTERVAL_MS = 20_000L
    private const val RETRY_INTERVAL_MS = 5L * 60_000L
    private const val MAX_WAIT_MS = 24L * 60L * 60_000L
    private const val LINK_CHECK_TIMEOUT_MS = 20_000L

    /** Real movies and episodes are bigger than this; debrid stand-in clips are a few MB. */
    private const val MIN_VIDEO_FILE_BYTES = 30L * 1_048_576L
    private const val MAX_FAILOVERS = 5
    private const val SEARCH_TIMEOUT_MS = 60_000L

    /** No single episode is this big; a file this size is a pack or an archive of one. */
    private const val MAX_EPISODE_FILE_BYTES = 40L * 1_073_741_824L
    private val ARCHIVE_REGEX = Regex("""\.(zip|rar|7z|tar|gz|iso)$""", RegexOption.IGNORE_CASE)
    private val EPISODE_TAG_REGEX = Regex(
        """(?<![a-z0-9])(?:s(\d{1,2})[ ._-]?e(\d{1,3})|(\d{1,2})x(\d{2,3}))(?![0-9])""",
        RegexOption.IGNORE_CASE,
    )
}

private fun contentDispositionFileName(value: String): String? {
    val encoded = Regex("filename\\*=(?:UTF-8'[^']*')?([^;]+)", RegexOption.IGNORE_CASE).find(value)
        ?.groupValues?.get(1)?.trim()?.trim('"')?.decodeUrlPart()
    val plain = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(value)
        ?.groupValues?.get(1)?.trim()
    return (encoded ?: plain)?.takeIf { it.isNotBlank() }
}

/** Decodes %XX escapes (UTF-8) in a URL path part; leaves the text as is if it is malformed. */
private fun String.decodeUrlPart(): String {
    if (!contains('%')) return this
    return runCatching {
        val bytes = ArrayList<Byte>(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c == '%' && i + 2 <= lastIndex) {
                bytes += substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        bytes.toByteArray().decodeToString()
    }.getOrDefault(this)
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
 * The download dialog: quality and size limit for everything, plus a season picker and a
 * tick list of that season's episodes for series (All / None picks the whole season). The choices are remembered as the default for next time.
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
    var maxSizeMb by remember { mutableStateOf(saved.maxSizeMb) }
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
    // Picked episodes, by key, across every season, so several seasons can be queued at once.
    var selected by remember(meta.id) {
        mutableStateOf(setOfNotNull(preferredEpisode?.let(::episodeKey)))
    }
    val downloads by DownloadsRepository.uiState.collectAsState()
    val savedKeys = remember(downloads, meta.id) {
        downloads.items
            .filter { it.parentMetaId.trim() == meta.id.trim() && it.status != DownloadStatus.Failed }
            .mapNotNull { item -> item.seasonNumber?.let { s -> item.episodeNumber?.let { e -> "$s|$e" } } }
            .toSet()
    }
    val allEpisodes = remember(meta, seasons, season, todayIsoDate) {
        (seasons + season).distinct().sorted().flatMap { meta.releasedEpisodesForSeason(seasonNumber = it, todayIsoDate = todayIsoDate) }
    }
    val pickedEpisodes = allEpisodes.filter { episodeKey(it) in selected }

    fun options(): DownloadOptions {
        DownloadPrefs.save(quality, maxSizeMb, askEachTime)
        return DownloadOptions(quality, maxSizeMb)
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
                        "The smallest English 1080p file (no remuxes), then 720p if there is no 1080p."
                    } else {
                        "Picks ${quality.label} when a source has it, otherwise the closest smaller one."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChipGroup(title = "Size per file") {
                    DOWNLOAD_SIZE_CHOICES.forEach { (mb, label) ->
                        FilterChip(
                            selected = mb == maxSizeMb,
                            onClick = { maxSizeMb = mb },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    text = when {
                        maxSizeMb == SIZE_SMALLEST -> "Picks the smallest file that is available."
                        maxSizeMb > 0 -> "Picks files up to ${sizeChoiceLabel(maxSizeMb)}, the smallest first."
                        else -> "Any size."
                    } + if (isSeries) " The size of each episode's own file counts, not the whole pack." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    val seasonKeys = seasonEpisodes.map(::episodeKey)
                    val pickedInSeason = seasonKeys.count { it in selected }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Episodes · $pickedInSeason of ${seasonEpisodes.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { selected = selected + seasonKeys },
                            enabled = seasonEpisodes.isNotEmpty() && pickedInSeason < seasonEpisodes.size,
                        ) { Text("All") }
                        TextButton(
                            onClick = { selected = selected - seasonKeys.toSet() },
                            enabled = pickedInSeason > 0,
                        ) { Text("None") }
                    }
                    if (seasonEpisodes.isEmpty()) {
                        Text(
                            text = "No released episodes in this season yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        seasonEpisodes.forEach { video ->
                            val key = episodeKey(video)
                            val checked = key in selected
                            val alreadySaved = "${video.season}|${video.episode}" in savedKeys
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = if (checked) selected - key else selected + key },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on -> selected = if (on) selected + key else selected - key },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = episodeListLabel(video),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (alreadySaved) {
                                        Text(
                                            text = "Already in Downloads",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = "Each episode is saved as its own file from debrid, never the whole season pack, " +
                            "and grouped by season in Downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val pickedSeasons = pickedEpisodes.mapNotNull { it.season }.distinct()
                    if (pickedSeasons.size > 1) {
                        Text(
                            text = "${pickedEpisodes.size} episodes selected from ${pickedSeasons.size} seasons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isSeries) {
                TextButton(onClick = {
                    AutoDownloader.downloadMovie(meta, options())
                    onDismiss()
                }) { Text("Download movie") }
            } else {
                val pickedSeasons = pickedEpisodes.mapNotNull { it.season }.distinct()
                val wholeSeason = pickedSeasons.size == 1 &&
                    pickedEpisodes.size > 1 &&
                    allEpisodes.count { it.season == pickedSeasons.single() } == pickedEpisodes.size
                TextButton(
                    enabled = pickedEpisodes.isNotEmpty(),
                    onClick = {
                        val opts = options()
                        if (pickedEpisodes.size == 1) {
                            AutoDownloader.downloadEpisode(meta, pickedEpisodes.single(), opts)
                        } else {
                            AutoDownloader.downloadEpisodes(meta, pickedEpisodes, opts)
                        }
                        onDismiss()
                    },
                ) {
                    Text(
                        when {
                            pickedEpisodes.isEmpty() -> "Download"
                            wholeSeason -> "Download ${if (pickedSeasons.single() == 0) "Specials" else "Season ${pickedSeasons.single()}"}"
                            pickedEpisodes.size == 1 -> "Download episode"
                            else -> "Download ${pickedEpisodes.size} episodes"
                        },
                    )
                }
            }
        },
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

private fun episodeKey(video: MetaVideo): String = "${video.season}|${video.episode}|${video.id}"

private fun episodeListLabel(video: MetaVideo): String {
    val prefix = video.episode?.let { "E$it" } ?: "Episode"
    return if (video.title.isNotBlank()) "$prefix · ${video.title}" else prefix
}

// ---------------------------------------------------------------------------------------------
// Quality choice
// ---------------------------------------------------------------------------------------------

/** What the user wants to store. The default is deliberately not the biggest file. */
enum class DownloadQuality(val label: String, val maxHeight: Int?) {
    /** The smallest English 1080p file, never a remux (then 720p, then anything up to 1080p). */
    SMALLEST_GOOD("Auto: smallest good", 1080),
    UHD_2160("4K", 2160),
    FHD_1080("1080p", 1080),
    HD_720("720p", 720),
    SD_480("480p", 480),
}

/** Picks the smallest file available, with no fixed limit. */
const val SIZE_SMALLEST = -1

/** Size per file choices in MB; [SIZE_SMALLEST] is the smallest available, 0 is no limit. */
internal val DOWNLOAD_SIZE_CHOICES = listOf(
    SIZE_SMALLEST to "Smallest",
    100 to "100 MB",
    250 to "250 MB",
    500 to "500 MB",
    1024 to "1 GB",
    2048 to "2 GB",
    0 to "No limit",
)

internal fun sizeChoiceLabel(mb: Int): String = DOWNLOAD_SIZE_CHOICES.firstOrNull { it.first == mb }?.second
    ?: if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

/** [maxSizeMb]: the largest file size in MB, 0 for no limit, [SIZE_SMALLEST] for the smallest available. */
data class DownloadOptions(
    val quality: DownloadQuality = DownloadQuality.SMALLEST_GOOD,
    val maxSizeMb: Int = SIZE_SMALLEST,
) {
    val sizeCapBytes: Long? get() = maxSizeMb.takeIf { it > 0 }?.let { it * 1_048_576L }
    val smallestFirst: Boolean get() = maxSizeMb != 0
}

/** The user's saved download choices. When [askEachTime] is off a movie downloads without a dialog. */
object DownloadPrefs {
    data class State(
        val quality: DownloadQuality = DownloadQuality.SMALLEST_GOOD,
        val maxSizeMb: Int = SIZE_SMALLEST,
        val askEachTime: Boolean = true,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(load())
    val state: kotlinx.coroutines.flow.StateFlow<State> = _state

    val current: State get() = _state.value

    fun options(): DownloadOptions = DownloadOptions(current.quality, current.maxSizeMb)

    fun save(quality: DownloadQuality, maxSizeMb: Int, askEachTime: Boolean) {
        val next = State(quality, maxSizeMb, askEachTime)
        _state.value = next
        runCatching { DownloadPrefsStorage.save("${next.quality.name}|mb:${next.maxSizeMb}|${next.askEachTime}") }
    }

    private fun load(): State = runCatching {
        val parts = DownloadPrefsStorage.load()?.split("|") ?: return@runCatching State()
        State(
            quality = DownloadQuality.entries.firstOrNull { it.name == parts.getOrNull(0) }
                ?: DownloadQuality.SMALLEST_GOOD,
            // Older versions saved whole GB (0 = no limit); the new default is the smallest file.
            maxSizeMb = parts.getOrNull(1)?.let { raw ->
                if (raw.startsWith("mb:")) raw.removePrefix("mb:").toIntOrNull()
                else raw.toIntOrNull()?.let { gb -> if (gb > 0) gb * 1024 else SIZE_SMALLEST }
            } ?: SIZE_SMALLEST,
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

/**
 * File size in bytes when the source reports it (add-on hint or a size printed in its text). For an
 * episode inside a season pack the printed size is usually the whole pack's, so it is not used:
 * the real file size is checked once the episode's own file has been resolved.
 */
internal fun StreamItem.reportedSizeBytes(isEpisode: Boolean = false): Long? {
    behaviorHints.videoSize?.takeIf { it > 0L }?.let { return it }
    clientResolve?.stream?.raw?.size?.takeIf { it > 0L }?.let { return it }
    val text = qualityText()
    if (isEpisode && com.nuvio.app.features.streams.StreamSmartRanker.isSeasonPack(this, text)) return null
    val match = SIZE_REGEX.find(text) ?: return null
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
private fun applyQuality(
    ranked: List<StreamItem>,
    options: DownloadOptions,
    sizeOf: (StreamItem) -> Long?,
): List<StreamItem> {
    val maxHeight = options.quality.maxHeight ?: 2160
    val sizeCap = options.sizeCapBytes
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

    val bySize = compareBy<StreamItem> { sizeOf(it) ?: Long.MAX_VALUE }
    if (sizeCap != null) {
        val withinSize = pool.filter { (sizeOf(it) ?: 0L) <= sizeCap }
        pool = if (withinSize.isNotEmpty()) withinSize else pool.sortedWith(bySize)
    }
    if (pool.size <= 1) return pool

    val english = pool.filter { it.isEnglish() }
    if (options.maxSizeMb == SIZE_SMALLEST) {
        // Smallest available: the smallest English file of watchable quality (480p or better),
        // whatever its resolution. The rest stay behind as failover sources.
        val watchable = english.filter { (it.heightOrNull() ?: 480) >= 480 }.ifEmpty { english }
        val first = watchable.sortedWith(bySize)
        return first + pool.filterNot { it in first }
    }

    if (smart) {
        // Auto: the smallest English 1080p file, then the smallest English 720p, then any English
        // file up to 1080p, then any English file. The rest stay behind as failover sources, in
        // the ranker's order (cached debrid sources first).
        val first = listOf<(StreamItem) -> Boolean>(
            { it.heightOrNull() == 1080 },
            { it.heightOrNull() == 720 },
            { (it.heightOrNull() ?: 0) <= 1080 },
            { true },
        ).firstNotNullOfOrNull { tier -> english.filter(tier).takeIf { it.isNotEmpty() } }
        pool = if (first != null) {
            first.sortedWith(bySize) + english.filterNot { it in first } + pool.filterNot { it in english }
        } else {
            val bestHeight = pool.first().heightOrNull()
            val (sameHeight, others) = pool.partition { it.heightOrNull() == bestHeight }
            sameHeight.sortedWith(bySize) + others
        }
    } else if (sizeCap != null) {
        // A fixed quality with a size limit: smallest first among the best resolution that fits.
        val bestHeight = pool.first().heightOrNull()
        val (sameHeight, others) = pool.partition { it.heightOrNull() == bestHeight }
        pool = sameHeight.sortedWith(bySize) + others
    }
    return pool
}

/** The size printed in the source's text (for a pack this is the whole pack). */
private fun StreamItem.printedSizeBytes(): Long? {
    val match = SIZE_REGEX.find(qualityText()) ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    val unit = match.groupValues[2].lowercase()
    val multiplier = if (unit.startsWith("g")) 1_073_741_824.0 else 1_048_576.0
    return (value * multiplier).toLong().takeIf { it > 0L }
}

private val SEASON_RANGE_REGEX = Regex(
    "(?<![a-z0-9])(?:s|seasons?[ ._]?)(\\d{1,2})[ ._]?(?:-|–|~|to)[ ._]?(?:s|season[ ._]?)?(\\d{1,2})(?![0-9])",
    RegexOption.IGNORE_CASE,
)
private val COMPLETE_SERIES_REGEX = Regex(
    "complete[ ._-]?(?:series|collection|show)|all[ ._-]seasons",
    RegexOption.IGNORE_CASE,
)

/** Seasons a pack holds, from "S01-S05" / "Seasons 1-3" / "Complete Series"; null if it does not say. */
private fun StreamItem.packSeasons(knownSeasons: Set<Int>): Set<Int>? {
    val text = qualityText()
    SEASON_RANGE_REGEX.find(text)?.let { match ->
        val from = match.groupValues[1].toIntOrNull()
        val to = match.groupValues[2].toIntOrNull()
        if (from != null && to != null && to >= from && to - from < 50) return (from..to).toSet()
    }
    if (COMPLETE_SERIES_REGEX.containsMatchIn(text)) return knownSeasons.takeIf { it.isNotEmpty() }
    return null
}

private val ENGLISH_REGEX = Regex(
    """🇬🇧|🇺🇸|(?<![a-z])(?:english|eng)(?![a-z])""",
    RegexOption.IGNORE_CASE,
)
private val FOREIGN_REGEX = Regex(
    "🇮🇹|🇪🇸|🇲🇽|🇫🇷|🇩🇪|🇷🇺|🇵🇹|🇧🇷|🇮🇳|🇯🇵|🇰🇷|🇨🇳|🇹🇷|🇵🇱|🇳🇱|🇺🇦|🇸🇦|🇹🇭|🇻🇳|🇮🇩|" +
        "(?<![a-z])(?:ita|italian|spa|spanish|esp|espanol|español|castellano|latino|french|truefrench|vff|vostfr|" +
        "german|deutsch|ger|rus|russian|hindi|tamil|telugu|malayalam|kannada|bengali|portuguese|pt-br|" +
        "japanese|jpn|korean|chinese|turkish|polish|dutch|ukrainian|arabic|thai|vietnamese|" +
        "hungarian|czech|greek|hebrew|persian)(?![a-z])",
    RegexOption.IGNORE_CASE,
)

/** True unless the source is labelled with another language and not with English. */
private fun StreamItem.isEnglish(): Boolean {
    val text = qualityText()
    if (ENGLISH_REGEX.containsMatchIn(text)) return true
    return !FOREIGN_REGEX.containsMatchIn(text)
}
