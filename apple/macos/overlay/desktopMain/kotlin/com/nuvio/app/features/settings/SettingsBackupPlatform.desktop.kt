package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

private fun chooseFile(mode: Int, title: String, fileName: String?): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    if (fileName != null) dialog.file = fileName
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: "", name)
}

@Composable
internal actual fun rememberBackupFileSaver(onDone: (saved: Boolean) -> Unit): (fileName: String, text: String) -> Unit {
    val scope = rememberCoroutineScope()
    val latestDone = rememberUpdatedState(onDone)
    return remember {
        { fileName, text ->
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    val target = chooseFile(FileDialog.SAVE, "Save Meowio backup", fileName) ?: return@withContext null
                    runCatching { target.writeText(text, Charsets.UTF_8) }.isSuccess
                }
                if (outcome != null) latestDone.value(outcome)
            }
        }
    }
}

@Composable
internal actual fun rememberBackupFileOpener(onText: (String?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val latestText = rememberUpdatedState(onText)
    return remember {
        {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    val source = chooseFile(FileDialog.LOAD, "Restore Meowio backup", null) ?: return@withContext false to null
                    true to runCatching {
                        val bytes = source.readBytes()
                        require(bytes.size <= MAX_BACKUP_BYTES)
                        String(bytes, Charsets.UTF_8)
                    }.getOrNull()
                }
                if (picked.first) latestText.value(picked.second)
            }
        }
    }
}

internal actual val backupFilesSupported: Boolean = true
