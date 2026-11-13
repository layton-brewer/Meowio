package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** Desktop windows have no behind-the-window blur; the glass panels keep their translucent look without it. */
@Composable
actual fun rememberNuvioWindowBlurBehind(radius: Dp): Boolean = false
