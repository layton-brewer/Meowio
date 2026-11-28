package com.nuvio.app.features.debrid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class LocalDebridCachedItem(
    val name: String?,
    val size: Long?,
)

/** [cached] holds the hashes that play at once; hashes outside [checked] were not looked at. */
internal data class LocalDebridCacheCheck(
    val cached: Map<String, LocalDebridCachedItem>,
    val checked: Set<String>,
)

internal object LocalDebridService {
    suspend fun checkCached(
        account: DebridServiceCredential,
        hashes: List<String>,
    ): LocalDebridCacheCheck? =
        when (account.provider.id) {
            DebridProviders.TORBOX_ID -> checkTorboxCached(account.apiKey, hashes)?.let { LocalDebridCacheCheck(it, hashes.toSet()) }
            DebridProviders.PREMIUMIZE_ID -> checkPremiumizeCached(account.apiKey, hashes)?.let { LocalDebridCacheCheck(it, hashes.toSet()) }
            DebridProviders.REAL_DEBRID_ID -> RealDebridCacheProbe.check(account.apiKey, hashes)
            else -> null
        }

    suspend fun isCached(account: DebridServiceCredential, hash: String): Boolean? {
        val normalizedHash = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        val check = checkCached(account, listOf(normalizedHash)) ?: return null
        if (normalizedHash !in check.checked) return null
        return check.cached.containsKey(normalizedHash)
    }

