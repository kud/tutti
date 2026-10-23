package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamedKeysTest {
    @Test
    fun `every named key is the protocol enum's own code`() {
        for (key in NamedKeys.all) {
            assertEquals(RemoteKeyCode.valueOf("KEYCODE_${key.name}").value, key.code, key.name)
        }
    }

    @Test
    fun `codes and names are unique`() {
        assertEquals(NamedKeys.all.size, NamedKeys.all.map { it.code }.toSet().size)
        assertEquals(NamedKeys.all.size, NamedKeys.all.map { it.name }.toSet().size)
    }

    @Test
    fun `about seventy keys, never letters or text editing`() {
        assertTrue(NamedKeys.all.size in 60..80, "${NamedKeys.all.size}")
        val letters = (29..54).toSet()
        val editing = setOf(V2Key.DEL, V2Key.FORWARD_DEL, V2Key.ENTER, V2Key.TAB, V2Key.SPACE, V2Key.ESCAPE)
        assertTrue(NamedKeys.all.none { it.code in letters || it.code in editing })
    }

    @Test
    fun `common keys are named keys`() {
        assertTrue(NamedKeys.common.all { NamedKeys.byCode(it) != null })
    }
}
