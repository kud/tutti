package io.kud.tutti.protocol.apps

/** Whether an app launch can go out the moment it is tapped. */
enum class LaunchLink {
    /** A route can go now: the remote link is ready, or IP control can `setActiveApp`. */
    READY,

    /**
     * No route: refuse, and say why. Never held for a link on its way back (Erwann, 2026-11-07:
     * « soit c'est connecté et ça marche, soit c'est pas connecté et ça fait rien »).
     */
    DOWN,

    /**
     * The TV is asleep (in standby, or out of reach where a wake could bring it back): wake it,
     * then send once it is on ([LaunchQueue.awake]), the way the TV's own app buttons do.
     */
    ASLEEP,
}

enum class LaunchPhase {
    /** Handed to [AppLauncher]: routes going out, the TV watched for the app. */
    SENDING,

    /** Tapped while the TV slept: one wake is under way, and the launch goes once the TV is on. */
    WAKING,
}

/** The one launch in flight. [heldUntil] is when a [LaunchPhase.WAKING] launch gives up. */
data class PendingLaunch(val id: Long, val packageName: String, val phase: LaunchPhase, val heldUntil: Long? = null)

/**
 * A launch that didn't bring its app up: [asleep] when it never went out because the TV it woke
 * never came on, else every route was tried; [detail] the redacted trace.
 */
data class LaunchFailure(val packageName: String, val detail: String? = null, val asleep: Boolean = false)

/**
 * App launches as the remote shows them, optimistic (CLAUDE.md: never block the UI on the
 * network). [optimistic] is the app the remote shows from the tap until the TV says what it shows;
 * [optimisticUntil] bounds it once a launch went out unconfirmed ([LaunchVerdict.SENT]).
 * [lastSentId] is the newest launch that went out, so a caller holding an id knows it left even
 * when it settled before anyone looked.
 */
data class LaunchBook(
    val pending: PendingLaunch? = null,
    val optimistic: String? = null,
    val optimisticUntil: Long? = null,
    val failure: LaunchFailure? = null,
    val nextId: Long = 1,
    val lastSentId: Long = 0,
) {
    /** The id a tap of [packageName] would join: the launch in flight for it, if any. */
    fun inFlightFor(packageName: String): Long? = pending?.takeIf { it.packageName == packageName }?.id

    /** The app on screen: the one asked for while it is on its way, else what the TV reported. */
    fun shown(reported: String?): String? = optimistic ?: reported
}

/** What the caller must do after a step. */
sealed interface LaunchEffect {
    /** Run the launch now ([AppLauncher]), then report it with [LaunchQueue.settled]. */
    data class Send(val id: Long, val packageName: String) : LaunchEffect

    /**
     * The TV is asleep: launch [id] waits on a wake until [until], then [LaunchQueue.awake] or
     * [LaunchQueue.notWoken]. [start] is false when a wake is already under way for an earlier
     * tap: this one only takes its place, and nothing wakes twice.
     */
    data class Wake(val id: Long, val packageName: String, val until: Long, val start: Boolean) : LaunchEffect

    /** The same app tapped again while its launch is in flight: nothing is sent twice. */
    data class Repeat(val packageName: String) : LaunchEffect

    /** No route and none coming: not sent. */
    data class Refused(val packageName: String) : LaunchEffect

    /** A newer tap replaced launch [id]; whatever was running for it stops. */
    data class Superseded(val id: Long) : LaunchEffect

    /** Launch [id] ended: [ok] when it went out (confirmed or sent unobserved). */
    data class Done(val id: Long, val ok: Boolean) : LaunchEffect

    /** Say it didn't open (the TV it woke never came on, or every route failed). */
    data class Failed(val failure: LaunchFailure) : LaunchEffect
}

data class LaunchStep(val book: LaunchBook, val effects: List<LaunchEffect> = emptyList())

/**
 * The launch reducer: one launch in flight, newest tap wins, a repeat of the same app never
 * launches twice, and a launch with no route is refused, never held for the link (Erwann,
 * 2026-11-07). A launch tapped while the TV sleeps wakes it first (Erwann, 2026-11-01: « allumer la TV
 * quand on appuie sur Netflix »): one wake, up to [WAKE_MS]; a newer app tapped meanwhile takes
 * the place of the one waiting rather than waking again. Pure; time is passed in.
 */
object LaunchQueue {
    /** How long an unconfirmed launch ([LaunchVerdict.SENT]) keeps the app on screen before the TV says. */
    const val SENT_GRACE_MS = 5_000L

    /**
     * How long a launch waits on the TV's wake: the wake's own 12 s to reach it, 2 s for it to
     * say it is on, and a moment for the screen to settle. Past it the launch is dropped, said.
     */
    const val WAKE_MS = 16_000L

