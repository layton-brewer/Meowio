package com.nuvio.tv.core.downloads

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class TvDownloadStatus { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED }

@Serializable
data class TvDownload(
    val id: String,
    val profileId: Int,
    val title: String,
    val detail: String = "",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String,
    val contentId: String? = null,
    val contentType: String? = null,
    val contentName: String? = null,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val year: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: TvDownloadStatus = TvDownloadStatus.QUEUED,
    val addedAtMs: Long = 0
) {
    val progressPercent: Int?
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else null
}

/** What a caller knows about a stream it wants saved. */
data class TvDownloadRequest(
    val profileId: Int,
    val title: String,
    val detail: String,
    val url: String,
    val headers: Map<String, String>,
    val suggestedFileName: String?,
    val contentId: String?,
    val contentType: String?,
    val contentName: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val year: String?
)

/**
 * Saves direct video links to the TV's storage so they can be played offline.
 *
 * One file downloads at a time and an interrupted one resumes where it stopped. Downloads run
 * while the app is open; anything unfinished is paused on the next launch and can be resumed.
 */
@Singleton
class TvDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeLock = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val directory: File by lazy {
        (context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")).apply { mkdirs() }
    }
    private val indexFile: File by lazy { File(context.filesDir, "tv_downloads.json") }

    private val mutableDownloads = MutableStateFlow(loadIndex())
    val downloads: StateFlow<List<TvDownload>> = mutableDownloads.asStateFlow()

    private var worker: Job? = null
    private var activeId: String? = null

    fun fileFor(download: TvDownload): File = File(directory, download.fileName)

    /** Returns false when the link is not a plain file that can be saved. */
    fun enqueue(request: TvDownloadRequest): Boolean {
        if (!isDownloadable(request.url)) return false
        if (mutableDownloads.value.any { it.url == request.url && it.profileId == request.profileId }) return true
        val id = UUID.randomUUID().toString()
        val download = TvDownload(
            id = id,
            profileId = request.profileId,
            title = request.title,
            detail = request.detail,
            url = request.url,
            headers = request.headers,
            fileName = "$id.${extensionFor(request.suggestedFileName, request.url)}",
            contentId = request.contentId,
            contentType = request.contentType,
            contentName = request.contentName,
            videoId = request.videoId,
            season = request.season,
            episode = request.episode,
            episodeTitle = request.episodeTitle,
            poster = request.poster,
            backdrop = request.backdrop,
            logo = request.logo,
            year = request.year,
            addedAtMs = System.currentTimeMillis()
        )
        mutableDownloads.update { it + download }
        persist()
        pump()
        return true
    }

    fun resume(id: String) {
        change(id) { item ->
            if (item.status == TvDownloadStatus.DONE) item else item.copy(status = TvDownloadStatus.QUEUED)
        }
        pump()
    }

    fun remove(id: String) {
        val item = mutableDownloads.value.firstOrNull { it.id == id } ?: return
        if (activeId == id) worker?.cancel()
        mutableDownloads.update { list -> list.filterNot { it.id == id } }
        persist()
        scope.launch { runCatching { fileFor(item).delete() } }
        pump()
    }

    private fun pump() {
        if (worker?.isActive == true) return
        val next = mutableDownloads.value.firstOrNull { it.status == TvDownloadStatus.QUEUED } ?: return
        activeId = next.id
        worker = scope.launch {
            try {
                transfer(next)
            } finally {
                activeId = null
                worker = null
                pump()
            }
        }
    }

    private suspend fun transfer(item: TvDownload) {
        val file = fileFor(item)
        var written = if (file.exists()) file.length() else 0L
        change(item.id) { it.copy(status = TvDownloadStatus.DOWNLOADING, downloadedBytes = written) }
        try {
            val request = Request.Builder().url(item.url).apply {
                item.headers.forEach { (name, value) -> runCatching { header(name, value) } }
                if (written > 0) header("Range", "bytes=$written-")
            }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416 && written > 0) {
                    // The server says there is nothing after what we already have.
                    change(item.id) { it.copy(status = TvDownloadStatus.DONE, totalBytes = written, downloadedBytes = written) }
                    return
                }
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("empty body")
                val resumed = response.code == 206 && written > 0
                if (!resumed) written = 0L
                val remaining = body.contentLength()
                val total = if (remaining >= 0) written + remaining else -1L
                if (total > 0 && directory.usableSpace < (total - written) + MIN_FREE_BYTES) error("not enough space")
                RandomAccessFile(file, "rw").use { output ->
                    if (resumed) output.seek(written) else output.setLength(0)
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContextActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 1000) {
                                lastReport = now
                                val done = written
                                changeInMemory(item.id) { it.copy(totalBytes = total, downloadedBytes = done) }
                            }
                        }
                    }
                }
                val done = written
                val complete = total < 0 || done >= total
                change(item.id) {
                    it.copy(
                        status = if (complete) TvDownloadStatus.DONE else TvDownloadStatus.FAILED,
                        totalBytes = if (total > 0) total else done,
                        downloadedBytes = done
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "download failed: ${error.message}")
            val done = written
            change(item.id) { it.copy(status = TvDownloadStatus.FAILED, downloadedBytes = done) }
        }
    }

    private suspend fun coroutineContextActive() = kotlin.coroutines.coroutineContext.ensureActive()

    private fun changeInMemory(id: String, transform: (TvDownload) -> TvDownload) {
        mutableDownloads.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun change(id: String, transform: (TvDownload) -> TvDownload) {
        changeInMemory(id, transform)
        persist()
    }

    private fun persist() {
        val snapshot = mutableDownloads.value
        scope.launch {
            storeLock.withLock {
                runCatching {
                    val temp = File(indexFile.parentFile, indexFile.name + ".tmp")
                    temp.writeText(json.encodeToString(ListSerializer(TvDownload.serializer()), snapshot))
                    if (!temp.renameTo(indexFile)) {
                        indexFile.writeText(temp.readText())
                        temp.delete()
                    }
                }
            }
        }
    }

    private fun loadIndex(): List<TvDownload> = runCatching {
        if (!indexFile.exists()) return@runCatching emptyList()
        json.decodeFromString(ListSerializer(TvDownload.serializer()), indexFile.readText()).map { item ->
            // Nothing is running right after launch, so anything mid-flight waits to be resumed.
            when (item.status) {
                TvDownloadStatus.DOWNLOADING, TvDownloadStatus.QUEUED -> item.copy(status = TvDownloadStatus.PAUSED)
                else -> item
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "TvDownloads"
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private val videoExtensions = setOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts", "wmv", "flv")

        /** Only plain http(s) files can be saved: not torrents, playlists or local relays. */
        fun isDownloadable(url: String?): Boolean {
            val value = url?.trim().orEmpty()
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return false
            val path = value.substringBefore('?').substringBefore('#').lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".m3u")) return false
            val host = value.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            return host != "localhost" && host != "127.0.0.1"
        }

        fun extensionFor(fileName: String?, url: String): String {
            val candidates = listOf(fileName.orEmpty(), url.substringBefore('?').substringBefore('#'))
            return candidates
                .map { it.substringAfterLast('/').substringAfterLast('.', "").lowercase() }
                .firstOrNull { it in videoExtensions }
                ?: "mkv"
        }
    }
}
