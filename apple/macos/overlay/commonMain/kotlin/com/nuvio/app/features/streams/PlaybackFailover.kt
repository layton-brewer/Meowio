package com.nuvio.app.features.streams

/**
 * Backup sources for the title that is about to play or is playing.
 *
 * Auto-play hands over the ranked sources it did not pick. When the playing source fails, the
 * player takes the next one from here and carries on, without sending the user back to a list.
 */
object PlaybackFailover {
    /** Upper bound on silent source swaps for one title, so a dead network cannot loop forever. */
    const val MAX_ATTEMPTS = 6

    private var activeKey: String? = null
    private var remaining: List<StreamItem> = emptyList()
    private var attempts = 0

    fun contentKey(videoId: String?, season: Int?, episode: Int?): String? =
        videoId?.takeIf { it.isNotBlank() }?.let { "$it|$season|$episode" }

    fun offer(key: String?, candidates: List<StreamItem>) {
        activeKey = key
        remaining = if (key == null) emptyList() else candidates
        attempts = 0
    }

    fun hasNext(key: String?): Boolean =
        key != null && key == activeKey && attempts < MAX_ATTEMPTS && remaining.isNotEmpty()

    /**
     * Takes the next backup source for [key], skipping the one that just failed.
     * Returns null when there is nothing left to try.
     */
    fun next(key: String?, failed: StreamItem? = null, failedUrl: String? = null): StreamItem? {
        if (key == null || key != activeKey) return null
        while (attempts < MAX_ATTEMPTS && remaining.isNotEmpty()) {
            val candidate = remaining.first()
            remaining = remaining.drop(1)
            if (candidate == failed) continue
            val url = candidate.playableDirectUrl
            if (url != null && url == failedUrl) continue
            attempts++
            return candidate
        }
        return null
    }

    fun clear() {
        activeKey = null
        remaining = emptyList()
        attempts = 0
    }
}
