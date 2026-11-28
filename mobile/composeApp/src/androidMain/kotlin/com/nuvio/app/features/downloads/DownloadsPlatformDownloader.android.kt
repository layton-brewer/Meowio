package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.takeWhile
import java.io.File
import java.net.URI

internal actual object DownloadsPlatformDownloader {
    private var appContext: Context? = null
    private var downloadScheduler: AndroidDownloadScheduler? = null
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    internal fun contextOrNull(): Context? = appContext

    private var queueKeeperStarted = false

    fun initialize(context: Context) {
        AutoDownloader.enqueueAllAtOnce = true
        val scheduler = scheduler(context)
        startQueueKeeper(scheduler)
    }

    /** While the app is on screen, checks every half minute that queued downloads keep moving. */
    @Synchronized
    private fun startQueueKeeper(scheduler: AndroidDownloadScheduler) {
        if (queueKeeperStarted) return
        queueKeeperStarted = true
        observerScope.launch {
            AppForegroundMonitor.events().collectLatest { visibility ->
                if (visibility != AppVisibility.Foreground) return@collectLatest
                while (true) {
                    runCatching { scheduler.keepQueueMoving() }
                    delay(30_000L)
                }
            }
        }
    }

    @Synchronized
    internal fun scheduler(context: Context): AndroidDownloadScheduler {
        appContext = context.applicationContext
        return downloadScheduler ?: AndroidDownloadScheduler(context.applicationContext).also { downloadScheduler = it }
    }

    internal fun managedTransfers(): List<AndroidDownloadTransfer> =
        downloadScheduler?.store?.transfers?.value?.values?.toList().orEmpty()

    actual fun restoreItem(item: DownloadItem): DownloadItem = downloadScheduler?.restore(item)
        ?: if (item.status == DownloadStatus.Downloading) item.copy(status = DownloadStatus.Paused) else item

    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        val scheduler = checkNotNull(downloadScheduler) { "Downloads are not initialized" }
        val transfer = scheduler.enqueue(request.item)
        val observer = observerScope.launch {
            scheduler.store.transfers.map { it[request.destinationFileName] }
                .distinctUntilChanged()
                .takeWhile { current ->
                    if (current == null || current.generation != transfer.generation) return@takeWhile false
                    val item = current.item
                    when (item.status) {
                        DownloadStatus.Downloading -> onProgress(item.downloadedBytes, item.totalBytes)
                        DownloadStatus.Completed -> onSuccess(checkNotNull(item.localFileUri), item.totalBytes)
                        DownloadStatus.Failed -> onFailure(item.errorMessage ?: "Download failed")
                        DownloadStatus.Paused -> onPaused()
                    }
                    item.status == DownloadStatus.Downloading
                }.collect()
        }
        return object : DownloadsTaskHandle {
            override fun cancel() {
                observer.cancel()
                if (scheduler.store.get(request.destinationFileName)?.generation == transfer.generation) {
                    scheduler.pause(request.destinationFileName)
                }
            }
        }
    }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        if (isContentUri(localFileUri)) {
            val context = appContext ?: return false
            val subtitles = DownloadSubtitleStorage(localFileUri)
            val deleted = deleteTreeDocument(context, localFileUri)
            if (deleted) {
                runCatching { subtitles.remove() }
                DownloadLocation.forgetTreeFileName(context, localFileUri)
            }
            return deleted
        }
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val scheduler = downloadScheduler ?: return false
        scheduler.remove(destinationFileName)
        return true
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        if (isContentUri(localFileUri)) {
            val context = appContext ?: return null
            if (treeDocumentExists(context, localFileUri!!)) return localFileUri
            val fileName = destinationFileName.trim().takeIf { it.isNotBlank() } ?: return null
            return (DownloadLocation.target(context) as? AndroidDownloadTarget.Tree)?.finishedUri(fileName)
        }
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val context = appContext ?: return null
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val downloadsDir = DownloadLocation.directory(context)
        val localFile = File(downloadsDir, fileName)
        return localFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val context = appContext ?: return false
        (DownloadLocation.target(context) as? AndroidDownloadTarget.Tree)?.let { tree ->
            if (openPickedFolder(context, tree)) return true
        }
        val downloadsDir = DownloadLocation.directory(context).apply { mkdirs() }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                downloadsDir,
            )
        }.getOrNull() ?: return false

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
            },
        )

        return intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

/** Opens a folder the user picked with the system file manager. */
private fun openPickedFolder(context: Context, tree: AndroidDownloadTarget.Tree): Boolean {
    val folder = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(tree.tree, DocumentsContract.getTreeDocumentId(tree.tree))
    }.getOrNull() ?: return false
    return listOf("vnd.android.document/directory", null).any { type ->
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    if (type != null) setDataAndType(folder, type) else data = folder
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            true
        }.getOrDefault(false)
    }
}

private fun String.toLocalFileOrNull(): File? {
    return runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
}
