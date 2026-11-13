package com.nuvio.app.features.livetv

/**
 * Live TV data shared by the phone and TV apps. Both apps keep identical copies of the files in
 * this package (only the package name differs), so the two behave the same.
 */
data class LiveChannel(
    /** Stable identifier used for favorites and the recent channel. */
    val id: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    /** Identifier that links the channel to the programme guide. */
    val guideId: String? = null,
    /** Extra request headers some providers require (User-Agent, Referer). */
    val headers: Map<String, String> = emptyMap(),
    /** Xtream stream id, used to ask the provider for this channel's programmes. */
    val xtreamStreamId: String? = null,
)

data class LivePlaylist(
    val channels: List<LiveChannel>,
    /** Programme guide addresses announced by the playlist itself. */
    val guideUrls: List<String> = emptyList(),
)

data class LiveProgramme(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
)

data class LiveNowNext(
    val now: LiveProgramme? = null,
    val next: LiveProgramme? = null,
)

enum class LiveSourceType { M3U, XTREAM }

/** Where the channel list comes from. Saved per profile. */
data class LiveSource(
    val type: LiveSourceType,
    /** M3U: the playlist address. Xtream: the server address. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Optional programme guide address that overrides the one in the playlist. */
    val guideUrl: String = "",
)

object LiveTvFilters {
    const val ALL = ""

    /** Group names in first-seen order. */
    fun groups(channels: List<LiveChannel>): List<String> =
        channels.mapNotNull { it.group?.takeIf(String::isNotBlank) }.distinct()

    fun filter(
        channels: List<LiveChannel>,
        query: String = "",
        group: String = ALL,
        favoritesOnly: Boolean = false,
        favorites: Set<String> = emptySet(),
    ): List<LiveChannel> {
        val needle = query.trim()
        return channels.filter { channel ->
            (!favoritesOnly || channel.id in favorites) &&
                (group == ALL || channel.group == group) &&
                (needle.isEmpty() || channel.name.contains(needle, ignoreCase = true))
        }
    }
}
