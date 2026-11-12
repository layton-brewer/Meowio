package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted "glass" surfaces: a translucent fill, a soft highlight along the top
 * and a thin light rim that is brightest at the top edge.
 */
enum class NuvioGlassStyle {
    /** Light frosting for cards, chips and buttons sitting on the app background. */
    Regular,

    /** Dense frosting for sheets, dialogs and panels that carry a lot of text. */
    Thick,

    /** Smoked glass for controls that float over artwork or video. */
    OverMedia,
}

object NuvioGlass {
    /** Neutral dark base shared by every dense glass surface. */
    val Base = Color(0xFF1C1C1E)

    val RimWidth: Dp = 0.75.dp
    val BlurRadius: Dp = 28.dp

    /** Opacity of a dense surface when whatever sits behind it is really blurred. */
    const val ThickBlurredAlpha = 0.62f

    /** Opacity of a dense surface when no blur is available, so text stays readable. */
    const val ThickOpaqueAlpha = 0.95f

    /** Opacity of a dense surface floating over video, which can never be blurred. */
    const val ThickOverVideoAlpha = 0.80f

    val Rim: Brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.06f)),
    )

    val RegularFill: Brush = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.05f)),
    )

    val OverMediaFill: Brush = Brush.verticalGradient(
        listOf(Color(0xFF34343A).copy(alpha = 0.58f), Color(0xFF141416).copy(alpha = 0.58f)),
    )

    fun thick(alpha: Float): Color = Base.copy(alpha = alpha)
}

/**
 * Clips to [shape] and paints a glass surface. Apply it before `clickable` so
 * press feedback stays inside the shape.
 */
fun Modifier.nuvioGlass(
    shape: Shape,
    style: NuvioGlassStyle = NuvioGlassStyle.Regular,
    thickAlpha: Float = NuvioGlass.ThickOpaqueAlpha,
): Modifier = this
    .clip(shape)
    .then(
        when (style) {
            NuvioGlassStyle.Regular -> Modifier.background(NuvioGlass.RegularFill)
            NuvioGlassStyle.Thick -> Modifier.background(NuvioGlass.thick(thickAlpha))
            NuvioGlassStyle.OverMedia -> Modifier.background(NuvioGlass.OverMediaFill)
        },
    )
    .nuvioGlassEdge(shape)

/** The highlight and rim only, for surfaces that already paint their own fill. */
fun Modifier.nuvioGlassEdge(shape: Shape): Modifier = this
    .drawWithCache {
        val sheenHeight = size.height * 0.5f
        val sheen = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
            startY = 0f,
            endY = sheenHeight,
        )
        onDrawWithContent {
            drawRect(brush = sheen, size = Size(size.width, sheenHeight))
            drawContent()
        }
    }
    .border(NuvioGlass.RimWidth, NuvioGlass.Rim, shape)

/**
 * Asks the system to blur everything behind the dialog or sheet window this is
 * called from. Returns true when the blur is active, so the caller can make its
 * surface more see-through.
 */
@Composable
expect fun rememberNuvioWindowBlurBehind(radius: Dp = NuvioGlass.BlurRadius): Boolean
