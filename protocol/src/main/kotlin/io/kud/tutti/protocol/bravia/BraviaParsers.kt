package io.kud.tutti.protocol.bravia

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Lenient parsers over `result` arrays. Unknown fields are ignored, missing ones become null. */
object BraviaParsers {
    fun methodTypes(results: JsonArray): List<MethodInfo> = results.mapNotNull { entry ->
        val row = entry as? JsonArray ?: return@mapNotNull null
        val name = row.getOrNull(0).string() ?: return@mapNotNull null
        val version = row.getOrNull(3).string() ?: return@mapNotNull null
        MethodInfo(name, version)
    }

    /** `guide.getSupportedApiInfo`: `[[{service, apis: [{name, versions: [{version, authLevel?}]}]}]]`. Unknown services are dropped. */
    fun supportedApiInfo(result: JsonArray): Map<BraviaService, List<MethodInfo>> =
        result.innerObjects().mapNotNull { entry ->
            val service = BraviaService.entries.firstOrNull { it.id == entry.string("service") } ?: return@mapNotNull null
            val methods = (entry["apis"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { api ->
                val name = api.string("name") ?: return@flatMap emptyList()
                (api["versions"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { v ->
                    v.string("version")?.let { MethodInfo(name, it) }
                }
            }
            service to methods
        }.toMap()

    fun powerStatus(result: JsonArray): PowerStatus = when (result.firstObject()?.string("status")) {
        "active" -> PowerStatus.ACTIVE
        "standby" -> PowerStatus.STANDBY
        else -> PowerStatus.UNKNOWN
    }

    fun settings(result: JsonArray): List<TvSetting> = result.innerObjects().mapNotNull { obj ->
        val target = obj.string("target") ?: return@mapNotNull null
        TvSetting(
            target = target,
            currentValue = obj.string("currentValue"),
            isAvailable = obj.bool("isAvailable") ?: true,
            candidates = (obj["candidate"] as? JsonArray).orEmpty().mapNotNull { c ->
                val candidate = c as? JsonObject ?: return@mapNotNull null
                SettingCandidate(
                    value = candidate.string("value").orEmpty(),
                    title = candidate.string("title"),
                    min = candidate.double("min"),
                    max = candidate.double("max"),
                    step = candidate.double("step"),
                    isAvailable = candidate.bool("isAvailable") ?: true,
                )
            },
            title = obj.string("title"),
        )
    }

    fun externalInputs(result: JsonArray): List<ExternalInput> = result.innerObjects().mapNotNull { obj ->
        val uri = obj.string("uri") ?: return@mapNotNull null
        ExternalInput(
            uri = uri,
            title = obj.string("title").orEmpty(),
            label = obj.string("label").orEmpty(),
            connected = obj.bool("connection") ?: false,
            icon = obj.string("icon"),
            status = obj.string("status"),
        )
    }

    fun applications(result: JsonArray): List<TvApp> = result.innerObjects().mapNotNull { obj ->
        val uri = obj.string("uri") ?: return@mapNotNull null
        TvApp(title = obj.string("title").orEmpty(), uri = uri, icon = obj.string("icon")?.ifEmpty { null })
    }

    /** `[{bundled, type}, [{name, value}, …]]`. */
    fun remoteControllerInfo(result: JsonArray): List<IrccCode> =
        (result.getOrNull(1) as? JsonArray).orEmpty().mapNotNull { e ->
            val obj = e as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val value = obj.string("value") ?: return@mapNotNull null
            IrccCode(name, value)
        }

    fun volumeInformation(result: JsonArray): List<VolumeInfo> = result.innerObjects().mapNotNull { obj ->
        VolumeInfo(
            target = obj.string("target") ?: return@mapNotNull null,
            volume = obj.int("volume") ?: return@mapNotNull null,
            muted = obj.bool("mute") ?: false,
            min = obj.int("minVolume"),
            max = obj.int("maxVolume"),
        )
    }

    fun playingContent(result: JsonArray): PlayingContent? = result.firstObject()?.let {
        PlayingContent(
            uri = it.string("uri"),
            source = it.string("source"),
            title = it.string("title"),
            programTitle = it.string("programTitle"),
            dispNum = it.string("dispNum"),
            startDateTime = it.string("startDateTime"),
            durationSec = it.int("durationSec"),
        )
    }

    fun powerSavingMode(result: JsonArray): String? = result.firstObject()?.string("mode")

    /** `system.getWolMode`: `[{"enabled": true}]`. */
    fun wolMode(result: JsonArray): Boolean? = result.firstObject()?.bool("enabled")

    /**
     * `videoScreen.getSceneSetting`: `[{currentValue, candidate: [{value}]}]`, as a [TvSetting]
     * under [TvSetting.SCENE_TARGET]. Null when the current input offers no scene (empty candidates).
     */
    fun sceneSetting(result: JsonArray): TvSetting? {
        val obj = result.firstObject() ?: return null
        val candidates = (obj["candidate"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { c ->
            c.string("value")?.takeIf { it.isNotEmpty() }?.let { SettingCandidate(it) }
        }
        if (candidates.isEmpty()) return null
        return TvSetting(TvSetting.SCENE_TARGET, obj.string("currentValue"), isAvailable = true, candidates = candidates)
    }

    fun systemInformation(result: JsonArray): SystemInfo? = result.firstObject()?.let {
        SystemInfo(
            model = it.string("model"),
            product = it.string("product"),
            generation = it.string("generation"),
            name = it.string("name"),
            mac = it.string("macAddr"),
        )
    }

    /** `system.getSystemSupportedFunction`: `[[{option, value}, …]]`. Entries without an option are dropped. */
    fun supportedFunctions(result: JsonArray): List<SupportedFunction> = result.innerObjects().mapNotNull {
        val option = it.string("option")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SupportedFunction(option, it.string("value"))
    }

    /** `system.getNetworkSettings`: `[[{netif, hwAddr, ipAddrV4, …}, …]]`, one entry per interface. */
    fun networkSettings(result: JsonArray): List<NetworkInterfaceInfo> = result.innerObjects().map {
        NetworkInterfaceInfo(name = it.string("netif"), mac = it.string("hwAddr"), ipv4 = it.string("ipAddrV4"))
    }

    // Results come either flat `[{…}]` or nested `[[{…}, …]]`; accept both.
    private fun JsonArray.innerObjects(): List<JsonObject> {
        val first = firstOrNull()
        val items = if (first is JsonArray) first else this
        return items.filterIsInstance<JsonObject>()
    }

    private fun JsonArray.firstObject(): JsonObject? = innerObjects().firstOrNull()

    private fun JsonElement?.string(): String? {
        val p = this as? JsonPrimitive ?: return null
        return if (p is JsonNull) null else p.content
    }

    private fun JsonObject.string(key: String): String? = this[key].string()

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
