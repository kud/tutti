package io.kud.salon.quick.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import io.kud.salon.quick.QuickRuntime
import io.kud.salon.quick.quickControls

/** Every widget tap lands here. Instantiated by Glance through reflection: keep the no-arg constructor. */
class QuickWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        QuickRuntime.start(context)
        val controls = context.quickControls() ?: return
        when (parameters[Op]) {
            OP_VOLUME_UP -> controls.volumeUp()
            OP_VOLUME_DOWN -> controls.volumeDown()
            OP_MUTE -> controls.toggleMute()
            OP_SCENE -> parameters[SceneId]?.let { controls.applyScene(it) }
        }
    }

    companion object {
        val Op = ActionParameters.Key<String>("quick_op")
        val SceneId = ActionParameters.Key<String>("quick_scene_id")

        const val OP_VOLUME_UP = "volume_up"
        const val OP_VOLUME_DOWN = "volume_down"
        const val OP_MUTE = "mute"
        const val OP_SCENE = "scene"
    }
}
