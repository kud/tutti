package io.kud.tutti.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.settings.SettingsCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Coming back to a Settings screen finds it where it was (Erwann, 2026-11-12), rotation included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenStatesTest {
    @get:Rule val rule = createComposeRule()

    private val stack = mutableStateListOf("settings")
    private val scrolls = mutableMapOf<String, ScrollState>()

    private fun host(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val states = rememberScreenStates(stack.toList())
            val key = stack.last()
            states.SaveableStateProvider(key) {
                val scroll = rememberScrollState()
                scrolls[key] = scroll
                Column(Modifier.size(200.dp).verticalScroll(scroll)) { Spacer(Modifier.height(2000.dp)) }
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
    }

    private fun scrollTo(key: String, px: Int) {
        rule.runOnIdle { runBlocking { scrolls.getValue(key).scrollTo(px) } }
        rule.waitForIdle()
    }

    @Test
    fun `the list keeps its scroll while a page is open, and a popped page starts again at the top`() {
        host()
        scrollTo("settings", 600)
        rule.runOnIdle { stack.add("settings:DISPLAY") }
        rule.waitForIdle()
        scrollTo("settings:DISPLAY", 300)
        rule.runOnIdle { stack.removeAt(stack.lastIndex) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(600, scrolls.getValue("settings").value) }
        rule.runOnIdle { stack.add("settings:DISPLAY") }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, scrolls.getValue("settings:DISPLAY").value) }
    }

    @Test
    fun `the scroll survives the activity being recreated`() {
        val tester = StateRestorationTester(rule)
        host(tester)
        scrollTo("settings", 450)
        tester.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(450, scrolls.getValue("settings").value) }
    }

    @Test
    fun `settings screens keep their state and the remote does not`() {
        assertTrue(keepsState(Screen.Settings))
        assertTrue(keepsState(Screen.SettingsPage(SettingsCategory.AUTOMATIC)))
        assertTrue(keepsState(Screen.TvSettings))
        assertFalse(keepsState(Screen.Remote))
    }
}
