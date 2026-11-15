package com.nuvio.tv.core.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Where Live TV keeps its settings. Each app stores them per profile, on the device only. */
interface LiveTvStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String?)
}

data class LiveTvState(
    /** True once the saved settings have been read. */
    val ready: Boolean = false,
    /** Whether the Live TV tab is shown in the app's navigation. */
    val tabVisible: Boolean = true,
    /** The first playlist being shown: the user's own, or the first built-in one. Null when there is nothing to show. */
    val source: LiveSource? = null,
    /** Every playlist the user added, in the order they were added, including switched-off ones. */
    val sources: List<LiveSource> = emptyList(),
    /** True while the built-in free channels are shown because the user has not added a source. */
    val usingDefault: Boolean = false,
    val loading: Boolean = false,
    val error: LiveTvError? = null,
    /**
     * Channels left out of All because an earlier channel in the list is the same channel from
     * another source (Court TV on Pluto TV, Samsung TV Plus and Plex). Their groups still list them.
     */
    val copiesInAll: Set<String> = emptySet(),
    /** Guide loads still running. The lists show "Loading guide" while this is above zero. */
    val guideLoads: Int = 0,
    /** Playlists that could not be loaded while others could, by [LiveSource.key]. */
    val failedSources: Map<String, LiveTvError> = emptyMap(),
    /** Account details from Xtream providers, by [LiveSource.key]. */
    val accounts: Map<String, LiveAccountInfo> = emptyMap(),
    val channels: List<LiveChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** Most recently watched first. */
    val recent: List<String> = emptyList(),
    /** What is on now, next and in the hours after, by channel id. */
    val guide: Map<String, LiveNowNext> = emptyMap(),
    /** Built-in channels that did not answer when checked. They are listed last. */
    val offline: Set<String> = emptySet(),
    /** The Live TV VPN. Used for Live TV playback only. */
    val vpn: LiveVpnSettings = LiveVpnSettings(),
    /** Why a channel could not be played the last time it was tried, by channel id. */
    val playbackProblems: Map<String, LivePlaybackProblem> = emptyMap(),
)

/** Which channels the list shows. Anything else is a group name. */
object LiveTvSelection {
    const val ALL = LiveTvFilters.ALL
    const val FAVORITES = "\u0000favorites"
    const val RECENT = "\u0000recent"
}

/**
 * Everything Live TV does apart from drawing the screen: saved playlists, channel list,
 * favorites, recent channels, the programme guide and the Live TV VPN settings. The phone and
 * TV apps share this file.
 */
