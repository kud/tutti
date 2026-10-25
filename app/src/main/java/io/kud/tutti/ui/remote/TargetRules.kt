package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.protocol.hid.RemoteLink

/** What a target is, for its glyph and its words. Kind, not link, decides how the remote behaves. */
enum class TargetKind { TV, COMPUTER }

/**
 * One row of the target sheet: a device present now (Iris, 2026-10-25). [link] is the link its
 * keys use right now, said in words on the row; [current] wears the tick.
 */
data class TargetRow(
    val id: String,
    val name: String,
    val kind: TargetKind,
    val link: RemoteLink,
    val current: Boolean,
    /** A computer whose TV input isn't showing: the row offers "Show on the TV" ([showOnTvOffered]). */
    val showOnTv: Boolean = false,
)

/**
 * "Computer on the TV" in one gesture (Iris's top 10, item 10): offered for a computer with a known
 * TV input when that input isn't on screen, or the TV is off. Switching needs IP control
 * (setPlayContent), so without it the row offers nothing rather than a button that can't work.
 */
fun showOnTvOffered(computer: Boolean, hdmiInput: String?, tvInput: String?, tvPowered: Boolean?, ipControl: Boolean): Boolean =
    computer && ipControl && hdmiInput != null && (tvInput != hdmiInput || tvPowered == false)

/**
 * The chip shows only when there is a choice: with the TV alone present the remote simply is the
 * TV's, and a chip that opens a sheet of one row would be noise.
 */
fun targetChipShown(rows: List<TargetRow>): Boolean = rows.size > 1 && rows.any { it.kind != TargetKind.TV }

/** The current row, or the TV's when none is marked (the remote's frame). */
fun currentTarget(rows: List<TargetRow>): TargetRow? = rows.firstOrNull { it.current } ?: rows.firstOrNull { it.kind == TargetKind.TV }

/**
 * TalkBack hears a target change only when it is real (Iris, 2026-10-25): a new target that holds
 * for [TARGET_SETTLE_MS], never a blip of the TV's input, never the first target on opening.
 */
fun targetAnnounced(lastAnnounced: String?, settled: String?): Boolean =
    lastAnnounced != null && settled != null && settled != lastAnnounced

const val TARGET_SETTLE_MS = 800L

/**
 * The pad's modes for a target, in the order the corner switch cycles them. A Bluetooth target
 * (the computer, or the TV with its LAN down) gets the gyro pointer as a third mode; it is always in
 * the cycle there, disabled with its reason when it can't work, never hidden (Iris, 2026-10-25).
 */
fun padModes(bluetooth: Boolean): List<PadMode> =
    if (bluetooth) listOf(PadMode.TOUCHPAD, PadMode.DPAD, PadMode.GYRO) else listOf(PadMode.TOUCHPAD, PadMode.DPAD)

/** The mode the switch moves to from [current]: the next in [padModes], wrapping round. */
fun nextPadMode(current: PadMode, bluetooth: Boolean): PadMode {
    val modes = padModes(bluetooth)
    val at = modes.indexOf(current)
    return if (at < 0) modes.first() else modes[(at + 1) % modes.size]
}

/** Why the gyro pointer can't run now, or [NONE]. The sensor comes first: no link fixes it. */
enum class GyroBlock { NONE, NO_SENSOR, LINK_DOWN }

fun gyroBlock(hasGyroscope: Boolean, gyroPossible: Boolean): GyroBlock = when {
    !hasGyroscope -> GyroBlock.NO_SENSOR
    !gyroPossible -> GyroBlock.LINK_DOWN
    else -> GyroBlock.NONE
}

/**
 * The mode the pad shows: the one remembered for this target, unless it can't run here. Gyro off
 * a Bluetooth target, or with the link gone, falls back to [previous] (never gyro itself), so a
 * dropped link puts the pad back where it was before gyro, with a short message.
 */
fun padModeShown(remembered: PadMode, bluetooth: Boolean, gyroPossible: Boolean, previous: PadMode = PadMode.TOUCHPAD): PadMode =
    if (remembered == PadMode.GYRO && !(bluetooth && gyroPossible)) {
        previous.takeIf { it != PadMode.GYRO } ?: PadMode.TOUCHPAD
    } else {
        remembered
    }

/** The quiet link indicator shows only when a key goes over Bluetooth: the LAN is the normal case. */
fun linkIndicatorShown(link: RemoteLink): Boolean = link == RemoteLink.BLUETOOTH
