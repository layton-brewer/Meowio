@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField

/** RatingPosterDB (RPDB): posters with IMDb, TMDB and Rotten Tomatoes ratings drawn on them. */
internal object RatingPosterKey {
    private const val PREFIX = "https://api.ratingposterdb.com/"
    private const val SUFFIX = "/imdb/poster-default/{imdb_id}.jpg"

    fun patternFor(key: String): String = PREFIX + key.trim() + SUFFIX

    fun keyFrom(pattern: String): String? =
        pattern.trim()
            .takeIf { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            ?.removePrefix(PREFIX)
            ?.removeSuffix(SUFFIX)
            ?.takeIf { it.isNotBlank() && '/' !in it }
}

/** "Ratings on posters": the RatingPosterDB key fills in the custom poster source. */
@Composable
internal fun RatingPosterKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember { mutableStateOf(currentKey) }
    val save = { if (key.isNotBlank()) onSave(key.trim()) }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "Ratings on posters",
        subtitle = "Add your RatingPosterDB key (ratingposterdb.com) and every poster shows its IMDb, TMDB and Rotten Tomatoes ratings. The key stays on this device.",
        width = 640.dp
    ) {
        InputField(
            value = key,
            onValueChange = { key = it },
            placeholder = "RatingPosterDB key",
            imeAction = ImeAction.Done,
            onImeAction = save,
            modifier = Modifier.fillMaxWidth()
        )
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = "Cancel", onClick = onDismiss)
            if (currentKey.isNotBlank()) {
                SettingsDialogActionButton(text = "Remove", onClick = onRemove)
            }
            SettingsDialogActionButton(text = "Save", onClick = save, primary = true, enabled = key.isNotBlank())
        }
    }
}
