package io.kud.tutti.ui.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.bluetooth.HidPhase
import io.kud.tutti.platform.bluetooth.HidSupport
import io.kud.tutti.protocol.hid.AirPointer
import io.kud.tutti.protocol.hid.ConsumerUsage
import io.kud.tutti.protocol.hid.KeyLayout
import io.kud.tutti.protocol.hid.KeyUsage
import io.kud.tutti.protocol.hid.MouseButton
import io.kud.tutti.protocol.hid.TouchpadMotion
import io.kud.tutti.ui.common.keyIcon

/** The three surfaces of Bluetooth mode (Iris, 2026-10-25: one screen, three segments). */
enum class HidSurface(@StringRes val label: Int, @DrawableRes val icon: Int) {
    TOUCHPAD(R.string.bt_touchpad, R.drawable.ic_pad_touchpad_outline),
    POINTER(R.string.bt_pointer, R.drawable.ic_bluetooth),
    KEYBOARD(R.string.bt_keyboard, R.drawable.ic_keyboard),
}

/**
 * Settings › Bluetooth › Keyboard and mouse: the phone as a Bluetooth keyboard and mouse for the
 * TV, with no LAN. The HID role is held only while this screen is in front (started on ON_START,
 * given back on ON_STOP): Android allows one HID device app at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothScreen(link: HidLink, onBack: () -> Unit) {
    val state by link.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bluetooth_hid", Context.MODE_PRIVATE) }
    var surface by remember {
        mutableStateOf(HidSurface.entries.firstOrNull { it.name == prefs.getString("surface", null) } ?: HidSurface.TOUCHPAD)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> link.start()
                Lifecycle.Event.ON_STOP -> link.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            link.stop()
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { link.start() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { link.start() }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.support == HidSupport.UNSUPPORTED) {
                Text(stringResource(R.string.bt_unsupported), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            val name = state.host?.name ?: stringResource(R.string.bt_the_tv)
            StatusLine(
                text = when (state.phase) {
                    HidPhase.CONNECTED -> stringResource(R.string.bt_status_connected, name)
                    HidPhase.CONNECTING -> stringResource(R.string.bt_status_connecting, name)
                    HidPhase.READY -> if (state.host != null) stringResource(R.string.bt_status_paired, name) else stringResource(R.string.bt_status_not_paired)
                    HidPhase.BUSY -> stringResource(R.string.bt_status_busy)
                    HidPhase.NEEDS_PERMISSION -> stringResource(R.string.bt_status_permission)
                    HidPhase.BLUETOOTH_OFF -> stringResource(R.string.bt_status_off)
                    HidPhase.REGISTERING, HidPhase.STOPPED -> stringResource(R.string.bt_status_starting)
                },
                action = when (state.phase) {
                    HidPhase.READY -> if (state.host != null) {
                        stringResource(R.string.bt_reconnect) to { link.reconnect() }
                    } else {
                        stringResource(R.string.bt_make_visible) to {
                            discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                        }
                    }
                    HidPhase.NEEDS_PERMISSION -> stringResource(R.string.bt_allow) to { permissions.launch(link.permissions()) }
                    HidPhase.BLUETOOTH_OFF -> stringResource(R.string.bt_turn_on) to { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    HidPhase.BUSY -> stringResource(R.string.bt_retry) to { link.stop(); link.start() }
                    else -> null
                },
            )
            if (state.phase == HidPhase.READY && state.host == null) {
                Text(stringResource(R.string.bt_pair_steps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.phase == HidPhase.READY && state.host == null && state.bonded.isNotEmpty()) {
                Text(stringResource(R.string.bt_known_devices), style = MaterialTheme.typography.titleSmall)
                state.bonded.forEach { host ->
                    OutlinedButton(onClick = { link.reconnect(host.address) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(host.name)
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HidSurface.entries.forEachIndexed { index, entry ->
                    val label = stringResource(entry.label)
                    SegmentedButton(
                        selected = entry == surface,
                        onClick = {
                            surface = entry
                            prefs.edit().putString("surface", entry.name).apply()
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, HidSurface.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Tab },
                        icon = { SegmentedButtonDefaults.Icon(entry == surface) { Icon(painterResource(entry.icon), null, Modifier.size(18.dp)) } },
                    ) { Text(label, maxLines = 1) }
                }
            }

            val live = state.phase == HidPhase.CONNECTED
            when (surface) {
                HidSurface.TOUCHPAD -> TouchpadSurface(link, live)
                HidSurface.POINTER -> PointerSurface(link, live, prefs)
                HidSurface.KEYBOARD -> KeyboardSurface(link, live, prefs)
            }

            if (state.host != null) {
                TextButton(onClick = {
                    link.forget()
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.bt_forget)) }
            }
            Text(stringResource(R.string.bt_role_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(text: String, action: Pair<String, () -> Unit>?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (action != null) Button(onClick = action.second, modifier = Modifier.heightIn(min = 48.dp)) { Text(action.first) }
    }
}

/** The TV-control "down" dress when the link isn't up: no fill, dashed outline, nothing sends. */
private fun Modifier.liveOrDown(live: Boolean, color: androidx.compose.ui.graphics.Color): Modifier =
    if (live) {
        border(BorderStroke(1.dp, color), RoundedCornerShape(24.dp))
    } else {
        drawBehind {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(24.dp.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
            )
        }
    }

