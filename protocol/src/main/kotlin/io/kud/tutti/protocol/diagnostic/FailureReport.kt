package io.kud.tutti.protocol.diagnostic

import io.kud.tutti.protocol.net.Redact

/**
 * The technical detail behind a failure the user sees, for "Copy details": what was tried, the
 * outcome, the steps taken, the exception chain and where it was thrown, then the context (app
 * version, link states). The whole text goes through [Redact.text] with [sensitive] (hosts, PSK…),
 * so nothing a caller passes is trusted to be clean. Plain text, no network: it only ever reaches
 * the clipboard.
 */
object FailureReport {
    fun render(
        what: String,
        outcome: String? = null,
        steps: List<String> = emptyList(),
        error: Throwable? = null,
        context: List<Pair<String, String>> = emptyList(),
        sensitive: Collection<String> = emptyList(),
    ): String {
        val lines = mutableListOf<String>()
        lines += "tutti: $what"
        if (outcome != null) lines += "outcome: $outcome"
        if (steps.isNotEmpty()) lines += "steps: ${steps.joinToString(" · ")}"
        if (error != null) lines += causes(error)
        context.forEach { (key, value) -> lines += "$key: $value" }
        return Redact.text(lines.joinToString("\n"), sensitive)
    }

    /**
     * Exception classes and messages down the cause chain ("A: msg ← B: msg"), then up to [frames]
     * frames from where the deepest one was thrown. Not scrubbed on its own: [render] scrubs.
     */
    fun causes(error: Throwable, frames: Int = FRAMES): String {
        val chain = generateSequence(error) { it.cause }.take(MAX_CAUSES).toList()
        val head = chain.joinToString(" ← ") { cause ->
            val name = cause::class.java.simpleName.ifEmpty { cause::class.java.name }
            listOfNotNull(name, cause.message).joinToString(": ")
        }
        val trace = chain.last().stackTrace.take(frames).joinToString("") { "\n  at $it" }
        return "error: $head$trace"
    }

    private const val MAX_CAUSES = 4
    private const val FRAMES = 6
}
