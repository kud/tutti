package io.kud.tutti.protocol.theatre

/**
 * A soundbar that died without closing its socket keeps a TCP link "up" until the read
 * timeout. The periodic poll is the proof of life: [limit] polls in a row that hear nothing
 * at all mean the link is dead, and the caller drops it.
 */
class SilenceWatch(private val limit: Int = 2) {
    private var silent = 0

    /** After one poll round, given [TheatreClient.heard] before and after it: true when the link should be dropped. */
    fun afterPoll(heardBefore: Int, heardAfter: Int): Boolean {
        silent = if (heardAfter != heardBefore) 0 else silent + 1
        return silent >= limit
    }
}
