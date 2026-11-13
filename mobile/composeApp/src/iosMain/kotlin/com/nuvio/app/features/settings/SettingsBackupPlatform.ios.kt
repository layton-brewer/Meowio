package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

// The iPhone build has no file picker for backups yet, so the section is hidden there.
@Composable
internal actual fun rememberBackupFileSaver(onDone: (saved: Boolean) -> Unit): (fileName: String, text: String) -> Unit =
    { _, _ -> onDone(false) }

@Composable
internal actual fun rememberBackupFileOpener(onText: (String?) -> Unit): () -> Unit = { onText(null) }

internal actual val backupFilesSupported: Boolean = false
