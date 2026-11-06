package io.kud.tutti.ui.settings

import io.kud.tutti.ui.common.Status
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TvShown
import io.kud.tutti.platform.sync.SyncProblem
import io.kud.tutti.platform.sync.SyncState
import io.kud.tutti.ui.remote.ConnInput
import io.kud.tutti.ui.remote.ConnLink
import io.kud.tutti.ui.remote.ConnWords
import io.kud.tutti.ui.remote.connections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings' status tiles: what each says, which need him, and their order (Erwann, 2026-11-06). */
class SettingsDashboardTest {
    private val now = 1_000_000_000L
    private val room = ConnInput(
        tvName = "Living TV",
        tvShown = TvShown(Link.ONLINE),
        ipSet = true,
        rest = Link.ONLINE,
        barName = "Bar",
        bar = Link.ONLINE,
    )
    private val allSet = UnlockSummary(done = 4, total = 4, next = null)

    private fun tiles(conn: ConnInput = room, sync: SyncState = SyncState(), unlocks: UnlockSummary = allSet) =
        dashboard(DashInput(connections(conn), sync, unlocks, now))

    private fun List<DashItem>.of(tile: DashTile) = first { it.tile == tile }

    @Test
    fun `all well, four calm tiles in their fixed order`() {
        val items = tiles()
        assertEquals(listOf(DashTile.TV, DashTile.SOUNDBAR, DashTile.SYNC, DashTile.UNLOCKS), items.map { it.tile })
        assertTrue(items.none { it.needsYou })
        assertEquals(DashWords.Device(ConnWords.CONNECTED, null), items.of(DashTile.TV).words)
        assertEquals(Status.OK, items.of(DashTile.SOUNDBAR).mark)
    }

    @Test
    fun `ip control needing pairing again goes first, named, with its action`() {
        val items = tiles(room.copy(rest = Link.NEEDS_PAIRING))
        val tv = items.first()
        assertEquals(DashTile.TV, tv.tile)
        assertTrue(tv.needsYou)
        assertEquals(DashWords.Device(ConnWords.NEEDS_PAIRING, ConnLink.SONY_IP), tv.words)
        assertEquals(DashAction.PAIR_TV, tv.action)
        assertEquals(Status.NEEDS_YOU, tv.mark)
    }

    @Test
    fun `a code to renew asks to set up ip control`() {
        val tv = tiles(room.copy(ipRenew = true)).of(DashTile.TV)
        assertTrue(tv.needsYou)
        assertEquals(DashAction.SET_UP_IP, tv.action)
    }

    @Test
    fun `a tv on standby or out of reach is no fault and offers nothing`() {
        for (conn in listOf(room.copy(tvAsleep = true), room.copy(tvShown = TvShown(Link.UNREACHABLE), rest = Link.UNREACHABLE, tvWakeable = true))) {
            val tv = tiles(conn).of(DashTile.TV)
            assertFalse(tv.needsYou)
            assertNull(tv.action)
            assertEquals(null, (tv.words as DashWords.Device).link)
        }
    }

    @Test
    fun `ip control not set up yet is progress, not a tv problem`() {
        val tv = tiles(room.copy(ipSet = false, rest = Link.ABSENT)).of(DashTile.TV)
        assertFalse(tv.needsYou)
        assertEquals(DashWords.Device(ConnWords.CONNECTED, null), tv.words)
    }

    @Test
    fun `no tv asks to pair one first`() {
        val tv = tiles(ConnInput()).first()
        assertEquals(DashTile.TV, tv.tile)
        assertTrue(tv.needsYou)
        assertEquals(DashAction.PAIR_TV, tv.action)
    }

    @Test
    fun `no bar is a calm offer, through devices while there is no tv`() {
        val bar = tiles(room.copy(barName = null, bar = Link.ABSENT)).of(DashTile.SOUNDBAR)
        assertFalse(bar.needsYou)
        assertEquals(DashWords.Device(ConnWords.NOT_ADDED, null), bar.words)
        assertEquals(DashAction.ADD_BAR, bar.action)
        assertEquals(DashAction.OPEN_DEVICES, tiles(ConnInput()).of(DashTile.SOUNDBAR).action)
    }

