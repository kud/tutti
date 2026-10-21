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
