package io.kud.tutti.protocol.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncMergeTest {

    private fun group(at: Long, value: String) = SyncGroup(at, buildJsonObject { put("v", value) })

    private fun file(vararg groups: Pair<String, SyncGroup>) = SyncFile(groups = mapOf(*groups))

    @Test
    fun laterChangeWinsPerGroup() {
        val local = file("remote" to group(200, "phone"), "scenes" to group(100, "phone"))
        val remote = file("remote" to group(150, "drive"), "scenes" to group(300, "drive"))
        val merged = SyncMerge.merge(local, remote, "test")
        assertEquals(group(200, "phone"), merged.groups.getValue("remote"))
        assertEquals(group(300, "drive"), merged.groups.getValue("scenes"))
        assertEquals(mapOf("scenes" to group(300, "drive")), SyncMerge.incoming(local, merged))
    }

    @Test
    fun aGroupOnlyOneSideHasIsKept() {
        val merged = SyncMerge.merge(file("remote" to group(1, "a")), file("future" to group(5, "z")), null)
        assertEquals(setOf("remote", "future"), merged.groups.keys)
    }

    @Test
    fun aPhoneThatNeverEditedTakesTheFile() {
        // A second phone or a reinstall joins with its groups at 0, so the file's settings come in.
        val fresh = file("preferences" to group(0, "defaults"))
        val drive = file("preferences" to group(1_700_000_000_000, "his"))
        val merged = SyncMerge.merge(fresh, drive, null)
        assertEquals(drive.groups, merged.groups)
        assertTrue(SyncMerge.sameContent(drive, merged))
    }

    @Test
    fun tiesBreakOnValuesSoBothPhonesWriteTheSameFile() {
        val a = file("remote" to group(10, "alpha"))
        val b = file("remote" to group(10, "beta"))
        assertEquals(SyncMerge.merge(a, b, null), SyncMerge.merge(b, a, null))
    }

    @Test
    fun roundTripsAndSaysItsSchema() {
        val original = SyncFile(writtenBy = "0.2.0", groups = mapOf("remote" to group(42, "x")))
        val text = SyncCodec.encode(original)
        val read = SyncCodec.decode(text)
        assertIs<SyncRead.Ok>(read)
        assertEquals(original, read.file)
        assertTrue(text.contains("\"schema\": 1"))
        assertTrue(text.contains("\"format\": \"tutti-sync\""))
    }

    @Test
    fun refusesWhatIsNotOursOrNewer() {
        assertEquals(SyncRead.Empty, SyncCodec.decode("  "))
        assertEquals(SyncRead.NotOurs, SyncCodec.decode("{\"format\":\"tutti-layout\",\"schema\":1}"))
        assertEquals(SyncRead.NotOurs, SyncCodec.decode("not json"))
        assertEquals(SyncRead.Newer(2), SyncCodec.decode("{\"format\":\"tutti-sync\",\"schema\":2,\"groups\":{}}"))
    }

    /** The version-1 file as written today, pinned: a later tutti must still read it. */
    @Test
    fun readsAPinnedVersionOneFileWithUnknownFields() {
        val text = """{"format":"tutti-sync","schema":1,"extra":true,"groups":{"x":{"at":3,"data":{"v":"1"},"note":"new"}}}"""
        val read = SyncCodec.decode(text)
        assertIs<SyncRead.Ok>(read)
        assertEquals(group(3, "1"), read.file.groups["x"])
    }
}
