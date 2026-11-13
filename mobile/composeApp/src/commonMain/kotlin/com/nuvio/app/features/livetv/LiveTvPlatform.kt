package com.nuvio.app.features.livetv

/** Device storage for Live TV settings, kept per profile and never synced. */
internal expect object LiveTvPlatformStorage {
    fun load(key: String): String?
    fun save(key: String, value: String?)
}

/** Unpacks gzip data, reading at most [maxBytes] of output. Null when the data cannot be unpacked. */
internal expect fun liveTvGunzip(bytes: ByteArray, maxBytes: Int): ByteArray?

/** The device's two-letter country code, or null when it is not known. */
internal expect fun liveTvCountryCode(): String?
