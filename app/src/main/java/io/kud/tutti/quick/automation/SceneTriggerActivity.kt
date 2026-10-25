package io.kud.tutti.quick.automation

import android.app.Activity
import android.os.Bundle
import io.kud.tutti.quick.QuickPreferences
import io.kud.tutti.quick.QuickRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * No window: applies the scene a [SceneLink] names, then finishes at once. Exported so an NFC tag
 * or an automation app can reach it, which is why it does nothing unless "Automation" is on in
 * Settings (off by default), and why it only ever applies a scene he saved: an unknown id is
 * ignored by the controller. No secret crosses this door, and nothing comes back out of it.
 */
class SceneTriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val id = SceneLink.parse(data?.scheme, data?.host, data?.path)
        if (id != null) {
            val app = applicationContext
            QuickRuntime.scope.launch {
                if (QuickPreferences(app).automationEnabled.first()) QuickRuntime.act(app) { applyScene(id) }
            }
        }
        finish()
    }
}
