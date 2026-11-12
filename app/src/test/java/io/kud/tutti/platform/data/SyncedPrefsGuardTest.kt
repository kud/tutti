package io.kud.tutti.platform.data

import io.kud.tutti.protocol.privacy.NonFreeFeature
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every option travels with the synced settings (Erwann, 2026-11-12), except what [SyncGroups.LOCAL_PREFS]
 * names with a reason. A new field of [AppPrefs] is synced unless someone lists it there on purpose;
 * this fails on a field that is neither synced nor listed, and on a listed name that no longer exists.
 */
class SyncedPrefsGuardTest {
    private val fields = (0 until AppPrefs.serializer().descriptor.elementsCount)
        .map { AppPrefs.serializer().descriptor.getElementName(it) }
        .toSet()

    private fun raw(prefs: AppPrefs) = SyncGroups.Raw(StoreJson.encodeToString(AppPrefs.serializer(), prefs), null, null, null)

    private fun synced(prefs: AppPrefs) = SyncGroups.read(raw(prefs))[SyncGroups.PREFERENCES] as JsonObject

    @Test
    fun `every preference is synced or kept local with a reason`() {
        assertTrue("the probe must see fields", fields.size > 10)
        val sent = synced(AppPrefs()).keys
        for (field in fields) {
            val local = SyncGroups.LOCAL_PREFS[field]
            assertTrue("$field is neither synced nor listed local", field in sent || local != null)
            if (local != null) assertTrue("$field needs a reason", local.isNotBlank())
        }
        for (name in SyncGroups.LOCAL_PREFS.keys) assertTrue("$name is not a field any more", name in fields)
    }

    @Test
    fun `the third-party switches and the master travel to the other phone`() {
        val mine = AppPrefs(
            traktLink = true,
            justWatchLink = true,
            titleDetails = true,
            titleDetailsOfferDeclined = true,
            castArtwork = true,
            onlyFreeServices = true,
        )
        val other = StoreJson.decodeFromString(
            AppPrefs.serializer(),
            SyncGroups.apply(raw(AppPrefs()), SyncGroups.read(raw(mine))).prefs!!,
        )
        assertTrue(other.traktLink && other.justWatchLink && other.titleDetails && other.titleDetailsOfferDeclined)
        assertTrue(other.castArtwork && other.onlyFreeServices)
        // And back off again: an off travels like an on.
        val back = StoreJson.decodeFromString(AppPrefs.serializer(), SyncGroups.apply(raw(other), SyncGroups.read(raw(AppPrefs()))).prefs!!)
        assertFalse(back.titleDetails || back.castArtwork || back.onlyFreeServices || back.traktLink)
    }

    @Test
    fun `a file from an older tutti keeps this phone's new switches`() {
        val mine = AppPrefs(titleDetails = true, onlyFreeServices = true)
        val older = JsonObject((synced(AppPrefs()) - setOf("titleDetails", "onlyFreeServices", "castArtwork", "titleDetailsOfferDeclined")))
        val after = StoreJson.decodeFromString(
            AppPrefs.serializer(),
            SyncGroups.apply(raw(mine), mapOf(SyncGroups.PREFERENCES to older)).prefs!!,
        )
        assertTrue(after.titleDetails && after.onlyFreeServices)
    }

    @Test
    fun `every non-free feature starts off, and the master wins`() {
        val fresh = AppPrefs()
        assertFalse(fresh.onlyFreeServices)
        for (feature in NonFreeFeature.entries) assertFalse(feature.name, fresh.allows(feature))
        val all = AppPrefs(traktLink = true, justWatchLink = true, titleDetails = true, castArtwork = true)
        for (feature in NonFreeFeature.entries) assertTrue(feature.name, all.allows(feature))
        val strict = all.copy(onlyFreeServices = true)
        for (feature in NonFreeFeature.entries) assertFalse(feature.name, strict.allows(feature))
        assertEquals(NonFreeFeature.entries.size, NonFreeFeature.entries.count { all.optedIn(it) })
    }

    @Test
    fun `prefs stored before these switches read them all off`() {
        val before = """{"theme":"DARK","traktLink":true,"eveningDim":true,"onboarded":true}"""
        val p = StoreJson.decodeFromString(AppPrefs.serializer(), before)
        assertTrue(p.traktLink && p.eveningDim && p.onboarded)
        assertEquals(ThemeMode.DARK, p.theme)
        assertFalse(p.titleDetails || p.titleDetailsOfferDeclined || p.castArtwork || p.onlyFreeServices)
    }
}
