package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.still_watching_continue
import nuvio.composeapp.generated.resources.still_watching_countdown
import nuvio.composeapp.generated.resources.still_watching_exit
import nuvio.composeapp.generated.resources.still_watching_title
import org.jetbrains.compose.resources.stringResource

/**
 * "Are you still watching?" for the phone, iPhone/iPad, Mac and Windows players, matching Android TV:
 * after a number of episodes in a row that auto-play started (Next Episode or Shuffle), the next
 * automatic switch waits for the viewer instead. Off by default, as on Android TV.
 */
data class StillWatchingSettings(
    val enabled: Boolean = false,
    val episodeThreshold: Int = StillWatching.DEFAULT_THRESHOLD,
)

internal class StillWatchingPromptState(val onContinue: () -> Unit)

object StillWatching {
    const val DEFAULT_THRESHOLD = 3
    val thresholdOptions = listOf(2, 3, 4, 5)
    private const val COUNTDOWN_SECONDS = 60

    private val settingsFlow = MutableStateFlow(StillWatchingSettings())
    val settings: StateFlow<StillWatchingSettings> = settingsFlow.asStateFlow()
    private var loadedProfileId: Int? = null

    private val promptFlow = MutableStateFlow<StillWatchingPromptState?>(null)
    internal val prompt: StateFlow<StillWatchingPromptState?> = promptFlow.asStateFlow()

    /** Episodes started by auto-play in a row in this player session. */
    private var consecutiveAutoPlays = 0

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        settingsFlow.value = decode(runCatching { StillWatchingStorage.load(profileId) }.getOrNull())
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setEpisodeThreshold(threshold: Int) = update { it.copy(episodeThreshold = threshold.coerceIn(1, 10)) }

    private fun update(change: (StillWatchingSettings) -> StillWatchingSettings) {
        ensureLoaded()
        val next = change(settingsFlow.value)
        settingsFlow.value = next
        runCatching { StillWatchingStorage.save(ProfileRepository.activeProfileId, "${next.enabled}|${next.episodeThreshold}") }
    }

    private fun decode(raw: String?): StillWatchingSettings {
        val parts = raw?.split('|') ?: return StillWatchingSettings()
        return StillWatchingSettings(
            enabled = parts.getOrNull(0) == "true",
            episodeThreshold = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10) ?: DEFAULT_THRESHOLD,
        )
    }

    /**
     * Called when auto-play is about to start the next episode. Returns true when the viewer should be
     * asked first; the prompt is then showing and [onContinue] plays the next episode if they say so.
     */
    internal fun interceptAutoPlay(onContinue: () -> Unit): Boolean {
        ensureLoaded()
        val current = settingsFlow.value
        if (!current.enabled || consecutiveAutoPlays < current.episodeThreshold) {
            consecutiveAutoPlays += 1
            return false
        }
        if (promptFlow.value == null) promptFlow.value = StillWatchingPromptState(onContinue)
        return true
    }

    /** The viewer chose the next episode themselves, or a new player session started. */
    internal fun resetCount() {
        consecutiveAutoPlays = 0
    }

    internal fun endSession() {
        consecutiveAutoPlays = 0
        promptFlow.value = null
    }

    internal fun continueWatching() {
        val pending = promptFlow.value ?: return
        consecutiveAutoPlays = 0
        promptFlow.value = null
        pending.onContinue()
    }

    internal val countdownSeconds: Int get() = COUNTDOWN_SECONDS
}

internal expect object StillWatchingStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
}

/** What the prompt shows while it is up: seconds left before the player closes. */
internal data class StillWatchingPromptUi(val remainingSeconds: Int)

/**
 * Runs the prompt for one player session: resets the count when the player opens and closes, and
 * exits the player when nobody answers within a minute, like Android TV. Null while no prompt is up.
 */
@Composable
internal fun rememberStillWatchingPrompt(onExit: () -> Unit): StillWatchingPromptUi? {
    DisposableEffect(Unit) {
        StillWatching.endSession()
        onDispose { StillWatching.endSession() }
    }
    val prompt by StillWatching.prompt.collectAsState()
    val current = prompt ?: return null
    var remaining by remember(current) { mutableIntStateOf(StillWatching.countdownSeconds) }
    LaunchedEffect(current) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
        StillWatching.endSession()
        onExit()
    }
    return StillWatchingPromptUi(remainingSeconds = remaining)
}

internal fun stillWatchingExit(onExit: () -> Unit) {
    StillWatching.endSession()
    onExit()
}

/** The prompt drawn in Compose (phone, iPhone and iPad). Mac and Windows show it in the controls page. */
@Composable
internal fun StillWatchingPromptHost(onExit: () -> Unit) {
    val ui = rememberStillWatchingPrompt(onExit) ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1C1B22).copy(alpha = 0.94f))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.still_watching_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = stringResource(Res.string.still_watching_countdown, ui.remainingSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StillWatchingButton(stringResource(Res.string.still_watching_exit), primary = false) {
                    stillWatchingExit(onExit)
                }
                StillWatchingButton(stringResource(Res.string.still_watching_continue), primary = true) {
                    StillWatching.continueWatching()
                }
            }
        }
    }
}

@Composable
private fun StillWatchingButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (primary) Color.Black else Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    )
}