    private suspend fun checkTorboxCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? {
        val normalizedHashes = hashes
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedHashes.isEmpty()) return emptyMap()
        val cached = requestTorboxCached(apiKey, normalizedHashes) ?: return null
        return resolveTorboxAliasMatches(cached, normalizedHashes) { hash ->
            requestTorboxCached(apiKey, listOf(hash))
        }
    }

    private suspend fun requestTorboxCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val response = TorboxApiClient.checkCached(apiKey = apiKey, hashes = hashes)
            if (!response.isSuccessful || response.body?.success == false) {
                null
            } else {
                // TorBox can answer under an alternative hash (the item's own "hash" field, or the
                // map key, may differ from the one asked for): match either back to the request.
                val requested = hashes.map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
                buildMap {
                    response.body?.data.orEmpty().forEach { (key, value) ->
                        val item = LocalDebridCachedItem(name = value.name, size = value.size)
                        val candidates = listOfNotNull(key.lowercase(), value.hash?.trim()?.lowercase())
                        val matches = candidates.filter { it in requested }
                        if (matches.isEmpty()) put(key.lowercase(), item) else matches.forEach { put(it, item) }
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }

    private suspend fun checkPremiumizeCached(
        apiKey: String,
        hashes: List<String>,
    ): Map<String, LocalDebridCachedItem>? =
        try {
            val normalizedHashes = hashes
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedHashes.isEmpty()) return emptyMap()
            val sources = normalizedHashes.map { hash -> "magnet:?xt=urn:btih:$hash" }
            val response = PremiumizeApiClient.checkCache(apiKey = apiKey, items = sources)
            val body = response.body
            if (!response.isSuccessful || body?.status.equals("error", ignoreCase = true)) {
                null
            } else {
                normalizedHashes.mapIndexedNotNull { index, hash ->
                    if (body?.response?.getOrNull(index) != true) return@mapIndexedNotNull null
                    hash to LocalDebridCachedItem(
                        name = body.filename?.getOrNull(index),
                        size = body.filesize?.getOrNull(index)?.asLongOrNull(),
                    )
                }.toMap()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
}

private fun kotlinx.serialization.json.JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}

/**
 * Real-Debrid turned off its cache lookup in late 2024, so this answers honestly with what can
 * still be known:
 * 1. torrents already finished in the person's account play at once (one list call);
 * 2. for a few more hashes, add the magnet, select its files and look: a cached torrent is
 *    "downloaded" straight away. The probe torrent is always deleted again.
 * Hashes beyond the probe budget stay unchecked (shown as unknown, not "not cached").
 */
internal object RealDebridCacheProbe {
    // ponytail: fixed budget per check, keeps us far under RD's 250 requests/minute. Raise if
    // auto-play often runs out of cached candidates.
    private const val PROBES_PER_CHECK = 8
    private const val RESULT_TTL_MS = 30 * 60 * 1000L
    private const val OWNED_TTL_MS = 60 * 1000L

    private val mutex = Mutex()
    private val results = mutableMapOf<String, Pair<LocalDebridCachedItem?, Long>>()
    private var owned: Triple<String, Map<String, LocalDebridCachedItem>, Long>? = null
    private val probeSlots = Semaphore(3)

    suspend fun check(apiKey: String, hashes: List<String>): LocalDebridCacheCheck? {
        val wanted = hashes.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return LocalDebridCacheCheck(emptyMap(), emptySet())
        val now = Clock.System.now().toEpochMilliseconds()
        val ownedTorrents = ownedTorrents(apiKey, now) ?: return null

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
            toProbe.map { hash -> async { hash to probeSlots.withPermit { probe(apiKey, hash) } } }.awaitAll()
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

    private suspend fun ownedTorrents(apiKey: String, now: Long): Map<String, LocalDebridCachedItem>? {
        mutex.withLock { owned?.takeIf { it.first == apiKey && it.third > now }?.let { return it.second } }
        val response = try {
            RealDebridApiClient.listTorrents(apiKey)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        }
        if (!response.isSuccessful) return null
        val map = response.body.orEmpty()
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
    private suspend fun probe(apiKey: String, hash: String): Result<LocalDebridCachedItem?>? {
        var torrentId: String? = null
        return try {
            val add = RealDebridApiClient.addMagnet(apiKey, "magnet:?xt=urn:btih:$hash")
            torrentId = add.body?.id?.takeIf { add.isSuccessful && it.isNotBlank() } ?: return null
            val select = RealDebridApiClient.selectFiles(apiKey, torrentId, "all")
            if (!select.isSuccessful && select.status != 202) return null
            val info = RealDebridApiClient.getTorrentInfo(apiKey, torrentId)
            if (!info.isSuccessful) return null
            val body = info.body
            if (body?.status.equals("downloaded", ignoreCase = true)) {
                Result.success(LocalDebridCachedItem(name = body?.filename, size = body?.bytes))
            } else {
                Result.success(null)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } finally {
            // Also when the check was cancelled: otherwise the probe torrent stays in the account.
            torrentId?.let { id ->
                withContext(NonCancellable) { runCatching { RealDebridApiClient.deleteTorrent(apiKey, id) } }
            }
        }
    }
}

/**
 * TorBox matches hashes through its alternative-hash index and answers with the
 * canonical hash as key, so an alias hit would otherwise read as a cache miss.
 */
internal suspend fun resolveTorboxAliasMatches(
    cached: Map<String, LocalDebridCachedItem>,
    requestedHashes: List<String>,
    fetchCached: suspend (String) -> Map<String, LocalDebridCachedItem>? = { null },
): Map<String, LocalDebridCachedItem> {
    if (cached.keys.all { it in requestedHashes }) return cached
    // One requested hash: every returned entry can only be its match.
    if (requestedHashes.size == 1) {
        val item = cached.values.firstOrNull() ?: return cached
        return mapOf(requestedHashes.single() to item)
    }
    val resolved = cached.toMutableMap()
    val matches = coroutineScope {
        requestedHashes
            .filter { it !in resolved }
            .map { hash ->
                async {
                    val item = try {
                        fetchCached(hash)?.values?.firstOrNull()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    hash to item
                }
            }
            .awaitAll()
    }
    matches.forEach { (hash, item) ->
        if (item != null) resolved[hash] = item
    }
    return resolved
}
