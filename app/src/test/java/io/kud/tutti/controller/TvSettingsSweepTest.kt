package io.kud.tutti.controller

import io.kud.tutti.protocol.bravia.BraviaError
import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.IrccCode
import io.kud.tutti.protocol.bravia.TvSetting
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TvSettingsSweepTest {
    private fun setting(target: String, value: String = "1") = TvSetting(target = target, currentValue = value, isAvailable = true, candidates = emptyList())

    private val showing = TvSettingsState(
        loaded = true,
        picture = listOf(setting("brightness")),
        scene = setting("scene", "auto"),
        sound = listOf(setting("soundField")),
        speaker = listOf(setting("tvPosition")),
        powerSaving = "low",
        powerSavingSupported = true,
        wol = true,
        sleepCode = "AAAAAQAAAAEAAABpAw==",
        ircc = listOf(IrccCode("Sleep", "AAAAAQAAAAEAAABpAw==")),
    )

    private val allFailed = TvSettingsAnswers(
        picture = TvAnswer.Failed,
        scene = TvAnswer.Failed,
        sound = TvAnswer.Failed,
        speaker = TvAnswer.Failed,
        powerSaving = TvAnswer.Failed,
        wol = TvAnswer.Failed,
        ircc = TvAnswer.Failed,
        system = TvAnswer.Failed,
    )

    private fun merge(current: TvSettingsState, answers: TvSettingsAnswers, recheck: Boolean, outputSettable: Boolean = false, savingSettable: Boolean = false) =
        TvSettingsSweep.merge(current, answers, recheck, outputSettable, savingSettable)

    @Test
    fun `a failed re-check keeps every row that was showing`() {
        assertEquals(showing, merge(showing, allFailed, recheck = true, outputSettable = true))
    }

    @Test
    fun `a failed first load shows nothing, as before`() {
        val merged = merge(TvSettingsState(), allFailed, recheck = false)
        assertEquals(emptyList<TvSetting>(), merged.picture)
        assertEquals(emptyList<TvSetting>(), merged.sound)
        assertNull(merged.scene)
        assertNull(merged.powerSaving)
        assertNull(merged.wol)
        assertNull(merged.sleepCode)
        assertFalse(merged.powerSavingSupported)
    }

    @Test
    fun `a failed first load still offers what the TV can set`() {
        val merged = merge(TvSettingsState(), allFailed, recheck = false, outputSettable = true, savingSettable = true)
        assertTrue(merged.outputWriteOnly)
        assertTrue(merged.powerSavingSupported)
    }

    @Test
    fun `a re-check takes what answered and keeps only what failed`() {
        val fresh = listOf(setting("brightness", "30"))
        val merged = merge(showing, allFailed.copy(picture = TvAnswer.Got(fresh), wol = TvAnswer.Got(false)), recheck = true)
        assertEquals(fresh, merged.picture)
        assertEquals(false, merged.wol)
        assertEquals(showing.sound, merged.sound)
        assertEquals(showing.scene, merged.scene)
    }

    @Test
    fun `an answer of nothing clears a row even on a re-check`() {
        val merged = merge(showing, allFailed.copy(scene = TvAnswer.Got(null), sound = TvAnswer.Got(emptyList())), recheck = true)
        assertNull(merged.scene)
        assertEquals(emptyList<TvSetting>(), merged.sound)
        assertFalse(merged.outputWriteOnly)
    }

    @Test
    fun `a part the TV no longer lists is cleared on a re-check`() {
        val merged = merge(showing, allFailed.copy(picture = TvAnswer.NotOffered), recheck = true)
        assertEquals(emptyList<TvSetting>(), merged.picture)
    }

    @Test
    fun `parts not asked yet keep what is showing`() {
        assertEquals(showing, merge(showing, TvSettingsAnswers(), recheck = false))
    }

    @Test
    fun `ask maps a result to an answer and never calls what the TV does not list`() = runBlocking {
        var called = false
        assertSame(TvAnswer.NotOffered, TvAnswer.ask(false) { called = true; BraviaResult.Ok(1) })
        assertFalse(called)
        assertEquals(TvAnswer.Got(1), TvAnswer.ask(true) { BraviaResult.Ok(1) })
        assertSame(TvAnswer.Failed, TvAnswer.ask(true) { BraviaResult.Failure(BraviaError.Transport("timeout")) })
    }
}
