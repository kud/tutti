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

    private val host = "192.0.2.10"
    private val up = PlaneState(online = true, host = host, pinned = true)
    private val connecting = PlaneState(online = false, host = host, pinned = true)

    @Test
    fun `a new identity is admitted at once while the other plane vouches for the tv`() {
        val rest = PendingIdentity(PinPlane.REST, host, a, seenAtMs = 1_000)
        assertEquals(AutoTrust.Decision.ADMIT, AutoTrust.decide(rest, nowMs = 1_000, remote = up, rest = connecting))
        val remote = PendingIdentity(PinPlane.REMOTE, host, a, seenAtMs = 1_000)
        assertEquals(AutoTrust.Decision.ADMIT, AutoTrust.decide(remote, nowMs = 1_000, remote = connecting, rest = up))
    }

    @Test
    fun `it waits for the other plane and is admitted when the gate opens inside the window`() {
        val remote = PendingIdentity(PinPlane.REMOTE, host, a, seenAtMs = 1_000)
        assertEquals(AutoTrust.Decision.WAIT, AutoTrust.decide(remote, nowMs = 1_000, remote = connecting, rest = connecting))
        val opens = 1_000 + AutoTrust.WINDOW_MS
        assertEquals(AutoTrust.Decision.ADMIT, AutoTrust.decide(remote, nowMs = opens, remote = connecting, rest = up))
    }

    @Test
    fun `past the window it is never admitted on its own`() {
        val remote = PendingIdentity(PinPlane.REMOTE, host, a, seenAtMs = 1_000)
        val late = 1_000 + AutoTrust.WINDOW_MS + 1
        assertEquals(AutoTrust.Decision.DROP, AutoTrust.decide(remote, nowMs = late, remote = connecting, rest = up))
        // Seen again: a fresh sighting starts a fresh window.
        assertEquals(AutoTrust.Decision.ADMIT, AutoTrust.decide(remote.copy(seenAtMs = late), nowMs = late, remote = connecting, rest = up))
    }

    @Test
    fun `another host or an unpinned other plane never vouches`() {
        val rest = PendingIdentity(PinPlane.REST, host, a, seenAtMs = 0)
        val elsewhere = PlaneState(online = true, host = "192.0.2.11", pinned = true)
        assertEquals(AutoTrust.Decision.WAIT, AutoTrust.decide(rest, nowMs = 0, remote = elsewhere, rest = connecting))
        val remote = PendingIdentity(PinPlane.REMOTE, host, a, seenAtMs = 0)
        val http = PlaneState(online = true, host = host, pinned = false)
        assertEquals(AutoTrust.Decision.WAIT, AutoTrust.decide(remote, nowMs = 0, remote = connecting, rest = http))
        // The other plane not set up at all, for the whole window: dropped, never admitted.
        val off = PlaneState(online = false, host = null, pinned = false)
        assertEquals(AutoTrust.Decision.DROP, AutoTrust.decide(remote, nowMs = AutoTrust.WINDOW_MS + 1, remote = connecting, rest = off))
    }

    @Test
    fun `when both planes changed neither vouches for the other`() {
        val remote = PendingIdentity(PinPlane.REMOTE, host, a, seenAtMs = 0)
        val rest = PendingIdentity(PinPlane.REST, host, b, seenAtMs = 0)
        // Even if a link still reads online from before its change, the other plane's change wins.
        assertEquals(AutoTrust.Decision.DROP, AutoTrust.decide(remote, nowMs = 0, remote = connecting, rest = up, otherPending = rest))
        assertEquals(AutoTrust.Decision.DROP, AutoTrust.decide(rest, nowMs = 0, remote = up, rest = connecting, otherPending = remote))
        // An old change on the other plane, long settled, does not block.
        val stale = rest.copy(seenAtMs = -AutoTrust.WINDOW_MS - 1)
        assertEquals(AutoTrust.Decision.ADMIT, AutoTrust.decide(remote, nowMs = 0, remote = connecting, rest = up, otherPending = stale))
    }

    @Test
    fun `an automatic admission is recorded beside the mismatches and a dev_4 log reads back`() {
        val mismatch = PinMismatchEvent(1L, PinPlane.REST, hostLabel(host, true), listOf("aaaaaaaa"), "bbbbbbbb", "cccccccc", false)
        // A mismatch encodes exactly as 0.3.1-dev.4 wrote it.
        assertEquals("1\t1\trest\t\u2026.10 (stored)\taaaaaaaa\tbbbbbbbb\tcccccccc\t0", mismatch.encode())
        val admitted = mismatch.copy(atMs = 2L, admittedVia = PinPlane.REMOTE)
        val stored = PinMismatchLog.add(PinMismatchLog.add(null, mismatch), admitted)
        assertEquals(listOf(admitted, mismatch), PinMismatchLog.decode(stored))
        val report = PinMismatchLog.report(PinMismatchLog.decode(stored))
        assertTrue("- rest admitted automatically via remote host=" in report)
        assertNull(PinMismatchEvent.decode(admitted.encode() + "\textra"))
        assertNull(PinMismatchEvent.decode(admitted.encode().replace("\tremote", "\tnowhere")))
    }
}
