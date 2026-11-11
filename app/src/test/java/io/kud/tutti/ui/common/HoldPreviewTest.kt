package io.kud.tutti.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hold preview's promise (Erwann, 2026-11-11): a tap shows, plays and waits for nothing new;
 * a hold names its action, then runs it; a slide runs nothing. Wired as a dock pin wires it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HoldPreviewTest {
    @get:Rule val rule = createComposeRule()

    private val haptics = mutableListOf<FeedbackEvent>()
    private var clicks = 0
    private var holds = 0
    private var faces = 0
    private var everShown = false
    private var timeout = 0L
    private lateinit var preview: HoldPreview

    private fun pin() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalFeedbackObserver provides { haptics += it }) {
                MaterialTheme {
                    timeout = LocalViewConfiguration.current.longPressTimeoutMillis
                    val feedback = rememberFeedback()
                    val press = rememberPressSource { feedback.click() }
                    preview = rememberHoldPreview(press.interaction, enabled = true)
                    if (preview.shown) everShown = true
                    Box(Modifier.padding(top = 200.dp, start = 100.dp)) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .testTag("pin")
                                .holdPreviewClickable(
                                    preview = preview,
                                    interaction = press.interaction,
                                    indication = null,
                                    role = null,
                                    holdLabel = "Night mode",
                                    onHold = {
                                        feedback.longPress()
                                        holds++
                                    },
                                    onClick = { clicks++ },
                                ),
                        )
                        HoldBubble(preview, "Night mode") { faces++ }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeByFrame()
    }

    private fun down() = rule.onNodeWithTag("pin").performTouchInput { down(center) }

    private fun up() = rule.onNodeWithTag("pin").performTouchInput { up() }

    private fun advance(ms: Long) = rule.mainClock.advanceTimeBy(ms)

    @Test
    fun aQuickTapShowsAndPlaysNothingNew() {
        pin()
        down()
        advance(190)
        up()
        advance(500)
        assertEquals(1, clicks)
        assertEquals(0, holds)
        assertFalse(everShown)
        assertEquals("nothing of the bubble is composed", 0, faces)
        assertEquals(listOf(FeedbackEvent.KEY), haptics)
    }

    @Test
    fun aLiftAfterTheBubbleStillTaps() {
        pin()
        down()
        advance(250)
        assertTrue(preview.shown)
        assertTrue(faces > 0)
        up()
        advance(500)
        assertEquals(1, clicks)
        assertEquals(0, holds)
        assertFalse(preview.shown)
        assertEquals(listOf(FeedbackEvent.KEY), haptics)
    }

    @Test
    fun holdingToTheTimeoutRunsTheHoldOnce() {
        pin()
        down()
        advance(timeout + 50)
        assertEquals(1, holds)
        up()
        advance(500)
        assertEquals(1, holds)
        assertEquals(0, clicks)
        assertFalse(preview.shown)
        assertEquals(listOf(FeedbackEvent.KEY, FeedbackEvent.LONG_PRESS), haptics)
    }

    @Test
    fun aSlidePastTheSlopRunsNothing() {
        pin()
        down()
        advance(300)
        assertTrue(preview.shown)
        rule.onNodeWithTag("pin").performTouchInput { moveBy(Offset(width / 3f, 0f)) }
        advance(16)
        assertFalse(preview.shown)
        advance(timeout)
        up()
        advance(500)
        assertEquals(0, clicks)
        assertEquals(0, holds)
        assertEquals(listOf(FeedbackEvent.KEY), haptics)
    }

    @Test
    fun theBubbleSitsAboveCentredAndInsideTheScreen() {
        val window = IntSize(400, 800)
        val content = IntSize(120, 80)
        // Above, centred: its bottom edge (8 px of ring room in) 12 px above the pin's top.
        assertEquals(IntOffset(72, 416), bubbleOffset(IntRect(100, 500, 164, 564), window, content, gap = 12, margin = 16, room = 8))
        // Near the right edge: kept 16 px inside.
        assertEquals(IntOffset(272, 416), bubbleOffset(IntRect(360, 500, 400, 564), window, content, gap = 12, margin = 16, room = 8))
        // No room above: beside the pin, towards the middle, never below.
        assertEquals(IntOffset(168, 12), bubbleOffset(IntRect(100, 20, 164, 84), window, content, gap = 12, margin = 16, room = 8))
        assertEquals(IntOffset(176, 12), bubbleOffset(IntRect(300, 20, 364, 84), window, content, gap = 12, margin = 16, room = 8))
    }
}
