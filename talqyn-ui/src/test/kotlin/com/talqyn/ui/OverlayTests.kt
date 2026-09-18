package com.talqyn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A layer drawn over the screen — the comparison, history, the restore spinner — and the touches under it. */
@RunWith(RobolectricTestRunner::class)
class OverlayTests {
    @get:Rule
    val compose = createComposeRule()

    private fun tapsReachingWhatTheLayerCovers(layer: Modifier): Int {
        var taps = 0
        compose.setContent {
            Box(Modifier.size(100.dp).testTag("screen")) {
                Box(Modifier.fillMaxSize().clickable { taps += 1 })
                Box(Modifier.fillMaxSize().then(layer))
            }
        }
        compose.onNodeWithTag("screen").performClick()
        compose.waitForIdle()
        return taps
    }

    /** What the modifier is for: a layer that handles no pointer input lets a tap through to what it covers. */
    @Test
    fun aLayerThatOnlyDrawsLetsTouchesThrough() {
        assertEquals(1, tapsReachingWhatTheLayerCovers(Modifier.background(Color.White)))
    }

    @Test
    fun aLayerThatBlocksTouchesTakesThem() {
        assertEquals(0, tapsReachingWhatTheLayerCovers(Modifier.background(Color.White).talqynBlocksTouches()))
    }
}
