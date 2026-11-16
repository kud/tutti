package io.kud.tutti.platform.net.sim

import io.kud.tutti.platform.net.AndroidTcpConnector
import io.kud.tutti.protocol.sim.FakeTheatreBar
import io.kud.tutti.protocol.sim.SimTcpConnector
import io.kud.tutti.protocol.sim.eventually
import io.kud.tutti.protocol.theatre.TheatreClient
import io.kud.tutti.protocol.theatre.TheatreFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Son sheet's controls end to end: the real soundbar client over the app's TCP connector
 * against the simulated bar, with the features the Bar 9 has and its late 360 Spatial ACK.
 */
class BarFeaturesSimTest {
    private val bar = FakeTheatreBar(
        FakeTheatreBar.DEFAULTS + mapOf(
            "audio.360ssm" to "off",
            "audio.drangecomp" to "auto",
            "audio.imaxmode" to "auto",
            "audio.voiceenhancer" to "upoff",
            "audio.soundfield" to "on",
            "main.bassstep" to "0",
            "bluetooth.mode" to "TX",
        ),
    ).apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() {
        scope.cancel()
        bar.close()
    }

    private suspend fun connected(timeoutMillis: Long = 3_000): TheatreClient {
        val client = TheatreClient(
            SimTcpConnector(AndroidTcpConnector(::loopbackOnly, 3_000), mapOf(TheatreFeature.PORT to bar)),
            scope,
            timeoutMillis = timeoutMillis,
        )
        client.connect(HOST)
        client.state.await("the bar's 360 Spatial read", millis = 6_000) { it.confirmed[TheatreFeature.SPATIAL_360.key] == "off" }
        return client
    }

    @Test
    fun `refresh shows exactly what the bar answers and refuses the rest`() = runBlocking<Unit> {
        val client = connected()
        val known = bar.values.keys
        val state = client.state.await("every feature answered or refused", millis = 8_000) { s ->
            TheatreFeature.everyday.all { it.key in s.confirmed || it.key in s.refused }
        }
        assertEquals(TheatreFeature.everyday.map { it.key }.filter { it in known }.toSet(), state.confirmed.keys)
        assertFalse(state.supports(TheatreFeature.VOICE_ZOOM))
        assertTrue("never asked: ${TheatreFeature.NEVER_ASKED}", bar.lines.none { line -> TheatreFeature.NEVER_ASKED.any { line.contains("\"$it\"") } })
    }

    @Test
    fun `a 360 Spatial ACK past the timeout still ends on, with no warning left`() = runBlocking<Unit> {
        bar.ackDelayMillis[TheatreFeature.SPATIAL_360.key] = 900
        val client = connected(timeoutMillis = 400)
        // The client holds the line until the bar answers or the timeout passes, as the controller's launch does.
        val setting = launch { client.setToggle(TheatreFeature.SPATIAL_360, true) }
        client.state.await("on at once, before the bar answers") { it.value(TheatreFeature.SPATIAL_360) == "on" }
        client.state.await("the timeout rolls it back and says it didn't take") {
            TheatreFeature.SPATIAL_360.key in it.overdue.values.map { o -> o.feature }
        }
        setting.join()
        val state = client.state.await("on, confirmed, and no didn't-take", millis = 4_000) {
            it.confirmed[TheatreFeature.SPATIAL_360.key] == "on" && it.pending.isEmpty() &&
                it.refusedValues(TheatreFeature.SPATIAL_360).isEmpty() && it.overdue.isEmpty()
        }
        assertEquals(true, state.isOn(TheatreFeature.SPATIAL_360))
        assertEquals("on", bar.values[TheatreFeature.SPATIAL_360.key])
    }

    @Test
    fun `a bar that drops what comes before its last answer still reads everything, and loses nothing`() = runBlocking<Unit> {
        bar.busyMillis = 60
        val client = connected()
        client.state.await("every feature answered or refused", millis = 15_000) { s ->
            TheatreFeature.everyday.all { it.key in s.confirmed || it.key in s.refused }
        }
        client.setToggle(TheatreFeature.NIGHT, true)
        client.setLevel(TheatreFeature.VOLUME, 25)
        client.state.await("both sets taken") {
            it.confirmed[TheatreFeature.NIGHT.key] == "on" && it.confirmed[TheatreFeature.VOLUME.key] == "25" && it.pending.isEmpty()
        }
        assertEquals("requests sent before the bar answered the last", 0, bar.dropped.get())
    }

    @Test
    fun `DRC and IMAX take each of their choices, DRC by its ACK alone`() = runBlocking<Unit> {
        val client = connected()
        for (value in listOf("on", "off", "auto")) {
            client.set(TheatreFeature.DRC, value)
            client.state.await("DRC $value confirmed") { it.confirmed[TheatreFeature.DRC.key] == value && it.pending.isEmpty() }
            client.set(TheatreFeature.IMAX, value)
            client.state.await("IMAX $value confirmed") { it.confirmed[TheatreFeature.IMAX.key] == value && it.pending.isEmpty() }
        }
        assertEquals("auto", bar.values[TheatreFeature.DRC.key])
    }

    @Test
    fun `voice, sound field, bass and the input switch, and a change on the bar follows`() = runBlocking<Unit> {
        val client = connected()
        client.setToggle(TheatreFeature.VOICE, true)
        client.setToggle(TheatreFeature.SOUND_FIELD, false)
        client.setLevel(TheatreFeature.BASS, 12)
        client.set(TheatreFeature.INPUT, "hdmi1")
        client.state.await("all four confirmed") {
            it.confirmed[TheatreFeature.VOICE.key] == "upon" && it.confirmed[TheatreFeature.SOUND_FIELD.key] == "off" &&
                it.confirmed[TheatreFeature.BASS.key] == "10" && it.confirmed[TheatreFeature.INPUT.key] == "hdmi1"
        }
        assertEquals("the level is clamped to the bar's range", "10", bar.values[TheatreFeature.BASS.key])
        bar.change(TheatreFeature.INPUT.key, "tv")
        client.state.await("the bar's own remote") { it.confirmed[TheatreFeature.INPUT.key] == "tv" }
    }

    @Test
    fun `mute toggles from what the bar reported`() = runBlocking<Unit> {
        val client = connected()
        val target = client.state.value.toggleTarget(TheatreFeature.MUTE)
        assertEquals(true, target)
        client.setToggle(TheatreFeature.MUTE, target!!)
        client.state.await("muted") { it.liveOn(TheatreFeature.MUTE) == true && it.pending.isEmpty() }
        assertEquals("on", bar.values[TheatreFeature.MUTE.key])
    }

    @Test
    fun `bluetooth pairing switches to bluetooth, then mode Off and RX`() = runBlocking<Unit> {
        val client = connected()
        val before = bar.lines.size
        client.startBluetoothPairing()
        assertTrue(eventually { bar.values["bluetooth.mode"] == "RX" })
        val sets = bar.lines.drop(before).filter { it.contains("\"set\"") }
        assertEquals(3, sets.size)
        assertTrue(sets[0], sets[0].contains("main.input") && sets[0].contains("bluetooth"))
        assertTrue(sets[1], sets[1].contains("bluetooth.mode") && sets[1].contains("\"Off\""))
        assertTrue(sets[2], sets[2].contains("bluetooth.mode") && sets[2].contains("\"RX\""))
        assertEquals("bluetooth", bar.values[TheatreFeature.INPUT.key])
    }
}
