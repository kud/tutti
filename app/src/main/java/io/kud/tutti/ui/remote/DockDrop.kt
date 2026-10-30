package io.kud.tutti.ui.remote

/**
 * What letting go of a lifted pin does in the layout editor (Erwann, 2026-10-30): over another
 * place it moves there, trading with whatever sits in it; off the dock it leaves (with an Undo);
 * back on its own place, or in a gap between places, nothing happens and it springs back.
 */
sealed interface DockDrop {
    data class Move(val from: Int, val to: Int) : DockDrop
    data class Remove(val from: Int) : DockDrop
    data object Stay : DockDrop
}

/** [from] the place lifted, [over] the place under the finger if any, [onDock] whether the finger is over the dock's frame. */
fun dockDrop(from: Int, over: Int?, onDock: Boolean): DockDrop = when {
    over != null && over != from -> DockDrop.Move(from, over)
    over == null && !onDock -> DockDrop.Remove(from)
    else -> DockDrop.Stay
}
