package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Content type the player uses for Live TV channels, which are never saved to watch history. */
const val LiveTvContentType = "livetv"

/** Connects the shared Live TV logic to this app's storage, network and profiles. */
@OptIn(ExperimentalTime::class)
/**
 * The copies of the channel now playing, for players without the relay (iPhone and iPad): when one
 * fails, [next] gives the following copy to try.
 */
object LiveTvBackups {
    private var chain: List<LiveChannel> = emptyList()
    private var index = 0

    internal fun start(channels: List<LiveChannel>) {
        chain = channels
        index = 0
    }

    /** The next copy to try after the current one failed, or null when none are left. */
    fun next(): LiveChannel? {
        if (index + 1 >= chain.size) return null
        index += 1
        return chain[index]
    }
}

object LiveTvRepository {
    private const val MAX_TEXT_BYTES = 32 * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val storage = object : LiveTvStorage {
        override suspend fun read(key: String): String? = LiveTvPlatformStorage.load(key)
        override suspend fun write(key: String, value: String?) = LiveTvPlatformStorage.save(key, value)
    }

    val controller = LiveTvController(
        storage = storage,
        loader = LiveTvLoader(
            fetchText = ::fetchText,
            nowMs = { Clock.System.now().toEpochMilliseconds() },
            streamText = ::liveTvStreamText,
        ),
        scope = scope,
        defaultSources = LiveTvDefaults.builtInSources(liveTvCountryCode()),
        homeGroup = LiveTvDefaults.groupForCountry(liveTvCountryCode()) ?: "USA",
        probe = { channel -> liveTvStreamAnswers(channel.url, channel.headers) },
    )

    fun ensureLoaded() = controller.start()

    /** What the player should open for [channel], with backup copies and the Live TV VPN applied. */
    suspend fun playbackTarget(channel: LiveChannel, startBackups: Boolean = true): LiveTvPlaybackTarget {
        val backups = controller.alternatesFor(channel)
        val target = withContext(Dispatchers.Default) {
            liveTvPlaybackTarget(channel, backups, controller.state.value.vpn)
        }
        // Where there is no relay (iPhone and iPad), the player itself moves to a backup copy when this one fails.
        if (startBackups) LiveTvBackups.start(if (target.url == channel.url) listOf(channel) + backups else listOf(channel))
        // Where there is no relay (iPhone and iPad), a YouTube page is turned into its live stream here.
        if (target.url != channel.url || !LiveTvYouTube.isYouTube(channel.url)) return target
        val hls = youTubeStream(channel.url)
        controller.reportPlayback(channel.id, if (hls == null) LivePlaybackProblem.YOUTUBE_NOT_LIVE else null)
        return if (hls == null) target else LiveTvPlaybackTarget(hls, emptyMap())
    }

    private suspend fun youTubeStream(url: String): String? {
        val response = runCatching {
            httpRequestRaw(
                method = "GET",
                url = LiveTvYouTube.pageUrl(url),
                headers = LiveTvYouTube.pageHeaders,
                body = "",
                maxResponseBodyBytes = MAX_TEXT_BYTES,
            )
        }.getOrNull() ?: return null
        if (response.status !in 200..299) return null
        return LiveTvYouTube.hlsFrom(response.body)
    }

    val wireGuardAvailable: Boolean get() = liveTvWireGuardAvailable()

    fun wireGuardProblem(config: String): String? = liveTvWireGuardProblem(config)

    fun onProfileChanged() {
        controller.reset()
        controller.start()
    }

    fun clearLocalState() = controller.reset()

    private suspend fun fetchText(url: String): String? {
        val response = runCatching {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = mapOf("User-Agent" to LiveTvDefaults.DOWNLOAD_USER_AGENT),
                body = "",
                maxResponseBodyBytes = MAX_TEXT_BYTES,
            )
        }.getOrNull() ?: return null
        if (response.status !in 200..299) return null
        val bytes = response.bodyBytes
        val gzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (!gzip) return response.body
        return liveTvGunzip(bytes, MAX_TEXT_BYTES)?.decodeToString()
    }
}
