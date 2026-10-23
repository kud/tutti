package io.kud.tutti.protocol.bravia

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BraviaParsersTest {
    private fun array(json: String): JsonArray = Json.parseToJsonElement(json).jsonArray

    // Invented fixtures, shaped after Sony's reference and Home Assistant's braviatv reads.
    @Test
    fun `playing content reads the tuner fields when present`() {
        val playing = BraviaParsers.playingContent(
            array(
                """[{"uri":"tv:dvbt?trip=1.1.1&srvName=Channel Nine","source":"tv:dvbt","title":"Channel Nine",""" +
                    """"programTitle":"The Garden Hour","dispNum":"009","startDateTime":"2026-01-02T20:00:00+0100",""" +
                    """"durationSec":3600,"programMediaType":"tv"}]""",
            ),
        )!!
        assertTrue(playing.isTuner)
        assertFalse(playing.isExternalInput)
        assertEquals("Channel Nine", playing.title)
        assertEquals("The Garden Hour", playing.programTitle)
        assertEquals("009", playing.dispNum)
        assertEquals("2026-01-02T20:00:00+0100", playing.startDateTime)
        assertEquals(3600, playing.durationSec)
    }

    @Test
    fun `playing content on an input leaves the tuner fields empty`() {
        val playing = BraviaParsers.playingContent(
            array("""[{"uri":"extInput:hdmi?port=2","source":"extInput:hdmi","title":"HDMI 2"}]"""),
        )!!
        assertTrue(playing.isExternalInput)
        assertFalse(playing.isTuner)
        assertEquals("HDMI 2", playing.title)
        assertEquals(null, playing.programTitle)
        assertEquals(null, playing.dispNum)
        assertEquals(null, playing.durationSec)
    }

    @Test
    fun `playing content with nothing playing is null`() {
        assertEquals(null, BraviaParsers.playingContent(array("[]")))
        assertEquals(null, BraviaParsers.playingContent(array("[[]]")))
    }

    @Test
    fun `method types keep name and version`() {
        val methods = BraviaParsers.methodTypes(
            array("[[\"getPowerStatus\",[],[\"{\\\"status\\\":\\\"string\\\"}\"],\"1.0\"],[\"getSoundSettings\",[],[],\"1.1\"],\"junk\"]"),
        )
        assertEquals(listOf(MethodInfo("getPowerStatus", "1.0"), MethodInfo("getSoundSettings", "1.1")), methods)
    }

    @Test
    fun `picture settings with range and enum candidates`() {
        val settings = BraviaParsers.settings(
            array(
                """[[{"target":"brightness","currentValue":"20","isAvailable":true,"candidate":[{"value":"","max":50,"min":0,"step":1}]},
                    {"target":"pictureMode","currentValue":"cinema","isAvailable":true,"candidate":[{"value":"vivid"},{"value":"cinema"}]},
                    {"target":"colorTemperature","currentValue":"expert1","isAvailable":false,"candidate":[]}]]""",
            ),
        )
        assertEquals(3, settings.size)
        assertTrue(settings[0].isRange)
        assertEquals(50.0, settings[0].candidates[0].max)
        assertFalse(settings[1].isRange)
        assertEquals(listOf("vivid", "cinema"), settings[1].candidates.map { it.value })
        assertFalse(settings[2].isAvailable)
    }

    @Test
    fun `external inputs and apps`() {
        val inputs = BraviaParsers.externalInputs(
            array("""[[{"uri":"extInput:hdmi?port=1","title":"HDMI 1","connection":true,"label":"Console","icon":"meta:hdmi","status":"true"},{"title":"no uri"}]]"""),
        )
        assertEquals(listOf(ExternalInput("extInput:hdmi?port=1", "HDMI 1", "Console", true, "meta:hdmi", "true")), inputs)

        val apps = BraviaParsers.applications(array("""[[{"title":"Netflix","uri":"com.sony.dtv.com.netflix.ninja","icon":""}]]"""))
        assertEquals(listOf(TvApp("Netflix", "com.sony.dtv.com.netflix.ninja", null)), apps)
    }

    @Test
    fun `system information and network settings carry the macs`() {
        val system = BraviaParsers.systemInformation(
            array("""[{"product":"TV","model":"K-00XR00","name":"BRAVIA","generation":"5.0.0","macAddr":"02:00:00:00:00:01"}]"""),
        )
        assertEquals("02:00:00:00:00:01", system?.mac)
        val interfaces = BraviaParsers.networkSettings(
            array(
                """[[{"netif":"eth0","hwAddr":"02:00:00:00:00:01","ipAddrV4":""},""" +
                    """{"netif":"wlan0","hwAddr":"02:00:00:00:00:02","ipAddrV4":"192.168.1.20"}]]""",
            ),
        )
        assertEquals(
            listOf(
                NetworkInterfaceInfo("eth0", "02:00:00:00:00:01", ""),
                NetworkInterfaceInfo("wlan0", "02:00:00:00:00:02", "192.168.1.20"),
            ),
            interfaces,
        )
    }

    @Test
    fun `remote controller info and power`() {
        val codes = BraviaParsers.remoteControllerInfo(
            array("""[{"bundled":true,"type":"RM-J1100"},[{"name":"PowerOff","value":"AAAAAQAAAAEAAAAvAw=="},{"name":"Input","value":"AAAAAQAAAAEAAAAlAw=="}]]"""),
        )
        assertEquals(IrccCode("PowerOff", "AAAAAQAAAAEAAAAvAw=="), codes.first())
        assertEquals(PowerStatus.STANDBY, BraviaParsers.powerStatus(array("""[{"status":"standby"}]""")))
        assertEquals(PowerStatus.UNKNOWN, BraviaParsers.powerStatus(array("[]")))
    }

    @Test
    fun `volume information`() {
        val info = BraviaParsers.volumeInformation(
            array("""[[{"target":"speaker","volume":12,"mute":false,"maxVolume":100,"minVolume":0},{"target":"headphone","volume":"x"}]]"""),
        )
        assertEquals(listOf(VolumeInfo("speaker", 12, false, 0, 100)), info)
    }
}
