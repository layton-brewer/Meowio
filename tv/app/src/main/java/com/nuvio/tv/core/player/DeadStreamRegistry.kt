package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream

/**
 * Links that just failed to play, remembered for a while so that auto-play and failover do not
 * pick them again: not for the rest of this title, and not when the viewer comes back to it a
 * few minutes later. Kept in memory only; a link gets another chance once [TTL_MS] has passed.
 */
object DeadStreamRegistry {
    const val TTL_MS = 30L * 60L * 1000L
    private const val MAX_ENTRIES = 500

    private val deadUntil = LinkedHashMap<String, Long>()

    /** Remembers the link [url] and, for a torrent, its info hash as dead. */
    @Synchronized
    fun markDead(
        url: String?,
        addonName: String? = null,
        infoHash: String? = null,
        fileIdx: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = buildList {
            url?.takeIf { it.isNotBlank() }?.let { add(urlKey(it)) }
            infoHash?.takeIf { it.isNotBlank() }?.let { add(torrentKey(addonName, it, fileIdx)) }
        }
        keys.forEach { key ->
            deadUntil.remove(key)
            deadUntil[key] = nowMs + TTL_MS
        }
        while (deadUntil.size > MAX_ENTRIES) deadUntil.remove(deadUntil.keys.first())
    }

    fun markDead(stream: Stream) = markDead(
        url = stream.getStreamUrl(),
        addonName = stream.addonName,
        infoHash = stream.getEffectiveInfoHash(),
        fileIdx = stream.getEffectiveFileIdx()
    )

    @Synchronized
    fun isDead(stream: Stream, nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        return keysOf(stream).any { it in deadUntil }
    }

    @Synchronized
    fun isDeadUrl(url: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (url.isNullOrBlank()) return false
        prune(nowMs)
        return urlKey(url) in deadUntil
    }

    /** [streams] without the dead ones, or all of them when every one is dead (the network may be at fault). */
    fun withoutDead(streams: List<Stream>): List<Stream> {
        val alive = streams.filterNot { isDead(it) }
        return alive.ifEmpty { streams }
    }

    @Synchronized
    fun clear() = deadUntil.clear()

    private fun prune(nowMs: Long) {
        val iterator = deadUntil.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value <= nowMs) iterator.remove()
    }

    private fun urlKey(url: String) = "url:$url"

    private fun torrentKey(addonName: String?, infoHash: String, fileIdx: Int?) =
        "torrent:${addonName.orEmpty()}:${infoHash.lowercase()}:${fileIdx ?: -1}"

    private fun keysOf(stream: Stream): List<String> = buildList {
        stream.getStreamUrl()?.takeIf { it.isNotBlank() }?.let { add(urlKey(it)) }
        stream.getEffectiveInfoHash()?.takeIf { it.isNotBlank() }?.let { hash ->
            add(torrentKey(stream.addonName, hash, stream.getEffectiveFileIdx()))
        }
    }
}
