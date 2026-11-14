package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows where downloads are saved and lets the user change it. */
@Composable
internal fun DownloadLocationRow() {
    var path by remember { mutableStateOf(DownloadLocation.currentPath()) }
    var showPicker by remember { mutableStateOf(false) }
    val options = remember { DownloadLocation.options() }
    val canChange = DownloadLocation.canBrowse || options.size > 1
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (canChange) it.clickable { showPicker = true } else it }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = if (canChange) "Download location (tap to change)" else "Download location",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Download location") },
            text = {
                Column {
                    Text(
                        "New downloads are saved here. Items you already downloaded stay where they are.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    options.forEach { option ->
                        val selected = option.path == path
                        TextButton(
                            onClick = {
                                DownloadLocation.select(option.path)
                                path = DownloadLocation.currentPath()
                                showPicker = false
                            },
                        ) {
                            Text((if (selected) "✓ " else "") + option.label)
                        }
                    }
                    if (DownloadLocation.canBrowse) {
                        TextButton(
                            onClick = {
                                showPicker = false
                                scope.launch {
                                    val picked = withContext(Dispatchers.Default) { DownloadLocation.browse() }
                                    if (picked != null) {
                                        DownloadLocation.select(picked)
                                        path = DownloadLocation.currentPath()
                                    }
                                }
                            },
                        ) { Text("Choose folder…") }
                    }
                    if (DownloadLocation.isCustom()) {
                        TextButton(
                            onClick = {
                                DownloadLocation.select(null)
                                path = DownloadLocation.currentPath()
                                showPicker = false
                            },
                        ) { Text("Reset to default") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Close") } },
        )
    }
}
