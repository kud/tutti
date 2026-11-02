package io.kud.tutti.protocol.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The settings sync file (`tutti-sync.json`), one document the person chose through the system's
 * file picker (docs/adr/0001-settings-sync.md). The app reads it, merges, and writes it back; the
 * storage app (Google Drive or any DocumentsProvider) does the uploading.
 *
 * The file is a set of named groups. Each group is one block of settings that travels whole, with
 * the wall-clock time it last changed on the phone that changed it. Groups this version doesn't
 * know are carried through untouched, so an older phone never strips what a newer one wrote.
 */
@Serializable
data class SyncFile(
    val format: String = FORMAT,
    val schema: Int = SCHEMA,
    /** The app version that last wrote the file, for a person reading it; never read back. */
    val writtenBy: String? = null,
    val groups: Map<String, SyncGroup> = emptyMap(),
) {
    companion object {
        const val FORMAT = "tutti-sync"
        const val SCHEMA = 1
        const val NAME = "tutti-sync.json"
        const val MIME = "application/json"

        /** Far above any real settings set; a bigger file is not ours. */
        const val MAX_CHARS = 2 * 1024 * 1024
    }
}

/** One group: its value as JSON, and when it last changed (epoch ms; 0 = never edited on that phone). */
@Serializable
data class SyncGroup(
    @SerialName("at") val updatedAt: Long,
    val data: JsonElement,
)

/** What reading a sync file gave. */
sealed interface SyncRead {
    data class Ok(val file: SyncFile) : SyncRead

    /** Blank: a file the picker just created, nothing in it yet. */
    data object Empty : SyncRead

    data object NotOurs : SyncRead

    /** Written by a newer tutti whose schema this one can't read; nothing is merged or written. */
    data class Newer(val schema: Int) : SyncRead
}

object SyncCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(file: SyncFile): String = json.encodeToString(SyncFile.serializer(), file)

    fun decode(text: String): SyncRead {
        if (text.isBlank()) return SyncRead.Empty
        if (text.length > SyncFile.MAX_CHARS) return SyncRead.NotOurs
        val file = runCatching { json.decodeFromString(SyncFile.serializer(), text) }.getOrNull() ?: return SyncRead.NotOurs
        return when {
            file.format != SyncFile.FORMAT -> SyncRead.NotOurs
            file.schema > SyncFile.SCHEMA -> SyncRead.Newer(file.schema)
            file.schema < 1 -> SyncRead.NotOurs
            else -> SyncRead.Ok(file)
        }
    }
}

/**
 * Two phones, one file: per group, the later change wins, and a group only one side has is kept.
 * Ties are broken on the values (the larger JSON text), never on which side is local, so two
 * phones merging the same pair write the same file.
 */
object SyncMerge {
    fun merge(local: SyncFile, remote: SyncFile, writtenBy: String?): SyncFile {
        val keys = (local.groups.keys + remote.groups.keys).sorted()
        val groups = keys.associateWith { key ->
            val mine = local.groups[key]
            val theirs = remote.groups[key]
            when {
                mine == null -> theirs!!
                theirs == null -> mine
                else -> later(mine, theirs)
            }
        }
        return SyncFile(writtenBy = writtenBy, groups = groups)
    }

    private fun later(a: SyncGroup, b: SyncGroup): SyncGroup = when {
        a.updatedAt > b.updatedAt -> a
        b.updatedAt > a.updatedAt -> b
        a.data.toString() >= b.data.toString() -> a
        else -> b
    }

    /** The groups of [merged] whose value differs from [local]'s: what this phone takes in. */
    fun incoming(local: SyncFile, merged: SyncFile): Map<String, SyncGroup> =
        merged.groups.filter { (key, group) -> local.groups[key]?.data != group.data }

    /** Same groups, values and times: the file needn't be written again. */
    fun sameContent(a: SyncFile, b: SyncFile): Boolean = a.groups == b.groups
}
