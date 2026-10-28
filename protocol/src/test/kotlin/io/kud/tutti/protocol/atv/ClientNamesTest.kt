package io.kud.tutti.protocol.atv

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Model strings are invented; no real device names or ids. */
class ClientNamesTest {
    @Test
    fun `a new name carries the model and a short suffix`() {
        val name = ClientNames.forInstall("Phone X", Random(1))
        assertTrue(Regex("""tutti \(Phone X [0-9a-f]{4}\)""").matches(name), name)
        assertTrue(ClientNames.isPerInstall(name))
    }

    @Test
    fun `two phones of the same model get different names`() {
        val random = Random(42)
        val names = List(20) { ClientNames.forInstall("Phone X", random) }.toSet()
        assertTrue(names.size > 1)
        assertNotEquals(ClientNames.forInstall("Phone X", Random(1)), ClientNames.forInstall("Phone X", Random(2)))
    }

    @Test
    fun `the name survives the certificate CN unchanged`() {
        for (model in listOf("Phone X", "ACME A6013", "Ünïcode Fone", "a,b=c+d<e>#f;\"g\\h(i)", "  spaced   out  ")) {
            val name = ClientNames.forInstall(model, Random(3))
            val identity = ClientCertificates.generate(name)
            assertEquals(name, ClientNames.of(identity.certificate), model)
        }
    }

    @Test
    fun `a blank or missing model falls back to Android`() {
        assertTrue(ClientNames.forInstall(null, Random(1)).startsWith("tutti (Android "))
        assertTrue(ClientNames.forInstall("  \n ", Random(1)).startsWith("tutti (Android "))
    }

    @Test
    fun `a long model is capped`() {
        val name = ClientNames.forInstall("X".repeat(200), Random(1))
        assertEquals("tutti (" + "X".repeat(24) + " ", name.substring(0, 32))
        assertTrue(name.length <= 40)
    }

    @Test
    fun `a legacy certificate reads as tutti and is not per-install`() {
        val legacy = ClientCertificates.generate("tutti")
        assertEquals(ClientNames.LEGACY, ClientNames.of(legacy.certificate))
        assertFalse(ClientNames.isPerInstall(ClientNames.of(legacy.certificate)))
    }
}
