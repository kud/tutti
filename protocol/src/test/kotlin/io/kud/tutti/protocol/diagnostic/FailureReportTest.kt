package io.kud.tutti.protocol.diagnostic

import java.io.IOException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureReportTest {
    @Test
    fun `says what was tried, the outcome, the steps, the chain and the context`() {
        val error = IOException("handshake failed", ConnectException("refused"))
        val out = FailureReport.render(
            what = "pair tv",
            outcome = "unreachable",
            steps = listOf("tls connect", "pairing request"),
            error = error,
            context = listOf("app" to "0.1.0-dev.35 (100035)", "links" to "remote ONLINE, rest UNAUTHORIZED, bar ABSENT"),
        )
        assertTrue(out.startsWith("tutti: pair tv"), out)
        assertTrue("outcome: unreachable" in out, out)
        assertTrue("steps: tls connect · pairing request" in out, out)
        assertTrue("IOException: handshake failed ← ConnectException: refused" in out, out)
        assertTrue("\n  at " in out, out)
        assertTrue("app: 0.1.0-dev.35 (100035)" in out, out)
        assertTrue("rest UNAUTHORIZED" in out, out)
    }

    @Test
    fun `scrubs hosts, the psk and anything key-shaped wherever it appears`() {
        val psk = "Hunter22"
        val out = FailureReport.render(
            what = "rest discover on 192.168.1.20",
            outcome = "Rpc(code=403, message=bad key $psk)",
            steps = listOf("pin 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b"),
            error = IllegalStateException("at fd00::12 with $psk"),
            context = listOf("host" to "tv.example.lan"),
            sensitive = listOf(psk, "tv.example.lan"),
        )
        for (leak in listOf("192.168", "Hunter22", "9f86d0", "fd00", "tv.example.lan")) {
            assertFalse(leak in out, "$leak leaked: $out")
        }
        // The probe: the scrubber still matched something, so a pass is not "found nothing".
        assertTrue("<ip>" in out && "<redacted>" in out && "<hex>" in out, out)
    }

    @Test
    fun `a bare failure still names itself`() {
        assertTrue(FailureReport.render("wake").startsWith("tutti: wake"))
    }
}
