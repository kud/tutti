package io.kud.tutti.platform.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import io.kud.tutti.MainActivity
import io.kud.tutti.R
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.protocol.scenes.Scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One launcher shortcut: an app his dock pins ([packageName]), a scene to apply ([sceneId]), or the
 * keyboard when both are null. The intent carries only these ids and the install's token, never a
 * label, a destination or anything the TV showed.
 */
data class LauncherShortcut(val id: String, val label: String, val sceneId: String? = null, val packageName: String? = null)

/** An app pin of the dock, in dock order: the package and the name the pin wears. */
data class PinnedApp(val packageName: String, val label: String)

/**
 * The dock's app pins, in the order he arranged them, one per package (a second pin of the same app
 * with another destination still launches the first one's: [MainActivity] resolves the pin again
 * at tap time). Only a pin's tap counts; a long-press app is a second thought, not a favourite.
 */
fun pinnedApps(dock: DockConfig): List<PinnedApp> =
    dock.slots.mapNotNull { slot ->
        (slot.primary as? DockAction.LaunchApp)?.let { PinnedApp(it.packageName, slot.label?.takeIf(String::isNotBlank) ?: it.label) }
    }.distinctBy { it.packageName }

/**
 * The tutti icon's long-press menu, ranked within the launcher's [max] (a launcher shows four at
 * most): the dock's pinned apps first, in dock order, each with its own logo (Erwann, 2026-11-13:
 * « les chaînes pinned avec leur logo »); then his scenes in the strip's order; Keyboard always last.
 * Apps leave room for one scene when he has any, so the menu never loses scenes entirely to apps;
 * whatever either side leaves unused, the other takes.
 */
fun launcherShortcuts(apps: List<PinnedApp>, scenes: List<Scene>, keyboardLabel: String, max: Int = MAX_SHOWN): List<LauncherShortcut> {
    if (max <= 0) return emptyList()
    val keyboard = LauncherShortcut(KEYBOARD_ID, keyboardLabel)
    val room = max - 1
    val appCount = minOf(apps.size, if (scenes.isEmpty()) room else (room - 1).coerceAtLeast(0))
    val sceneCount = minOf(scenes.size, room - appCount)
    val appShortcuts = apps.take(appCount).map { LauncherShortcut(APP_PREFIX + it.packageName, it.label, packageName = it.packageName) }
    val sceneShortcuts = scenes.take(sceneCount).map { LauncherShortcut(SCENE_PREFIX + it.id, it.name, sceneId = it.id) }
    return appShortcuts + sceneShortcuts + keyboard
}

/** The id [reportShortcutUsed][ShortcutManagerCompat.reportShortcutUsed] ranks a launch under, or null. */
fun shortcutIdFor(action: String?, sceneId: String?, packageName: String?): String? = when (action) {
    ACTION_SCENE -> sceneId?.takeIf(String::isNotBlank)?.let { SCENE_PREFIX + it }
    ACTION_APP -> packageName?.takeIf(String::isNotBlank)?.let { APP_PREFIX + it }
    ACTION_KEYBOARD -> KEYBOARD_ID
    else -> null
}

const val ACTION_SCENE = "io.kud.tutti.action.SCENE"
const val ACTION_APP = "io.kud.tutti.action.APP"
const val ACTION_KEYBOARD = "io.kud.tutti.action.KEYBOARD"
const val EXTRA_SCENE_ID = "io.kud.tutti.extra.SCENE_ID"
const val EXTRA_PACKAGE = "io.kud.tutti.extra.PACKAGE"
private const val KEYBOARD_ID = "keyboard"
private const val SCENE_PREFIX = "scene-"
private const val APP_PREFIX = "app-"
private const val MAX_SHOWN = 4

/**
 * Replaces the dynamic shortcuts with the dock's apps, [scenes] and Keyboard; a deleted scene's
 * pinned copy is disabled. Every scene and app shortcut carries [token] ([ShortcutGuard]), and a
 * pinned scene shortcut that is no longer among the dynamic ones is rewritten with it too, so a
 * shortcut pinned before the token existed (or before a new token) works again from the first time
 * the app is opened after the update. Icons are drawn off the main thread ([ShortcutIcons]).
 */
suspend fun publishLauncherShortcuts(context: Context, dock: DockConfig, scenes: List<Scene>, appIcons: Map<String, String>, token: String) {
    withContext(Dispatchers.Default) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MAX_SHOWN)
        val list = launcherShortcuts(pinnedApps(dock), scenes, context.getString(R.string.shortcut_keyboard), max)
        runCatching {
            val infos = list.mapIndexed { rank, shortcut -> shortcutInfo(context, shortcut, scenes, appIcons, token, rank) }
            ShortcutManagerCompat.setDynamicShortcuts(context, infos)
            val live = list.map { it.id }.toSet()
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .map { it.id }.filter { it.startsWith(SCENE_PREFIX) && it !in live }
            val kept = pinnedSceneShortcuts(pinned, scenes)
            if (kept.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, kept.map { shortcutInfo(context, it, scenes, appIcons, token, rank = 0) })
            val gone = pinned.filter { id -> kept.none { it.id == id } }
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, null)
        }
    }
}

/** The pinned scene shortcuts among [pinnedIds] whose scene still exists, to republish with the token. */
fun pinnedSceneShortcuts(pinnedIds: List<String>, scenes: List<Scene>): List<LauncherShortcut> =
    pinnedIds.mapNotNull { id ->
        scenes.firstOrNull { SCENE_PREFIX + it.id == id }?.let { LauncherShortcut(id, it.name, sceneId = it.id) }
    }

private fun shortcutInfo(
    context: Context,
    shortcut: LauncherShortcut,
    scenes: List<Scene>,
    appIcons: Map<String, String>,
    token: String,
    rank: Int,
): ShortcutInfoCompat {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val (long, icon) = when {
        shortcut.packageName != null -> {
            intent.setAction(ACTION_APP).putExtra(EXTRA_PACKAGE, shortcut.packageName).putExtra(EXTRA_TOKEN, token)
            context.getString(R.string.shortcut_app_long, shortcut.label) to
                ShortcutIcons.app(context, shortcut.packageName, shortcut.label, appIcons[shortcut.packageName])
        }
        shortcut.sceneId != null -> {
            intent.setAction(ACTION_SCENE).putExtra(EXTRA_SCENE_ID, shortcut.sceneId).putExtra(EXTRA_TOKEN, token)
            context.getString(R.string.shortcut_scene_long, shortcut.label) to
                ShortcutIcons.glyph(context, ShortcutIcons.sceneGlyph(scenes.firstOrNull { it.id == shortcut.sceneId }?.glyph))
        }
        else -> {
            intent.setAction(ACTION_KEYBOARD)
            context.getString(R.string.shortcut_keyboard_long) to ShortcutIcons.glyph(context, R.drawable.ic_keyboard)
        }
    }
    return ShortcutInfoCompat.Builder(context, shortcut.id)
        .setShortLabel(shortcut.label)
        .setLongLabel(long)
        .setIcon(icon)
        .setIntent(intent)
        .setRank(rank)
        .build()
}
