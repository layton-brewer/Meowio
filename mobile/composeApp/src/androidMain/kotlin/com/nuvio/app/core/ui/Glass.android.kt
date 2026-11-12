package com.nuvio.app.core.ui

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogWindowProvider

@Composable
actual fun rememberNuvioWindowBlurBehind(radius: Dp): Boolean {
    val view = LocalView.current
    val window = remember(view) { view.findDialogWindow() }
    val supported = remember(view) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    }
    val active = supported && window != null
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }

    DisposableEffect(window, active, radiusPx) {
        if (active && window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
        }
        onDispose { }
    }
    return active
}

private fun View.findDialogWindow(): Window? {
    var current: Any? = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = (current as? View)?.parent
    }
    return null
}
