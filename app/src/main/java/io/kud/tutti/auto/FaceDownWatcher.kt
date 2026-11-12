package io.kud.tutti.auto

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import io.kud.tutti.platform.data.VibrationLevel
import io.kud.tutti.protocol.auto.AutoPause.Cause
import io.kud.tutti.protocol.auto.AutoPause.Decision
import io.kud.tutti.protocol.auto.FaceDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "Face down to pause": listens to gravity only while the remote is in front ([start] in the
 * activity's onStart, [stop] in onStop) and the option is on. Android stops continuous sensors for
 * apps in the background, and a foreground service just for this would cost more than it gives, so
 * a phone picked up while tutti is away plays again when tutti next comes to the front, face up.
 * Gravity at the normal rate (about five samples a second), batched up to half a second.
 */
class FaceDownWatcher(private val context: Context, private val auto: AutoPlayback, private val scope: CoroutineScope) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var job: Job? = null
    private var listening = false

    fun start() {
        job?.cancel()
        job = scope.launch {
            auto.controller.state.map { it.loaded && it.prefs.faceDownPause }.distinctUntilChanged().collect { on ->
                if (on) listen() else {
                    unlisten()
                    auto.drop(Cause.FACE_DOWN)
                    auto.pose = FaceDown.State()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        unlisten()
    }

    private fun listen() {
        if (listening || sensor == null) return
        listening = sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US) == true
    }

    private fun unlisten() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val step = FaceDown.sample(auto.pose, event.values[2], SystemClock.elapsedRealtime())
        auto.pose = step.state
        val change = step.change ?: return
        scope.launch {
            val decision = auto.onFaceDown(change == FaceDown.Change.WENT_DOWN)
            // The screen is on the table: a short buzz says it was heard, the double one that it played.
            if (decision !is Decision.Nothing) buzz(decision is Decision.Resume)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun buzz(double: Boolean) {
        if (auto.controller.state.value.prefs.vibrationLevel == VibrationLevel.OFF) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createPredefined(if (double) VibrationEffect.EFFECT_DOUBLE_CLICK else VibrationEffect.EFFECT_HEAVY_CLICK))
    }

    private companion object {
        const val BATCH_US = 500_000
    }
}
