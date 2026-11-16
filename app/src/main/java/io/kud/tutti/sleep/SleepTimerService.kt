package io.kud.tutti.sleep

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.kud.tutti.MainActivity
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.PowerTarget
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.VolumeSource
import io.kud.tutti.platform.diagnostic.AppLog
import io.kud.tutti.protocol.diagnostic.EventLog
import io.kud.tutti.protocol.sleep.EpisodeEnd
import io.kud.tutti.protocol.sleep.SleepFade
import io.kud.tutti.protocol.sleep.SwitchOffAt
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The running timer, process-wide: the Power sheet and the now-showing card read it. Gone with the process, which is honest. */
object SleepTimers {
    private val mutable = MutableStateFlow<SleepTimerState?>(null)
    val state: StateFlow<SleepTimerState?> = mutable.asStateFlow()

    internal fun set(value: SleepTimerState?) {
        mutable.value = value
    }

    internal fun update(transform: (SleepTimerState?) -> SleepTimerState?) = mutable.update(transform)

    /** Whether exact alarms are allowed; false means "may switch off a few minutes late". */
    fun exactAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** The system page that grants exact alarms, or null where none is needed. */
    fun exactSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + context.packageName))
        } else {
            null
        }

    /** Starts a timer from the Power sheet (tutti is in front, so the foreground service may start). */
    fun start(context: Context, minutes: Int) {
        ContextCompat.startForegroundService(context, SleepTimerService.intent(context, SleepTimerService.ACTION_START).putExtra(SleepTimerService.EXTRA_MINUTES, minutes))
    }

    /** Starts a timer ending with the Cast item playing ([EpisodeEnd]); the sheet offered it only when [EpisodeEnd.offered]. */
    fun startEpisode(context: Context) {
        ContextCompat.startForegroundService(context, SleepTimerService.intent(context, SleepTimerService.ACTION_START).putExtra(SleepTimerService.EXTRA_EPISODE, true))
    }

    /** Starts a timer ending at a time of day the sheet already checked ([SwitchOffAt.plan]). */
    fun startAt(context: Context, endsAt: Long) {
        ContextCompat.startForegroundService(context, SleepTimerService.intent(context, SleepTimerService.ACTION_START).putExtra(SleepTimerService.EXTRA_ENDS_AT, endsAt))
    }

    /**
     * Exact alarms may have been granted since the timer started (the system page, then back): a
     * running timer still on an inexact alarm moves to an exact one. Called on resume and on the
     * system's permission broadcast; a no-op otherwise.
     */
    fun refreshExact(context: Context) {
        if (needsExactUpgrade(state.value, exactAllowed(context))) SleepTimerService.running?.upgradeExact()
    }

    fun cancel(context: Context) {
        // A running service takes it in-process; one that died with the process leaves only the alarm.
        SleepTimerService.running?.cancelTimer() ?: run {
            SleepTimerService.cancelAlarm(context)
            set(null)
        }
    }
}

/**
 * The gentle switch-off timer, as a `connectedDevice` foreground service for its whole life
 * (docs/architecture.md, "Gentle switch-off timer"). Its notification is the timer: the end time, a
 * countdown chronometer and Cancel. While waiting it holds no wake lock; an alarm wakes it at the
 * fade's start, then the fade runs under a partial wake lock capped at 3 minutes: the bar's volume
 * steps down every ten seconds (a hand on the rail wins), and at the end the TV and the bar go off.
 */
@AndroidEntryPoint
class SleepTimerService : Service() {
    @Inject lateinit var controller: TuttiController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var fade: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var retained = false

