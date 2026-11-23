package com.nuvio.app.features.player

/**
 * Quality choice for players built on mpv (iPhone/iPad, Mac and Windows).
 *
 * Adaptive streams (HLS/DASH) show up in mpv as one video track per rendition. The quality picker
 * lists their heights and holds playback to the chosen one by selecting that track; "Auto" hands
 * the choice back to mpv. A file with a single video track has nothing to choose, so the picker
 * stays hidden, just like on Android.
 */
internal data class MpvVideoRendition(val id: String, val height: Int)

internal fun mpvVideoRenditions(property: (String) -> String?): List<MpvVideoRendition> {
    val count = property("track-list/count")?.toIntOrNull() ?: return emptyList()
    return (0 until count).mapNotNull { index ->
        val prefix = "track-list/$index"
        if (property("$prefix/type") != "video") return@mapNotNull null
        if (property("$prefix/albumart") == "yes" || property("$prefix/image") == "yes") return@mapNotNull null
        val id = property("$prefix/id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val height = property("$prefix/demux-h")?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        MpvVideoRendition(id = id, height = height)
    }
}

/** Distinct heights, highest first; empty when there is no real choice. */
internal fun mpvVideoQualityHeights(property: (String) -> String?): List<Int> {
    val heights = mpvVideoRenditions(property).map { it.height }.distinct().sortedDescending()
    return if (heights.size > 1) heights else emptyList()
}

/**
 * The value for mpv's `vid` property that holds playback at or below [maxHeight]:
 * the tallest rendition that fits, or the smallest one when none fits. `auto` when not capped.
 */
internal fun mpvVideoTrackForCap(maxHeight: Int?, property: (String) -> String?): String {
    if (maxHeight == null || maxHeight <= 0) return "auto"
    val renditions = mpvVideoRenditions(property)
    if (renditions.size < 2) return "auto"
    val fitting = renditions.filter { it.height <= maxHeight }.maxByOrNull { it.height }
    return (fitting ?: renditions.minBy { it.height }).id
}
