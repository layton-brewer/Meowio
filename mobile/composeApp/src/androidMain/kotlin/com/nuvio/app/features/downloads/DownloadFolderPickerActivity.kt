package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred

/**
 * Invisible screen that opens Android's system folder picker so the user can choose any folder
 * (phone storage, SD card, USB drive) for downloads. The app keeps lasting read and write access
 * to the picked folder.
 */
internal class DownloadFolderPickerActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        finishWith(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            runCatching { picker.launch(initialUri()) }.onFailure { finishWith(null) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) pending?.complete(null)
    }

    private fun initialUri(): Uri? = DownloadLocation.savedTreeUri(this)

    private fun finishWith(uri: Uri?) {
        val granted = uri?.let { tree ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                tree.toString()
            }.getOrNull()
        }
        pending?.complete(granted)
        pending = null
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        @Volatile
        private var pending: CompletableDeferred<String?>? = null

        /** Opens the system folder picker; the result is the picked folder's tree URI or null. */
        fun launch(context: Context): CompletableDeferred<String?> {
            pending?.complete(null)
            val result = CompletableDeferred<String?>()
            pending = result
            val intent = Intent(context, DownloadFolderPickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure {
                pending = null
                result.complete(null)
            }
            return result
        }
    }
}
