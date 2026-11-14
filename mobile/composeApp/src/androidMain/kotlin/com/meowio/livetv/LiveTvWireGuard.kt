package com.meowio.livetv

import android.util.Base64
import com.wireguard.config.BadConfigException
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.net.Inet6Address
import java.security.SecureRandom

/**
 * WireGuard profiles for the Live TV VPN, handled the way NuvioPlusIPTV does it: the official
 * WireGuard parser reads the provider's file and the same checks decide what can be used.
 *
 * The phone and TV apps keep identical copies of this file.
 */
object LiveTvWireGuard {
    /** A running engine: a proxy on 127.0.0.1 that only carries Live TV. */
    class Running internal constructor(
        val port: Int,
        val username: String,
        val password: String,
        private val handle: Long,
    ) {
        @Volatile private var closed = false

        /** True once traffic has been received and the last handshake is recent. */
        fun probe(): Boolean = !closed && LiveTvVpnNative.probeNative(handle)

        /** The public address Live TV appears from, or null when it cannot be told. */
        fun egress(): String? = if (closed) null else LiveTvVpnNative.egressNative(handle)

        fun close() {
            if (closed) return
            closed = true
            LiveTvVpnNative.stopNative(handle)
        }
    }

    /** Null when [text] is a WireGuard file the Live TV VPN can use, otherwise the reason it cannot. */
    fun problem(text: String): String? = try {
        parse(text)
        null
    } catch (e: IOException) {
        e.message ?: "WireGuard configuration is invalid."
    }

    /** The profile's name from its file, such as the server it connects to. */
    fun endpoint(text: String): String? = runCatching {
        parse(text).peers.firstOrNull()?.endpoint?.orElse(null)?.host
    }.getOrNull()

    /**
     * Starts the engine with [text]. With [protectHttps] false, only plain HTTP streams go through
     * WireGuard and HTTPS connects directly, as in NuvioPlusIPTV's "HTTP streams only" mode.
     */
    @Throws(IOException::class)
    fun start(text: String, protectHttps: Boolean): Running {
        if (!LiveTvVpnNative.available) throw IOException("WireGuard is not included in this build.")
        val config = parse(text)
        val secret = ByteArray(24).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(secret, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        secret.fill(0)
        val handle = LiveTvVpnNative.startNative(input(config, token, protectHttps))
        if (handle == 0L) throw IOException("The Live TV VPN could not connect.")
        val port = LiveTvVpnNative.portNative(handle)
        if (port <= 0) {
            LiveTvVpnNative.stopNative(handle)
            throw IOException("The Live TV VPN could not connect.")
        }
        return Running(port = port, username = "nuvio", password = token, handle = handle)
    }

    private fun input(config: Config, token: String, protectHttps: Boolean): String {
        val iface = config.`interface`
        if (iface.dnsSearchDomains.isNotEmpty()) {
            throw IOException("The Live TV VPN does not support DNS search domains. Use a profile with DNS server addresses only.")
        }
        val addresses = JSONArray().apply { iface.addresses.forEach { put(it.address.hostAddress) } }
        val dns = JSONArray().apply { iface.dnsServers.forEach { put(it.hostAddress) } }
        return JSONObject()
            .put("uapi", config.toWgUserspaceString())
            .put("addresses", addresses)
            .put("dns", dns)
            .put("mtu", iface.mtu.orElse(1280))
            .put("token", token)
            .put("tunnelHttps", protectHttps)
            .toString()
    }

    @Throws(IOException::class)
    private fun parse(text: String): Config {
        val config = try {
            BufferedReader(StringReader(text)).use { Config.parse(it) }
        } catch (e: BadConfigException) {
            // The parser's message can include the rejected private key, so it is not shown.
            throw IOException("WireGuard configuration is invalid.")
        } catch (e: IllegalArgumentException) {
            throw IOException("WireGuard configuration is invalid.")
        }
        validate(config)
        return config
    }

    /** NuvioPlusIPTV's requirements for a profile, checked when it is added and again before use. */
    private fun validate(config: Config) {
        val iface = config.`interface`
        if (iface.addresses.isEmpty()) throw IOException("WireGuard profiles require an interface Address.")
        if (config.peers.size != 1 || !config.peers[0].endpoint.isPresent) {
            throw IOException("WireGuard profiles require exactly one remote VPN peer with an Endpoint.")
        }
        if (iface.dnsServers.isEmpty()) throw IOException("WireGuard profiles require at least one DNS server IP address.")
        var configuredV4 = false
        var configuredV6 = false
        var defaultV4 = false
        var defaultV6 = false
        iface.addresses.forEach { if (it.address is Inet6Address) configuredV6 = true else configuredV4 = true }
        iface.dnsServers.forEach { if (it is Inet6Address) configuredV6 = true else configuredV4 = true }
        config.peers.forEach { peer ->
            peer.allowedIps.forEach {
                if (it.address is Inet6Address) {
                    configuredV6 = true
                    if (it.mask == 0) defaultV6 = true
                } else {
                    configuredV4 = true
                    if (it.mask == 0) defaultV4 = true
                }
            }
        }
        if ((!defaultV4 && !defaultV6) || (configuredV4 && !defaultV4) || (configuredV6 && !defaultV6)) {
            throw IOException(
                "WireGuard profiles require default AllowedIPs routes for every configured IP family " +
                    "(0.0.0.0/0 for IPv4 and ::/0 for IPv6)."
            )
        }
    }
}
