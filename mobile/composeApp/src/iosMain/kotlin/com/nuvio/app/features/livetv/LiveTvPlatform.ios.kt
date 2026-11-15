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
import platform.zlib.Z_NO_FLUSH
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

internal actual suspend fun liveTvStreamText(url: String, onChunk: (String) -> Unit): Boolean = false

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