    fun tap(book: LaunchBook, packageName: String, link: LaunchLink, now: Long): LaunchStep {
        if (book.pending?.packageName == packageName) return LaunchStep(book, listOf(LaunchEffect.Repeat(packageName)))
        // A wake under way: until a route is ready, a new app joins that wake instead of being refused.
        val waking = book.pending?.takeIf { it.phase == LaunchPhase.WAKING }
        val route = if (waking != null && link != LaunchLink.READY) LaunchLink.ASLEEP else link
        if (route == LaunchLink.DOWN) return LaunchStep(book, listOf(LaunchEffect.Refused(packageName)))
        val id = book.nextId
        val superseded = book.pending?.let { listOf(LaunchEffect.Superseded(it.id), LaunchEffect.Done(it.id, ok = false)) }.orEmpty()
        val pending = when (route) {
            LaunchLink.READY -> PendingLaunch(id, packageName, LaunchPhase.SENDING)
            else -> PendingLaunch(id, packageName, LaunchPhase.WAKING, heldUntil = waking?.heldUntil ?: (now + WAKE_MS))
        }
        val next = book.copy(
            pending = pending,
            optimistic = packageName,
            optimisticUntil = null,
            failure = null,
            nextId = id + 1,
            lastSentId = if (pending.phase == LaunchPhase.SENDING) id else book.lastSentId,
        )
        val go = when (pending.phase) {
            LaunchPhase.SENDING -> LaunchEffect.Send(id, packageName)
            LaunchPhase.WAKING -> LaunchEffect.Wake(id, packageName, pending.heldUntil!!, start = waking == null)
        }
        return LaunchStep(next, superseded + go)
    }

    /** The TV woke and can take a launch: the launch waiting on it goes, whichever app it is now. */
    fun awake(book: LaunchBook): LaunchStep {
        val pending = book.pending
        if (pending == null || pending.phase != LaunchPhase.WAKING) return LaunchStep(book)
        return LaunchStep(
            book.copy(pending = pending.copy(phase = LaunchPhase.SENDING, heldUntil = null), lastSentId = pending.id),
            listOf(LaunchEffect.Send(pending.id, pending.packageName)),
        )
    }

    /** The wake failed: the launch waiting on it is dropped, never sent into the void, and said. */
    fun notWoken(book: LaunchBook): LaunchStep {
        val pending = book.pending
        if (pending == null || pending.phase != LaunchPhase.WAKING) return LaunchStep(book)
        return expire(book, pending)
    }

    /** Time passing: a waking launch past its window is dropped and said; a stale optimistic app goes. */
    fun tick(book: LaunchBook, now: Long): LaunchStep {
        val pending = book.pending
        if (pending != null && pending.phase != LaunchPhase.SENDING && now >= pending.heldUntil!!) return expire(book, pending)
        val until = book.optimisticUntil
        if (pending == null && until != null && now >= until) return LaunchStep(book.copy(optimistic = null, optimisticUntil = null))
        return LaunchStep(book)
    }

    /** [AppLauncher] finished launch [id]. A launch already replaced by a newer tap changes nothing. */
    fun settled(book: LaunchBook, id: Long, verdict: LaunchVerdict, now: Long, detail: String? = null): LaunchStep {
        val pending = book.pending
        if (pending == null || pending.id != id) return LaunchStep(book)
        return when (verdict) {
            // The TV reports it: the truth takes over from here.
            LaunchVerdict.CONFIRMED -> LaunchStep(
                book.copy(pending = null, optimistic = null, optimisticUntil = null),
                listOf(LaunchEffect.Done(id, ok = true)),
            )
            // Out, but nothing can tell: keep it on screen a while, until the TV says otherwise.
            LaunchVerdict.SENT -> LaunchStep(
                book.copy(pending = null, optimisticUntil = now + SENT_GRACE_MS),
                listOf(LaunchEffect.Done(id, ok = true)),
            )
            // Roll back to what the TV shows, and say so quietly.
            LaunchVerdict.FAILED -> {
                val failure = LaunchFailure(pending.packageName, detail = detail)
                LaunchStep(
                    book.copy(pending = null, optimistic = null, optimisticUntil = null, failure = failure),
                    listOf(LaunchEffect.Done(id, ok = false), LaunchEffect.Failed(failure)),
                )
            }
        }
    }

    /**
     * The TV reported its foreground app. While a launch is in flight [AppLauncher] watches for it
     * itself; afterwards any report is the truth and replaces the optimistic app.
     */
    fun foreground(book: LaunchBook, reported: String?): LaunchStep =
        if (book.pending == null && book.optimistic != null && reported != null) {
            LaunchStep(book.copy(optimistic = null, optimisticUntil = null))
        } else {
            LaunchStep(book)
        }

    /** Forgets the last failure once it has been shown. */
    fun clearFailure(book: LaunchBook): LaunchBook = book.copy(failure = null)

    private fun expire(book: LaunchBook, pending: PendingLaunch): LaunchStep {
        val failure = LaunchFailure(pending.packageName, asleep = true)
        return LaunchStep(
            book.copy(pending = null, optimistic = null, optimisticUntil = null, failure = failure),
            listOf(LaunchEffect.Done(pending.id, ok = false), LaunchEffect.Failed(failure)),
        )
    }
}
