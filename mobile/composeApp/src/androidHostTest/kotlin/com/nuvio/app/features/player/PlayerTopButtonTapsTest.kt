package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w640dp-h360dp-land")
class PlayerTopButtonTapsTest {
    @get:Rule
    val compose = createComposeRule()

    private val taps = PlayerTopButtonTaps()
    private var backClicks = 0
    private var videoClicks = 0

    private fun showToolbar(interceptAtRoot: Boolean) {
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalPlayerTopButtonTaps provides taps) {
                    Box(
                        Modifier.size(640.dp, 360.dp)
                            .then(if (interceptAtRoot) Modifier.playerTopButtonTapRoot(taps) else Modifier)
                            .testTag("player"),
                    ) {
                        // A video layer beneath the toolbar must not win its hit test.
                        Box(Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTapGestures { videoClicks += 1 }
                        })
                        PlayerToolbar(
                            isLocked = false,
                            onLockToggle = {},
                            onBack = { backClicks += 1 },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun toolbarReceivesTheTapEvenWithoutRootInterception() {
        showToolbar(interceptAtRoot = false)
        compose.onNodeWithContentDescription("Close player").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, backClicks)
            assertEquals(0, videoClicks)
        }
    }

    @Test
    fun rootInterceptionAndClickableInvokeTheButtonOnlyOnce() {
        showToolbar(interceptAtRoot = true)
        compose.onNodeWithContentDescription("Close player").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, backClicks)
            assertEquals(0, videoClicks)
        }
    }

    @Test
    fun draggingAwayAndBackCancelsOnlyThatGesture() {
        showToolbar(interceptAtRoot = true)
        val back = compose.onNodeWithContentDescription("Close player")
        back.performTouchInput {
            down(center)
            moveBy(Offset(0f, 150f))
            moveBy(Offset(0f, -150f))
            up()
        }
        compose.runOnIdle {
            assertEquals(0, backClicks)
            assertEquals(0, videoClicks)
        }
        back.performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test
    fun aButtonReplacedWhilePressedDoesNotReceiveTheOldTap() {
        showToolbar(interceptAtRoot = true)
        var replacementClicks = 0
        val back = compose.onNodeWithContentDescription("Close player")
        back.performTouchInput { down(center) }
        compose.runOnIdle {
            taps.register("back", Rect(0f, 0f, 10_000f, 10_000f)) { replacementClicks += 1 }
        }
        back.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, backClicks)
            assertEquals(0, replacementClicks)
            assertEquals(0, videoClicks)
        }
    }

    @Test
    fun disposingAnOldButtonCannotRemoveItsReplacement() {
        val original: () -> Unit = { error("The original button was removed") }
        val replacement: () -> Unit = { backClicks += 1 }
        val bounds = Rect(0f, 0f, 48f, 48f)
        taps.register("back", bounds, original)
        taps.register("back", bounds, replacement)
        taps.unregister("back", original)
        assertSame(replacement, taps.hit(Offset(24f, 24f), 0f))
        taps.unregister("back", replacement)
        assertNull(taps.hit(Offset(24f, 24f), 0f))
    }
}
