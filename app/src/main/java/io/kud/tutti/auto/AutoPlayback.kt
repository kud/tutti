package io.kud.tutti.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.diagnostic.AppLog
import io.kud.tutti.protocol.auto.AutoPause
import io.kud.tutti.protocol.auto.AutoPause.Cause
import io.kud.tutti.protocol.auto.AutoPause.Decision
import io.kud.tutti.protocol.auto.FaceDown
import io.kud.tutti.protocol.cast.CastPlayerState
import io.kud.tutti.protocol.diagnostic.EventLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings › Automatic, the two pauses (Erwann, 2026-11-12): a call ([onCall], from
 * [CallStateReceiver]) and the phone face down ([onFaceDown], from [FaceDownWatcher]). One ledger
 * for both ([AutoPause]), kept in its own small file on this phone so a call outlives the process
 * that heard it ring: Android may well stop that process before the call ends.
 */
@Singleton
class AutoPlayback @Inject constructor(
    @ApplicationContext private val context: Context,
    val controller: TuttiController,
) {
    private val lock = Mutex()
    private val file = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private var ledger: AutoPause.State = runCatching {
        file.getString(KEY, null)?.let { json.decodeFromString(AutoPause.State.serializer(), it) }
    }.getOrNull() ?: AutoPause.State()

    /** The face-down detector's state: kept here so it outlives the activity that feeds it. */
    @Volatile var pose: FaceDown.State = FaceDown.State()

    /**
     * A call rang or went off-hook ([active]), or hung up. Holds the sessions while it decides, so a
     * process woken only for the broadcast connects first (bounded by [LINK_MS]).
     */
    suspend fun onCall(active: Boolean) {
        val loaded = withTimeoutOrNull(LINK_MS) { controller.state.first { it.loaded } } ?: return
        if (!loaded.prefs.pauseForCalls || !callPermission(context)) {
            if (Cause.CALL in ledger.causes) lock.withLock { save(AutoPause.drop(ledger, Cause.CALL)) }
            return
        }
        // Nothing paused for a call and nothing ringing: no reason to wake the TV's link.
        if (!active && Cause.CALL !in ledger.causes) return
        held { if (active) begin(Cause.CALL) else end(Cause.CALL) }
    }

    /** The phone went face down ([down]) or came up again; the remote is in front, so the link is live. */
    suspend fun onFaceDown(down: Boolean): Decision =
        if (down) begin(Cause.FACE_DOWN) else end(Cause.FACE_DOWN)

    /** An option turned off: its reason goes, and nothing plays. */
    suspend fun drop(cause: Cause) = lock.withLock { if (cause in ledger.causes) save(AutoPause.drop(ledger, cause)) }

    private suspend fun begin(cause: Cause): Decision = lock.withLock {
        val step = AutoPause.begin(ledger, cause, snapshot(controller.state.value), SystemClock.elapsedRealtime())
        save(step.state)
        act(step.decision)
        step.decision
    }

    private suspend fun end(cause: Cause): Decision = lock.withLock {
        val step = AutoPause.end(ledger, cause, snapshot(controller.state.value), SystemClock.elapsedRealtime())
        save(step.state)
        act(step.decision)
        step.decision
    }

    private fun act(decision: Decision) {
        when (decision) {
            is Decision.Pause -> {
                AppLog.events.record(EventLog.Kind.AUTO, decision.why)
                controller.key(AutoPause.KEY_PAUSE)
            }
            is Decision.Resume -> {
                AppLog.events.record(EventLog.Kind.AUTO, decision.why)
                val s = controller.state.value
                // The bar starts low before the TV plays, then climbs back to where it was.
                val level = s.volume?.takeIf { s.bar.connected }
                if (level != null) {
                    controller.scope.launch {
                        val whole = controller.fadeBarBackTo(level)
                        AppLog.events.record(EventLog.Kind.AUTO, if (whole) "bar faded back in" else "bar fade stopped: volume moved or bar down")
                    }
                }
                controller.key(AutoPause.KEY_PLAY)
            }
            is Decision.Nothing -> AppLog.events.record(EventLog.Kind.AUTO, decision.why)
        }
    }

    private suspend fun held(action: suspend () -> Unit) {
        val epoch = controller.retainSession()
        try {
            withTimeoutOrNull(LINK_MS) {
                controller.state.first { s ->
                    s.links.epoch == epoch && s.links.tv == Link.ONLINE && s.remote.currentApp != null &&
                        (s.devices.bar == null || s.bar.connected)
                }
            }
            action()
        } finally {
            controller.release()
        }
    }

    private fun snapshot(s: TuttiUiState): AutoPause.Snapshot {
        val cast = controller.castNowPlaying.value
        return AutoPause.Snapshot(
            tvOn = s.links.tv == Link.ONLINE && s.remote.powered != false,
            app = s.remote.currentApp,
            castPlaying = cast?.state?.let { it == CastPlayerState.PLAYING || it == CastPlayerState.BUFFERING },
            // Compared for equality only, in memory and in the private ledger; never logged.
            castItem = cast?.let { (it.title + "\u0000" + it.secondary.orEmpty()).hashCode().toString(16) },
        )
    }

    private fun save(state: AutoPause.State) {
        ledger = state
        file.edit().putString(KEY, json.encodeToString(AutoPause.State.serializer(), state)).apply()
    }

    companion object {
        private const val FILE = "auto_pause"
        private const val KEY = "ledger"

        /** How long a woken process waits for the TV (and the bar) before deciding on what it has. */
        const val LINK_MS = 6_000L

        private val json = Json { ignoreUnknownKeys = true }

        fun callPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoEntryPoint {
    fun autoPlayback(): AutoPlayback

    fun nightHours(): NightHoursRunner
}

internal fun Context.autoEntry(): AutoEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, AutoEntryPoint::class.java)
