package io.kud.tutti.protocol.privacy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreeServicesTest {
    @Test fun `a feature runs only when its own switch is on and the master is off`() {
        assertTrue(FreeServices.allows(onlyFree = false, optedIn = true))
        assertFalse(FreeServices.allows(onlyFree = false, optedIn = false))
        assertFalse(FreeServices.allows(onlyFree = true, optedIn = true))
        assertFalse(FreeServices.allows(onlyFree = true, optedIn = false))
    }

    @Test fun `an offer shows once, never under the master, never once declined or on`() {
        assertTrue(FreeServices.offers(onlyFree = false, optedIn = false, declined = false))
        assertFalse(FreeServices.offers(onlyFree = true, optedIn = false, declined = false))
        assertFalse(FreeServices.offers(onlyFree = false, optedIn = true, declined = false))
        assertFalse(FreeServices.offers(onlyFree = false, optedIn = false, declined = true))
    }

    @Test fun `every feature says plainly what it sends`() {
        for (feature in NonFreeFeature.entries) assertTrue(feature.sends.length > 10, feature.name)
    }
}
