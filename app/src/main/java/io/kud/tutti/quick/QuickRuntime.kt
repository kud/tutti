package io.kud.tutti.quick

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import io.kud.tutti.quick.notification.QuickNotifications
import io.kud.tutti.quick.widget.QuickWidget
import io.kud.tutti.quick.widget.QuickWidgetPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide glue for the quick surfaces: one scope that outlives any single
 * tile bind, widget session or overlay, and the observers that keep the widget
 * and the notification in step with [QuickControls.state].
 *
 * [start] is idempotent. The app calls it from `TuttiApplication.onCreate`, and
 * every quick surface calls it too, so a process cold-started by the widget or a
 * notification action still reconciles.
 */
object QuickRuntime {
    private const val TAG = "QuickRuntime"

    /** Main.immediate: tile and notification updates touch main-thread-only APIs. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val controls = app.quickControls() ?: return

        scope.launch { QuickNotifications.run(app, controls, QuickPreferences(app)) }

        // Android 15+: the picker's generated preview, once per version, off the main thread.
        scope.launch(Dispatchers.IO) { QuickWidgetPreview.publish(app) }

        // The widget's own session recomposes while it is alive; this covers the
        // rest of the time. Trailing debounce: a volume drag is one update, not forty.
        // A StateFlow already drops repeated values, so no distinctUntilChanged.
        scope.launch {
            controls.state
                .collectLatest {
                    delay(WIDGET_DEBOUNCE_MS)
                    QuickWidget().updateAll(app)
                }
        }
    }

    /**
     * Runs one action on the process scope, so it survives the surface that asked
     * for it (an overlay finishing, a receiver returning). Null when nothing is bound.
     */
    fun act(context: Context, block: suspend QuickControls.() -> Unit): Job? {
        val controls = context.quickControls() ?: return null
        return scope.launch {
            try {
                controls.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The controller reflects failures in state; this only keeps the scope alive.
                Log.w(TAG, "Quick action failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private const val WIDGET_DEBOUNCE_MS = 300L
}
