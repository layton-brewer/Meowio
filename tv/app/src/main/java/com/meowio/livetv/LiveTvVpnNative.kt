package com.meowio.livetv

/**
 * The Live TV VPN engine (native/livetv-vpn), NuvioPlusIPTV's WireGuard engine by ghbarker.
 * WireGuard runs inside the app on a private network stack and is reachable only as a
 * password-protected proxy on 127.0.0.1. It never becomes a system VPN, so only the Live TV relay
 * can send traffic through it.
 *
 * The phone and TV apps keep identical copies of this file. The class name and method names are
 * fixed because the native library looks them up by name.
 */
object LiveTvVpnNative {
    /** False when this build does not include the engine. */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("meowiovpn") }.isSuccess
    }

    /**
     * Decides which addresses may use the tunnel. The app sets it to the Live TV rules, which
     * refuse debrid hosts. Until then nothing is allowed.
     */
    @Volatile
    var mayRoute: (String) -> Boolean = { false }

    /** Asked by the engine before every request it carries. */
    @JvmStatic
    fun preparePlayback(address: String, request: Long): Boolean =
        runCatching { mayRoute(address) && !requestCancelledNative(request) }.getOrDefault(false)

    @JvmStatic external fun startNative(input: String): Long
    @JvmStatic external fun stopNative(handle: Long)
    @JvmStatic external fun configureNative(handle: Long, input: String): Boolean
    @JvmStatic external fun portNative(handle: Long): Int
    @JvmStatic external fun statsNative(handle: Long): LongArray?
    @JvmStatic external fun probeNative(handle: Long): Boolean
    @JvmStatic external fun egressNative(handle: Long): String?
    @JvmStatic external fun requestCancelledNative(request: Long): Boolean
}
