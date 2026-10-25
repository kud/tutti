package io.kud.tutti.platform.diagnostic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.platform.data.PhoneIconBitmaps
import io.kud.tutti.platform.discovery.DiscoveredTv
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.platform.security.ClientIdentityStore
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.atv.ClientDeviceInfo
import io.kud.tutti.protocol.diagnostic.DiagnosticReport
import io.kud.tutti.protocol.diagnostic.DiagnosticRunner
import io.kud.tutti.protocol.diagnostic.DiagnosticSteps
import io.kud.tutti.protocol.diagnostic.DiagnosticTargets
import io.kud.tutti.protocol.diagnostic.DiscoveryOutcome
import io.kud.tutti.protocol.diagnostic.StepResult
import io.kud.tutti.protocol.grpc.CloudPlaneState
import io.kud.tutti.protocol.net.Redact
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnector
import io.kud.tutti.ui.common.brandMark
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class DiagnosticUi(
    val steps: List<StepResult> = DiagnosticSteps.initial(),
    val running: Boolean = false,
    /** Plain text, already scrubbed; null until a run has started. */
    val report: String? = null,
)

/**
 * Runs [DiagnosticRunner] against the configured devices with fresh, read-only connections
 * of its own, beside the controller's live sessions. Survives leaving the screen; [cancel]
 * or a new [start] stops it.
 */
@Singleton
class Diagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TuttiController,
    private val secure: SecureStore,
    private val identities: ClientIdentityStore,
    private val tls: TlsConnector,
    private val tcp: TcpConnector,
    private val discovery: TvDiscovery,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(DiagnosticUi())
    val state: StateFlow<DiagnosticUi> = mutableState.asStateFlow()

    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            val ui = controller.state.value
            val tv = ui.devices.tv
            val bar = ui.devices.bar
            val psk = secure.tvPsk()
            val restPin = secure.tvRestPin()
            val targets = DiagnosticTargets(
                tvHost = tv?.host,
                barHost = bar?.host,
                psk = psk,
                remotePin = secure.tvRemotePin(),
                restPinRecorded = restPin != null,
            )
            // Pins learned here are not saved: the diagnostic never changes what the app trusts.
            val transport = tv?.let { OkHttpBraviaTransport(it.host, restPin) {} }
            val runner = DiagnosticRunner(
                targets = targets,
                bravia = transport,
                tls = tls,
                tcp = tcp,
                identity = { identities.existing() },
                discover = { discover(tv?.host) },
                cloudNote = cloudNote(ui.cloud),
                deviceInfo = ClientDeviceInfo(model = "tutti", packageName = context.packageName),
                // Keeps the scrubbed getPlayingContentInfo reply, to copy while an app plays.
                developer = ui.prefs.developerMode,
                barHttp = bar?.let { io.kud.tutti.platform.net.OkHttpBarHttpTransport(it.host) },
            )
            val environment = environment(targets)
            val publisher = launch {
                runner.steps.collect { steps ->
                    mutableState.value = DiagnosticUi(
                        steps = steps,
                        running = true,
                        report = DiagnosticReport.render(environment, steps, runner.sensitive),
                    )
                }
            }
            try {
                runner.run()
            } finally {
                publisher.cancel()
                val steps = runner.steps.value
                mutableState.value = DiagnosticUi(steps, running = false, report = DiagnosticReport.render(environment, steps, runner.sensitive))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        mutableState.update { it.copy(running = false) }
    }

    private suspend fun discover(configured: String?): DiscoveryOutcome {
        var last: List<DiscoveredTv> = emptyList()
        withTimeoutOrNull(DISCOVERY_MS) { discovery.discover().collect { last = it } }
        return DiscoveryOutcome(found = last.size, configuredTvSeen = configured?.let { host -> last.any { it.host == host } })
    }

    private fun cloudNote(state: CloudPlaneState): String = when (state) {
        is CloudPlaneState.Unavailable -> "unavailable: interface only, nothing on the wire"
        CloudPlaneState.Disabled -> "disabled (opt-in)"
        CloudPlaneState.SignedOut -> "signed out"
        CloudPlaneState.Connecting -> "connecting"
        is CloudPlaneState.Connected -> "connected (not probed)"
    }

    private fun environment(targets: DiagnosticTargets): List<Pair<String, String>> {
        val ui = controller.state.value
        val links = ui.links
        fun yes(value: Boolean) = if (value) "yes" else "no"
        return listOf(
            "app" to AppVersion.of(context),
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "network" to if (onWifi()) "wifi" else "not wifi",
            "configured" to "tv ${yes(targets.tvHost != null)}, bar ${yes(targets.barHost != null)}, psk ${yes(targets.psk != null)}, " +
                "remote pairing ${yes(targets.remotePin != null)}, rest pin ${yes(targets.restPinRecorded)}",
            "live session" to "remote ${links.tv}, rest ${links.rest}, bar ${links.bar}, voice zoom shown ${yes(ui.voiceZoomAvailable)}",
            // The int5 probe: what the TV sends beside its field's text. The label goes by length only.
            "tv text field" to (ui.remote.imeProbe?.let { "int5 ${it.int5}, label ${Redact.secret(it.label)}" } ?: "none seen"),
            // Feature names only, never values: the model and firmware stay out of the report.
            // Routes tried, link sent, foreground package seen; already through Redact.
            "last app launch" to (controller.lastLaunchTrace ?: "none this session"),
            "bar settings" to "answered ${ui.bar.confirmed.size}, refused ${ui.bar.refused.sorted().joinToString().ifEmpty { "none" }}, " +
                "silent ${ui.bar.silent.sorted().joinToString().ifEmpty { "none" }}",
        ) + appIcons() + liveBarWire()
    }

    /**
     * What became of each TV app icon this session: a count per outcome, then one line per app
     * without one (package, outcome, the URL's scheme, port, path head and how its host relates
     * to the TV), and what its tile shows instead: the phone's own icon, the brand mark or the
     * letter. Never a host or a header, nor which phone package matched; scrubbed by the
     * controller and again by the report.
     */
    private fun appIcons(): List<Pair<String, String>> {
        val lines = controller.iconReport { pkg ->
            when {
                runCatching { PhoneIconBitmaps.has(context, pkg) }.getOrDefault(false) -> "phone"
                brandMark(pkg) != null -> "mark"
                else -> "letter"
            }
        }
        return listOf("app icons" to lines.first()) +
            lines.drop(1).mapIndexed { i, line -> "app icon %02d".format(i + 1) to line }
    }

    /**
     * The live session's last raw exchanges on TCP 33336 (`>` sent, `<` read as it arrived,
     * `.` a note), already scrubbed by the wire log and scrubbed again by the report.
     */
    private fun liveBarWire(): List<Pair<String, String>> {
        val lines = controller.barWire
        if (lines.isEmpty()) return listOf("live bar wire" to "nothing exchanged this session")
        return lines.mapIndexed { i, line -> "live bar wire %02d".format(i + 1) to line }
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private companion object {
        const val DISCOVERY_MS = 5_000L
    }
}
