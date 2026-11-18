package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.RealDebridApiClient
import com.nuvio.app.features.debrid.RealDebridTorrentInfoDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Real-Debrid's torrents list. A finished torrent's `links` are its selected files in order; the
 * list call has no file names, so torrents with several files are looked up one by one.
 */
internal class RealDebridCloudLibraryProviderApi : CloudLibraryProviderApi {
    override val provider = DebridProviders.RealDebrid

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val response = RealDebridApiClient.listTorrents(apiKey)
            if (!response.isSuccessful) {
                throw IllegalStateException(response.rawBody.takeIf { it.isNotBlank() } ?: "Real-Debrid request failed (HTTP ${response.status}).")
            }
            val torrents = response.body.orEmpty()
            val slots = Semaphore(4)
            // ponytail: file names for at most 60 multi-file torrents per refresh (RD allows 250
            // calls a minute); later ones show numbered files until the next refresh.
            var lookups = 0
            coroutineScope {
                torrents.map { torrent ->
                    val needsInfo = torrent.isDownloaded && torrent.links.orEmpty().size > 1 && lookups++ < 60
                    async {
                        val info = if (needsInfo) {
                            slots.withPermit { runCatching { RealDebridApiClient.getTorrentInfo(apiKey, torrent.id.orEmpty()).body }.getOrNull() }
                        } else {
                            null
                        }
                        realDebridCloudItem(torrent, info, provider.id, provider.displayName)
                    }
                }.awaitAll().filterNotNull()
            }
        }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val link = file.id?.takeIf { it.isNotBlank() } ?: return CloudLibraryPlaybackResult.Failed()
        return try {
            val response = RealDebridApiClient.unrestrictLink(apiKey, link)
            val url = response.body?.download?.takeIf { response.isSuccessful && it.isNotBlank() }
                ?: return CloudLibraryPlaybackResult.Failed(response.rawBody.takeIf { it.isNotBlank() })
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = response.body.filename?.takeIf { it.isNotBlank() } ?: file.name,
                videoSizeBytes = response.body.filesize ?: file.sizeBytes,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }
}

private val RealDebridTorrentInfoDto.isDownloaded: Boolean
    get() = status.equals("downloaded", ignoreCase = true)

internal fun realDebridCloudItem(
    torrent: RealDebridTorrentInfoDto,
    info: RealDebridTorrentInfoDto?,
    providerId: String,
    providerName: String,
): CloudLibraryItem? {
    val id = torrent.id?.takeIf { it.isNotBlank() } ?: return null
    val name = torrent.filename?.takeIf { it.isNotBlank() } ?: id
    val links = torrent.links.orEmpty().filter { it.isNotBlank() }
    val selected = info?.files.orEmpty().filter { it.selected == 1 }.sortedBy { it.id ?: 0 }
    val files = if (!torrent.isDownloaded) {
        emptyList()
    } else {
        links.mapIndexed { index, link ->
            val selectedFile = selected.getOrNull(index)?.takeIf { selected.size == links.size }
            val fileName = when {
                selectedFile != null -> selectedFile.displayName()
                links.size == 1 -> name
                else -> "$name (${index + 1})"
            }
            CloudLibraryFile(
                id = link,
                name = fileName,
                sizeBytes = selectedFile?.bytes ?: torrent.bytes.takeIf { links.size == 1 },
                // Unnamed parts of a multi-file torrent might be videos; let the player decide.
                playable = isPlayablePremiumizeCloudFile(fileName, null) || (selectedFile == null && links.size > 1),
            )
        }.sortedWith(compareBy<CloudLibraryFile> { !it.playable }.thenBy { it.name.lowercase() })
    }
    return CloudLibraryItem(
        providerId = providerId,
        providerName = providerName,
        id = id,
        type = CloudLibraryItemType.Torrent,
        name = name,
        status = if (torrent.isDownloaded) "Ready" else torrent.status,
        sizeBytes = torrent.bytes,
        progressFraction = torrent.progress?.let { it / 100f },
        files = files,
    )
}
