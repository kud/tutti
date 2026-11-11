package io.kud.tutti.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroSoundRuleTest {
    private val quietPhoneKeySoundsOn = IntroSoundContext(
        keySounds = true,
        touchSounds = true,
        coldStart = true,
        ringer = Ringer.NORMAL,
        doNotDisturb = false,
        phoneMediaPlaying = false,
    )

    @Test
    fun `plays on a cold start with key sounds on and nothing in the way`() {
        assertTrue(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn))
    }

    @Test
    fun `key sounds off, the default, means no chime`() {
        assertFalse(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn.copy(keySounds = false)))
    }

    @Test
    fun `android's touch sounds switch silences it like the key click`() {
        assertFalse(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn.copy(touchSounds = false)))
    }

    @Test
    fun `only a cold start chimes`() {
        assertFalse(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn.copy(coldStart = false)))
    }

    @Test
    fun `silent and vibrate ringers keep it quiet`() {
        assertFalse(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn.copy(ringer = Ringer.SILENT)))
        assertFalse(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn.copy(ringer = Ringer.VIBRATE)))
    }

    @Test
    fun `do not disturb keeps it quiet`() {
        assertFalse(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn.copy(doNotDisturb = true)))
    }

    @Test
    fun `never plays over the phone's own media`() {
        assertFalse(IntroSoundRule.shouldPlay(quietPhoneKeySoundsOn.copy(phoneMediaPlaying = true)))
    }
}
