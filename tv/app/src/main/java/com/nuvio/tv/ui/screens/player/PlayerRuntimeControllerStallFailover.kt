package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlaceholderVideoDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches a source after its first frame. When it stops mid-playback and does not recover (the
 * host went away, a debrid link died, the server stopped sending data), it moves to the next best
 * source at the same position, in the background, instead of buffering forever. Covers both
 * engines. A peer-to-peer torrent is left alone, since its speed depends on the swarm.
 */
internal fun PlayerRuntimeController.startPlaybackStallFailoverMonitor() {
    playbackStallFailoverJob?.cancel()
    val url = currentStreamUrl
    playbackStallFailoverJob = scope.launch {
        var stalledMs = 0L
        var lastPositionMs: Long? = null
        var placeholderCheckDone = false
        var placeholderWaitMs = 0L
        while (isActive) {
            delay(PlaybackStallFailoverPolicy.POLL_INTERVAL_MS)
            if (currentStreamUrl != url) return@launch
            val sample = playbackStallSample()
            if (!placeholderCheckDone && sample != null && hasRenderedFirstFrame && !isTorrentStream &&
                _uiState.value.error == null && sourceFallbackJob?.isActive != true
            ) {
                placeholderWaitMs += PlaybackStallFailoverPolicy.POLL_INTERVAL_MS
                if (sample.durationMs > 0L || placeholderWaitMs >= PlaceholderVideoDetector.DURATION_WAIT_MS) {
                    placeholderCheckDone = true
                    if (isPlayingDebridPlaceholder(url, sample.durationMs)) {
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "Source is a debrid stand-in clip (${sample.durationMs / 1_000L}s), moving to the next best source"
                        )
                        if (tryFailoverToNextSource(context.getString(R.string.player_error_source_placeholder))) {
                            return@launch
                        }
                        Log.w(PlayerRuntimeController.TAG, "No backup source for the stand-in clip")
                    }
                }
            }
            val watching = sample != null && hasRenderedFirstFrame && !isInBackground && !isTorrentStream &&
                _uiState.value.error == null &&
                errorRetryJob?.isActive != true && sourceFallbackJob?.isActive != true
            if (!watching || sample == null) {
                stalledMs = 0L
                lastPositionMs = null
                continue
            }
            val previousPositionMs = lastPositionMs
            stalledMs = PlaybackStallFailoverPolicy.nextStalledMs(
                stalledMs = stalledMs,
                lastPositionMs = previousPositionMs,
                sample = sample,
                elapsedMs = PlaybackStallFailoverPolicy.POLL_INTERVAL_MS
            )
            // Keep the position where the stall began, so tiny watchdog seeks do not reset it.
            if (stalledMs == 0L || previousPositionMs == null) lastPositionMs = sample.positionMs
            if (!PlaybackStallFailoverPolicy.shouldFailover(stalledMs)) continue

            val seconds = (PlaybackStallFailoverPolicy.STALL_FAILOVER_MS / 1_000L).toInt()
            Log.w(PlayerRuntimeController.TAG, "Source stopped loading for ${seconds}s mid-playback, moving to the next best source")
            if (!tryFailoverToNextSource(context.getString(R.string.player_error_source_stalled, seconds))) {
                // Nothing left to try: keep waiting on this source, as the player would anyway.
                Log.w(PlayerRuntimeController.TAG, "No backup source for the stalled source")
            }
            return@launch
        }
    }
}

/**
 * True when the video that just opened is a debrid add-on's "downloading" or "failed" clip
 * instead of the title: its address is a known stand-in clip, or it is far shorter than the
 * runtime from metadata. Trailers and titles without a long runtime are left alone.
 */
private fun PlayerRuntimeController.isPlayingDebridPlaceholder(url: String, durationMs: Long): Boolean {
    if (_uiState.value.isLive) return false
    val season = currentSeason
    val episode = currentEpisode
    val episodeRuntime = if (season != null && episode != null) {
        metaVideos.firstOrNull { it.season == season && it.episode == episode }?.runtime
    } else {
        null
    }
    return PlaceholderVideoDetector.isPlaceholderPlayback(
        url = url,
        durationMs = durationMs,
        expectedRuntimeMinutes = episodeRuntime?.takeIf { it > 0 } ?: metaRuntimeMinutes,
        streamText = _uiState.value.currentStreamName,
    )
}

private fun PlayerRuntimeController.playbackStallSample(): PlaybackStallFailoverPolicy.Sample? {
    if (isUsingMpvEngine()) {
        val view = mpvView ?: return null
        return PlaybackStallFailoverPolicy.Sample(
            wantsToPlay = view.isPlayingNow(),
            waitingForData = view.isPausedForCacheNow() || view.isCoreIdleNow(),
            positionMs = view.currentPositionMs().coerceAtLeast(0L),
            durationMs = view.durationMs()
        )
    }
    val player = _exoPlayer ?: return null
    return PlaybackStallFailoverPolicy.Sample(
        wantsToPlay = player.playWhenReady,
        waitingForData = player.playbackState == Player.STATE_BUFFERING,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration
    )
}
