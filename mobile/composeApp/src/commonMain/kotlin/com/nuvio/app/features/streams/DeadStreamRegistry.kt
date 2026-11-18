package com.nuvio.app.features.streams

import kotlin.concurrent.Volatile

/**
 * Links that just failed to play, remembered for a while so that auto-play and failover do not
 * pick them again: not for the rest of this title, and not when the viewer comes back to it a
 * few minutes later. Kept in memory only; a link gets another chance once [TTL_MS] has passed.
 */
object DeadStreamRegistry {
    const val TTL_MS = 30L * 60L * 1000L
    private const val MAX_ENTRIES = 500

    // Replaced as a whole on every change, so readers on any thread see a consistent map.
    @Volatile
    private var deadUntil: Map<String, Long> = emptyMap()

    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /** Remembers the link [url] and, for a torrent, its info hash as dead. */
    fun markDead(
        url: String?,
        addonName: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        nowMs: Long = now(),
    ) {
        val keys = buildList {
            url?.trim()?.takeIf { it.isNotEmpty() }?.let { add(urlKey(it)) }
            infoHash?.takeIf { it.isNotBlank() }?.let { add(torrentKey(addonName, it, fileIdx)) }
        }
        if (keys.isEmpty()) return
        val next = LinkedHashMap<String, Long>()
        deadUntil.forEach { (key, until) -> if (until > nowMs && key !in keys) next[key] = until }
        keys.forEach { next[it] = nowMs + TTL_MS }
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        deadUntil = next
    }

    fun markDead(stream: StreamItem, playedUrl: String? = null) {
        markDead(
            url = playedUrl ?: stream.playableDirectUrl,
            addonName = stream.addonName,
            infoHash = stream.p2pInfoHash,
            fileIdx = stream.fileIdx,
        )
        if (playedUrl != null && stream.playableDirectUrl != null && stream.playableDirectUrl != playedUrl) {
            markDead(url = stream.playableDirectUrl)
        }
    }

    fun isDead(stream: StreamItem, nowMs: Long = now()): Boolean {
        val map = deadUntil
        if (map.isEmpty()) return false
        return keysOf(stream).any { key -> (map[key] ?: 0L) > nowMs }
    }

    /** [streams] without the dead ones, or all of them when every one is dead (the network may be at fault). */
    fun withoutDead(streams: List<StreamItem>): List<StreamItem> {
        if (deadUntil.isEmpty()) return streams
        val alive = streams.filterNot { isDead(it) }
        return alive.ifEmpty { streams }
    }

    fun clear() {
        deadUntil = emptyMap()
    }

    private fun urlKey(url: String) = "url:$url"

    private fun torrentKey(addonName: String?, infoHash: String, fileIdx: Int?) =
        "torrent:${addonName.orEmpty()}:${infoHash.lowercase()}:${fileIdx ?: -1}"

    private fun keysOf(stream: StreamItem): List<String> = buildList {
        stream.playableDirectUrl?.let { add(urlKey(it)) }
        stream.p2pInfoHash?.let { add(torrentKey(stream.addonName, it, stream.fileIdx)) }
    }
}