    /** The episode followed, while the timer aims at the end of a Cast item; null for a fixed end. */
    private var episode: EpisodeEnd.Plan? = null
    private var follow: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        running = null
        stopFollowing()
        scope.cancel()
        releaseHolds()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val now = System.currentTimeMillis()
                val plan = if (intent.getBooleanExtra(EXTRA_EPISODE, false)) {
                    EpisodeEnd.start(controller.castNowPlaying.value, now) ?: return stopNow()
                } else {
                    null
                }
                val endsAt = plan?.endsAt ?: startEnd(intent, now) ?: return stopNow()
                fade?.cancel()
                stopFollowing()
                releaseHolds()
                val exact = SleepTimers.exactAllowed(this)
                SleepTimers.set(SleepTimerState(endsAt, exact, episode = plan != null))
                foreground()
                schedule(endsAt)
                if (plan != null) followEpisode(plan)
            }
            ACTION_WAKE -> {
                val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
                val known = SleepTimers.state.value
                if (known != null && known.endsAt != endsAt) {
                    // A stale alarm from a timer since replaced: keep the current one.
                    foreground()
                    return START_NOT_STICKY
                }
                // The process may have died meanwhile: the alarm carries the end, so the timer goes on.
                if (known == null) SleepTimers.set(SleepTimerState(endsAt, intent.getBooleanExtra(EXTRA_EXACT, true)))
                foreground()
                wake()
            }
            ACTION_CANCEL -> cancelTimer()
            else -> if (SleepTimers.state.value == null) return stopNow() else foreground()
        }
        return START_NOT_STICKY
    }

    /** Exact alarms arrived mid-timer: re-arm the fade's start exactly, or just say so once fading (the fade runs on its own clock). */
    internal fun upgradeExact() {
        val timer = SleepTimers.state.value ?: return
        if (!needsExactUpgrade(timer, SleepTimers.exactAllowed(this))) return
        if (timer.fading(System.currentTimeMillis())) SleepTimers.update { it?.copy(exact = true) } else schedule(timer.endsAt)
        foreground()
    }

    /** Called by the alarm, in-process when the service is alive (no new foreground start needed). */
    internal fun wake() {
        val timer = SleepTimers.state.value ?: return finish()
        val now = System.currentTimeMillis()
        if (!timer.fading(now)) {
            val start = SleepFade.fadeStartsAt(timer.endsAt)
            // Woken early (rare): another alarm unless the fade is a moment away.
            if (start - now > EARLY_SLACK_MS) {
                schedule(timer.endsAt)
                return
            }
        }
        if (fade?.isActive == true) return
        holdForFade()
        fade = scope.launch {
            if (episode != null) {
                // Read Cast afresh before fading: the phone may have slept through a pause or a jump.
                val woke = System.currentTimeMillis()
                withTimeoutOrNull(CAST_WAIT_MS) { controller.castNowPlaying.first { it != null && it.readAtMillis >= woke - CAST_FRESH_MS } }
                replanEpisode()
            }
            runFade()
        }
    }

    /**
     * Follows the Cast item for an episode timer: every Cast change and every [FOLLOW_MS] while the
     * process runs, and once more when the alarm wakes the fade. Cast keeps reading with the remote
     * out of sight ([TuttiController.keepCast]) for as long as the timer runs.
     */
    private fun followEpisode(plan: EpisodeEnd.Plan) {
        episode = plan
        controller.keepCast(true)
        AppLog.events.record(EventLog.Kind.CAST, "episode end: following, ${SleepFade.minutesLeft(System.currentTimeMillis(), plan.endsAt)} min left")
        follow = scope.launch {
            launch { controller.castNowPlaying.collect { replanEpisode() } }
            while (true) {
                delay(FOLLOW_MS)
                replanEpisode()
            }
        }
    }

    private fun stopFollowing() {
        follow?.cancel()
        follow = null
        if (episode != null) controller.keepCast(false)
        episode = null
    }

    /** One re-read of the episode: moves the end and the alarm when the plan moved; logs only what changed. */
    private fun replanEpisode() {
        val plan = episode ?: return
        val now = System.currentTimeMillis()
        val step = EpisodeEnd.replan(plan, controller.castNowPlaying.value, now)
        episode = step.plan
        step.why?.let { AppLog.events.record(EventLog.Kind.CAST, EpisodeEnd.logLine(it, plan.endsAt, step.plan.endsAt, now)) }
        if (step.plan.endsAt == plan.endsAt) return
        SleepTimers.update { it?.copy(endsAt = step.plan.endsAt) }
        // While fading the loop reads the new end itself; otherwise the alarm moves with it (a past
        // fade start fires at once, which is how a sooner end starts the fade).
        if (fade?.isActive != true) schedule(step.plan.endsAt)
        foreground()
    }

    private suspend fun runFade() {
        while (true) {
            if (episode != null) replanEpisode()
            val endsAt = SleepTimers.state.value?.endsAt ?: return
            val now = System.currentTimeMillis()
            val s = controller.state.value
            val barOnline = s.links.bar == Link.ONLINE
            when (val step = sleepStep(now, endsAt, SleepTimers.state.value?.startVolume, s.volume, barOnline)) {
                SleepStep.Wait -> if (episode != null) {
                    // An episode end moved out of the fade (a pause, a jump back): the level the fade
                    // took goes back, and the timer waits for its new fade start.
                    val start = SleepTimers.state.value?.startVolume
                    SleepFade.restoreOnCancel(start, s.volume)?.takeIf { barOnline }?.let { controller.setVolume(it, VolumeSource.QUICK) }
                    SleepTimers.update { it?.copy(startVolume = null) }
                    releaseHolds()
                    schedule(endsAt)
                    foreground()
                    return
                } else {
                    Unit
                }
                is SleepStep.Lower -> {
                    remember(step.startVolume)
                    // Lowering never meets the guard; the ceiling clamps as for any change.
                    controller.setVolume(step.level, VolumeSource.QUICK)
                }
                is SleepStep.Hold -> remember(step.startVolume)
                SleepStep.SwitchOff -> {
                    switchOff()
                    return
                }
            }
            foreground()
            val next = SleepFade.nextWake(now, endsAt) ?: endsAt
            delay((next - System.currentTimeMillis()).coerceAtLeast(MIN_STEP_MS))
        }
    }

    private fun remember(startVolume: Int?) {
        if (startVolume != null) SleepTimers.update { it?.copy(startVolume = it.startVolume ?: startVolume) }
    }

    private suspend fun switchOff() {
        // The sessions restarted with the fade; give a silent link a moment rather than send into nothing.
        withTimeoutOrNull(LINK_WAIT_MS) {
            controller.state.first { it.links.bar == Link.ONLINE || it.links.tv == Link.ONLINE || it.links.rest == Link.ONLINE }
        }
        controller.power(PowerTarget.BOTH, on = false)
        // Let the commands leave before the sessions are let go.
        delay(FLUSH_MS)
        finish()
    }

    /** Cancel from the notification or the Power sheet: mid-fade, the level goes back (guard and ceiling apply). */
    internal fun cancelTimer() {
        cancelAlarm(this)
        val timer = SleepTimers.state.value
        fade?.cancel()
        val s = controller.state.value
        val restore = sleepRestore(timer, System.currentTimeMillis(), s.volume, s.links.bar == Link.ONLINE)
        if (restore != null) {
            controller.setVolume(restore, VolumeSource.QUICK)
            scope.launch {
                delay(FLUSH_MS)
                finish()
            }
        } else {
            finish()
        }
    }

    private fun finish() {
        stopFollowing()
        SleepTimers.set(null)
        releaseHolds()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopNow(): Int {
        finish()
        return START_NOT_STICKY
    }

    private fun holdForFade() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tutti:switch-off-fade")
                .apply {
                    setReferenceCounted(false)
                    acquire(FADE_LOCK_MS)
                }
        }
        if (!retained) {
            controller.retain()
            retained = true
        }
    }

    private fun releaseHolds() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (retained) {
            controller.release()
            retained = false
        }
    }

    private fun schedule(endsAt: Long) {
        val alarms = getSystemService(AlarmManager::class.java)
        val at = SleepFade.fadeStartsAt(endsAt)
        val exact = SleepTimers.exactAllowed(this)
        val pending = alarmIntent(this, endsAt, exact)
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            // Exact alarms revoked between the check and the call: the inexact one still goes.
            Log.w(TAG, "exact alarm refused", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        SleepTimers.update { it?.copy(exact = exact) }
    }

    private fun foreground() {
        val timer = SleepTimers.state.value ?: return
        ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(timer),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun notification(timer: SleepTimerState): Notification {
        val at = DateFormat.getTimeFormat(this).format(Date(timer.endsAt))
        val tomorrow = SwitchOffAt.isTomorrow(Instant.now(), Instant.ofEpochMilli(timer.endsAt), ZoneId.systemDefault())
        val text = when {
            timer.fading(System.currentTimeMillis()) -> getString(R.string.gentle_off_fading)
            timer.episode -> getString(R.string.gentle_off_episode_notification)
            !timer.exact -> getString(R.string.gentle_off_late)
            else -> getString(R.string.gentle_off_notification_text)
        }
        val cancel = PendingIntent.getService(this, REQUEST_CANCEL, intent(this, ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bedtime)
            .setContentTitle(getString(if (tomorrow) R.string.gentle_off_at_tomorrow else R.string.gentle_off_at, at))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The countdown is the system's: nothing redraws it.
            .setWhen(timer.endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), getString(R.string.gentle_off_cancel), cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "SleepTimer"
        const val ACTION_START = "io.kud.tutti.sleep.START"
        const val ACTION_WAKE = "io.kud.tutti.sleep.WAKE"
        const val ACTION_CANCEL = "io.kud.tutti.sleep.CANCEL"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_ENDS_AT = "ends_at"
        const val EXTRA_EXACT = "exact"
        const val EXTRA_EPISODE = "episode"
        const val CHANNEL_ID = "switch_off_timer"
        private const val NOTIFICATION_ID = 0x5A20
        private const val REQUEST_ALARM = 0x5A21
        private const val REQUEST_CANCEL = 0x5A22
        private const val REQUEST_OPEN = 0x5A23

        /** The fade's wake lock: the two-minute fade, the switch-off and a margin, never longer. */
        private const val FADE_LOCK_MS = 3 * 60_000L
        private const val EARLY_SLACK_MS = 60_000L
        private const val MIN_STEP_MS = 250L
        private const val LINK_WAIT_MS = 10_000L
        private const val FLUSH_MS = 2_000L

        /** An episode timer re-reads Cast this often while the process runs (the session re-reads every 15 s). */
        private const val FOLLOW_MS = 15_000L

        /** At the fade's alarm, how long to wait for Cast to come back with a fresh reading. */
        private const val CAST_WAIT_MS = 10_000L
        private const val CAST_FRESH_MS = 2_000L

        @Volatile internal var running: SleepTimerService? = null

        /**
         * The end a start asks for: an absolute [EXTRA_ENDS_AT] checked by [SwitchOffAt.accepts], or
         * [EXTRA_MINUTES] from the offered list. Anything else starts nothing.
         */
        internal fun startEnd(intent: Intent, now: Long): Long? {
            val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
            if (endsAt != 0L) return endsAt.takeIf { SwitchOffAt.accepts(now, it) }
            val minutes = intent.getIntExtra(EXTRA_MINUTES, 0).takeIf { it in SleepFade.DURATIONS_MIN } ?: return null
            return SleepFade.endsAt(now, minutes)
        }

        fun intent(context: Context, action: String): Intent = Intent(context, SleepTimerService::class.java).setAction(action)

        private fun alarmIntent(context: Context, endsAt: Long, exact: Boolean): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            Intent(context, SleepTimerAlarmReceiver::class.java).putExtra(EXTRA_ENDS_AT, endsAt).putExtra(EXTRA_EXACT, exact),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        internal fun cancelAlarm(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, 0L, true))
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.gentle_off_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.gentle_off_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }
}

/**
 * The alarm at the fade's start. A live service takes it in-process; otherwise the service starts
 * from the alarm, which an exact alarm allows from the background. An inexact one may not: then the
 * timer is lost with the process, and the start failure is logged rather than crashing.
 */
class SleepTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val live = SleepTimerService.running
        if (live != null && SleepTimers.state.value?.endsAt == intent.getLongExtra(SleepTimerService.EXTRA_ENDS_AT, 0L)) {
            live.wake()
            return
        }
        val wake = SleepTimerService.intent(context, SleepTimerService.ACTION_WAKE)
            .putExtra(SleepTimerService.EXTRA_ENDS_AT, intent.getLongExtra(SleepTimerService.EXTRA_ENDS_AT, 0L))
            .putExtra(SleepTimerService.EXTRA_EXACT, intent.getBooleanExtra(SleepTimerService.EXTRA_EXACT, true))
        try {
            ContextCompat.startForegroundService(context, wake)
        } catch (e: IllegalStateException) {
            Log.w("SleepTimer", "could not start the switch-off timer from the alarm", e)
        }
    }
}

/**
 * The system's word that exact alarms were granted (sent to the app, even in the background, only on
 * a grant). A running inexact timer turns exact; without one there is nothing to do.
 */
class ExactAlarmGrantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The action's constant is API 31; the string keeps lint quiet on minSdk 29, where it never comes.
        if (intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") SleepTimers.refreshExact(context)
    }
}
