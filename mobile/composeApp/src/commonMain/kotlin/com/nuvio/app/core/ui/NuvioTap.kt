package com.nuvio.app.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * A tap target that fires on the first quick touch. It watches the Initial pass and ignores
 * whether another handler (fling stop, parent gesture detector) already consumed the down,
 * so top-bar buttons never need a press-and-hold.
 */
@androidx.compose.runtime.Composable
internal fun Modifier.nuvioTap(onClick: () -> Unit): Modifier {
    val latestOnClick by androidx.compose.runtime.rememberUpdatedState(onClick)
    return this
        .semantics(mergeDescendants = true) {
            role = androidx.compose.ui.semantics.Role.Button
            onClick { latestOnClick(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                val slop = viewConfiguration.touchSlop * 2.5f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@awaitEachGesture
                    if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
                    if (change.changedToUpIgnoreConsumed()) {
                        event.changes.forEach { it.consume() }
                        latestOnClick()
                        return@awaitEachGesture
                    }
                }
            }
        }
}
