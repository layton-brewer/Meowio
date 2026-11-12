package com.nuvio.tv.ui.theme

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Frosted "glass" surfaces: a translucent fill, a soft highlight along the top
 * and a thin light rim that is brightest at the top edge. Fills stay dense
 * enough for text to read from across a room.
 */
object NuvioGlass {
    /** Neutral dark base shared by every dense glass surface. */
    val Base = Color(0xFF1C1C1E)

    val RimWidth: Dp = 1.dp
    val BlurRadius: Dp = 28.dp

    /** Opacity of a dense surface when whatever sits behind it is really blurred. */
    const val BlurredAlpha = 0.66f

    /** Opacity of a dense surface when no blur is available. */
    const val OpaqueAlpha = 0.95f

    /** Opacity of a dense surface floating over video, which can never be blurred. */
    const val OverVideoAlpha = 0.84f

    /** Fill for small unfocused controls: buttons, tabs and chips. */
    val ControlFill = Color.White.copy(alpha = 0.10f)

    /** Fill for the selected, unfocused control in a group. */
    val ControlSelectedFill = Color.White.copy(alpha = 0.22f)

    val Rim: Brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.07f))
    )

    fun panel(alpha: Float): Color = Base.copy(alpha = alpha)
}

/** Clips to [shape] and paints a dense glass panel. */
fun Modifier.nuvioGlassPanel(
    shape: Shape,
    alpha: Float = NuvioGlass.OpaqueAlpha,
    base: Color = NuvioGlass.Base
): Modifier = this
    .clip(shape)
    .background(base.copy(alpha = alpha))
    .nuvioGlassEdge(shape)

/** The highlight and rim only, for surfaces that already paint their own fill. */
fun Modifier.nuvioGlassEdge(shape: Shape): Modifier = this
    .drawWithCache {
        val sheenHeight = size.height * 0.5f
        val sheen = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
            startY = 0f,
            endY = sheenHeight
        )
        onDrawWithContent {
            drawRect(brush = sheen, size = Size(size.width, sheenHeight))
            drawContent()
        }
    }
    .border(NuvioGlass.RimWidth, NuvioGlass.Rim, shape)

/**
 * Asks the system to blur everything behind the dialog window this is called
 * from. Returns true when the blur is active, so the caller can make its
 * surface more see-through. Many TV devices do not support it and get the
 * dense fill instead.
 */
@Composable
fun rememberNuvioWindowBlurBehind(radius: Dp = NuvioGlass.BlurRadius): Boolean {
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
