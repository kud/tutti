package io.kud.tutti.protocol.bravia

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every value here is invented: no real cookie, code or client id. */
class IpRegistrationTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val client = "tutti:00000000-0000-4000-8000-000000000000"

    private class Recorder(private val answer: (Map<String, String>) -> HttpReply) : BraviaTransport {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()

        override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply {
            calls += Triple(path, body, headers)
            return answer(headers)
        }
    }

    private fun ok(vararg cookies: String) = HttpReply(200, "{\"result\":[],\"id\":1}", cookies.toList())

    @Test
    fun `basic auth is an empty user and the code`() {
        // base64(":1234")
        assertEquals("Basic OjEyMzQ=", IpRegistration.basic("1234"))
    }

    @Test
    fun `a code is exactly four digits`() {
        assertTrue(IpRegistration.validCode("0420"))
        listOf("", "123", "12345", "12a4", "１２３４", " 1234").forEach { assertFalse(IpRegistration.validCode(it), it) }
    }

    @Test
    fun `the request carries sony's params`() {
        val params = JsonArray(IpRegistration.params(client))
        val who = params[0].jsonObject
        assertEquals(client, who["clientid"]!!.jsonPrimitive.content)
        assertEquals("tutti", who["nickname"]!!.jsonPrimitive.content)
        assertEquals("private", who["level"]!!.jsonPrimitive.content)
        val wol = params[1].jsonArray.single().jsonObject
        assertEquals("WOL", wol["function"]!!.jsonPrimitive.content)
        assertEquals("yes", wol["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `max-age wins over expires`() {
        val cookie = IpRegistration.parseSetCookie(
            listOf("auth=fakecookie123; Path=/sony/; Expires=Thu, 01 Jan 2099 00:00:00 GMT; Max-Age=1209600"),
            client,
            now,
        )!!
        assertEquals("fakecookie123", cookie.value)
        assertEquals(now + 14 * day, cookie.expiresAtMs)
        assertEquals(client, cookie.clientId)
    }

    @Test
    fun `expires is read leniently, in the forms seen on old servers`() {
        val expected = java.time.Instant.parse("2027-01-07T10:00:00Z").toEpochMilli()
        val earlier = expected - 14 * day
        listOf(
            "Thu, 07 Jan 2027 10:00:00 GMT",
            "Thu, 07-Jan-2027 10:00:00 GMT",
            "Thu, 07-Jan-27 10:00:00 GMT",
        ).forEach { date ->
            assertEquals(expected, IpRegistration.parseSetCookie(listOf("auth=x1; expires=$date; path=/"), client, earlier)?.expiresAtMs, date)
        }
        // Unreadable: the cookie still counts, with no expiry.
        val odd = IpRegistration.parseSetCookie(listOf("auth=x1; Expires=someday"), client, now)!!
        assertNull(odd.expiresAtMs)
    }

    @Test
    fun `only the auth cookie, with a clean value, still alive`() {
        assertNull(IpRegistration.parseSetCookie(listOf("session=abc; Path=/"), client, now))
        assertNull(IpRegistration.parseSetCookie(listOf("auth=bad value"), client, now))
        assertNull(IpRegistration.parseSetCookie(listOf("auth="), client, now))
        assertNull(IpRegistration.parseSetCookie(listOf("auth=x1; Max-Age=0"), client, now))
        assertNull(IpRegistration.parseSetCookie(listOf("auth=x1; Expires=Thu, 01 Jan 1970 00:00:00 GMT"), client, now))
        assertEquals("x2", IpRegistration.parseSetCookie(listOf("other=1", "auth=x2"), client, now)?.value)
    }

    @Test
    fun `renewal comes three days before expiry, or a week in when it is unknown`() {
        val known = AuthCookie(client, "x1", expiresAtMs = now + 14 * day, obtainedAtMs = now)
        assertFalse(IpRegistration.needsRenewal(known, now + 10 * day))
        assertTrue(IpRegistration.needsRenewal(known, now + 11 * day))
        assertFalse(IpRegistration.expired(known, now + 13 * day))
        assertTrue(IpRegistration.expired(known, now + 14 * day))

        val unknown = AuthCookie(client, "x1", expiresAtMs = null, obtainedAtMs = now)
        assertFalse(IpRegistration.needsRenewal(unknown, now + 6 * day))
        assertTrue(IpRegistration.needsRenewal(unknown, now + 7 * day))
        assertFalse(IpRegistration.expired(unknown, now + 365 * day))
    }

    @Test
    fun `the stored form round-trips and refuses anything else`() {
        val cookie = AuthCookie(client, "x1-abc", expiresAtMs = now + day, obtainedAtMs = now)
        assertEquals(cookie, AuthCookie.decode(cookie.encode()))
        val noExpiry = AuthCookie(client, "x1-abc", expiresAtMs = null, obtainedAtMs = now)
        assertEquals(noExpiry, AuthCookie.decode(noExpiry.encode()))
        listOf(null, "", "x1-abc", "2\t$client\t1\t\tx", "1\t\t1\t\tx", "1\t$client\tno\t\tx", "1\t$client\t1\t\tbad value").forEach {
            assertNull(AuthCookie.decode(it), "$it")
        }
    }

    @Test
    fun `the cookie never shows in toString`() {
        val text = AuthCookie(client, "fakesecretvalue", null, now).toString()
        assertFalse("fakesecretvalue" in text)
        assertFalse("fakesecretvalue" in ok("auth=fakesecretvalue").toString())
    }

    @Test
    fun `asking without credentials makes the TV show its code`() = runTest {
        val transport = Recorder { HttpReply(401, "") }
        val result = BraviaClient(transport) { null }.requestCode(client, nowMs = now)
        assertEquals(BraviaResult.Ok(Registration.CodeShown), result)
        val (path, body, headers) = transport.calls.single()
        assertEquals("/sony/accessControl", path)
        assertFalse(BraviaClient.carriesCredential(headers))
        val rpc = Json.parseToJsonElement(body).jsonObject
        assertEquals("actRegister", rpc["method"]!!.jsonPrimitive.content)
        assertEquals("1.0", rpc["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a 403 when asking is a TV that won't register, not a code on screen`() = runTest {
        assertEquals(BraviaResult.Failure(BraviaError.Unauthorized), BraviaClient(Recorder { HttpReply(403, "") }) { null }.requestCode(client))
        val inBody = BraviaClient(Recorder { HttpReply(200, "{\"error\":[403,\"Forbidden\"],\"id\":1}") }) { null }
        assertEquals(BraviaResult.Failure(BraviaError.Unauthorized), inBody.requestCode(client))
        // Sending a code, 403 is a refusal like 401.
        assertEquals(BraviaResult.Ok(Registration.Refused), BraviaClient(Recorder { HttpReply(403, "") }) { null }.submitCode(client, "1234"))
    }

    @Test
    fun `a client already registered may be let straight back in`() = runTest {
        val result = BraviaClient(Recorder { ok("auth=x9; Max-Age=60") }) { null }.requestCode(client, nowMs = now)
        assertEquals(BraviaResult.Ok(Registration.Registered(AuthCookie(client, "x9", now + 60_000, now))), result)
    }

    @Test
    fun `the code goes as basic auth and the cookie comes back`() = runTest {
        val transport = Recorder { headers -> if (headers[BraviaClient.AUTHORIZATION] == "Basic OjEyMzQ=") ok("auth=x1; Max-Age=1209600") else HttpReply(401, "") }
        val api = BraviaClient(transport) { null }
        val got = api.submitCode(client, "1234", nowMs = now)
        assertEquals(BraviaResult.Ok(Registration.Registered(AuthCookie(client, "x1", now + 14 * day, now))), got)
        assertEquals(BraviaResult.Ok(Registration.Refused), api.submitCode(client, "4321", nowMs = now))
        // Not four digits: refused here, never sent.
        val before = transport.calls.size
        assertEquals(BraviaResult.Ok(Registration.Refused), api.submitCode(client, "12", nowMs = now))
        assertEquals(before, transport.calls.size)
    }

    @Test
    fun `a 200 without a cookie is not a registration`() = runTest {
        assertEquals(BraviaResult.Failure(BraviaError.Malformed("no auth cookie")), BraviaClient(Recorder { ok() }) { null }.submitCode(client, "1234"))
    }

    @Test
    fun `sony's errors and the network stay failures`() = runTest {
        val off = BraviaClient(Recorder { HttpReply(200, "{\"error\":[40005,\"Display Is Turned off\"],\"id\":1}") }) { null }
        assertEquals(BraviaResult.Failure(BraviaError.Rpc(40005, "Display Is Turned off")), off.requestCode(client))
        val refusedInBody = BraviaClient(Recorder { HttpReply(200, "{\"error\":[401,\"Unauthorized\"],\"id\":1}") }) { null }
        assertEquals(BraviaResult.Ok(Registration.Refused), refusedInBody.submitCode(client, "1234"))
        val down = BraviaClient(object : BraviaTransport {
            override suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply = throw IOException("refused")
        }) { null }
        assertIs<BraviaError.Transport>((down.requestCode(client) as BraviaResult.Failure).error)
    }

    @Test
    fun `renewal sends only the cookie and keeps it when the TV issues nothing new`() = runTest {
        val current = AuthCookie(client, "x1", now + day, now - 13 * day)
        val transport = Recorder { ok() }
        val api = BraviaClient(transport) { null }
        assertEquals(BraviaResult.Ok(Registration.Registered(current)), api.renew(current, nowMs = now))
        val headers = transport.calls.single().third
        assertEquals("auth=x1", headers[BraviaClient.COOKIE])
        assertNull(headers[BraviaClient.AUTHORIZATION])
        assertNull(headers[BraviaClient.PSK_HEADER])

        val fresh = BraviaClient(Recorder { ok("auth=x2; Max-Age=1209600") }) { null }.renew(current, nowMs = now)
        assertEquals(BraviaResult.Ok(Registration.Registered(AuthCookie(client, "x2", now + 14 * day, now))), fresh)
        assertEquals(BraviaResult.Ok(Registration.Refused), BraviaClient(Recorder { HttpReply(401, "") }) { null }.renew(current, nowMs = now))
    }

    @Test
    fun `calls carry the cookie instead of the psk when there is one`() = runTest {
        val transport = Recorder { HttpReply(200, "{\"result\":[{\"status\":\"active\"}],\"id\":1}") }
        BraviaClient(transport, cookie = { "x1" }) { "0000" }.powerStatus()
        val headers = transport.calls.single().third
        assertEquals("auth=x1", headers[BraviaClient.COOKIE])
        assertNull(headers[BraviaClient.PSK_HEADER])
        assertTrue(BraviaClient.carriesCredential(headers))

        BraviaClient(transport, cookie = { null }) { "0000" }.powerStatus()
        assertEquals("0000", transport.calls.last().third[BraviaClient.PSK_HEADER])
        assertNull(transport.calls.last().third[BraviaClient.COOKIE])
    }

    @Test
    fun `ircc carries the cookie too`() = runTest {
        val transport = Recorder { HttpReply(200, "") }
        assertEquals(BraviaResult.Ok(Unit), BraviaClient(transport, cookie = { "x1" }) { null }.sendIrcc("AAAAAQAAAAEAAAAvAw=="))
        assertEquals("auth=x1", transport.calls.single().third[BraviaClient.COOKIE])
    }

    @Test
    fun `credential headers are recognised in any case`() {
        assertTrue(BraviaClient.carriesCredential(mapOf("cookie" to "auth=x")))
        assertTrue(BraviaClient.carriesCredential(mapOf("AUTHORIZATION" to "Basic x")))
        assertTrue(BraviaClient.carriesCredential(mapOf("x-auth-psk" to "k")))
        assertFalse(BraviaClient.carriesCredential(mapOf("Content-Type" to "application/json")))
    }
}
