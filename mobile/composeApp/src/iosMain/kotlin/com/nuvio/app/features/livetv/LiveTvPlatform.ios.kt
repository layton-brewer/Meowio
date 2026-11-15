package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.ProfileScopedKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.free
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_STREAM_END
import platform.zlib.Z_OK
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale

internal actual object LiveTvPlatformStorage {
    actual fun load(key: String): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of("live_tv_$key"))

    actual fun save(key: String, value: String?) {
        val scopedKey = ProfileScopedKey.of("live_tv_$key")
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(scopedKey)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(value, forKey = scopedKey)
        }
    }
}

/**
 * Unpacks a gzip guide file with the system zlib. A guide larger than [maxBytes] is cut there:
 * the programmes read up to that point are still used.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun liveTvGunzip(bytes: ByteArray, maxBytes: Int): ByteArray? {
    if (bytes.isEmpty()) return null
    val parts = ArrayList<ByteArray>()
    var total = 0
    memScoped {
        val stream = alloc<z_stream>()
        // 15 + 32: accept a gzip or zlib header.
        if (inflateInit2_(stream.ptr, 15 + 32, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) != Z_OK) {
            return null
        }
        val chunk = ByteArray(256 * 1024)
        try {
            bytes.usePinned { input ->
                chunk.usePinned { output ->
                    stream.next_in = input.addressOf(0).reinterpret()
                    stream.avail_in = bytes.size.convert()
                    while (total < maxBytes) {
                        stream.next_out = output.addressOf(0).reinterpret()
                        stream.avail_out = chunk.size.convert()
                        val status = inflate(stream.ptr, Z_NO_FLUSH)
                        val produced = chunk.size - stream.avail_out.toInt()
                        if (produced > 0) {
                            parts += chunk.copyOf(produced)
                            total += produced
                        }
                        if (status != Z_OK || produced == 0) break
                    }
                }
            }
        } finally {
            inflateEnd(stream.ptr)
        }
    }
    if (total == 0) return null
    val result = ByteArray(minOf(total, maxBytes))
    var offset = 0
    for (part in parts) {
        val count = minOf(part.size, result.size - offset)
        if (count <= 0) break
        part.copyInto(result, offset, 0, count)
        offset += count
    }
    return result
}

internal actual fun liveTvCountryCode(): String? =
    NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String

/** Guide files are big, so they get a client with room to finish; the shared one gives up after a minute. */
private val guideHttpClient = HttpClient(Darwin) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15 * 60_000L
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 120_000
    }
    expectSuccess = false
}

/** Characters read from a guide before it is cut, the same limit as on Android. */
private const val MAX_GUIDE_CHARS = 600L * 1024 * 1024

/** Unpacks gzip piece by piece with the system zlib, so a guide never has to fit in memory. */
@OptIn(ExperimentalForeignApi::class)
private class GzipStream {
    private val stream = nativeHeap.alloc<z_stream>()
    private val ready = inflateInit2_(stream.ptr, 15 + 32, zlibVersion()?.toKString(), sizeOf<z_stream>().toInt()) == Z_OK
    private val buffer = ByteArray(64 * 1024)
    var failed = !ready
        private set
    var ended = false
        private set

    /** Unpacks [input], passing the result on in pieces. */
    fun feed(input: ByteArray, sink: (ByteArray, Int) -> Unit) {
        if (failed || ended || input.isEmpty()) return
        input.usePinned { inPin ->
            buffer.usePinned { outPin ->
                stream.next_in = inPin.addressOf(0).reinterpret()
                stream.avail_in = input.size.convert()
                do {
                    stream.next_out = outPin.addressOf(0).reinterpret()
                    stream.avail_out = buffer.size.convert()
                    val status = inflate(stream.ptr, Z_NO_FLUSH)
                    val produced = buffer.size - stream.avail_out.toInt()
                    if (produced > 0) sink(buffer, produced)
                    if (status == Z_STREAM_END) {
                        ended = true
                        return@usePinned
                    }
                    if (status != Z_OK) {
                        failed = true
                        return@usePinned
                    }
                } while (stream.avail_in.toInt() > 0 || stream.avail_out.toInt() == 0)
            }
        }
    }

    fun close() {
        if (ready) inflateEnd(stream.ptr)
        nativeHeap.free(stream)
    }
}

/** Turns bytes into text, holding back the end of a character that a piece cut in two. */
private class Utf8Pieces(private val onText: (String) -> Unit) {
    private var carry = ByteArray(0)

    fun add(bytes: ByteArray, count: Int) {
        val all = if (carry.isEmpty()) bytes.copyOf(count) else carry + bytes.copyOf(count)
        var cut = all.size
        var back = 0
        while (back < 3 && cut - 1 - back >= 0) {
            val b = all[cut - 1 - back].toInt() and 0xFF
            if (b and 0xC0 == 0x80) {
                back++
                continue
            }
            val needed = when {
                b >= 0xF0 -> 4
                b >= 0xE0 -> 3
                b >= 0xC0 -> 2
                else -> 1
            }
            if (needed > back + 1) cut = cut - 1 - back
            break
        }
        carry = all.copyOfRange(cut, all.size)
        if (cut > 0) onText(all.decodeToString(0, cut))
    }
}

