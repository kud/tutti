package io.kud.tutti.protocol.theatre

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceWatchTest {
    @Test
    fun `two silent polls in a row drop the link`() {
        val watch = SilenceWatch()
        assertFalse(watch.afterPoll(5, 5))
        assertTrue(watch.afterPoll(5, 5))
    }

    @Test
    fun `any answer resets the count`() {
        val watch = SilenceWatch()
        assertFalse(watch.afterPoll(5, 5))
        assertFalse(watch.afterPoll(5, 6))
        assertFalse(watch.afterPoll(6, 6))
        assertTrue(watch.afterPoll(6, 6))
    }
}
