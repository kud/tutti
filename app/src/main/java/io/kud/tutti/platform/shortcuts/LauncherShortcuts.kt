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

/**
 * Replaces the dynamic shortcuts with [scenes]'s; a deleted scene's pinned copy is disabled. Every
 * scene shortcut carries [token] ([ShortcutGuard]), and a pinned scene shortcut that is no longer
 * among the dynamic ones is rewritten with it too, so a shortcut pinned before the token existed (or
 * before a new token) works again from the first time the app is opened after the update.
 */
fun publishLauncherShortcuts(context: Context, scenes: List<Scene>, token: String) {
    val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(4)
    val list = launcherShortcuts(scenes, context.getString(R.string.shortcut_keyboard), max)
    val infos = list.mapIndexed { rank, shortcut -> shortcutInfo(context, shortcut, token, rank) }
    runCatching {
        ShortcutManagerCompat.setDynamicShortcuts(context, infos)
        val live = list.map { it.id }.toSet()
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .map { it.id }.filter { it.startsWith(SCENE_PREFIX) && it !in live }
        val kept = pinnedSceneShortcuts(pinned, scenes)
        if (kept.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, kept.map { shortcutInfo(context, it, token, rank = 0) })
        val gone = pinned.filter { id -> kept.none { it.id == id } }
        if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, null)
    }
}

/** The pinned scene shortcuts among [pinnedIds] whose scene still exists, to republish with the token. */
fun pinnedSceneShortcuts(pinnedIds: List<String>, scenes: List<Scene>): List<LauncherShortcut> =
    pinnedIds.mapNotNull { id ->
        scenes.firstOrNull { SCENE_PREFIX + it.id == id }?.let { LauncherShortcut(id, it.name, it.id) }
    }

private fun shortcutInfo(context: Context, shortcut: LauncherShortcut, token: String, rank: Int): ShortcutInfoCompat {
    val intent = Intent(context, MainActivity::class.java)
        .setAction(if (shortcut.sceneId == null) ACTION_KEYBOARD else ACTION_SCENE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    shortcut.sceneId?.let { intent.putExtra(EXTRA_SCENE_ID, it).putExtra(EXTRA_TOKEN, token) }
    return ShortcutInfoCompat.Builder(context, shortcut.id)
        .setShortLabel(shortcut.label)
        .setLongLabel(
            if (shortcut.sceneId == null) context.getString(R.string.shortcut_keyboard_long) else context.getString(R.string.shortcut_scene_long, shortcut.label),
        )
        .setIcon(IconCompat.createWithResource(context, if (shortcut.sceneId == null) R.drawable.ic_keyboard else R.drawable.ic_scene))
        .setIntent(intent)
        .setRank(rank)
        .build()
}
