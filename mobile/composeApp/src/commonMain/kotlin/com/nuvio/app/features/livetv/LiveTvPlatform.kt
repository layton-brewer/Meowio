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

/**
 * Downloads [url] and hands its text over piece by piece, unpacking gzip. Returns false when the
 * platform cannot do this, in which case the caller falls back to a plain download.
 */
internal expect suspend fun liveTvStreamText(url: String, onChunk: (String) -> Unit): Boolean

/**
 * Opens a stream briefly to see whether it is there: true or false, or null when the platform
 * cannot tell.
 */
internal expect suspend fun liveTvStreamAnswers(url: String, headers: Map<String, String>): Boolean?
