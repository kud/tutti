package io.kud.tutti.platform.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkRulesTest {

    @Test
    fun belowSinceSdkNothingIsAskedAndNothingIsGated() {
        for (sdk in listOf(21, LocalNetworkRules.SINCE_SDK - 1)) {
            assertEquals(LocalNetworkAccess.ALLOWED, LocalNetworkRules.decide(sdk, granted = false))
            assertEquals(LocalNetworkAccess.ALLOWED, LocalNetworkRules.decide(sdk, granted = false, outlook = LocalNetworkRules.Outlook.CANNOT_ASK))
        }
    }

    @Test
    fun sinceSdkTheAnswersFollowTheGrantAndTheOutlook() {
        // Granted: allowed, whatever the outlook says — the gate reads Android's own answer.
        assertEquals(
            LocalNetworkAccess.ALLOWED,
            LocalNetworkRules.decide(LocalNetworkRules.SINCE_SDK, granted = true, outlook = LocalNetworkRules.Outlook.CANNOT_ASK),
        )
        // Not granted and a dialog may come: ask.
        assertEquals(
            LocalNetworkAccess.NEEDS_REQUEST,
            LocalNetworkRules.decide(LocalNetworkRules.SINCE_SDK, granted = false, outlook = LocalNetworkRules.Outlook.CAN_ASK),
        )
        assertEquals(
            LocalNetworkAccess.NEEDS_REQUEST,
            LocalNetworkRules.decide(LocalNetworkRules.SINCE_SDK, granted = false, outlook = LocalNetworkRules.Outlook.UNKNOWN),
        )
    }

    @Test
    fun beforeAnyRefusalTheOutlookIsUnknownAndALaunchCanCome() {
        assertEquals(LocalNetworkRules.Outlook.UNKNOWN, LocalNetworkRules.outlook(denied = false, shouldShowRationale = false))
        assertEquals(LocalNetworkRules.Outlook.UNKNOWN, LocalNetworkRules.outlook(denied = false, shouldShowRationale = true))
    }

    /** A refusal with the system's "can we explain again?" state: another dialog is allowed. */
    @Test
    fun aRefusalWithRationaleCanStillBeAsked() {
        assertEquals(LocalNetworkRules.Outlook.CAN_ASK, LocalNetworkRules.outlook(denied = true, shouldShowRationale = true))
    }

    /** A refusal without a rationale (never the first ask): only the system's app page remains. */
    @Test
    fun aRefusalWithoutRationaleMeansTheSystemPage() {
        assertEquals(LocalNetworkRules.Outlook.CANNOT_ASK, LocalNetworkRules.outlook(denied = true, shouldShowRationale = false))
        assertEquals(
            LocalNetworkAccess.PERMANENTLY_DENIED,
            LocalNetworkRules.decide(LocalNetworkRules.SINCE_SDK, granted = false, outlook = LocalNetworkRules.Outlook.CANNOT_ASK),
        )
    }

    /** [LocalNetworkPermissionMissing] is an IOException: the typed failure every gate raises. */
    @Test
    fun theMissingFailureIsATypedIoException() {
        val failure = LocalNetworkPermissionMissing()
        assertTrue(failure is java.io.IOException)
        assertEquals("local network permission missing", failure.message)
        assertFalse(failure.message.isNullOrBlank())
    }
}
