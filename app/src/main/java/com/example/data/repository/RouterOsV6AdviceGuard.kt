package com.example.data.repository

/** A narrow safety gate for well-known v7-only command paths. It is not a full
 *  RouterOS grammar or a substitute for reviewing generated commands manually. */
internal object RouterOsV6AdviceGuard {
    private val v7OnlyPatterns = listOf(
        Regex("(?i)(?:^|[\\s`])/(?:interface/wifi|routing/(?:table|bgp|ospf|filter)|user-manager)(?:/|\\s|$)")
    )

    fun findKnownV7Paths(version: String, advice: String): List<String> {
        if (!version.trim().startsWith("6.")) return emptyList()
        val matches = linkedSetOf<String>()
        v7OnlyPatterns.forEach { pattern ->
            pattern.findAll(advice).forEach { matches += it.value.trim().trim('`', ' ') }
        }
        return matches.toList()
    }
}
