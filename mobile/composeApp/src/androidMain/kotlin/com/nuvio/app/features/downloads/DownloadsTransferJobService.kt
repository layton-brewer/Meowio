package com.nuvio.app.features.downloads

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RequiresApi(34)
class DownloadsTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<String, Job>()

    override fun onStartJob(params: JobParameters): Boolean = startTransfer(params)

    override fun onNetworkChanged(params: JobParameters) {
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return
        if (runs[generation]?.isActive == true) startTransfer(params)
    }

    private fun startTransfer(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        val own = scheduler.store.get(fileName)?.takeIf { it.generation == generation && scheduler.isActive(it) }
        // This job's own download may already be done (another job picked it up from the queue);
        // then it carries on with the queue instead.
        val first = own ?: scheduler.claimNextQueued() ?: return false
        DownloadsLiveStatusPlatform.initialize(this)
        showNotification(params, first)
        val run = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var current: AndroidDownloadTransfer? = first
                var retryOwn = false
                while (current != null) {
                    val transfer: AndroidDownloadTransfer = current
                    val isOwn = transfer.generation == generation
                    if (transfer !== first) showNotification(params, transfer)
                    val retry = try {
                        scheduler.execute(transfer, params.network) { updated ->
                            updated.item.totalBytes?.let { updateEstimatedNetworkBytes(params, it, 0L) }
                            updateTransferredNetworkBytes(params, updated.item.downloadedBytes, 0L)
                            DownloadsLiveStatusPlatform.notifyTransfer(updated.item)
                        }
                    } finally {
                        scheduler.release(transfer.item.fileName)
                    }
                    scheduler.notifyCurrent(transfer.item.fileName)
                    if (retry) {
                        if (isOwn) {
                            // Android retries this job after a short wait; the queue continues then.
                            retryOwn = true
                            break
                        }
                        // A queued download hit a network error: give the connection a moment.
                        delay(QUEUED_RETRY_DELAY_MS)
                    }
                    // Next one from the queue, so the downloads keep going without the app open.
                    current = scheduler.claimNextQueued()
                }
                jobFinished(params, retryOwn)
            } finally {
                if (runs[generation] === currentCoroutineContext()[Job]) runs.remove(generation)
            }
        }
        runs.put(generation, run)?.cancel()
        run.start()
        return true
    }

    private fun showNotification(params: JobParameters, transfer: AndroidDownloadTransfer) {
        setNotification(params, DownloadsLiveStatusPlatform.notificationId(transfer.item.id),
            DownloadsLiveStatusPlatform.buildNotification(this, transfer.item), JOB_END_NOTIFICATION_POLICY_REMOVE)
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val scheduler = DownloadsPlatformDownloader.scheduler(this)
        val fileName = params.extras.getString(AndroidDownloadScheduler.FILE_NAME) ?: return false
        val generation = params.extras.getString(AndroidDownloadScheduler.GENERATION) ?: return false
        runs.remove(generation)?.cancel()
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation }
            ?: return scheduler.hasQueued()
        if (params.stopReason == JobParameters.STOP_REASON_USER) {
            scheduler.pause(fileName)
            return false
        }
        // Run again later while this download or any queued one still needs downloading.
        return scheduler.isActive(transfer) || scheduler.hasQueued()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val QUEUED_RETRY_DELAY_MS = 15_000L
    }
}
