package com.nuvio.app.features.streams

import kotlin.math.min

/**
 * Spots the stand-in videos that debrid add-ons serve instead of the title: a short clip that
 * says "Torrent is being downloaded to debrid...", "Downloading", "Failed" or "Limit exceeded"
 * (Torrentio, Comet, MediaFusion, StremThru, AIOStreams and others do this when a torrent is not
 * cached yet). Such a link "plays" fine, so the normal error and stall failover never fires.
 *
 * Two checks:
 *  - the link's own address points at one of those stand-in clips, so it is never picked, and
 *  - the video that opened is far too short for the title, judged by the runtime from metadata,
 *    so real short content (short episodes, shorts, trailers) is left alone.
 */
object PlaceholderVideoDetector {
    /** No stand-in clip is longer than this; a real title of known length never is this short. */
    const val MAX_PLACEHOLDER_MS = 5L * 60L * 1000L

    /** Without a runtime from metadata, only a clip this short counts as a stand-in. */
    const val UNKNOWN_RUNTIME_MAX_MS = 90L * 1000L

    /** Titles shorter than this (shorts, minisodes) are never judged by length. */
    const val MIN_RUNTIME_MINUTES_FOR_LENGTH_CHECK = 10

    /** How long to wait for the length after the first frame before giving up on the check. */
    const val DURATION_WAIT_MS = 20_000L

    private val PLACEHOLDER_PATH_REGEX = Regex(
        "/(?:videos?|static|assets|exceptions|media|public|placeholders?)/(?:[^/?#]+/)*" +
            "([^/?#]*(?:download|caching|cached|queued|failed|failure|error|limit|blocked|" +
            "infringement|unavailable|not[_-]?found|no[_-]?results?|expired|unauthori[sz]ed|" +
            "forbidden|invalid|magnet|torrent)[^/?#]*)\\.(?:mp4|mkv|webm|m4v|mov)(?:[?#]|$)",
        RegexOption.IGNORE_CASE
    )

    /** A real file name names a year or an episode; a stand-in clip never does. */
    private val REAL_TITLE_MARKER_REGEX = Regex(
        "(?:^|[^0-9])(?:19|20)\\d{2}(?:[^0-9]|$)|s\\d{1,2}[ ._-]?e\\d{1,3}|\\d{3,4}p",
        RegexOption.IGNORE_CASE
    )

    private val TRAILER_TEXT_REGEX = Regex("\\b(?:trailer|teaser)s?\\b", RegexOption.IGNORE_CASE)

    /** True when [url] is the address of a debrid add-on's stand-in clip. */
    fun isPlaceholderUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return false
        val match = PLACEHOLDER_PATH_REGEX.find(url) ?: return false
        val fileName = match.groupValues[1]
        if (fileName.length > 48) return false
        return !REAL_TITLE_MARKER_REGEX.containsMatchIn(fileName)
    }

    /** True when the stream is labelled as a trailer or teaser, which is meant to be short. */
    fun isTrailerText(text: String?): Boolean = !text.isNullOrBlank() && TRAILER_TEXT_REGEX.containsMatchIn(text)

    /**
     * True when a video of [durationMs] is too short to be the title, whose runtime from
     * metadata is [expectedRuntimeMinutes] (null or 0 when unknown).
     */
    fun isPlaceholderDuration(durationMs: Long, expectedRuntimeMinutes: Int?): Boolean {
        if (durationMs <= 0L) return false
        val expected = expectedRuntimeMinutes?.takeIf { it > 0 }
            ?: return durationMs <= UNKNOWN_RUNTIME_MAX_MS
        if (expected < MIN_RUNTIME_MINUTES_FOR_LENGTH_CHECK) return false
        // A fifth of the runtime, capped: a 22-minute episode must run at least 4.4 minutes.
        val limitMs = min(MAX_PLACEHOLDER_MS, expected * 60_000L / 5L)
        return durationMs <= limitMs
    }

    /** Both checks together for the video that just opened. */
    fun isPlaceholderPlayback(
        url: String?,
        durationMs: Long,
        expectedRuntimeMinutes: Int?,
        streamText: String? = null,
    ): Boolean {
        if (isPlaceholderUrl(url)) return true
        if (isTrailerText(streamText)) return false
        return isPlaceholderDuration(durationMs, expectedRuntimeMinutes)
    }

    /** Minutes in a metadata runtime such as "2h 10min", "45 min" or "1:52"; null when unknown. */
    fun runtimeMinutes(raw: String?): Int? {
        val text = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text)?.let { match ->
            return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        }
        val hours = Regex("(\\d+)\\s*h").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = Regex("(\\d+)\\s*m").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (hours != null || minutes != null) return (hours ?: 0) * 60 + (minutes ?: 0)
        return Regex("\\d+").find(text)?.value?.toIntOrNull()?.takeIf { it in 1..1000 }
    }
}
