package io.kud.tutti.protocol.theatre

sealed interface TheatreOutbound {
    val id: Int
    val feature: String

    data class GetRequest(override val id: Int, override val feature: String) : TheatreOutbound

    /** [numeric] values are written as JSON numbers (levels), others as strings. */
    data class SetRequest(
        override val id: Int,
        override val feature: String,
        val value: String,
        val numeric: Boolean,
    ) : TheatreOutbound
}

sealed interface TheatreInbound {
    /** Answer to a request. A `get` result carries the feature; a `set` result usually only `"ACK"`. */
    data class Result(val id: Int?, val feature: String?, val value: String?) : TheatreInbound {
        val isAck: Boolean get() = value.equals(ACK, ignoreCase = true)
    }

    /** Unsolicited state change, pushed on the open socket. */
    data class Notify(val feature: String, val value: String) : TheatreInbound

    data class Malformed(val line: String, val reason: String) : TheatreInbound

    companion object {
        const val ACK = "ACK"
    }
}
