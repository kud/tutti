package io.kud.tutti.ui.settings

import io.kud.tutti.ui.common.Status
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.platform.sync.SyncProblem
import io.kud.tutti.platform.sync.SyncState
import io.kud.tutti.ui.remote.ConnAction
import io.kud.tutti.ui.remote.ConnDevice
import io.kud.tutti.ui.remote.ConnLine
import io.kud.tutti.ui.remote.ConnLink
import io.kud.tutti.ui.remote.ConnWords

/**
 * The status tiles at the top of Settings (Erwann, 2026-11-06: « un dashboard ou des tuiles en haut
 * sur ce qu'on a »): what the room has and where each part stands, so the list under it can say what
 * each section holds rather than how it is. Pure, so the tiles, Connexions and the checklist agree
 * (`SettingsDashboardTest`). The TV and the bar read [io.kud.tutti.ui.remote.connections], the same
 * lines Connexions shows, so the two never disagree.
 */
enum class DashTile { TV, SOUNDBAR, SYNC, UNLOCKS }

/** What a tile says, as values; the screen says them in words. */
sealed interface DashWords {
    /** A device's link, in Connexions' own words; [link] says which link when it isn't the main one. */
    data class Device(val words: ConnWords, val link: ConnLink?) : DashWords

    data class Sync(val words: SyncWords, val age: SyncAge?) : DashWords

    /** What tutti can do: [done] of [total] everyday steps, [next] to do, or none left. */
    data class Progress(val done: Int, val total: Int, val next: Unlock?) : DashWords
}

enum class SyncWords { OFF, ON, PAUSED, WAITING, PROBLEM }

/** How long ago sync last ran, in words a person says: never "0 minutes ago". */
sealed interface SyncAge {
    data object JustNow : SyncAge

    data class Minutes(val n: Int) : SyncAge

    data class Hours(val n: Int) : SyncAge

    data class Days(val n: Int) : SyncAge
}

fun syncAge(at: Long, now: Long): SyncAge {
    val seconds = ((now - at).coerceAtLeast(0)) / 1000
    return when {
        seconds < 60 -> SyncAge.JustNow
        seconds < 3600 -> SyncAge.Minutes((seconds / 60).toInt())
        seconds < 86_400 -> SyncAge.Hours((seconds / 3600).toInt())
        else -> SyncAge.Days((seconds / 86_400).toInt())
    }
}

/** The one thing a tile asks of him, its button. A tile without one opens its section on a tap. */
enum class DashAction { PAIR_TV, SET_UP_IP, ADD_BAR, OPEN_DEVICES, CHECK_SYNC }

data class DashItem(
    val tile: DashTile,
    /** The glyph beside the words: the same marks as Connexions, never colour alone. */
    val mark: Status,
    val words: DashWords,
    val action: DashAction? = null,
    /** Something only he can fix: the tile goes first, full width, with [action] as its button. */
    val needsYou: Boolean = false,
)

/** Everything the tiles read, flattened so the rules test without Android. */
data class DashInput(
    val connections: List<ConnDevice>,
    val sync: SyncState,
    val unlocks: UnlockSummary,
    val now: Long,
)

/**
 * The tiles in their place: TV, soundbar, sync, what tutti can do; any that needs him moves to the
 * front, keeping that order among themselves, so the eye lands on the one problem first.
 */
fun dashboard(input: DashInput): List<DashItem> {
    val items = listOf(tvTile(input.connections), barTile(input.connections), syncTile(input.sync, input.now), unlocksTile(input.unlocks))
    return items.filter { it.needsYou } + items.filterNot { it.needsYou }
}

/** Words only he can act on: a pairing lost, a key or code refused, a permission, a bar that moved. */
private val NEEDS_YOU = setOf(ConnWords.NEEDS_PAIRING, ConnWords.KEY_REFUSED, ConnWords.CODE_TO_RENEW, ConnWords.NEEDS_PERMISSION, ConnWords.MOVED, ConnWords.NEW_IDENTITY)

private fun ConnLine.needsYou(): Boolean = words in NEEDS_YOU

