package io.kud.tutti.platform.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Android lets a socket leave for the living room, asked before the first one opens.
 * [LocalNetworkRules] is pure: the Connections sheet and the tests judge on the same words the
 * device code does ([LocalNetworkPermission]).
 */
enum class LocalNetworkAccess { ALLOWED, NEEDS_REQUEST, PERMANENTLY_DENIED }

object LocalNetworkRules {

    /** The first Android that refuses LAN traffic without the local-network permission (Android 17). */
    const val SINCE_SDK = 37

    /**
     * What the app may do about LAN right now. Below [SINCE_SDK] nothing is asked and no socket
     * is gated. Granted: allowed. Not granted: ask, unless the system has already said it never
     * will show another dialog for this app ([outlook]).
     */
    fun decide(sdkInt: Int, granted: Boolean, outlook: Outlook = Outlook.UNKNOWN): LocalNetworkAccess = when {
        sdkInt < SINCE_SDK -> LocalNetworkAccess.ALLOWED
        granted -> LocalNetworkAccess.ALLOWED
        outlook == Outlook.CANNOT_ASK -> LocalNetworkAccess.PERMANENTLY_DENIED
        else -> LocalNetworkAccess.NEEDS_REQUEST
    }

    /**
     * What the next system dialog could be, from the evidence on hand: [denied] marks the answer
     * that leaves, and after a refusal the system's own "can we explain again?" state
     * (`shouldShowRequestPermissionRationale`). Any refusal with a rationale means it can still be
     * asked; one without (first ask excepted, which the caller has not reached if it got a
     * callback) means "never ask again": only the system's app page helps. [denied] false speaks
     * of nothing — the caller is granted and the future is nobody's to predict — so [Outlook.UNKNOWN],
     * which [decide] already knows not to over-read ([NEEDS_REQUEST], the ask rides on its own flow).
     */
    fun outlook(denied: Boolean, shouldShowRationale: Boolean): Outlook = when {
        !denied -> Outlook.UNKNOWN
        shouldShowRationale -> Outlook.CAN_ASK
        else -> Outlook.CANNOT_ASK
    }

    enum class Outlook { UNKNOWN, CAN_ASK, CANNOT_ASK }
}

/** The typed failure every LAN gate raises instead of a socket that times out in silence. */
class LocalNetworkPermissionMissing : IOException("local network permission missing")

/**
 * The Android 17 (API 37) gate: reads the manifest permission, nothing more.
 * Every connector, transport, mDNS browse and Wake-on-LAN send calls [require] (or [missing])
 * before touching the LAN, so a missing permission is a typed error on screen, not a timeout.
 */
@Singleton
class LocalNetworkPermission @Inject constructor(@ApplicationContext private val context: Context) {

    /** True when Android's own answer says the permission is granted. */
    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    /** True when this Android gates LAN sockets and tutti is not granted the permission. */
    fun missing(): Boolean = LocalNetworkRules.decide(Build.VERSION.SDK_INT, granted()) != LocalNetworkAccess.ALLOWED

    /** One check at the top of a connect: throws, never silently misreads a dead device. */
    fun require() {
        if (missing()) throw LocalNetworkPermissionMissing()
    }
}