    @Test
    fun `a bar out of reach says so without asking anything`() {
        val bar = tiles(room.copy(bar = Link.UNREACHABLE)).of(DashTile.SOUNDBAR)
        assertFalse(bar.needsYou)
        assertEquals(Status.NEEDS_YOU, bar.mark)
        assertEquals(DashWords.Device(ConnWords.UNREACHABLE, null), bar.words)
    }

    @Test
    fun `a bar at a new address needs him`() {
        val bar = tiles(room.copy(bar = Link.UNREACHABLE, barOffer = true)).of(DashTile.SOUNDBAR)
        assertTrue(bar.needsYou)
        assertEquals(DashAction.OPEN_DEVICES, bar.action)
    }

    @Test
    fun `sync off, on with its age, paused, waiting and in trouble`() {
        assertEquals(DashWords.Sync(SyncWords.OFF, null), tiles().of(DashTile.SYNC).words)
        val on = SyncState(hasFile = true, auto = true, lastSyncedAt = now - 5 * 60_000)
        assertEquals(DashWords.Sync(SyncWords.ON, SyncAge.Minutes(5)), tiles(sync = on).of(DashTile.SYNC).words)
        assertEquals(SyncWords.PAUSED, (tiles(sync = on.copy(auto = false)).of(DashTile.SYNC).words as DashWords.Sync).words)
        val waiting = tiles(sync = on.copy(problem = SyncProblem.WAITING)).of(DashTile.SYNC)
        assertFalse(waiting.needsYou)
        assertEquals(Status.WORKING, waiting.mark)
        val broken = tiles(sync = on.copy(problem = SyncProblem.UNREACHABLE))
        assertEquals(DashTile.SYNC, broken.first().tile)
        assertEquals(DashAction.CHECK_SYNC, broken.first().action)
    }

    @Test
    fun `two problems keep the fixed order between them`() {
        val items = tiles(room.copy(rest = Link.NEEDS_PAIRING), sync = SyncState(hasFile = true, problem = SyncProblem.NOT_OURS))
        assertEquals(listOf(DashTile.TV, DashTile.SYNC, DashTile.SOUNDBAR, DashTile.UNLOCKS), items.map { it.tile })
    }

    @Test
    fun `what tutti can do is progress, never a problem`() {
        val item = tiles(unlocks = UnlockSummary(1, 4, Unlock.IP_CONTROL)).of(DashTile.UNLOCKS)
        assertFalse(item.needsYou)
        assertEquals(DashWords.Progress(1, 4, Unlock.IP_CONTROL), item.words)
        assertEquals(Status.NOT_YET, item.mark)
        assertEquals(Status.OK, tiles().of(DashTile.UNLOCKS).mark)
    }

    @Test
    fun `sync age never says zero minutes`() {
        assertEquals(SyncAge.JustNow, syncAge(now - 20_000, now))
        assertEquals(SyncAge.JustNow, syncAge(now + 5_000, now))
        assertEquals(SyncAge.Minutes(1), syncAge(now - 60_000, now))
        assertEquals(SyncAge.Hours(2), syncAge(now - 2 * 3_600_000, now))
        assertEquals(SyncAge.Days(3), syncAge(now - 3 * 86_400_000L, now))
    }

    @Test
    fun `rows pair the calm tiles and let an odd one out span the width`() {
        val calm = tiles().let(::dashRows)
        assertEquals(listOf(2, 2), calm.map { it.size })

        val one = dashRows(tiles(room.copy(rest = Link.NEEDS_PAIRING)))
        assertEquals(listOf(listOf(DashTile.TV), listOf(DashTile.SOUNDBAR, DashTile.SYNC), listOf(DashTile.UNLOCKS)), one.map { row -> row.map { it.tile } })

        val two = dashRows(tiles(room.copy(rest = Link.NEEDS_PAIRING), sync = SyncState(hasFile = true, problem = SyncProblem.UNREACHABLE)))
        assertEquals(listOf(1, 1, 2), two.map { it.size })
    }
}
