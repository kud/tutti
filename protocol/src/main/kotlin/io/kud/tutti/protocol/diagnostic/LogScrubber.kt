package io.kud.tutti.protocol.diagnostic

import io.kud.tutti.protocol.net.Redact

/**
 * The scrub every line of the event log and every "Report on GitHub" link goes through, on top of
 * [Redact.text]: [Redact] knows the shapes of values (addresses, MACs, ids, long hex and tokens);
 * this also knows where secrets sit in text whatever their shape: a short PSK after "psk=", a
 * cookie header, a certificate block, a device name in a JSON reply. Known values ([known]: hosts,
 * the PSK, the IP-control cookie, device names) go to [Redact] whole.
 *
 * Applied when a line is written, never when it is read, so the event log never holds raw text;
 * and once more on whatever leaves the phone, since nothing a caller passes is trusted to be clean.
 */
object LogScrubber {
    fun scrub(input: String, known: Collection<String> = emptyList()): String {
        var out = PEM.replace(input, "<certificate>")
        out = HEADER.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + MARKER }
        out = FIELD.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + MARKER }
        return Redact.text(out, known)
    }

    private const val MARKER = "<redacted>"

    /** A PEM block (certificate, key), BEGIN to END, or a BEGIN left open by a cut line. */
    private val PEM = Regex("""-----BEGIN [A-Z0-9 ]+-----(?:[\s\S]*?-----END [A-Z0-9 ]+-----|[\s\S]*$)""")

    /** Headers whose whole value is a secret, to the end of the line: cookies, auth, the PSK header. */
    private val HEADER = Regex(
        """(?im)^(\s*(?:set-cookie|cookie|authorization|proxy-authorization|x-auth-psk))(\s*:\s*)\S.*$""",
    )

    /**
     * `key=value`, `key: value` and `"key": "value"` where the key names a secret or an identifier.
     * Only after `=` or `:`, so prose ("host unreachable", "pin mismatch") keeps its words.
     * Android compiles this with ICU too: no class opens with a colon (see [Redact]).
     */
    private val FIELD = Regex(
        """(?i)(["']?\b(?:psk|pre-?shared-?key|auth-?psk|token|access_token|refresh_token|id_token|password|passwd|secret|""" +
            """cookie|auth|authorization|session_?key|pin|pairing_?key|pairing_?secret|secret_?key|private_?key|""" +
            """serial|serial_?number|serialnumber|mac|mac_?address|macaddr|host|hostname|ip|ip_?address|address|""" +
            """device_?name|name|nickname|ssid|bssid|duid|android_?id)\b["']?)(\s*[=:]\s*)""" +
            """("[^"\n]*"|'[^'\n]*'|[^\s,;&}\]]+)""",
    )

    internal val patterns: List<Regex> get() = listOf(PEM, HEADER, FIELD)
}
