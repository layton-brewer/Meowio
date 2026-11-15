package com.nuvio.tv.core.livetv

enum class LiveTvError {
    /** The address or login is missing or not a web address. */
    INVALID_SOURCE,

    /** The provider could not be reached or answered with an error. */
    NETWORK,

    /** The address answered, but not with a channel playlist. */
    NOT_A_PLAYLIST,

    /** The Xtream provider rejected the username or password. */
    LOGIN_FAILED,

    /** The playlist loaded but holds no channels. */
    NO_CHANNELS,
}

class LiveTvException(val error: LiveTvError) : Exception(error.name)

/**
 * Loads channels and programme guides for a [LiveSource]. Networking is passed in, so the phone
 * and TV apps share this logic and it can be tested without a network.
 *
 * @param fetchText downloads an address and returns its text, or null when the request failed.
 * Guide files compressed with gzip should come back already decompressed.
 * @param streamText downloads an address and hands its text over piece by piece, returning false
 * when that is not possible. Used for guides, which can be far too large to hold in memory.
 */
class LiveTvLoader(
    private val fetchText: suspend (url: String) -> String?,
    private val nowMs: () -> Long,
    private val streamText: suspend (url: String, onChunk: (String) -> Unit) -> Boolean = { _, _ -> false },
) {

    /** Channels for [source]. Throws [LiveTvException] with the reason when they cannot be loaded. */
    suspend fun loadPlaylist(source: LiveSource): LivePlaylist {
        val playlist = when (source.type) {
            LiveSourceType.M3U -> loadM3u(source)
            LiveSourceType.XTREAM -> loadXtream(source)
        }
        val key = source.key
        return playlist.copy(channels = playlist.channels.map { it.copy(sourceId = key, group = LiveTvDefaults.cleanGroup(source, it.group)) })
    }

    private suspend fun loadM3u(source: LiveSource): LivePlaylist {
        val url = source.url.trim()
        if (!M3uParser.isHttpUrl(url)) throw LiveTvException(LiveTvError.INVALID_SOURCE)
        val body = fetchText(url) ?: throw LiveTvException(LiveTvError.NETWORK)
        if (!M3uParser.looksLikePlaylist(body)) throw LiveTvException(LiveTvError.NOT_A_PLAYLIST)
        if (M3uParser.isSingleStreamManifest(body)) {
            // The address is one stream, not a list. Offer it as a single channel.
            val name = url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { url }
            return LivePlaylist(channels = listOf(LiveChannel(id = url, name = name, url = url)))
        }
        val playlist = M3uParser.parse(body)
        if (playlist.channels.isEmpty()) throw LiveTvException(LiveTvError.NO_CHANNELS)
        return playlist
    }

    private suspend fun loadXtream(source: LiveSource): LivePlaylist {
        if (XtreamApi.normalizeServer(source.url).isEmpty() || source.username.isBlank() || source.password.isBlank()) {
            throw LiveTvException(LiveTvError.INVALID_SOURCE)
        }
        val login = fetchText(XtreamApi.loginUrl(source)) ?: throw LiveTvException(LiveTvError.NETWORK)
        if (!XtreamApi.isAuthenticated(login)) throw LiveTvException(LiveTvError.LOGIN_FAILED)
        // Categories only add group names, so a failure there should not block the channel list.
        val categories = fetchText(XtreamApi.categoriesUrl(source))?.let(XtreamApi::parseCategories).orEmpty()
        val streams = fetchText(XtreamApi.streamsUrl(source)) ?: throw LiveTvException(LiveTvError.NETWORK)
        val channels = XtreamApi.parseStreams(streams, categories, source)
        if (channels.isEmpty()) throw LiveTvException(LiveTvError.NO_CHANNELS)
        // Providers publish their whole guide as one XMLTV file.
        return LivePlaylist(
            channels = channels,
            guideUrls = listOf(XtreamApi.guideFileUrl(source)),
            account = XtreamApi.parseAccount(login),
        )
    }

    /** True when [playlist] announces so many guide files that they are loaded one group at a time. */
    fun hasGuidePerGroup(source: LiveSource, playlist: LivePlaylist): Boolean =
        !M3uParser.isHttpUrl(source.guideUrl.trim()) && playlist.guideUrls.size > MAX_ANNOUNCED_GUIDE_FILES

    /**
     * What is on now and next for every channel that the guide knows, keyed by [LiveChannel.id].
     * Uses the guide address the user entered, otherwise the ones the playlist announced.
     * [onProgress] gets what is known so far after each guide file, so the first channels show
     * their programmes while bigger files are still loading. Returns an empty map when there is
     * no guide or it cannot be read.
     */
    suspend fun loadGuide(
        source: LiveSource,
        playlist: LivePlaylist,
        onProgress: (Map<String, LiveNowNext>) -> Unit = {},
    ): Map<String, LiveNowNext> {
        // A playlist that lists dozens of guide files is a worldwide list; those load per group.
        if (hasGuidePerGroup(source, playlist)) return emptyMap()
        val named = source.guideUrl.trim().takeIf(M3uParser::isHttpUrl)?.let(::listOf) ?: playlist.guideUrls.take(MAX_GUIDE_FILES)
        val guideUrls = (named + LiveTvDefaults.extraGuideUrls(source)).distinct()
        return readGuides(guideUrls, playlist.channels, onProgress)
    }

    /** The guide for the channels of one [group] of a worldwide playlist. */
    suspend fun loadGroupGuide(
        source: LiveSource,
        playlist: LivePlaylist,
        group: String,
        onProgress: (Map<String, LiveNowNext>) -> Unit = {},
    ): Map<String, LiveNowNext> {
        if (!hasGuidePerGroup(source, playlist)) return emptyMap()
        // The free services' small guides come first, so most channels show programmes within
        // seconds; the country's big guide files follow for the rest.
        val urls = (LiveTvDefaults.fallbackGuideUrls(group) + LiveTvDefaults.guideUrlsForGroup(group, playlist.guideUrls)).distinct()
        if (urls.isEmpty()) return emptyMap()
        return readGuides(urls.take(MAX_GROUP_GUIDE_FILES), playlist.channels.filter { it.group == group }, onProgress)
    }

    private suspend fun readGuides(
        urls: List<String>,
        channels: List<LiveChannel>,
        onProgress: (Map<String, LiveNowNext>) -> Unit,
    ): Map<String, LiveNowNext> {
        if (urls.isEmpty() || channels.isEmpty()) return emptyMap()
        // Channels are matched by tvg-id and by name, as NuvioPlusIPTV does, so a playlist whose
        // ids differ a little from the guide's still gets its programmes.
        val matcher = GuideMatcher(channels)
        val byGuideId = HashMap<String, LiveNowNext>()
        var result: Map<String, LiveNowNext> = emptyMap()
        var lastAssigned: Map<String, String> = emptyMap()
        for (url in urls) {
            // One file that cannot be read must not stop the others from being tried, and a file
            // whose host is blocked is tried again from its mirror.
            for (address in LiveTvDefaults.guideMirrors(url)) {
                val reader = XmltvParser.Reader(nowMs(), matcher = matcher)
                val read = runCatching {
                    streamText(address, reader::feed) || fetchText(address)?.also(reader::feed) != null
                }.getOrDefault(false)
                val entries = reader.result()
                if (!read && entries.isEmpty()) continue
                entries.forEach { (guideId, entry) ->
                    if (guideId !in byGuideId) byGuideId[guideId] = entry
                }
                break
            }
            val assigned = matcher.assign(byGuideId.keys)
            if (assigned != lastAssigned) {
                lastAssigned = assigned
                result = assigned.mapNotNull { (channelId, guideId) -> byGuideId[guideId]?.let { channelId to it } }.toMap()
                onProgress(result)
            }
            // Stop once nearly every channel has its guide; later files are only for the gaps.
            if (assigned.size * 10 >= channels.size * 9) break
        }
        return result
    }

    /** What is on now and next for one Xtream channel, asked from the provider on demand. */
    suspend fun loadChannelGuide(source: LiveSource, channel: LiveChannel): LiveNowNext? {
        if (source.type != LiveSourceType.XTREAM) return null
        val streamId = channel.xtreamStreamId ?: return null
        val body = fetchText(XtreamApi.shortGuideUrl(source, streamId)) ?: return null
        return LiveNowNext.of(XtreamApi.parseShortGuide(body), nowMs())
    }

    private companion object {
        const val MAX_GUIDE_FILES = 3
        const val MAX_GROUP_GUIDE_FILES = 8
        const val MAX_ANNOUNCED_GUIDE_FILES = 6
    }
}
