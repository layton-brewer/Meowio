package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool

internal class AndroidDownloadScheduler(val context: Context) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))
    val directory: File get() = DownloadLocation.directory(context)

    /** Folder a transfer writes into: the one it started in if still usable, else the current choice. */
    private fun targetFor(transfer: AndroidDownloadTransfer?): AndroidDownloadTarget =
        transfer?.targetLocation?.let { AndroidDownloadTarget.fromLocation(context, it) }
            ?: DownloadLocation.target(context)

    /** Plain file whose `.subtitles` folder holds the subtitles saved for this download. */
    private fun subtitleAnchor(target: AndroidDownloadTarget, fileName: String): File = when (target) {
        is AndroidDownloadTarget.Folder -> File(target.directory, fileName)
        is AndroidDownloadTarget.Tree -> File(DownloadLocation.subtitleDirectory(context), fileName)
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(item: DownloadItem): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        val transfer = store.begin(item)
        val existing = previous?.generation == transfer.generation
        if (existing && Build.VERSION.SDK_INT >= 34) return transfer
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // At most MAX_ACTIVE downloads run, like before 0.2.79. The rest wait in the queue:
                // a running download job starts the next one when it finishes (no new job is needed,
                // so this keeps going in the background), and the app fills free slots while open.
                val scheduler = jobScheduler()
                if (scheduler.allPendingJobs.size < MAX_ACTIVE) {
                    runCatching { scheduler.schedule(buildJob(transfer)) }
                }
            } else {
                val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
                    .setInputData(workDataOf(FILE_NAME to item.fileName, GENERATION to transfer.generation))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(transfer), if (existing) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, work,
                )
                operation.result.addListener({
                    try {
                        operation.result.get()
                    } catch (error: Exception) {
                        fail(transfer, error)
                        notifyCurrent(item.fileName)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
        return store.get(item.fileName) ?: transfer
    }

    @RequiresApi(34)
    internal fun buildJob(transfer: AndroidDownloadTransfer): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    @RequiresApi(34)
    private fun jobScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    fun restore(item: DownloadItem): DownloadItem {
        val transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        // A download without a job of its own is waiting in the queue; it is started by a running
        // download job or when the app is opened, so it stays queued here.
        return transfer.item
    }

    fun pause(fileName: String) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val paused = store.update(fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused, errorMessage = null))
        }
        cancelScheduled(transfer)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        store.remove(fileName)
        transfer?.let(::cancelScheduled)
        transfer?.let { DownloadsLiveStatusPlatform.removeNotification(it.item.id) }
        cleanupScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    val target = targetFor(transfer)
                    runCatching { target.deletePartial(fileName) }
                    DownloadSubtitleStorage(subtitleAnchor(target, fileName).toURI().toString()).remove()
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        val target = targetFor(transfer)
        if (transfer.targetLocation != target.location) {
            updateActive(transfer) { it.copy(targetLocation = target.location) }
        }
        val client = if (network != null) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else downloadHttpClient
        try {
            DownloadSubtitles.prepare(transfer.item, subtitleAnchor(target, fileName).toURI().toString())
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            var lastProgressAt = 0L
            val alreadyFinished = target.finishedUri(fileName)
            running[fileName] = Progress(transfer.item.downloadedBytes, SystemClock.elapsedRealtime())
            if (alreadyFinished == null) transferAndroidDownload(
                item = transfer.item,
                target = target,
                validator = transfer.validator,
                client = client,
                onHeaders = { total, validator ->
                    updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
                },
                onProgress = { bytes, total ->
                    // Paused or removed while a queued job was downloading it: stop here.
                    if (!isActive(transfer)) throw IOException("Download stopped")
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= 1_000L || bytes == total) {
                        lastProgressAt = now
                        updateActive(transfer) {
                            it.copy(item = it.item.copy(downloadedBytes = bytes, totalBytes = total))
                        }?.let(onProgress)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            updateActive(transfer) { current ->
                val finishedUri = alreadyFinished ?: target.finish(fileName)
                val bytes = target.finishedLength(finishedUri)
                current.copy(item = current.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = finishedUri,
                    downloadedBytes = bytes,
                    totalBytes = bytes,
                    errorMessage = null,
                ))
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val retry = shouldRetryAndroidDownload(error, transfer.retryCount)
            if (retry) updateActive(transfer) { it.copy(retryCount = it.retryCount + 1) }
            else fail(transfer, error)
            retry
        } finally {
            running.remove(fileName)
            if (network != null) withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = error.message ?: "Download failed"))
        }
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private class Progress(val bytes: Long, val at: Long)

    /** Transfers being downloaded right now (or claimed by a job that is about to start them). */
    private val running = ConcurrentHashMap<String, Progress>()

    private fun hasOwnJob(transfer: AndroidDownloadTransfer): Boolean =
        Build.VERSION.SDK_INT >= 34 && jobScheduler().getPendingJob(transfer.jobId) != null

    /** Queued downloads, oldest first: still to download, not running and without a job of their own. */
    private fun queued(): List<AndroidDownloadTransfer> =
        store.transfers.value.values
            .filter { it.item.status == DownloadStatus.Downloading && !running.containsKey(it.item.fileName) && !hasOwnJob(it) }
            .sortedBy { it.item.createdAtEpochMs }

    fun hasQueued(): Boolean = queued().isNotEmpty()

    /** Takes the next queued download for a job that just finished one. */
    @Synchronized
    fun claimNextQueued(): AndroidDownloadTransfer? {
        val next = queued().firstOrNull() ?: return null
        running[next.item.fileName] = Progress(next.item.downloadedBytes, SystemClock.elapsedRealtime())
        return next
    }

    fun release(fileName: String) {
        running.remove(fileName)
    }

    /**
     * While the app is on screen (the only time Android lets the app start a download job):
     * starts queued downloads until MAX_ACTIVE are running, e.g. after the app was closed.
     */
    @Synchronized
    fun keepQueueMoving() {
        if (Build.VERSION.SDK_INT < 34) return
        val scheduler = jobScheduler()
        val pending = scheduler.allPendingJobs
        if (pending.size > MAX_ACTIVE) {
            // Older versions gave every queued download its own job at once; put the extra ones
            // back in the queue (they resume from where they stopped), keeping running ones first.
            val runningJobIds = store.transfers.value.values
                .filter { running.containsKey(it.item.fileName) }
                .map { it.jobId }
                .toSet()
            pending.sortedBy { if (it.id in runningJobIds) 0 else 1 }
                .drop(MAX_ACTIVE)
                .forEach { job -> scheduler.cancel(job.id) }
            return
        }
        var free = MAX_ACTIVE - pending.size
        if (free <= 0) return
        for (transfer in queued()) {
            if (free <= 0) break
            try {
                if (scheduler.schedule(buildJob(transfer)) == JobScheduler.RESULT_SUCCESS) free--
            } catch (error: Exception) {
                return // not allowed right now (for example the app just left the screen)
            }
        }
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        /** How many downloads transfer at the same time. */
        const val MAX_ACTIVE = 3

        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int): Boolean =
    retries < 4 && when (error) {
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }
