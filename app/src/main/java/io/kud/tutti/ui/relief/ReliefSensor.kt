package io.kud.tutti.ui.relief

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.kud.tutti.ui.common.LocalReducedMotion
import kotlinx.coroutines.awaitCancellation

/**
 * The remote's relief scene while [enabled], else null (and nothing changes on screen).
 *
 * The eye follows the game rotation vector (gyroscope + accelerometer; no magnetometer, so no
 * compass jitter), sampled at about 30 Hz only while the caller is composed and the activity is
 * started: leaving the remote, or the app going to the background, unregisters it. Each start
 * captures a fresh resting pose. Reduced motion never registers the sensor: the slabs stay at
 * the resting pose. This sensor needs no permission (only rates above 200 Hz would).
 */
@Composable
fun rememberReliefScene(enabled: Boolean): ReliefScene? {
    if (!enabled) return null
    val scene = remember { ReliefScene() }
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(scene, reduced, lifecycle, context) {
        scene.rest()
        if (reduced) return@LaunchedEffect
        val manager = context.getSystemService(SensorManager::class.java) ?: return@LaunchedEffect
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val tracker = HeadTracker()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    tracker.update(event.values, event.timestamp)
                    scene.eyeX = tracker.eyeX
                    scene.eyeY = tracker.eyeY
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SAMPLING_US)
            try {
                awaitCancellation()
            } finally {
                manager.unregisterListener(listener)
                scene.rest()
            }
        }
    }
    return scene
}

/** About 30 Hz: smooth enough for a slow tilt, gentle on the battery. */
private const val SAMPLING_US = 33_000
