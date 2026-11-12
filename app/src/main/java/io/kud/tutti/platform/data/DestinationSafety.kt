package io.kud.tutti.platform.data

import io.kud.tutti.protocol.apps.AppDestinations

/*
 * Every way a destination link comes in from outside this phone (a layout file, the sync file)
 * passes here (Erwann, 2026-11-12): a link [AppDestinations.accepts] refuses is dropped and
 * the rest kept. A pin loses only its link, so it opens the app as the app would.
 */

/** The per-app defaults [AppDestinations.accepts] lets through. */
internal fun Map<String, String>.safeDestinations(): Map<String, String> =
    filter { (pkg, link) -> AppDestinations.accepts(pkg, link) }.mapValues { it.value.trim() }

/** This action with any refused app link dropped, macros included. */
internal fun DockAction.safeLink(): DockAction = when (this) {
    is DockAction.LaunchApp ->
        if (link == null || link == AppDestinations.AS_USUAL || AppDestinations.accepts(packageName, link)) this else copy(link = null)
    is DockAction.Macro -> copy(steps = steps.map { if (it is MacroStep.Run) MacroStep.Run(it.action.safeLink()) else it })
    else -> this
}

private fun DockSlot.safeLinks(): DockSlot = copy(primary = primary?.safeLink(), secondary = secondary?.safeLink())

/** The dock with every refused app link dropped, pins kept. */
internal fun DockConfig.safeLinks(): DockConfig = copy(
    slots = slots.map { it.safeLinks() },
    spare = spare.map { it.safeLinks() },
    legacySlots = legacySlots?.map { it.safeLinks() },
    longPress = longPress.mapValues { it.value.safeLink() },
)

/** The preferences with every refused per-app default dropped. */
internal fun AppPrefs.safeDestinations(): AppPrefs = copy(appDestinations = appDestinations.safeDestinations())
