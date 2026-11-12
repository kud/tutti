package io.kud.tutti.auto

import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.diagnostic.AppLog
import io.kud.tutti.protocol.auto.NightHours
import io.kud.tutti.protocol.diagnostic.EventLog
import io.kud.tutti.protocol.theatre.TheatreFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sound that follows the hour ([NightHours]): looks at the window whenever the state changes (the
 * bar connecting, a value reported) and once a minute while the process runs. No alarm and no
 * WorkManager: the window applies when tutti next talks to the bar, the way the bar's own values
 * only ever change through a link. The open window is stored on this phone (`nightSession`, never
 * synced), held here in memory as the one truth so a state that lags the store never opens it twice.
 */
@Singleton
class NightHoursRunner @Inject constructor(
    private val controller: TuttiController,
    private val store: TuttiStore,
) {
    private val started = AtomicBoolean(false)
    private val lock = Mutex()
    private var session: NightHours.Session? = null
    private var read = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val minutes = flow {
            while (true) {
                emit(Unit)
                delay(TICK_MS)
            }
        }
        controller.scope.launch {
            combine(controller.state, minutes) { state, _ -> state }.conflate().collect { look(it) }
        }
    }

    private suspend fun look(s: TuttiUiState) = lock.withLock {
        if (!s.loaded) return@withLock
        if (!read) {
            session = s.prefs.nightSession
            read = true
        }
        val bar = NightHours.Bar(
            s.bar.connected,
            NightHours.Feature.entries.associateWith { s.bar.liveOn(it.theatre) },
        )
        val window = s.prefs.nightHours
        val step = NightHours.step(session, window.enabled, Instant.now(), ZoneId.systemDefault(), window.start, window.end, bar)
        step.log.forEach { AppLog.events.record(EventLog.Kind.AUTO, it) }
        step.set.forEach { (feature, on) -> controller.setBarToggle(feature.theatre, on) }
        if (step.session != session) {
            session = step.session
            store.updatePrefs { it.copy(nightSession = step.session) }
        }
    }

    private val NightHours.Feature.theatre: TheatreFeature
        get() = when (this) {
            NightHours.Feature.NIGHT -> TheatreFeature.NIGHT
            NightHours.Feature.VOICE -> TheatreFeature.VOICE
        }

    private companion object {
        const val TICK_MS = 60_000L
    }
}
