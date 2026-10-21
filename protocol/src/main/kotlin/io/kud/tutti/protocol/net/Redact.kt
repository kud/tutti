package io.kud.tutti.protocol.net

/** Secrets never reach a log line. Use this for anything that might hold one. */
object Redact {
    fun secret(value: String?): String = if (value.isNullOrEmpty()) "<empty>" else "<redacted:${value.length}>"
}
