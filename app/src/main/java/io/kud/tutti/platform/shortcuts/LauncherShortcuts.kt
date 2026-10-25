package io.kud.tutti.platform.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.kud.tutti.MainActivity
import io.kud.tutti.R
import io.kud.tutti.protocol.scenes.Scene

/** One launcher shortcut: a scene to apply, or the keyboard when [sceneId] is null. */
data class LauncherShortcut(val id: String, val label: String, val sceneId: String?)

/**
 * The tutti icon's long-press menu (gesture audit item 10): his first scenes, in the order he keeps
 * them (the strip's), then Keyboard last, within the launcher's [max]. A launcher shows four at most.
 */
fun launcherShortcuts(scenes: List<Scene>, keyboardLabel: String, max: Int = 4): List<LauncherShortcut> {
    if (max <= 0) return emptyList()
    val keyboard = LauncherShortcut(KEYBOARD_ID, keyboardLabel, null)
    return scenes.take(max - 1).map { LauncherShortcut(SCENE_PREFIX + it.id, it.name, it.id) } + keyboard
}

const val ACTION_SCENE = "io.kud.tutti.action.SCENE"
const val ACTION_KEYBOARD = "io.kud.tutti.action.KEYBOARD"
const val EXTRA_SCENE_ID = "io.kud.tutti.extra.SCENE_ID"
private const val KEYBOARD_ID = "keyboard"
private const val SCENE_PREFIX = "scene-"

/** Replaces the dynamic shortcuts with [scenes]'s; a deleted scene's pinned copy is disabled. */
fun publishLauncherShortcuts(context: Context, scenes: List<Scene>) {
    val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
    val list = launcherShortcuts(scenes, context.getString(R.string.shortcut_keyboard), max)
    val infos = list.mapIndexed { rank, shortcut ->
        val intent = Intent(context, MainActivity::class.java)
            .setAction(if (shortcut.sceneId == null) ACTION_KEYBOARD else ACTION_SCENE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        shortcut.sceneId?.let { intent.putExtra(EXTRA_SCENE_ID, it) }
        ShortcutInfoCompat.Builder(context, shortcut.id)
            .setShortLabel(shortcut.label)
            .setLongLabel(
                if (shortcut.sceneId == null) context.getString(R.string.shortcut_keyboard_long) else context.getString(R.string.shortcut_scene_long, shortcut.label),
            )
            .setIcon(IconCompat.createWithResource(context, if (shortcut.sceneId == null) R.drawable.ic_keyboard else R.drawable.ic_scene))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }
    runCatching {
        ShortcutManagerCompat.setDynamicShortcuts(context, infos)
        val live = list.map { it.id }.toSet()
        val gone = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .map { it.id }.filter { it.startsWith(SCENE_PREFIX) && it !in live && scenes.none { s -> SCENE_PREFIX + s.id == it } }
        if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, null)
    }
}
