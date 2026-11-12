package io.kud.tutti.platform.data

import io.kud.tutti.protocol.layout.LayoutCodec
import io.kud.tutti.protocol.layout.LayoutRead
import io.kud.tutti.protocol.sync.SyncCodec
import io.kud.tutti.protocol.sync.SyncFile
import io.kud.tutti.protocol.sync.SyncGroup
import io.kud.tutti.protocol.sync.SyncRead
import kotlinx.serialization.json.JsonElement

/**
 * Settings › Backup (2026-11-12): every setting that travels, out to a file he chooses and back in.
 * It is settings sync's own file, written once: the groups [SyncGroups] reads (preferences that
 * sync, the layout and dock, scenes, app profiles, app destinations), never what stays on this phone
 * ([SyncGroups.LOCAL_PREFS]), and never devices, hosts, device ids, pairing material or tokens,
 * which [SyncGroups.Raw] has no way to hold. Coming back in, it goes through [SyncGroups.apply], so
 * a refused link is dropped exactly as sync drops it and a group that doesn't decode keeps the
 * stored value. A sync file is a backup too: the format is the same.
 */
internal object SettingsBackup {

    fun export(raw: SyncGroups.Raw, writtenBy: String?, at: Long): String = SyncCodec.encode(
        SyncFile(writtenBy = writtenBy, groups = SyncGroups.read(raw).mapValues { (_, data) -> SyncGroup(at, data) }),
    )

    sealed interface Read {
        /** The groups to hand to [SyncGroups.apply]. */
        data class Ok(val groups: Map<String, JsonElement>) : Read

        /** A layout file from Export layout: it belongs to Import layout, which still reads it. */
        data object LayoutFile : Read

        data class Newer(val schema: Int) : Read

        data object NotOurs : Read
    }

    fun read(text: String): Read = when (val sync = SyncCodec.decode(text)) {
        is SyncRead.Ok -> if (sync.file.groups.isEmpty()) Read.NotOurs else Read.Ok(sync.file.groups.mapValues { it.value.data })
        is SyncRead.Newer -> Read.Newer(sync.schema)
        SyncRead.Empty, SyncRead.NotOurs -> if (LayoutCodec.decode(text) is LayoutRead.Ok) Read.LayoutFile else Read.NotOurs
    }
}
