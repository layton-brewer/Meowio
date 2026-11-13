package com.nuvio.app.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

@Composable
internal actual fun rememberBackupFileSaver(onDone: (saved: Boolean) -> Unit): (fileName: String, text: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestDone = rememberUpdatedState(onDone)
    val pendingText = remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingText.value
        pendingText.value = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray()) }
                }.isSuccess
            }
            latestDone.value(saved)
        }
    }
    return { fileName, text ->
        pendingText.value = text
        launcher.launch(fileName)
    }
}

@Composable
internal actual fun rememberBackupFileOpener(onText: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestText = rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= MAX_BACKUP_BYTES)
                        String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            latestText.value(text)
        }
    }
    return { launcher.launch(arrayOf("application/json", "text/*", "*/*")) }
}

internal actual val backupFilesSupported: Boolean = true