class LiveTvController(
    private val storage: LiveTvStorage,
    private val loader: LiveTvLoader,
    private val scope: CoroutineScope,
    /**
     * Channels shown until the user adds a source of their own: the free streaming services,
     * Free-TV and iptv-org for the device's country, merged into one list.
     */
    private val defaultSources: List<LiveSource> = LiveTvDefaults.builtInSources(null),
    /**
     * The built-in group listed first: the device country's (USA in the US). The other countries
     * follow in their usual order.
     */
    private val homeGroup: String? = "USA",
    /** Whether a channel's stream answers: true, false, or null when that cannot be told. */
    private val probe: (suspend (LiveChannel) -> Boolean?)? = null,
) {
    private val _state = MutableStateFlow(LiveTvState())
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private var startJob: Job? = null
    private var loadJob: Job? = null
    private var channelsRequested = false
    private val guideRequested = HashSet<String>()
    private val groupGuideRequested = HashSet<String>()
    private val groupGuideTries = HashMap<String, Int>()
    private val groupCheckRequested = HashSet<String>()
    private var playlist: LivePlaylist? = null
    /** Each loaded playlist with its source, for guides that load one group at a time. */
    private var loadedPlaylists: List<Pair<LiveSource, LivePlaylist>> = emptyList()
    /** Every channel, including copies merged away from the list. Backups come from here. */
    private var allChannels: List<LiveChannel> = emptyList()
    /** [LiveTvVariants.key] of each channel in [allChannels], by channel id. */
    private var channelKeys: Map<String, String> = emptyMap()

    /** Reads the saved settings. Safe to call more than once. */
    fun start() {
        if (startJob != null) return
        startJob = scope.launch {
            val saved = LiveTvCodec.decodeSources(storage.read(KEY_SOURCES)).ifEmpty {
                // Older versions kept a single playlist.
                listOfNotNull(LiveTvCodec.decodeSource(storage.read(KEY_SOURCE))?.let(::cleaned))
            }
            val favorites = LiveTvCodec.decodeIds(storage.read(KEY_FAVORITES)).toSet()
            val recent = LiveTvCodec.decodeIds(storage.read(KEY_RECENT)).take(MAX_RECENT)
            val tabVisible = storage.read(KEY_TAB_VISIBLE) != "false"
            val vpn = LiveTvCodec.decodeVpn(storage.read(KEY_VPN))
            _state.update {
                withSources(it, saved).copy(
                    ready = true,
                    tabVisible = tabVisible,
                    favorites = favorites,
                    recent = recent,
                    vpn = vpn,
                )
            }
            if (channelsRequested) loadChannels()
        }
    }

    /** Forgets everything held in memory, for example when the profile changes. */
    fun reset() {
        startJob?.cancel()
        loadJob?.cancel()
        startJob = null
        loadJob = null
        channelsRequested = false
        guideRequested.clear()
        groupGuideRequested.clear()
        groupGuideTries.clear()
        groupCheckRequested.clear()
        playlist = null
        loadedPlaylists = emptyList()
        allChannels = emptyList()
        channelKeys = emptyMap()
        _state.value = LiveTvState()
    }

    /** Loads the channel list the first time the Live TV screen opens. */
    fun ensureChannels() {
        start()
        if (channelsRequested) return
        channelsRequested = true
        if (_state.value.ready) loadChannels()
    }

    fun refresh() {
        channelsRequested = true
        loadChannels()
    }

    /** Replaces every saved playlist with [source]. */
    fun setSource(source: LiveSource) = saveSources(listOf(cleaned(source)))

    /** Adds a playlist, or updates it when the same one is already saved. */
    fun addSource(source: LiveSource) {
        val added = cleaned(source)
        val current = _state.value.sources
        val index = current.indexOfFirst { it.key == added.key }
        saveSources(if (index >= 0) current.toMutableList().also { it[index] = added } else current + added)
    }

    /** Saves [updated] in place of the playlist saved under [key]. */
    fun updateSource(key: String, updated: LiveSource) {
        val current = _state.value.sources
        val index = current.indexOfFirst { it.key == key }
        if (index < 0) {
            addSource(updated)
            return
        }
        // Keep the identifier, so favorites saved for its channels stay attached.
        val cleanedSource = cleaned(updated.copy(id = current[index].key))
        val list = current.toMutableList().also { it[index] = cleanedSource }
        saveSources(list.distinctBy(LiveSource::key))
    }

    fun setSourceEnabled(key: String, enabled: Boolean) {
        saveSources(_state.value.sources.map { if (it.key == key) it.copy(enabled = enabled) else it })
    }

    /** Removes the playlist saved under [key]. */
    fun removeSource(key: String) = saveSources(_state.value.sources.filter { it.key != key })

    /** Drops every playlist of the user's and goes back to the built-in channels, when there are any. */
    fun removeSource() = saveSources(emptyList())

    fun setTabVisible(visible: Boolean) {
        _state.update { it.copy(tabVisible = visible) }
        scope.launch { storage.write(KEY_TAB_VISIBLE, visible.toString()) }
    }

    fun setVpn(settings: LiveVpnSettings) {
        val cleanedSettings = settings.copy(
            profiles = settings.profiles.map { it.copy(config = LiveVpnGuard.normalizeWireGuard(it.config)) },
            proxyUrl = settings.proxyUrl.trim(),
        )
        _state.update { it.copy(vpn = cleanedSettings) }
        scope.launch { storage.write(KEY_VPN, LiveTvCodec.encodeVpn(cleanedSettings)) }
    }

    /** Records how playing [channelId] went: [problem] is why it failed, or null when it played. */
    fun reportPlayback(channelId: String, problem: LivePlaybackProblem?) {
        _state.update {
            if (it.playbackProblems[channelId] == problem) it
            else it.copy(
                playbackProblems = if (problem == null) it.playbackProblems - channelId
                else it.playbackProblems + (channelId to problem)
            )
        }
    }

    fun toggleFavorite(channelId: String) {
        val favorites = _state.value.favorites.let { if (channelId in it) it - channelId else it + channelId }
        _state.update { it.copy(favorites = favorites) }
        scope.launch { storage.write(KEY_FAVORITES, LiveTvCodec.encodeIds(favorites)) }
    }

    /** Remembers [channelId] as the channel watched last. */
    fun markWatched(channelId: String) {
        val recent = (listOf(channelId) + _state.value.recent.filter { it != channelId }).take(MAX_RECENT)
        _state.update { it.copy(recent = recent) }
        scope.launch { storage.write(KEY_RECENT, LiveTvCodec.encodeIds(recent)) }
    }

    /** Other copies of [channel] to fall back on when it does not play, best first. */
    fun alternatesFor(channel: LiveChannel): List<LiveChannel> {
        val state = _state.value
        return LiveTvVariants.alternatesFor(channel, allChannels.ifEmpty { state.channels }, state.offline)
    }

    /**
     * Asks an Xtream provider what is on [channel] now, once per channel. Playlists with a guide
     * file are covered by the guide loaded with the channel list instead.
     */
    fun requestChannelGuide(channel: LiveChannel) {
        // A worldwide list loads its guide one group at a time, also while every channel is shown.
        channel.group?.let(::requestGroupGuide)
        val source = sourceOf(channel) ?: return
        if (source.type != LiveSourceType.XTREAM || channel.xtreamStreamId == null) return
        if (channel.id in _state.value.guide || !guideRequested.add(channel.id)) return
        scope.launch {
            val entry = runCatching { loader.loadChannelGuide(source, channel) }.getOrNull() ?: return@launch
            if (sourceOf(channel) == source) _state.update { it.copy(guide = it.guide + (channel.id to entry)) }
        }
    }

    /**
     * Loads the guide for one group of a worldwide playlist, such as the built-in channels, the
     * first time that group is opened. Other playlists get their whole guide with the channels.
     */
    fun requestGroupGuide(group: String) {
        val merged = playlist ?: return
        val targets = loadedPlaylists.filter { (source, list) ->
            loader.hasGuidePerGroup(source, list) && list.channels.any { it.group == group }
        }
        if (targets.isEmpty() || !groupGuideRequested.add(group)) return
        scope.launch {
            var found = false
            for ((source, list) in targets) {
                val guide = loadGuideTracked {
                    loader.loadGroupGuide(source, list, group) { partial -> publishGuide(merged, partial) }
                }
                if (guide.isNotEmpty()) found = true
                publishGuide(merged, guide)
            }
            if (!found && playlist === merged) {
                // A guide host can be slow or down for a moment: ask again a little later, a few times.
                val tries = (groupGuideTries[group] ?: 0) + 1
                groupGuideTries[group] = tries
                if (tries < MAX_GUIDE_TRIES) {
                    delay(GUIDE_RETRY_MS)
                    if (playlist === merged) {
                        groupGuideRequested.remove(group)
                        requestGroupGuide(group)
                    }
                }
            }
        }
    }

    /** Runs one guide load off the main thread, counted in [LiveTvState.guideLoads]. */
    private suspend fun loadGuideTracked(load: suspend () -> Map<String, LiveNowNext>): Map<String, LiveNowNext> {
        _state.update { it.copy(guideLoads = it.guideLoads + 1) }
        return try {
            withContext(Dispatchers.Default) { runCatching { load() }.getOrDefault(emptyMap()) }
        } finally {
            _state.update { it.copy(guideLoads = maxOf(0, it.guideLoads - 1)) }
        }
    }

    /**
     * Adds [guide] entries to the shown guide, while [merged] is still the playlist on screen.
     * Channels the guide does not know get the programmes of another copy of the same channel,
     * so a channel carried by both Free-TV and Pluto TV shows Pluto TV's guide.
     */
    private fun publishGuide(merged: LivePlaylist, guide: Map<String, LiveNowNext>) {
        if (guide.isEmpty() || playlist !== merged) return
        _state.update { current ->
            val combined = HashMap(current.guide)
            guide.forEach { (id, entry) -> if (id !in combined) combined[id] = entry }
            current.copy(guide = withSharedGuides(combined))
        }
    }

    private fun withSharedGuides(guide: Map<String, LiveNowNext>): Map<String, LiveNowNext> {
        if (channelKeys.isEmpty()) return guide
        val byKey = HashMap<String, LiveNowNext>()
        guide.forEach { (id, entry) -> channelKeys[id]?.takeIf(String::isNotEmpty)?.let { if (it !in byKey) byKey[it] = entry } }
        if (byKey.isEmpty()) return guide
        val result = HashMap(guide)
        channelKeys.forEach { (id, key) ->
            if (id !in result) byKey[key]?.let { result[id] = it }
        }
        return result
    }

    /**
     * Checks which channels of a group answer, the first time that group is opened, so the ones
     * that do not can be listed last. Only the built-in channels are checked: a provider's own
     * list often allows a single connection, and checking it could get the account blocked.
     */
    fun requestGroupCheck(group: String) {
        val check = probe ?: return
        val loaded = playlist ?: return
        if (!_state.value.usingDefault || !groupCheckRequested.add(group)) return
        val channels = loaded.channels.filter { it.group == group }.take(MAX_CHECKED_PER_GROUP)
        if (channels.isEmpty()) return
        scope.launch {
            val gate = Semaphore(CHECK_PARALLELISM)
            channels.map { channel ->
                launch {
                    val answered = gate.withPermit {
                        withContext(Dispatchers.Default) { runCatching { check(channel) }.getOrNull() }
                    }
                    if (answered == false && playlist === loaded) {
                        _state.update { it.copy(offline = it.offline + channel.id) }
                    }
                }
            }
        }
    }

    private fun sourceOf(channel: LiveChannel): LiveSource? {
        val state = _state.value
        if (state.usingDefault) return state.source
        return state.sources.firstOrNull { it.key == channel.sourceId } ?: state.source.takeIf { channel.sourceId.isEmpty() }
    }

    private fun cleaned(source: LiveSource): LiveSource {
        val trimmed = source.copy(
            url = source.url.trim(),
            username = source.username.trim(),
            password = source.password.trim(),
            guideUrl = source.guideUrl.trim(),
            name = source.name.trim(),
        )
        return trimmed.copy(id = trimmed.key)
    }

    private fun withSources(state: LiveTvState, sources: List<LiveSource>): LiveTvState {
        val shown = sources.firstOrNull(LiveSource::enabled)
        val source = shown ?: defaultSources.firstOrNull().takeIf { sources.isEmpty() }
        return state.copy(sources = sources, source = source, usingDefault = sources.isEmpty() && source != null)
    }

    private fun saveSources(sources: List<LiveSource>) {
        loadJob?.cancel()
        playlist = null
        _state.update {
            withSources(it, sources).copy(
                loading = false,
                error = null,
                failedSources = emptyMap(),
                accounts = emptyMap(),
                channels = emptyList(),
                copiesInAll = emptySet(),
                groups = emptyList(),
                guide = emptyMap(),
                offline = emptySet(),
            )
        }
        scope.launch {
            storage.write(KEY_SOURCES, if (sources.isEmpty()) null else LiveTvCodec.encodeSources(sources))
            storage.write(KEY_SOURCE, null)
        }
        if (_state.value.source != null) refresh()
    }

    /** The playlists to load: the user's switched-on ones, or the built-in one. */
    private fun sourcesToLoad(state: LiveTvState): List<LiveSource> =
        if (state.usingDefault) defaultSources else state.sources.filter(LiveSource::enabled)

    private fun loadChannels() {
        val sources = sourcesToLoad(_state.value)
        if (sources.isEmpty()) return
        loadJob?.cancel()
        guideRequested.clear()
        groupGuideRequested.clear()
        groupGuideTries.clear()
        groupCheckRequested.clear()
        playlist = null
        loadedPlaylists = emptyList()
        _state.update { it.copy(loading = true, error = null, failedSources = emptyMap(), guideLoads = 0) }
        loadJob = scope.launch {
            try {
                val results = withContext(Dispatchers.Default) {
                    sources.map { source ->
                        async {
                            try {
                                Result.success(loader.loadPlaylist(source))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }.awaitAll()
                }
                val loaded = ArrayList<Pair<LiveSource, LivePlaylist>>()
                val failed = LinkedHashMap<String, LiveTvError>()
                val seenIds = HashSet<String>()
                results.forEachIndexed { index, result ->
                    val source = sources[index]
                    result.fold(
                        onSuccess = { list ->
                            // The same address or stream id can appear in two playlists.
                            val channels = list.channels.map { channel ->
                                if (seenIds.add(channel.id)) channel else {
                                    val id = "${source.key}|${channel.id}"
                                    seenIds.add(id)
                                    channel.copy(id = id)
                                }
                            }
                            loaded += source to list.copy(channels = channels)
                        },
                        onFailure = { failed[source.key] = (it as? LiveTvException)?.error ?: LiveTvError.NETWORK },
                    )
                }
                if (loaded.isEmpty()) {
                    fail(failed.values.firstOrNull() ?: LiveTvError.NETWORK)
                    return@launch
                }
                val everyChannel = loaded.flatMap { it.second.channels }
                val merged = LivePlaylist(
                    channels = everyChannel,
                    guideUrls = loaded.flatMap { it.second.guideUrls }.distinct(),
                )
                this@LiveTvController.playlist = merged
                loadedPlaylists = loaded
                allChannels = everyChannel
                channelKeys = withContext(Dispatchers.Default) { everyChannel.associate { it.id to LiveTvVariants.key(it.name) } }
                // The built-in sources carry many of the same channels: list each once per group and
                // keep the other copies as backups for when it does not play.
                val shown = if (_state.value.usingDefault) homeFirst(mergedCopies(everyChannel, channelKeys)) else everyChannel
                val groups = LiveTvFilters.groups(shown)
                val copies = if (_state.value.usingDefault) laterCopies(shown, channelKeys) else emptySet()
                val accounts = loaded.mapNotNull { (source, list) -> list.account?.let { source.key to it } }.toMap()
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        // A built-in source that is down is simply left out, without a warning.
                        failedSources = if (it.usingDefault) emptyMap() else failed,
                        accounts = accounts,
                        channels = shown,
                        copiesInAll = copies,
                        groups = groups,
                        guide = emptyMap(),
                        offline = emptySet(),
                    )
                }
                // Every playlist's guide loads at once, and each shows its channels' programmes as
                // soon as its first guide file is read, so a big file never holds up the small ones.
                loaded.forEach { (source, list) ->
                    launch {
                        val guide = loadGuideTracked {
                            loader.loadGuide(source, list) { partial -> publishGuide(merged, partial) }
                        }
                        publishGuide(merged, guide)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveTvException) {
                fail(e.error)
            } catch (e: Exception) {
                fail(LiveTvError.NETWORK)
            }
        }
    }

    /** [channels] with the home country's group moved to the front, keeping every other order. */
    private fun homeFirst(channels: List<LiveChannel>): List<LiveChannel> {
        val home = homeGroup ?: return channels
        val (first, rest) = channels.partition { it.group == home }
        return first + rest
    }

    /** [channels] with later copies of a channel in the same group left out. */
    private fun mergedCopies(channels: List<LiveChannel>, keys: Map<String, String>): List<LiveChannel> {
        val seen = HashSet<String>()
        return channels.filter { channel ->
            val key = keys[channel.id].orEmpty()
            key.isEmpty() || seen.add((channel.group ?: "") + "\u0000" + key)
        }
    }

    /** Ids of channels in [channels] that repeat an earlier channel, whatever their group. */
    private fun laterCopies(channels: List<LiveChannel>, keys: Map<String, String>): Set<String> {
        val seen = HashSet<String>()
        return channels.filter { channel ->
            val key = keys[channel.id].orEmpty()
            key.isNotEmpty() && !seen.add(key)
        }.mapTo(HashSet(), LiveChannel::id)
    }

    private fun fail(error: LiveTvError) {
        _state.update { it.copy(loading = false, error = error, channels = emptyList(), copiesInAll = emptySet(), groups = emptyList(), guide = emptyMap()) }
    }

    companion object {
        const val MAX_RECENT = 12
        private const val MAX_GUIDE_TRIES = 3
        private const val GUIDE_RETRY_MS = 45_000L
        private const val MAX_CHECKED_PER_GROUP = 200
        private const val CHECK_PARALLELISM = 6
        private const val KEY_SOURCE = "source"
        private const val KEY_SOURCES = "sources"
        private const val KEY_VPN = "vpn"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENT = "recent"
        private const val KEY_TAB_VISIBLE = "tab_visible"

        /** The channels to list for a search text and a [LiveTvSelection]. */
        fun visibleChannels(state: LiveTvState, query: String, selection: String): List<LiveChannel> {
            val channels = matchingChannels(state, query, selection)
            if (state.offline.isEmpty()) return channels
            // A stable sort keeps the playlist order within the working and the silent channels.
            return channels.sortedBy { it.id in state.offline }
        }

        fun isOffline(state: LiveTvState, channel: LiveChannel): Boolean = channel.id in state.offline

        /** How many channels All lists, for the All button. */
        fun allCount(state: LiveTvState): Int = state.channels.size - state.copiesInAll.size

        /** Why [channel] could not be played the last time it was tried, or null. */
        fun playbackProblem(state: LiveTvState, channel: LiveChannel): LivePlaybackProblem? =
            state.playbackProblems[channel.id]

        private fun matchingChannels(state: LiveTvState, query: String, selection: String): List<LiveChannel> = when {
            // A search looks through every channel, whatever list is open, as a TV guide's search does.
            query.isNotBlank() && selection != LiveTvSelection.FAVORITES && selection != LiveTvSelection.RECENT ->
                matchingChannels(state, "", LiveTvSelection.ALL).let { LiveTvFilters.filter(it, query) }
            else -> matchingChannelsIn(state, query, selection)
        }

        private fun matchingChannelsIn(state: LiveTvState, query: String, selection: String): List<LiveChannel> = when (selection) {
            LiveTvSelection.FAVORITES ->
                LiveTvFilters.filter(state.channels, query, favoritesOnly = true, favorites = state.favorites)
            LiveTvSelection.RECENT -> {
                val byId = state.channels.associateBy(LiveChannel::id)
                LiveTvFilters.filter(state.recent.mapNotNull(byId::get), query)
            }
            LiveTvSelection.ALL -> LiveTvFilters.filter(state.channels, query, group = selection)
                .let { list -> if (state.copiesInAll.isEmpty()) list else list.filter { it.id !in state.copiesInAll } }
            else -> LiveTvFilters.filter(state.channels, query, group = selection)
        }

        /** The programmes of a channel that overlap the time from [fromMs] to [toMs], for the guide grid. */
        fun programmesBetween(state: LiveTvState, channel: LiveChannel, fromMs: Long, toMs: Long): List<LiveProgramme> =
            state.guide[channel.id]?.upcoming.orEmpty().filter { it.stopMs > fromMs && it.startMs < toMs }

        /** The playlist a channel came from, for showing its name. */
        fun sourceLabel(state: LiveTvState, channel: LiveChannel): String? =
            if (state.sources.size < 2) null else state.sources.firstOrNull { it.key == channel.sourceId }?.label

        /**
         * The group to open first. Live TV always opens on All, so every channel is there from the
         * start; opening on the country's group made it look as if most channels were missing.
         */
        @Suppress("UNUSED_PARAMETER")
        fun preferredGroup(state: LiveTvState, countryCode: String?): String? = null

        /** Guide text for a channel row, or null when nothing is known. */
        fun nowTitle(state: LiveTvState, channel: LiveChannel): String? =
            state.guide[channel.id]?.now?.title?.takeIf(String::isNotBlank)

        fun nextTitle(state: LiveTvState, channel: LiveChannel): String? =
            state.guide[channel.id]?.next?.title?.takeIf(String::isNotBlank)
    }
}
