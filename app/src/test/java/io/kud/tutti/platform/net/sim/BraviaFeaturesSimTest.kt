package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.protocol.bravia.BraviaClient
import io.kud.tutti.protocol.bravia.BraviaError
import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.Ircc
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.net.WakeOnLan
import io.kud.tutti.protocol.sim.FakeBraviaTv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV features that ride IP control, end to end through the real OkHttp transport and BRAVIA
 * client against the simulated TV: screen off, close apps, inputs, the app list and launches,
 * the sleep timer key over IRCC, settings, Wake-on-LAN and the MAC it needs, power over REST.
 * Apps, codes, labels and MACs are invented.
 */
class BraviaFeaturesSimTest {
    private val tv = FakeBraviaTv(psk = "1234").start()

    @After
    fun stop() = tv.close()

    private suspend fun client(psk: String = "1234"): BraviaClient {
        val transport = OkHttpBraviaTransport(HOST, null, {}, tv.https.port, tv.http.port, ::loopbackOnly, 2_000)
        return BraviaClient(transport) { psk }.also { it.discover().ok() }
    }

    private fun <T> BraviaResult<T>.ok(): T = (this as? BraviaResult.Ok)?.value ?: throw AssertionError("expected Ok, got $this")

    private fun BraviaResult<*>.error(): BraviaError = (this as? BraviaResult.Failure)?.error ?: throw AssertionError("expected a failure, got $this")

    @Test
    fun `power goes off and on over REST`() = runBlocking {
        val client = client()
        client.setPower(false).ok()
        assertEquals(PowerStatus.STANDBY, client.powerStatus().ok())
        client.setPower(true).ok()
        assertEquals(PowerStatus.ACTIVE, client.powerStatus().ok())
    }

    @Test
    fun `screen off keeps the sound, reads back as picture off, and lights again`() = runBlocking {
        val client = client()
        assertEquals("off", client.powerSavingMode().ok())
        client.setPowerSavingMode("pictureOff").ok()
        assertEquals("pictureOff", tv.powerSaving)
        assertEquals("pictureOff", client.powerSavingMode().ok())
        assertEquals("active", tv.power)
        client.setPowerSavingMode("off").ok()
        assertEquals("off", client.powerSavingMode().ok())
        assertEquals(BraviaError.Rpc(3, "Illegal Argument"), client.setPowerSavingMode("dim").error())
    }

    @Test
    fun `close apps closes everything the TV runs, once`() = runBlocking {
        client().terminateApps().ok()
        assertEquals(1, tv.terminated.get())
        assertEquals(1, tv.calls.count { it == "terminateApps" })
    }

    @Test
    fun `close apps without the key is refused and closes nothing`() = runBlocking {
        assertEquals(BraviaError.Unauthorized, client(psk = "9999").terminateApps().error())
        assertEquals(0, tv.terminated.get())
    }

    @Test
    fun `inputs say which are connected, follow a plug, and switching shows the new input`() = runBlocking {
        val client = client()
        val inputs = client.externalInputs().ok()
        assertEquals(listOf(1, 4), inputs.filter { it.connected }.map { it.uri.substringAfter("port=").toInt() })
        assertEquals("Computer", inputs.single { it.uri.endsWith("port=4") }.label)
        tv.hdmiConnected[2] = true
        assertTrue(client.externalInputs().ok().single { it.uri.endsWith("port=2") }.connected)

        client.setPlayContent("extInput:hdmi?port=4").ok()
        assertEquals("extInput:hdmi?port=4", client.playingContent().ok()?.uri)
        assertEquals(BraviaError.Rpc(3, "Illegal Argument"), client.setPlayContent("extInput:hdmi?port=9").error())
    }

    @Test
    fun `the app list names packages, a launch goes to the TV, and an app reports no content`() = runBlocking {
        val client = client()
        val apps = client.applications().ok()
        assertEquals(listOf("com.example.video", "com.example.music"), apps.map { AppLibrary.packageOf(it.uri) })
        assertTrue("blank icons are no icon", apps.all { it.icon == null })
        client.setActiveApp(apps.first().uri).ok()
        assertEquals(listOf(apps.first().uri), tv.launched.toList())
        // Inside an app the TV has no playing content: the card falls back to the remote's package.
        assertEquals(BraviaError.Rpc(7, "Illegal State"), client.playingContent().error())
    }

    @Test
    fun `the sleep timer key comes from the TV's own list and goes over IRCC with the key`() = runBlocking {
        val client = client()
        val code = Ircc.sleepTimerCode(client.remoteControllerInfo().ok())
        assertEquals("AAAAAQAAAAEAAAA2Aw==", code)
        client.sendIrcc(code!!).ok()
        assertEquals(listOf(code), tv.ircc.toList())
        assertEquals(BraviaError.Unauthorized, client(psk = "9999").sendIrcc(code).error())
        assertEquals(1, tv.ircc.size)
    }

    @Test
    fun `a TV without a sleep timer key offers none`() = runBlocking {
        tv.irccKeys.removeIf { it.first == "SleepTimer" }
        assertNull(Ircc.sleepTimerCode(client().remoteControllerInfo().ok()))
    }

    @Test
    fun `remote start reads and writes, and the MAC to wake is the interface carrying the TV's address`() = runBlocking {
        val client = client()
        assertEquals(false, client.wolMode().ok())
        client.setWolMode(true).ok()
        assertEquals(true, client.wolMode().ok())
        val interfaces = client.networkSettings().ok()
        assertEquals("02:00:00:00:00:01", WakeOnLan.macFor(HOST, interfaces.map { it.ipv4 to it.mac }, null))
    }

    @Test
    fun `picture, sound output and scene settings list their candidates and take a new value`() = runBlocking {
        val client = client()
        val picture = client.pictureSettings().ok()
        assertEquals(listOf("pictureMode", "brightness"), picture.map { it.target })
        assertEquals(listOf("standard", "cinema", "game", "vivid"), picture.first().candidates.map { it.value })
        client.setPictureSetting("pictureMode", "cinema").ok()
        assertEquals("cinema", client.pictureSettings("pictureMode").ok().single().currentValue)
        client.setPictureSetting("brightness", "30").ok()
        assertEquals("30", tv.picture["brightness"])
        assertEquals(BraviaError.Rpc(3, "Illegal Argument"), client.setPictureSetting("brightness", "99").error())

        assertEquals("audioSystem", client.soundSettings("outputTerminal").ok().single().currentValue)
        client.setSoundSetting("outputTerminal", "speaker").ok()
        assertEquals("speaker", tv.outputTerminal)

        assertEquals("auto", client.sceneSetting().ok()?.currentValue)
        client.setSceneSetting("general").ok()
        assertEquals("general", client.sceneSetting().ok()?.currentValue)
    }

    @Test
    fun `a TV that won't register by code refuses the request, and shows no code`() = runBlocking {
        tv.code = null
        val transport = OkHttpBraviaTransport(HOST, null, {}, tv.https.port, tv.http.port, ::loopbackOnly, 2_000)
        val api = BraviaClient(transport, cookie = { null }) { null }
        assertEquals(BraviaError.Unauthorized, api.requestCode("tutti:00000000-0000-4000-8000-000000000002").error())
        assertEquals(0, tv.codesShown.get())
    }
}