/** A step the Settings stack can take; waking and the LAN ask stay the remote's own (Connexions). */
private fun ConnAction.dash(): DashAction? = when (this) {
    ConnAction.PAIR_TV -> DashAction.PAIR_TV
    ConnAction.SET_UP_IP -> DashAction.SET_UP_IP
    ConnAction.ADD_BAR -> DashAction.ADD_BAR
    ConnAction.USE_NEW_ADDRESS, ConnAction.OPEN_DEVICES, ConnAction.PAIR_COMPUTER -> DashAction.OPEN_DEVICES
    ConnAction.WAKE_TV, ConnAction.CONTROL, ConnAction.LAN_REQUEST, ConnAction.LAN_OPEN_SETTINGS, ConnAction.TRUST_IDENTITY -> null
}

private fun tvTile(connections: List<ConnDevice>): DashItem {
    val tv = connections.firstOrNull { it.kind == DeviceKind.TV }
    if (tv == null || tv.name == null) {
        // No TV, no remote: the one tile that asks to be set up before anything else.
        return DashItem(DashTile.TV, Status.NOT_YET, DashWords.Device(ConnWords.NOT_ADDED, null), DashAction.PAIR_TV, needsYou = true)
    }
    val google = tv.lines.first { it.link == ConnLink.GOOGLE_TV }
    // The Google TV link is the remote itself; IP control speaks only when it needs him. A TV on
    // standby or out of reach is no fault (design.md), and IP control not set up yet is a step of
    // What tutti can do, its own tile.
    val shown = tv.lines.firstOrNull { it.link != ConnLink.BLUETOOTH && it.needsYou() } ?: google
    return DashItem(
        tile = DashTile.TV,
        mark = shown.words.mark,
        words = DashWords.Device(shown.words, shown.link.takeIf { it != ConnLink.GOOGLE_TV }),
        action = shown.action?.dash()?.takeIf { shown.needsYou() },
        needsYou = shown.needsYou(),
    )
}

private fun barTile(connections: List<ConnDevice>): DashItem {
    val bar = connections.firstOrNull { it.kind == DeviceKind.SOUNDBAR }
    if (bar == null || bar.name == null) {
        // A bar is optional: "not added" is a calm offer, never a problem. With no TV, Devices
        // holds "Add a device", the whole flow.
        val add = if (connections.any { it.kind == DeviceKind.TV && it.name != null }) DashAction.ADD_BAR else DashAction.OPEN_DEVICES
        return DashItem(DashTile.SOUNDBAR, Status.NOT_YET, DashWords.Device(ConnWords.NOT_ADDED, null), add)
    }
    val line = bar.lines.first()
    return DashItem(
        tile = DashTile.SOUNDBAR,
        mark = line.words.mark,
        words = DashWords.Device(line.words, null),
        action = line.action?.dash()?.takeIf { line.needsYou() },
        needsYou = line.needsYou(),
    )
}

private fun syncTile(sync: SyncState, now: Long): DashItem = when {
    !sync.hasFile -> DashItem(DashTile.SYNC, Status.NOT_YET, DashWords.Sync(SyncWords.OFF, null))
    sync.problem == SyncProblem.WAITING -> DashItem(DashTile.SYNC, Status.WORKING, DashWords.Sync(SyncWords.WAITING, null))
    sync.problem != null -> DashItem(DashTile.SYNC, Status.NEEDS_YOU, DashWords.Sync(SyncWords.PROBLEM, null), DashAction.CHECK_SYNC, needsYou = true)
    else -> DashItem(
        tile = DashTile.SYNC,
        mark = if (sync.auto) Status.OK else Status.STANDBY,
        words = DashWords.Sync(if (sync.auto) SyncWords.ON else SyncWords.PAUSED, sync.lastSyncedAt?.let { syncAge(it, now) }),
    )
}

/**
 * Progress only, never a problem: a step that needs a second look is the TV's or the bar's tile
 * already, and one problem is said in one place.
 */
private fun unlocksTile(summary: UnlockSummary): DashItem =
    DashItem(DashTile.UNLOCKS, if (summary.next == null) Status.OK else Status.NOT_YET, DashWords.Progress(summary.done, summary.total, summary.next))
