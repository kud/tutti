package io.kud.tutti.controller

import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.Ircc
import io.kud.tutti.protocol.bravia.IrccCode
import io.kud.tutti.protocol.bravia.SystemInfo
import io.kud.tutti.protocol.bravia.TvSetting

/** What one call of a TV settings sweep came back with. */
sealed interface TvAnswer<out T> {
    /** The TV answered; the value may itself be empty or null ("the TV has none"). */
    data class Got<T>(val value: T) : TvAnswer<T>

    /** The call was made and failed (timeout, refused key, transport). */
    data object Failed : TvAnswer<Nothing>

    /** The TV does not list the method, so it was never asked. */
    data object NotOffered : TvAnswer<Nothing>

    /** Not asked yet in this sweep: whatever is showing stays. */
    data object Pending : TvAnswer<Nothing>

    companion object {
        /** Asks only when the TV lists the method; a Failure becomes [Failed]. */
        suspend fun <T> ask(offered: Boolean, call: suspend () -> BraviaResult<T>): TvAnswer<T> {
            if (!offered) return NotOffered
            return when (val result = call()) {
                is BraviaResult.Ok -> Got(result.value)
                is BraviaResult.Failure -> Failed
            }
        }
    }
}

/** Every answer of one [TuttiController.loadTvSettings] sweep; [TvAnswer.Pending] until asked. */
data class TvSettingsAnswers(
    val picture: TvAnswer<List<TvSetting>> = TvAnswer.Pending,
    val scene: TvAnswer<TvSetting?> = TvAnswer.Pending,
    val sound: TvAnswer<List<TvSetting>> = TvAnswer.Pending,
    val speaker: TvAnswer<List<TvSetting>> = TvAnswer.Pending,
    val powerSaving: TvAnswer<String?> = TvAnswer.Pending,
    val wol: TvAnswer<Boolean?> = TvAnswer.Pending,
    val ircc: TvAnswer<List<IrccCode>> = TvAnswer.Pending,
    val system: TvAnswer<SystemInfo?> = TvAnswer.Pending,
)

/**
 * Folds a sweep's answers into what is showing. A first load shows a failed part as empty, as it
 * always has. A re-check (the state was already [TvSettingsState.loaded]) keeps the previous value
 * of any part whose call failed, so a timeout on a quiet re-check never wipes rows that were on
 * screen (TvHub.loadCue keeps re-checks quiet: Iris, 2026-10-26). A part the TV does not list is
 * empty either way, and a part not asked yet keeps what is showing.
 */
object TvSettingsSweep {
    fun merge(
        current: TvSettingsState,
        answers: TvSettingsAnswers,
        recheck: Boolean,
        outputSettable: Boolean,
        powerSavingSettable: Boolean,
    ): TvSettingsState {
        fun <T, R> TvAnswer<T>.pick(previous: R, absent: R, read: (T) -> R): R = when (this) {
            is TvAnswer.Got -> read(value)
            TvAnswer.NotOffered -> absent
            TvAnswer.Failed -> if (recheck) previous else absent
            TvAnswer.Pending -> previous
        }
        fun <T> TvAnswer<T>.pick(previous: T, absent: T): T = pick(previous, absent) { it }
        return current.copy(
            picture = answers.picture.pick(current.picture, emptyList()),
            scene = answers.scene.pick(current.scene, null),
            sound = answers.sound.pick(current.sound, emptyList()),
            speaker = answers.speaker.pick(current.speaker, emptyList()),
            outputWriteOnly = answers.sound.pick(current.outputWriteOnly, outputSettable) { false },
            powerSaving = answers.powerSaving.pick(current.powerSaving, null),
            powerSavingSupported = answers.powerSaving.pick(current.powerSavingSupported, powerSavingSettable) { it != null || powerSavingSettable },
            wol = answers.wol.pick(current.wol, null),
            sleepCode = answers.ircc.pick(current.sleepCode, null) { Ircc.sleepTimerCode(it) },
            ircc = answers.ircc.pick(current.ircc, emptyList()) { Ircc.shortcuts(it) },
            system = answers.system.pick(current.system, null),
        )
    }
}
