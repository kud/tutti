package io.kud.tutti.protocol.bravia

import kotlin.test.Test
import kotlin.test.assertEquals

class IrccShortcutsTest {
    @Test
    fun `display names are spaced out`() {
        assertEquals("Action Menu", Ircc.displayName("ActionMenu"))
        assertEquals("Tv Radio", Ircc.displayName("Tv_Radio"))
        assertEquals("Num 1", Ircc.displayName("Num1"))
        assertEquals("Netflix", Ircc.displayName("Netflix"))
    }

    @Test
    fun `one per name, blanks dropped, sorted for reading`() {
        val codes = listOf(
            IrccCode("Sleep", "AAAA"),
            IrccCode("ActionMenu", "BBBB"),
            IrccCode("Sleep", "CCCC"),
            IrccCode("", "DDDD"),
            IrccCode("Empty", ""),
        )
        assertEquals(listOf(IrccCode("ActionMenu", "BBBB"), IrccCode("Sleep", "AAAA")), Ircc.shortcuts(codes))
    }
}
