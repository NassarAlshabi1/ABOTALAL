package com.example.data.repository

/** Best-effort scrubbing for diagnostic log text before it can leave the device. */
internal object RouterDiagnosticRedactor {
    private val assignment = Regex(
        "(?i)([\\\"']?(?:password|passwd|passphrase|eap[-_ ]?password|secret|token|api[-_ ]?key|private[-_ ]?key|community|authorization|wpa[-_ ]?psk|psk|key)[\\\"']?\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s\\\"',;}]+)"
    )
    private val bearerOrBasic = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/-]+=*")

    fun redact(value: String): String = assignment.replace(
        bearerOrBasic.replace(value) { "${it.groupValues[1]} [REDACTED]" }
    ) { match -> "${match.groupValues[1]}[REDACTED]" }

    fun redactAndLimit(value: String, maxLength: Int): String = redact(value).take(maxLength.coerceAtLeast(0))
}