internal actual suspend fun liveTvStreamText(url: String, onChunk: (String) -> Unit): Boolean = runCatching {
    guideHttpClient.prepareGet(url) {
        header("User-Agent", LiveTvDefaults.DOWNLOAD_USER_AGENT)
    }.execute { response ->
        if (!response.status.isSuccess()) return@execute false
        val channel = response.bodyAsChannel()
        var total = 0L
        val text = Utf8Pieces { piece ->
            total += piece.length
            onChunk(piece)
        }
        var gzip: GzipStream? = null
        var decided = false
        val read = ByteArray(64 * 1024)
        try {
            while (total < MAX_GUIDE_CHARS) {
                val count = channel.readAvailable(read, 0, read.size)
                if (count < 0) break
                if (count == 0) continue
                if (!decided) {
                    decided = true
                    if (count > 1 && read[0] == 0x1f.toByte() && read[1] == 0x8b.toByte()) gzip = GzipStream()
                }
                val unpacker = gzip
                if (unpacker == null) {
                    text.add(read, count)
                } else {
                    unpacker.feed(read.copyOf(count)) { bytes, produced -> text.add(bytes, produced) }
                    if (unpacker.failed || unpacker.ended) break
                }
            }
        } catch (e: Exception) {
            // Keep what was read from a download that was cut off.
            if (total == 0L) throw e
        } finally {
            gzip?.close()
        }
        total > 0
    }
}.getOrDefault(false)

internal actual suspend fun liveTvStreamAnswers(url: String, headers: Map<String, String>): Boolean? = null

/**
 * iOS plays Live TV straight from the channel. When the Live TV VPN (an HTTP proxy here) is on,
 * the proxy is remembered for that address only and the player picks it up for that playback;
 * every other playback, debrid included, is told to use no proxy.
 */
object LiveTvIosProxy {
    private val proxies = HashMap<String, String>()

    fun proxyFor(url: String): String? = proxies[url]

    internal fun remember(url: String, proxy: String?) {
        if (proxy == null) proxies.remove(url) else proxies[url] = proxy
        while (proxies.size > 8) proxies.remove(proxies.keys.first())
    }
}

/**
 * The WireGuard engine inside the app (native/livetv-vpn, built as a static library). Swift
 * implements this and registers it at startup, the same way the player is registered.
 */
interface LiveTvVpnEngine {
    /** Null when [config] is a WireGuard file the Live TV VPN can use, otherwise the reason it cannot. */
    fun check(config: String): String?

    /** Starts the tunnel; returns "port:password" of its proxy on 127.0.0.1, or null when it could not start. */
    fun start(config: String, protectHttps: Boolean): String?

    fun stop()
}

object LiveTvVpnEngineRegistry {
    internal var engine: LiveTvVpnEngine? = null

    fun register(engine: LiveTvVpnEngine) {
        this.engine = engine
    }
}

/** Keeps one WireGuard tunnel running for Live TV, restarted when the profile changes. */
private object LiveTvIosWireGuard {
    private var key: String? = null
    private var proxy: LiveProxy? = null

    fun proxyFor(config: String, protectHttps: Boolean): LiveProxy? {
        val engine = LiveTvVpnEngineRegistry.engine ?: return null
        val wanted = "$protectHttps\n$config"
        if (key == wanted) proxy?.let { return it }
        stop()
        val started = engine.start(config, protectHttps) ?: return null
        val split = started.indexOf(':')
        val port = started.substring(0, split.coerceAtLeast(0)).toIntOrNull() ?: return null
        if (split < 0 || port <= 0) return null
        key = wanted
        return LiveProxy("127.0.0.1", port, "nuvio", started.substring(split + 1)).also { proxy = it }
    }

    fun stop() {
        if (key != null) LiveTvVpnEngineRegistry.engine?.stop()
        key = null
        proxy = null
    }
}

internal actual fun liveTvPlaybackTarget(
    channel: LiveChannel,
    backups: List<LiveChannel>,
    vpn: LiveVpnSettings,
): LiveTvPlaybackTarget {
    val proxy = if (vpn.ready && LiveVpnGuard.mayRoute(channel.url)) {
        when (vpn.type) {
            LiveVpnType.PROXY -> LiveVpnGuard.parseProxy(vpn.proxyUrl)?.asUrl()
            LiveVpnType.WIREGUARD -> (
                LiveTvIosWireGuard.proxyFor(vpn.wireGuardConfig, vpn.protectHttps)?.asUrl()
                    // The VPN is on but cannot start: send the channel to a dead proxy rather than around it.
                    ?: "http://127.0.0.1:1".also { LiveTvRepository.controller.reportPlayback(channel.id, LivePlaybackProblem.VPN) }
            )
        }
    } else {
        if (!vpn.ready) LiveTvIosWireGuard.stop()
        null
    }
    LiveTvIosProxy.remember(channel.url, proxy)
    return LiveTvPlaybackTarget(channel.url, channel.headers)
}

internal actual fun liveTvWireGuardAvailable(): Boolean = LiveTvVpnEngineRegistry.engine != null

internal actual fun liveTvWireGuardProblem(config: String): String? =
    LiveTvVpnEngineRegistry.engine?.check(config) ?: if (LiveTvVpnEngineRegistry.engine == null) {
        "WireGuard is not included in this build. Use an HTTP proxy instead."
    } else {
        null
    }
