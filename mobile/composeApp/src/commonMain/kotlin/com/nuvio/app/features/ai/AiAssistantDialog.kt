package com.nuvio.app.features.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.livetv.LiveTvPlatformStorage
import com.nuvio.app.features.settings.SettingsSecretTextField
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.ai_ask
import nuvio.composeapp.generated.resources.ai_error_busy
import nuvio.composeapp.generated.resources.ai_error_declined
import nuvio.composeapp.generated.resources.ai_error_key
import nuvio.composeapp.generated.resources.ai_error_network
import nuvio.composeapp.generated.resources.ai_error_unexpected
import nuvio.composeapp.generated.resources.ai_key_note
import nuvio.composeapp.generated.resources.ai_key_placeholder
import nuvio.composeapp.generated.resources.ai_kind_movie
import nuvio.composeapp.generated.resources.ai_kind_series
import nuvio.composeapp.generated.resources.ai_note
import nuvio.composeapp.generated.resources.ai_prompt_placeholder
import nuvio.composeapp.generated.resources.ai_remove_key
import nuvio.composeapp.generated.resources.ai_save_key
import nuvio.composeapp.generated.resources.ai_thinking
import nuvio.composeapp.generated.resources.ai_title
import nuvio.composeapp.generated.resources.insights_close
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Carries a title picked in the assistant over to the Search tab, which looks it up. */
object AiSearchHandoff {
    val pendingTitle = MutableStateFlow<String?>(null)
}

/**
 * The viewer's own key. It is kept in the per-profile device store that is never synced or
 * backed up, and is only ever sent to the assistant's endpoint.
 */
private object AiKeyStore {
    private const val KEY = "ai_api_key"
    fun load(): String = LiveTvPlatformStorage.load(KEY).orEmpty()
    fun save(value: String) = LiveTvPlatformStorage.save(KEY, value.trim().ifEmpty { null })
}

private fun viewerContext(): AiViewerContext = runCatching {
    WatchProgressRepository.ensureLoaded()
    LibraryRepository.ensureLoaded()
    AiViewerContext(
        recentlyWatched = WatchProgressRepository.uiState.value.entries
            .sortedByDescending { it.lastUpdatedEpochMs }
            .map { it.title },
        library = LibraryRepository.uiState.value.items.map { it.name },
    )
}.getOrDefault(AiViewerContext())

private suspend fun askAssistant(key: String, prompt: String): AiResult = withContext(Dispatchers.Default) {
    if (key.isBlank()) return@withContext AiResult.Failure(AiFailure.NO_KEY)
    try {
        val response = httpRequestRaw(
            method = "POST",
            url = AiAssistant.ENDPOINT,
            headers = AiAssistant.headers(key),
            body = AiAssistant.requestBody(prompt, viewerContext()),
        )
        AiAssistant.parseResponse(response.status, response.body)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AiResult.Failure(AiFailure.NETWORK)
    }
}

/** Asks Claude for things to watch, using the viewer's own key. Opened from Settings. */
@Composable
internal fun AiAssistantDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(AiKeyStore.load()) }
    var keyDraft by remember { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var picks by remember { mutableStateOf(emptyList<AiPick>()) }
    var failure by remember { mutableStateOf<AiFailure?>(null) }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.ai_title),
        message = stringResource(if (key.isBlank()) Res.string.ai_key_note else Res.string.ai_note),
    ) {
        if (key.isBlank()) {
            SettingsSecretTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = stringResource(Res.string.ai_key_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.insights_close)) }
                TextButton(
                    enabled = keyDraft.isNotBlank(),
                    onClick = {
                        AiKeyStore.save(keyDraft)
                        key = keyDraft.trim()
                        keyDraft = ""
                    },
                ) { Text(stringResource(Res.string.ai_save_key)) }
            }
        } else {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text(stringResource(Res.string.ai_prompt_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            failure?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (picks.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    picks.forEach { pick ->
                        val kind = stringResource(if (pick.isSeries) Res.string.ai_kind_series else Res.string.ai_kind_movie)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AiSearchHandoff.pendingTitle.value = pick.title
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = listOfNotNull(pick.title, pick.year?.toString(), kind).joinToString("  ·  "),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (pick.reason.isNotEmpty()) {
                                Text(
                                    text = pick.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        AiKeyStore.save("")
                        key = ""
                        picks = emptyList()
                        failure = null
                    },
                ) { Text(stringResource(Res.string.ai_remove_key)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.insights_close)) }
                TextButton(
                    enabled = !loading && prompt.isNotBlank(),
                    onClick = {
                        loading = true
                        failure = null
                        scope.launch {
                            when (val result = askAssistant(key, prompt)) {
                                is AiResult.Picks -> picks = result.picks
                                is AiResult.Failure -> {
                                    picks = emptyList()
                                    failure = result.kind
                                }
                            }
                            loading = false
                        }
                    },
                ) { Text(stringResource(if (loading) Res.string.ai_thinking else Res.string.ai_ask)) }
            }
        }
    }
}

private fun AiFailure.messageRes(): StringResource = when (this) {
    AiFailure.NO_KEY, AiFailure.BAD_KEY -> Res.string.ai_error_key
    AiFailure.RATE_LIMITED -> Res.string.ai_error_busy
    AiFailure.DECLINED -> Res.string.ai_error_declined
    AiFailure.NETWORK -> Res.string.ai_error_network
    AiFailure.UNEXPECTED -> Res.string.ai_error_unexpected
}
