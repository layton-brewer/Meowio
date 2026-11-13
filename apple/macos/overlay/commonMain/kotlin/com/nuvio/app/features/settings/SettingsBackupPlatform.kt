package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/** Returns an action that asks where to save a backup file and writes [text] there. */
@Composable
internal expect fun rememberBackupFileSaver(onDone: (saved: Boolean) -> Unit): (fileName: String, text: String) -> Unit

/** Returns an action that asks for a backup file and hands back its text, or null when it could not be read. */
@Composable
internal expect fun rememberBackupFileOpener(onText: (String?) -> Unit): () -> Unit

/** False where the platform has no file picker wired up yet. */
internal expect val backupFilesSupported: Boolean
