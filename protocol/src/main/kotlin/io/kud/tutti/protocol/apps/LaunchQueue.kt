package io.kud.tutti.protocol.apps

/** Whether an app launch can go out the moment it is tapped. */
enum class LaunchLink {
    /** A route can go now: the remote link is ready, or IP control can `setActiveApp`. */
    READY,

    /** No route yet, but the link is a blip on its way back: hold the launch for [LaunchQueue.HOLD_MS]. */
    ON_ITS_WAY,

    /** No route, and none is coming soon: refuse, and say why. */
    DOWN,
}

enum class LaunchPhase {
    /** Tapped while the link was on its way back; sent the moment it is ready. */
    QUEUED,

    /** Handed to [AppLauncher]: routes going out, the TV watched for the app. */
    SENDING,
}

/** The one launch in flight. [heldUntil] is when a [LaunchPhase.QUEUED] launch gives up. */
data class PendingLaunch(val id: Long, val packageName: String, val phase: LaunchPhase, val heldUntil: Long? = null)

/** A launch that didn't bring its app up: [expired] when it never went out, [detail] the redacted trace. */
data class LaunchFailure(val packageName: String, val expired: Boolean, val detail: String? = null)

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

    /** Held: wait for the link up to [until], then [LaunchQueue.linkReady] or [LaunchQueue.tick]. */
    data class Held(val id: Long, val packageName: String, val until: Long) : LaunchEffect

    /** The same app tapped again while its launch is in flight: nothing is sent twice. */
    data class Repeat(val packageName: String) : LaunchEffect

    /** No route and none coming: not sent. */
    data class Refused(val packageName: String) : LaunchEffect

    /** A newer tap replaced launch [id]; whatever was running for it stops. */
    data class Superseded(val id: Long) : LaunchEffect

    /** Launch [id] ended: [ok] when it went out (confirmed or sent unobserved). */
    data class Done(val id: Long, val ok: Boolean) : LaunchEffect

    /** Say it didn't open (a held launch past its window, or every route failed). */
    data class Failed(val failure: LaunchFailure) : LaunchEffect
}

data class LaunchStep(val book: LaunchBook, val effects: List<LaunchEffect> = emptyList())

/**
 * The launch reducer: one launch in flight, newest tap wins, a repeat of the same app never
 * launches twice, a launch tapped in a blip waits [HOLD_MS] for the link and is dropped (and said)
 * past it. Pure; time is passed in.
 */
object LaunchQueue {
    /**
     * How long a launch waits for the remote link. Longer than a key's 2 s: a reconnect after a
     * blip lands in about 1.5 to 2.5 s, and the tile keeps saying it is waiting, so an app opening
     * a moment late is what was asked, not a surprise.
     */
    const val HOLD_MS = 4_000L

    /** How long an unconfirmed launch ([LaunchVerdict.SENT]) keeps the app on screen before the TV says. */
    const val SENT_GRACE_MS = 5_000L

    fun tap(book: LaunchBook, packageName: String, link: LaunchLink, now: Long): LaunchStep {
        if (book.pending?.packageName == packageName) return LaunchStep(book, listOf(LaunchEffect.Repeat(packageName)))
        if (link == LaunchLink.DOWN) return LaunchStep(book, listOf(LaunchEffect.Refused(packageName)))
        val id = book.nextId
        val superseded = book.pending?.let { listOf(LaunchEffect.Superseded(it.id), LaunchEffect.Done(it.id, ok = false)) }.orEmpty()
        val pending = when (link) {
            LaunchLink.READY -> PendingLaunch(id, packageName, LaunchPhase.SENDING)
            else -> PendingLaunch(id, packageName, LaunchPhase.QUEUED, heldUntil = now + HOLD_MS)
        }
        val next = book.copy(
            pending = pending,
            optimistic = packageName,
            optimisticUntil = null,
            failure = null,
            nextId = id + 1,
            lastSentId = if (pending.phase == LaunchPhase.SENDING) id else book.lastSentId,
        )
        val go = if (pending.phase == LaunchPhase.SENDING) {
            LaunchEffect.Send(id, packageName)
        } else {
            LaunchEffect.Held(id, packageName, pending.heldUntil!!)
        }
        return LaunchStep(next, superseded + go)
    }

    /** The link came back while launch [id] was held: send it, unless its window has passed. */
    fun linkReady(book: LaunchBook, id: Long, now: Long): LaunchStep {
        val pending = book.pending
        if (pending == null || pending.id != id || pending.phase != LaunchPhase.QUEUED) return LaunchStep(book)
        if (now > pending.heldUntil!!) return expire(book, pending)
        return LaunchStep(
            book.copy(pending = pending.copy(phase = LaunchPhase.SENDING, heldUntil = null), lastSentId = id),
            listOf(LaunchEffect.Send(id, pending.packageName)),
        )
    }

    /** Time passing: a held launch past its window is dropped and said; a stale optimistic app goes. */
    fun tick(book: LaunchBook, now: Long): LaunchStep {
        val pending = book.pending
        if (pending != null && pending.phase == LaunchPhase.QUEUED && now >= pending.heldUntil!!) return expire(book, pending)
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
                val failure = LaunchFailure(pending.packageName, expired = false, detail = detail)
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
        val failure = LaunchFailure(pending.packageName, expired = true)
        return LaunchStep(
            book.copy(pending = null, optimistic = null, optimisticUntil = null, failure = failure),
            listOf(LaunchEffect.Done(pending.id, ok = false), LaunchEffect.Failed(failure)),
        )
    }
}
