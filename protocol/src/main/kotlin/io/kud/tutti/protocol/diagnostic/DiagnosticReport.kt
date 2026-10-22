package io.kud.tutti.protocol.diagnostic

import io.kud.tutti.protocol.net.Redact

/**
 * The plain-text report "Copier le rapport" puts on the clipboard. Every line goes through
 * [Redact.text] with [sensitive] (hosts, PSK, pin, identifying values the devices sent),
 * whatever the step or the environment claims to contain: nothing is trusted to be clean.
 */
object DiagnosticReport {
    fun render(
        environment: List<Pair<String, String>>,
        steps: List<StepResult>,
        sensitive: Collection<String>,
    ): String {
        val lines = mutableListOf<String>()
        lines += "tutti diagnostic"
        environment.forEach { (k, v) -> lines += "$k: $v" }
        for (plane in Plane.entries) {
            val inPlane = steps.filter { it.plane == plane }
            if (inPlane.isEmpty()) continue
            lines += ""
            lines += "[${plane.label}]"
            for (step in inPlane) {
                val time = step.millis?.let { "$it ms" } ?: "-"
                val head = buildString {
                    append(statusLabel(step.status).padEnd(8))
                    append(step.id.padEnd(22))
                    append(' ')
                    append(time.padStart(8))
                    step.errorClass?.let { append("  ").append(it) }
                }
                lines += head.trimEnd()
                step.message?.let { lines += "        $it" }
                step.facts.forEach { (k, v) -> lines += "        $k: $v" }
            }
        }
        val counts = StepStatus.entries.associateWith { s -> steps.count { it.status == s } }
        lines += ""
        lines += "summary: ${counts[StepStatus.OK]} ok, ${counts[StepStatus.FAILED]} failed, ${counts[StepStatus.SKIPPED]} skipped" +
            (counts[StepStatus.PENDING]!! + counts[StepStatus.RUNNING]!!).let { if (it > 0) ", $it not run" else "" }
        return lines.joinToString("\n") { Redact.text(it, sensitive) }
    }

    fun statusLabel(status: StepStatus): String = when (status) {
        StepStatus.PENDING -> "PENDING"
        StepStatus.RUNNING -> "RUNNING"
        StepStatus.OK -> "OK"
        StepStatus.FAILED -> "FAILED"
        StepStatus.SKIPPED -> "SKIPPED"
    }
}
