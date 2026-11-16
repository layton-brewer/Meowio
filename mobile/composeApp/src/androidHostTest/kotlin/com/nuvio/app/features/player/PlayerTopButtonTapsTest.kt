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
import androidx.compose.ui.test.onNodeWithText
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

    private var backClicks = 0
    private var videoClicks = 0

    @Test
    fun everyToolbarActionReceivesItsOwnTap() {
        var lock = 0
        var engine = 0
        var party = 0
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 360.dp)) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { context -> android.view.View(context).apply { setOnTouchListener { _, _ -> videoClicks++; true } } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlayerToolbar(false, { lock++ }, { backClicks++ },
                        onPartyClick = { party++ }, onSwitchEngineClick = { engine++ })
                }
            }
        }
        compose.onNodeWithContentDescription("Close player").performTouchInput { click() }
        compose.onNodeWithContentDescription("Lock player controls").performTouchInput { click() }
        compose.onNodeWithContentDescription("Switch player engine").performTouchInput { click() }
        compose.onNodeWithContentDescription("Watch party").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, backClicks)
            assertEquals(1, lock)
            assertEquals(1, engine)
            assertEquals(1, party)
            assertEquals(0, videoClicks)
        }
    }
    @Test
    fun partyPanelCloseReceivesTapAboveNativeVideo() {
        val visible = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 360.dp)) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { context -> android.view.View(context).apply { setOnTouchListener { _, _ -> videoClicks++; true } } },
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlayerSidePanel(visible.value, { visible.value = false }, modal = false) {
                        PlayerDialogButton("Close", { visible.value = false })
                    }
                }
            }
        }
        compose.onNodeWithText("Close").performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(false, visible.value)
            assertEquals(0, videoClicks)
        }
    }

}
