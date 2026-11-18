package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.remote.api.RealDebridApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-Debrid turned off its cache lookup in late 2024, so this answers honestly with what can
 * still be known:
 * 1. torrents already finished in the person's account play at once (one list call);
 * 2. for a few more hashes, add the magnet, select its files and look: a cached torrent is
 *    "downloaded" straight away. The probe torrent is always deleted again.
 * Hashes beyond the probe budget stay unchecked (shown as unknown, not "not cached").
 */
@Singleton
class RealDebridCacheProbe @Inject constructor(
    private val api: RealDebridApi
) {
    private val mutex = Mutex()
    private val results = mutableMapOf<String, Pair<LocalDebridCachedItem?, Long>>()
    private var owned: Triple<String, Map<String, LocalDebridCachedItem>, Long>? = null
    private val probeSlots = Semaphore(3)

    suspend fun check(apiKey: String, hashes: List<String>): LocalDebridCacheCheck? {
        val wanted = hashes.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return LocalDebridCacheCheck(emptyMap(), emptySet())
        val now = System.currentTimeMillis()
        val auth = "Bearer ${apiKey.trim()}"
        val ownedTorrents = ownedTorrents(apiKey, auth, now) ?: return null

        val cached = mutableMapOf<String, LocalDebridCachedItem>()
        val checked = mutableSetOf<String>()
        val toProbe = mutableListOf<String>()
        mutex.withLock {
            for (hash in wanted) {
                val ownedItem = ownedTorrents[hash]
                val remembered = results["$apiKey|$hash"]?.takeIf { it.second > now }
                when {
                    ownedItem != null -> { cached[hash] = ownedItem; checked += hash }
                    remembered != null -> { remembered.first?.let { cached[hash] = it }; checked += hash }
                    toProbe.size < PROBES_PER_CHECK -> toProbe += hash
                }
            }
        }

        val probed = coroutineScope {
            toProbe.map { hash -> async { hash to probeSlots.withPermit { probe(auth, hash) } } }.awaitAll()
        }
        mutex.withLock {
            probed.forEach { (hash, outcome) ->
                if (outcome == null) return@forEach // network trouble: leave it unchecked
                checked += hash
                val item = outcome.getOrNull()
                if (item != null) cached[hash] = item
                results["$apiKey|$hash"] = item to (now + RESULT_TTL_MS)
            }
        }
        return LocalDebridCacheCheck(cached, checked)
    }

    private suspend fun ownedTorrents(apiKey: String, auth: String, now: Long): Map<String, LocalDebridCachedItem>? {
        mutex.withLock { owned?.takeIf { it.first == apiKey && it.third > now }?.let { return it.second } }
        val response = try {
            api.listTorrents(auth)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        }
        if (!response.isSuccessful) {
            response.errorBody()?.close()
            return null
        }
        val map = response.body().orEmpty()
            .filter { it.status.equals("downloaded", ignoreCase = true) }
            .mapNotNull { torrent ->
                val hash = torrent.hash?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                hash to LocalDebridCachedItem(name = torrent.filename, size = torrent.bytes)
            }
            .toMap()
        mutex.withLock { owned = Triple(apiKey, map, now + OWNED_TTL_MS) }
        return map
    }

    /** null = could not tell; success(null) = not cached; success(item) = cached. */
    private suspend fun probe(auth: String, hash: String): Result<LocalDebridCachedItem?>? {
        var torrentId: String? = null
        return try {
            val add = api.addMagnet(auth, "magnet:?xt=urn:btih:$hash")
            torrentId = add.body()?.id?.takeIf { add.isSuccessful && it.isNotBlank() } ?: return null
            val select = api.selectFiles(auth, torrentId, "all")
            select.body()?.close()
            select.errorBody()?.close()
            if (!select.isSuccessful && select.code() != 202) return null
            val info = api.getTorrentInfo(auth, torrentId)
            if (!info.isSuccessful) return null
            val body = info.body()
            if (body?.status.equals("downloaded", ignoreCase = true)) {
                Result.success(LocalDebridCachedItem(name = body?.filename, size = body?.bytes))
            } else {
                Result.success(null)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } finally {
            torrentId?.let { id -> runCatching { api.deleteTorrent(auth, id).body()?.close() } }
        }
    }

    private companion object {
        // ponytail: fixed budget per check, keeps us far under RD's 250 requests/minute. Raise if
        // auto-play often runs out of cached candidates.
        const val PROBES_PER_CHECK = 8
        const val RESULT_TTL_MS = 30 * 60 * 1000L
        const val OWNED_TTL_MS = 60 * 1000L
    }
}
