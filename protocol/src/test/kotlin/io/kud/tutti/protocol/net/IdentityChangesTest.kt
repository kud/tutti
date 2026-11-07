package io.kud.tutti.protocol.net

import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.SecretKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityChangesTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test
    fun `admitting keeps the previous identity as the second and drops older ones`() {
        assertEquals(listOf(a), TrustedPins.admit(emptyList(), a))
        assertEquals(listOf(b, a), TrustedPins.admit(listOf(a), b))
        assertEquals(listOf(c, b), TrustedPins.admit(listOf(b, a), c))
        assertEquals(listOf(a, b), TrustedPins.admit(listOf(b, a), a))
    }

    @Test
    fun `following only moves an identity that is already trusted`() {
        assertEquals(listOf(a, b), TrustedPins.follow(listOf(b, a), a))
        assertNull(TrustedPins.follow(listOf(b, a), c))
    }

    @Test
    fun `the gate needs the other plane online pinned and at the same host`() {
        val up = PlaneState(online = true, host = "192.0.2.10", pinned = true)
        val down = PlaneState(online = false, host = "192.0.2.10", pinned = true)
        val http = PlaneState(online = true, host = "192.0.2.10", pinned = false)
        val elsewhere = PlaneState(online = true, host = "192.0.2.11", pinned = true)
        assertTrue(TrustGate.allows(PinPlane.REST, "192.0.2.10", remote = up, rest = down))
        assertTrue(TrustGate.allows(PinPlane.REMOTE, "192.0.2.10", remote = down, rest = up))
        assertFalse(TrustGate.allows(PinPlane.REST, "192.0.2.10", remote = down, rest = up))
        assertFalse(TrustGate.allows(PinPlane.REMOTE, "192.0.2.10", remote = up, rest = http))
        assertFalse(TrustGate.allows(PinPlane.REST, "192.0.2.10", remote = elsewhere, rest = down))
    }

    @Test
    fun `events round trip newest first and stay bounded`() {
        var stored: String? = null
        repeat(PinMismatchLog.MAX + 3) { i ->
            stored = PinMismatchLog.add(stored, PinMismatchEvent(i.toLong(), PinPlane.REST, hostLabel("192.0.2.10", true), listOf("aaaaaaaa"), "bbbbbbbb", "cccccccc", false))
        }
        val events = PinMismatchLog.decode(stored)
        assertEquals(PinMismatchLog.MAX, events.size)
        assertEquals((PinMismatchLog.MAX + 2).toLong(), events.first().atMs)
        assertEquals(listOf("aaaaaaaa"), events.first().pinned)
        assertNull(PinMismatchEvent.decode("garbage"))
        assertEquals(emptyList(), PinMismatchLog.decode(null))
    }

    @Test
    fun `the report names no full address and no full digest`() {
        val event = PinMismatchEvent(1L, PinPlane.REMOTE, hostLabel("192.0.2.10", stored = true), listOf(pinLabel(a)), pinLabel(b), pinLabel(c), true)
        val report = PinMismatchLog.report(listOf(event))
        assertFalse("192.0.2" in report)
        assertFalse(a in report)
        assertTrue(".10 (stored)" in report)
        assertTrue("same-key" in report)
        assertEquals("pin mismatches: none recorded", PinMismatchLog.report(emptyList()))
    }

    @Test
    fun `forgetting the tv also forgets the second identities`() {
        val all = SecretKeys.all(Device.TV_ID)
        assertTrue(SecretKeys.remotePin2(Device.TV_ID) in all)
        assertTrue(SecretKeys.restPublicKeyPin2(Device.TV_ID) in all)
        // The 0.3.0 keys are unchanged, so a stored pairing reads back as it was.
        assertEquals("pin/tv/remote", SecretKeys.remotePin(Device.TV_ID))
        assertEquals("pin/tv/rest-spki", SecretKeys.restPublicKeyPin(Device.TV_ID))
    }

    @Test
    fun `a 0_3_0 store reads back as one trusted identity per plane`() {
        val stored = mapOf(SecretKeys.remotePin(Device.TV_ID) to a, SecretKeys.restPublicKeyPin(Device.TV_ID) to b)
        assertEquals(listOf(a), SecretKeys.pins(stored::get, SecretKeys.remotePin(Device.TV_ID), SecretKeys.remotePin2(Device.TV_ID)))
        assertEquals(listOf(b), SecretKeys.pins(stored::get, SecretKeys.restPublicKeyPin(Device.TV_ID), SecretKeys.restPublicKeyPin2(Device.TV_ID)))
        val both = stored + (SecretKeys.remotePin2(Device.TV_ID) to c)
        assertEquals(listOf(a, c), SecretKeys.pins(both::get, SecretKeys.remotePin(Device.TV_ID), SecretKeys.remotePin2(Device.TV_ID)))
    }
}
