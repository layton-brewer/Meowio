package com.nuvio.tv.data.repository

import com.meowio.livetv.LiveTvSecretBox
import com.meowio.livetv.LiveTvVpnNative
import com.meowio.livetv.LiveTvWireGuard
import com.nuvio.tv.core.livetv.LiveChannel
import com.nuvio.tv.core.livetv.LiveProxy
import com.nuvio.tv.core.livetv.LiveTvController
import com.nuvio.tv.core.livetv.LiveTvDefaults
import com.nuvio.tv.core.livetv.LiveTvLoader
import com.nuvio.tv.core.livetv.LiveTvRelay
import com.nuvio.tv.core.livetv.LiveTvStorage
import com.nuvio.tv.core.livetv.LiveVpnGuard
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LiveTvDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Connects the shared Live TV logic to this app's storage, network and profiles. */
@Singleton
class LiveTvRepository @Inject constructor(
    storage: LiveTvDataStore,
    profileManager: ProfileManager,
    // Providers are whatever address the user enters, often with broken certificates.
    @param:Named("addonPermissive") okHttpClient: OkHttpClient
) {
    // The shared client's interceptors replace every request's User-Agent with the app's own,
    // which some guide and playlist hosts refuse. Live TV sets its own agent, so they are dropped.
    private val client = okHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .cache(null)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val controller = LiveTvController(
        storage = sealedVpn(storage),
        loader = LiveTvLoader(fetchText = ::fetchText, nowMs = System::currentTimeMillis, streamText = ::streamText),
        scope = scope,
        defaultSources = LiveTvDefaults.builtInSources(java.util.Locale.getDefault().country),
        probe = ::answers
    )

    /** Plays Live TV through the backup-channel relay and, when switched on, the Live TV VPN. */
    private val relay = LiveTvRelay(
        baseClient = client,
        wireGuard = LiveTvRelay.WireGuardStarter { config, protectHttps ->
            val running = LiveTvWireGuard.start(config, protectHttps)
            LiveTvRelay.Tunnel(
                proxy = LiveProxy("127.0.0.1", running.port, running.username, running.password),
                close = running::close
            )
        },
        log = { android.util.Log.i("LiveTv", it) },
        onResult = { channelId, problem -> scope.launch { controller.reportPlayback(channelId, problem) } }
    )

    /** Whether WireGuard can be used for the Live TV VPN in this build. */
    val wireGuardAvailable: Boolean get() = LiveTvVpnNative.available

    /** Null when [config] is a WireGuard file the Live TV VPN can use, otherwise why not. */
    fun wireGuardProblem(config: String): String? = LiveTvWireGuard.problem(config)

    private companion object {
        const val MAX_TEXT_BYTES = 32 * 1024 * 1024
        const val MAX_STREAM_CHARS = 600L * 1024 * 1024

        init {
            // The tunnel refuses anything the Live TV rules do not allow, debrid above all.
            LiveTvVpnNative.mayRoute = LiveVpnGuard::mayRoute
        }

        /** Saves the VPN settings (WireGuard private keys included) encrypted on this device. */
        fun sealedVpn(storage: LiveTvStorage): LiveTvStorage = object : LiveTvStorage {
            override suspend fun read(key: String): String? =
                if (key == "vpn") LiveTvSecretBox.open(storage.read(key)) else storage.read(key)

            override suspend fun write(key: String, value: String?) =
                storage.write(key, if (key == "vpn") LiveTvSecretBox.seal(value) else value)
        }
    }

    /**
     * What the player should open for [channel]: the channel itself, or the relay when there are
     * backup copies to fall back on or the Live TV VPN is on. The relay adds the channel's own
     * request headers itself, so none are returned for it.
     */
    fun playbackTarget(channel: LiveChannel): Pair<String, Map<String, String>> {
        val state = controller.state.value
        val backups = controller.alternatesFor(channel)
        val url = runCatching { relay.open(channel, backups, state.vpn) }.getOrElse { channel.url }
        return if (url == channel.url) url to channel.headers else url to emptyMap()
    }

    init {
        controller.start()
        scope.launch {
            profileManager.activeProfileId.drop(1).collect {
                controller.reset()
                controller.start()
            }
        }
    }

    /** Opens a channel briefly to see whether its stream is there. */
    private suspend fun answers(channel: LiveChannel): Boolean? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(channel.url).apply {
                channel.headers.forEach { (name, value) -> runCatching { header(name, value) } }
            }.build()
            probeClient.newCall(request).execute().use { response -> streamStatusAnswers(response.code) }
        } catch (e: IllegalArgumentException) {
            // Not an http link, so there is nothing to check.
            null
        } catch (e: java.io.IOException) {
            false
        }
    }

    /**
     * Only a missing stream or a failing server counts as down. Other refusals, such as 401 or 403,
     * often come from servers that dislike this check's request and still play in the player.
     */
    private fun streamStatusAnswers(code: Int): Boolean? = when {
        code in 200..399 -> true
        code == 404 || code == 410 || code >= 500 -> false
        else -> null
    }

    private val probeClient by lazy {
        client.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", LiveTvDefaults.DOWNLOAD_USER_AGENT).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                body.byteStream().use { stream -> readText(stream) }
            }
        }.getOrNull()
    }

    /** Hands a guide over in pieces, so its full size never has to fit in memory. */
    private suspend fun streamText(url: String, onChunk: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", LiveTvDefaults.DOWNLOAD_USER_AGENT).build()).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body ?: return@use false
                body.byteStream().use { raw ->
                    val reader = unpacked(raw).reader(Charsets.UTF_8)
                    val buffer = CharArray(64 * 1024)
                    var total = 0L
                    try {
                        while (total < MAX_STREAM_CHARS) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            onChunk(String(buffer, 0, count))
                            total += count
                        }
                    } catch (e: java.io.IOException) {
                        // Keep what was read from a download that was cut off.
                        if (total == 0L) throw e
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    private fun unpacked(raw: InputStream): InputStream {
        val pushback = PushbackInputStream(raw, 2)
        val magic = ByteArray(2)
        val read = pushback.read(magic, 0, 2)
        if (read > 0) pushback.unread(magic, 0, read)
        val gzip = read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        return if (gzip) GZIPInputStream(pushback) else pushback
    }

    /** Reads at most [MAX_TEXT_BYTES], unpacking gzip files that servers send as plain downloads. */
    private fun readText(raw: InputStream): String {
        val input = unpacked(raw)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            while (out.size() < MAX_TEXT_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_TEXT_BYTES - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        } catch (e: java.io.IOException) {
            // A cut-off download still holds usable channels or programmes.
            if (out.size() == 0) throw e
        }
        return out.toString(Charsets.UTF_8.name())
    }

}
