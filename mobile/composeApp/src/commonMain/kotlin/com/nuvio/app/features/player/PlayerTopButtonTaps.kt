package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Taps for the phone player's top-right buttons (switch engine, watch party, lock, back).
 *
 * Over the live video picture these buttons did not reliably receive taps through Compose.
 * What worked on device (v0.2.9) was matching the touch in the Android activity, before any
 * view sees it: MainActivity.dispatchTouchEvent asks this registry whether a touch landed on
 * one of these buttons and, if so, runs it and keeps the touch away from everything else.
 *
 * Each button registers its layout coordinates, and the bounds are read at touch time, so a
 * screen transition or animation that moved the button since it was laid out cannot leave
 * stale bounds behind (stale bounds would make taps miss).
 */
internal class PlayerTopButtonTaps {
    companion object {
        /** One registry for the whole app, checked by the Android activity. */
        val shared = PlayerTopButtonTaps()
    }

    private class Target(var coordinates: LayoutCoordinates, var onClick: () -> Unit)

    private val targets = LinkedHashMap<String, Target>()

    val hasTargets: Boolean get() = targets.isNotEmpty()

    fun register(key: String, coordinates: LayoutCoordinates, onClick: () -> Unit) {
        val existing = targets[key]
        if (existing != null) {
            existing.coordinates = coordinates
            existing.onClick = onClick
        } else {
            targets[key] = Target(coordinates, onClick)
        }
    }

    fun unregister(key: String, onClick: (() -> Unit)? = null) {
        // Only drop the entry if it still belongs to the caller, so a newly composed
        // button that re-registered under the same key is not removed by the old one.
        val existing = targets[key] ?: return
        if (onClick == null || existing.onClick === onClick) targets.remove(key)
    }

    /** The button under [windowPosition] (window pixels), allowing [slopPx] around each one. */
    fun hit(windowPosition: Offset, slopPx: Float): (() -> Unit)? {
        var best: Target? = null
        var bestDistance = Float.MAX_VALUE
        for (target in targets.values) {
            val coordinates = target.coordinates
            if (!coordinates.isAttached) continue
            val bounds = coordinates.boundsInWindow()
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            if (!bounds.inflate(slopPx).contains(windowPosition)) continue
            val distance = (bounds.center - windowPosition).getDistanceSquared()
            if (distance < bestDistance) {
                bestDistance = distance
                best = target
            }
        }
        return best?.onClick
    }
}

internal val LocalPlayerTopButtonTaps = compositionLocalOf<PlayerTopButtonTaps?> { null }

/** Registers this element as a top button with the activity-level tap registry. */
@Composable
internal fun Modifier.playerTopButtonTarget(key: String, onClick: () -> Unit): Modifier {
    val taps = LocalPlayerTopButtonTaps.current ?: return this
    val latestOnClick by rememberUpdatedState(onClick)
    val stableClick = remember { { latestOnClick() } }
    DisposableEffect(taps, key) {
        onDispose { taps.unregister(key, stableClick) }
    }
    return onGloballyPositioned { coordinates ->
        if (coordinates.isAttached) taps.register(key, coordinates, stableClick)
    }
}
