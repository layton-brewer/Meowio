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
                check(jobScheduler().schedule(buildJob(transfer)) == JobScheduler.RESULT_SUCCESS) {
                    "Android could not start the download. Open the app and try again."
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
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(status = DownloadStatus.Paused, errorMessage = null)
            } else item
        if (transfer.item.status == DownloadStatus.Downloading && Build.VERSION.SDK_INT >= 34 &&
            jobScheduler().getPendingJob(transfer.jobId) == null) {
            pause(item.fileName)
            transfer = store.get(item.fileName) ?: transfer
        }
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
            // Every queued download has its own job and Android decides how many run at once. A
            // running job always transfers straight away: a job that sits idle waiting for a turn
            // gets stopped by Android and pushed back, which left whole queues at 0%.
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
                    val now = SystemClock.elapsedRealtime()
                    running[fileName]?.let { mark -> if (mark.bytes != bytes) running[fileName] = Progress(bytes, now) }
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

    /** Transfers running right now, with the last time each one received bytes. */
    private val running = ConcurrentHashMap<String, Progress>()

    /**
     * Keeps the queue moving while the app is on screen (the only time Android lets the app start
     * a download job): a queued download whose job went missing gets a new one, and a running
     * download that has received nothing for a few minutes is restarted on a fresh connection.
     */
    fun keepQueueMoving() {
        if (Build.VERSION.SDK_INT < 34) return
        val scheduler = jobScheduler()
        val now = SystemClock.elapsedRealtime()
        for (transfer in store.transfers.value.values) {
            if (transfer.item.status != DownloadStatus.Downloading) continue
            val fileName = transfer.item.fileName
            val mark = running[fileName]
            val missing = mark == null && scheduler.getPendingJob(transfer.jobId) == null
            val stalled = mark != null && now - mark.at >= STALL_RESTART_MS
            if (!missing && !stalled) continue
            try {
                if (stalled) running.remove(fileName)
                scheduler.schedule(buildJob(transfer))
            } catch (error: Exception) {
                // Not allowed right now (for example the app just left the screen); try next time.
            }
        }
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        /** A running download with no new bytes for this long is restarted. */
        private const val STALL_RESTART_MS = 3L * 60_000L

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
