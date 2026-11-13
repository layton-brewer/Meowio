package com.nuvio.app.features.livetv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val source: LiveSource? = null,
    /** True while the built-in free channels are shown because the user has not added a source. */
    val usingDefault: Boolean = false,
    val loading: Boolean = false,
    val error: LiveTvError? = null,
    val channels: List<LiveChannel> = emptyList(),
    val groups: List<String> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** Most recently watched first. */
    val recent: List<String> = emptyList(),
    /** What is on now and next, by channel id. */
    val guide: Map<String, LiveNowNext> = emptyMap(),
    /** Built-in channels that did not answer when checked. They are listed last. */
    val offline: Set<String> = emptySet(),
)

/** Which channels the list shows. Anything else is a group name. */
object LiveTvSelection {
    const val ALL = LiveTvFilters.ALL
    const val FAVORITES = "\u0000favorites"
    const val RECENT = "\u0000recent"
}

/**
 * Everything Live TV does apart from drawing the screen: saved source, channel list, favorites,
 * recent channels and the programme guide. The phone and TV apps share this file.
 */
class LiveTvController(
    private val storage: LiveTvStorage,
    private val loader: LiveTvLoader,
    private val scope: CoroutineScope,
    /** Channels shown until the user adds a source of their own. */
    private val defaultSource: LiveSource? = LiveTvDefaults.source,
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
    private val groupCheckRequested = HashSet<String>()
    private var playlist: LivePlaylist? = null

    /** Reads the saved settings. Safe to call more than once. */
    fun start() {
        if (startJob != null) return
        startJob = scope.launch {
            val saved = LiveTvCodec.decodeSource(storage.read(KEY_SOURCE))
            val source = saved ?: defaultSource
            val favorites = LiveTvCodec.decodeIds(storage.read(KEY_FAVORITES)).toSet()
            val recent = LiveTvCodec.decodeIds(storage.read(KEY_RECENT)).take(MAX_RECENT)
            val tabVisible = storage.read(KEY_TAB_VISIBLE) != "false"
            _state.update {
                it.copy(
                    ready = true,
                    tabVisible = tabVisible,
                    source = source,
                    usingDefault = saved == null && source != null,
                    favorites = favorites,
                    recent = recent,
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
        groupCheckRequested.clear()
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

    fun setSource(source: LiveSource) {
        val cleaned = source.copy(
            url = source.url.trim(),
            username = source.username.trim(),
            password = source.password.trim(),
            guideUrl = source.guideUrl.trim(),
        )
        _state.update { it.copy(source = cleaned, usingDefault = false) }
        scope.launch { storage.write(KEY_SOURCE, LiveTvCodec.encodeSource(cleaned)) }
        refresh()
    }

    /** Drops the user's own source and goes back to the built-in channels, when there are any. */
    fun removeSource() {
        loadJob?.cancel()
        guideRequested.clear()
        groupGuideRequested.clear()
        groupCheckRequested.clear()
        _state.update {
            it.copy(
                source = defaultSource,
                usingDefault = defaultSource != null,
                loading = false,
                error = null,
                channels = emptyList(),
                groups = emptyList(),
                guide = emptyMap(),
            )
        }
        scope.launch { storage.write(KEY_SOURCE, null) }
        if (defaultSource != null) refresh()
    }

    fun setTabVisible(visible: Boolean) {
        _state.update { it.copy(tabVisible = visible) }
        scope.launch { storage.write(KEY_TAB_VISIBLE, visible.toString()) }
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

    /**
     * Asks an Xtream provider what is on [channel] now, once per channel. Playlists with a guide
     * file are covered by the guide loaded with the channel list instead.
     */
    fun requestChannelGuide(channel: LiveChannel) {
        val source = _state.value.source ?: return
        if (source.type != LiveSourceType.XTREAM || channel.xtreamStreamId == null) return
        if (channel.id in _state.value.guide || !guideRequested.add(channel.id)) return
        scope.launch {
            val entry = runCatching { loader.loadChannelGuide(source, channel) }.getOrNull() ?: return@launch
            if (_state.value.source == source) _state.update { it.copy(guide = it.guide + (channel.id to entry)) }
        }
    }

    /**
     * Loads the guide for one group of a worldwide playlist, such as the built-in channels, the
     * first time that group is opened. Other playlists get their whole guide with the channels.
     */
    fun requestGroupGuide(group: String) {
        val source = _state.value.source ?: return
        val loaded = playlist ?: return
        if (!loader.hasGuidePerGroup(source, loaded) || !groupGuideRequested.add(group)) return
        scope.launch {
            val guide = withContext(Dispatchers.Default) {
                runCatching { loader.loadGroupGuide(source, loaded, group) }.getOrDefault(emptyMap())
            }
            if (guide.isNotEmpty() && playlist === loaded) _state.update { it.copy(guide = it.guide + guide) }
        }
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

    private fun loadChannels() {
        val source = _state.value.source ?: return
        loadJob?.cancel()
        guideRequested.clear()
        groupGuideRequested.clear()
        groupCheckRequested.clear()
        playlist = null
        _state.update { it.copy(loading = true, error = null) }
        loadJob = scope.launch {
            try {
                val playlist = withContext(Dispatchers.Default) { loader.loadPlaylist(source) }
                this@LiveTvController.playlist = playlist
                val groups = LiveTvFilters.groups(playlist.channels)
                _state.update {
                    it.copy(loading = false, error = null, channels = playlist.channels, groups = groups, guide = emptyMap(), offline = emptySet())
                }
                val guide = withContext(Dispatchers.Default) {
                    runCatching { loader.loadGuide(source, playlist) }.getOrDefault(emptyMap())
                }
                if (guide.isNotEmpty()) _state.update { it.copy(guide = guide + it.guide) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveTvException) {
                fail(e.error)
            } catch (e: Exception) {
                fail(LiveTvError.NETWORK)
            }
        }
    }

    private fun fail(error: LiveTvError) {
        _state.update { it.copy(loading = false, error = error, channels = emptyList(), groups = emptyList(), guide = emptyMap()) }
    }

    companion object {
        const val MAX_RECENT = 12
        private const val MAX_CHECKED_PER_GROUP = 200
        private const val CHECK_PARALLELISM = 6
        private const val KEY_SOURCE = "source"
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

        private fun matchingChannels(state: LiveTvState, query: String, selection: String): List<LiveChannel> = when (selection) {
            LiveTvSelection.FAVORITES ->
                LiveTvFilters.filter(state.channels, query, favoritesOnly = true, favorites = state.favorites)
            LiveTvSelection.RECENT -> {
                val byId = state.channels.associateBy(LiveChannel::id)
                LiveTvFilters.filter(state.recent.mapNotNull(byId::get), query)
            }
            else -> LiveTvFilters.filter(state.channels, query, group = selection)
        }

        /** The group to open first on the built-in channels: the one for the device's country. */
        fun preferredGroup(state: LiveTvState, countryCode: String?): String? {
            if (!state.usingDefault) return null
            val group = LiveTvDefaults.groupForCountry(countryCode) ?: return null
            return group.takeIf { it in state.groups }
        }

        /** Guide text for a channel row, or null when nothing is known. */
        fun nowTitle(state: LiveTvState, channel: LiveChannel): String? =
            state.guide[channel.id]?.now?.title?.takeIf(String::isNotBlank)

        fun nextTitle(state: LiveTvState, channel: LiveChannel): String? =
            state.guide[channel.id]?.next?.title?.takeIf(String::isNotBlank)
    }
}
