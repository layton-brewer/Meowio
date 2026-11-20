package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.poster.CustomPosterUrlRepository

/** RatingPosterDB (RPDB): posters with IMDb, TMDB and Rotten Tomatoes ratings drawn on them. */
internal object RatingPosterKey {
    private const val PREFIX = "https://api.ratingposterdb.com/"
    private const val SUFFIX = "/imdb/poster-default/{imdb_id}.jpg"

    fun patternFor(key: String): String = PREFIX + key.trim() + SUFFIX

    /** The key, when the custom poster source is an RPDB pattern made from one. */
    fun keyFrom(pattern: String): String? =
        pattern.trim()
            .takeIf { it.startsWith(PREFIX) && it.endsWith(SUFFIX) }
            ?.removePrefix(PREFIX)
            ?.removeSuffix(SUFFIX)
            ?.takeIf { it.isNotBlank() && '/' !in it }
}

/**
 * "Ratings on posters": paste a RatingPosterDB key and every poster shows its ratings. It fills in
 * the custom poster source below, so screens and fallbacks work the same as a hand-written pattern.
 */
@Composable
internal fun RatingPosterKeySection(isTablet: Boolean) {
    CustomPosterUrlRepository.ensureLoaded()
    val currentPattern by CustomPosterUrlRepository.pattern.collectAsState()
    val savedKey = RatingPosterKey.keyFrom(currentPattern).orEmpty()
    var editingKey by rememberSaveable(savedKey) { mutableStateOf(savedKey) }

    SettingsSection(title = "RATINGS ON POSTERS", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Add your RatingPosterDB key (ratingposterdb.com) and every poster shows its IMDb, TMDB and Rotten Tomatoes ratings. The key stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = editingKey,
                    onValueChange = { editingKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("RatingPosterDB key", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { CustomPosterUrlRepository.setPattern(RatingPosterKey.patternFor(editingKey)) },
                        enabled = editingKey.isNotBlank() && editingKey.trim() != savedKey,
                    ) {
                        Text("Save")
                    }
                    if (savedKey.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                editingKey = ""
                                CustomPosterUrlRepository.clearPattern()
                            },
                        ) {
                            Text("Remove")
                        }
                    }
                }
                if (savedKey.isNotBlank()) {
                    Text(
                        text = "✓ Ratings on posters are on",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
