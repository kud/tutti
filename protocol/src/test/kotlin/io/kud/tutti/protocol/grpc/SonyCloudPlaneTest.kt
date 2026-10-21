package io.kud.tutti.protocol.grpc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonyCloudPlaneTest {
    @Test
    fun `pkce matches the rfc 7636 appendix b vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        assertEquals(43, Pkce.verifier().length)
    }

    @Test
    fun `session keys never print their secrets`() {
        val keys = SessionKeys("dev", "kid-secret", "sk-secret", "hmac-secret", 1_000)
        val text = keys.toString()
        assertFalse(text.contains("secret"))
        assertTrue(keys.isExpired(nowMillis = 1_000 - 1, marginMillis = 10))
    }

    @Test
    fun `the unavailable plane is inert`() = runTest {
        val plane = UnavailableSonyCloudPlane()
        assertIs<CloudPlaneState.Unavailable>(plane.state.value)
        assertNull(plane.getStates(listOf(FieldPaths.POWER)))
        assertFalse(plane.exec(FieldPaths.NIGHT_MODE, GrpcValue.Bool(true)))
    }
}