@Composable
private fun TouchpadSurface(link: HidLink, live: Boolean) {
    val motion = remember { TouchpadMotion() }
    val haptic = LocalHapticFeedback.current
    val hint = stringResource(R.string.bt_touchpad_hint)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp)
                .liveOrDown(live, MaterialTheme.colorScheme.outline)
                .semantics { contentDescription = hint }
                .pointerInput(live) {
                    if (!live) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var last = down.uptimeMillis
                        var travel = 0f
                        var moved = false
                        var fingers = 1
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            fingers = maxOf(fingers, pressed)
                            val pan: Offset = event.calculatePan()
                            val now = event.changes.first().uptimeMillis
                            travel += pan.getDistance()
                            if (travel > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                if (pressed >= 2) link.move(motion.scroll(pan.y.toDouble()))
                                else link.move(motion.move(pan.x.toDouble(), pan.y.toDouble(), (now - last).toDouble()))
                            }
                            last = now
                            event.changes.forEach { it.consume() }
                        }
                        if (!moved) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            link.click(if (fingers >= 2) MouseButton.SECONDARY else MouseButton.PRIMARY)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_pad_touchpad_outline), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MediaRow(link, live)
    }
}

@Composable
private fun PointerSurface(link: HidLink, live: Boolean, prefs: android.content.SharedPreferences) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat("sensitivity", 1f)) }
    val pointer = remember { AirPointer(sensitivity.toDouble()) }
    var frozen by remember { mutableStateOf(false) }
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val gyro = remember { sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }

    if (gyro == null) {
        Text(stringResource(R.string.bt_pointer_no_gyro), style = MaterialTheme.typography.bodyLarge)
        return
    }
    // The gyroscope runs only while this segment is in front and the link is up.
    DisposableEffect(live, gyro) {
        if (!live) return@DisposableEffect onDispose { }
        var lastNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val dt = if (lastNs == 0L) 0.0 else (event.timestamp - lastNs) / 1e9
                lastNs = event.timestamp
                link.move(pointer.sample(event.values[0].toDouble(), event.values[2].toDouble(), dt))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors?.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors?.unregisterListener(listener) }
    }
    LaunchedEffect(sensitivity) { pointer.sensitivity = sensitivity.toDouble() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.bt_pointer_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val frozenLabel = stringResource(R.string.bt_pointer_frozen)
        val recentre = stringResource(R.string.bt_pointer_recentre)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = if (frozen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            border = BorderStroke(if (frozen) 3.dp else 1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .semantics {
                    role = Role.Button
                    stateDescription = if (frozen) frozenLabel else ""
                }
                .pointerInput(live) {
                    if (!live) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        pointer.hold()
                        frozen = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        pointer.release()
                        frozen = false
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (frozen) frozenLabel else recentre, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            }
        }
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                link.click(MouseButton.PRIMARY)
            },
            enabled = live,
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        ) { Text(stringResource(R.string.bt_click), style = MaterialTheme.typography.headlineSmall) }
        val sensitivityLabel = stringResource(R.string.bt_sensitivity)
        Text(sensitivityLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val percent = (sensitivity * 100).toInt()
        val valueText = stringResource(R.string.bt_sensitivity_value, percent)
        Slider(
            value = sensitivity,
            onValueChange = { sensitivity = it },
            onValueChangeFinished = { prefs.edit().putFloat("sensitivity", sensitivity).apply() },
            valueRange = AirPointer.MIN_SENSITIVITY.toFloat()..AirPointer.MAX_SENSITIVITY.toFloat(),
            modifier = Modifier.semantics {
                contentDescription = sensitivityLabel
                stateDescription = valueText
            },
        )
        MediaRow(link, live)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardSurface(link: HidLink, live: Boolean, prefs: android.content.SharedPreferences) {
    var layout by remember { mutableStateOf(KeyLayout.entries.firstOrNull { it.name == prefs.getString("layout", null) } ?: KeyLayout.US) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var missing by remember { mutableStateOf<Set<Char>>(emptySet()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = field,
            enabled = live,
            onValueChange = { next ->
                val old = field.text
                val new = next.text
                val common = old.commonPrefixWith(new).length
                repeat(old.length - common) { link.key(KeyUsage.BACKSPACE) }
                val typed = layout.type(new.substring(common))
                link.type(typed.strokes)
                missing = typed.unsupported
                field = next
            },
            label = { Text(stringResource(R.string.bt_keyboard_field)) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (missing.isNotEmpty()) {
            Text(
                stringResource(R.string.bt_keyboard_missing, missing.joinToString(" ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundKey(R.drawable.ic_chevron_left, R.string.bt_key_left, live) { link.key(KeyUsage.LEFT) }
            RoundKey(R.drawable.ic_arrow_up, R.string.bt_key_up, live) { link.key(KeyUsage.UP) }
            RoundKey(R.drawable.ic_arrow_down, R.string.bt_key_down, live) { link.key(KeyUsage.DOWN) }
            RoundKey(R.drawable.ic_chevron_right, R.string.bt_key_right, live) { link.key(KeyUsage.RIGHT) }
            Box(Modifier.weight(1f))
            TextButton(onClick = {
                link.key(KeyUsage.BACKSPACE)
                field = TextFieldValue(field.text.dropLast(1))
            }, enabled = live, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bt_key_backspace)) }
            Button(onClick = {
                link.key(KeyUsage.ENTER)
                field = TextFieldValue("")
            }, enabled = live, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.bt_key_enter)) }
        }
        Text(stringResource(R.string.bt_layout), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            KeyLayout.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = entry == layout,
                    onClick = {
                        layout = entry
                        prefs.edit().putString("layout", entry.name).apply()
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, KeyLayout.entries.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(if (entry == KeyLayout.US) R.string.bt_layout_us else R.string.bt_layout_fr), maxLines = 1)
                }
            }
        }
        Text(stringResource(R.string.bt_layout_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MediaRow(link, live)
    }
}

/** Home, Back and media over the consumer page, wearing the remote's own glyphs (keyIcon). */
@Composable
private fun MediaRow(link: HidLink, live: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundKey(keyIcon(KeyEvent.KEYCODE_BACK), R.string.bt_key_back, live) { link.consumer(ConsumerUsage.BACK) }
        RoundKey(keyIcon(KeyEvent.KEYCODE_HOME), R.string.bt_key_home, live) { link.consumer(ConsumerUsage.HOME) }
        RoundKey(keyIcon(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), R.string.bt_key_play_pause, live) { link.consumer(ConsumerUsage.PLAY_PAUSE) }
        RoundKey(R.drawable.ic_volume_off, R.string.bt_key_mute, live) { link.consumer(ConsumerUsage.MUTE) }
        RoundKey(R.drawable.ic_arrow_down, R.string.bt_key_volume_down, live) { link.consumer(ConsumerUsage.VOLUME_DOWN) }
        RoundKey(R.drawable.ic_volume_up, R.string.bt_key_volume_up, live) { link.consumer(ConsumerUsage.VOLUME_UP) }
    }
}

@Composable
private fun RoundKey(@DrawableRes icon: Int, @StringRes label: Int, live: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = live, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}
