package com.example.data.repository

/** A narrow safety gate for well-known version-specific command paths (v6-only
 *  and v7-only). It is not a full RouterOS grammar or a substitute for manually
 *  reviewing generated commands. */
internal object RouterOsV6AdviceGuard {
    private val v7OnlyPatterns = listOf(
        Regex("(?i)(?:^|[\\s`])/(?:interface/wifi|routing/(?:table|bgp|ospf|filter|rule)|user-manager)(?:/|\\s|$)")
    )
    private val v6OnlyPatterns = listOf(
        Regex("(?i)(?:^|[\\s`])/tool/user-manager(?:/|\\s|$)")
    )

    private fun majorOf(version: String): Int? =
        Regex("^\\s*(\\d+)(?:\\.|\\s|$)").find(version)?.groupValues?.getOrNull(1)?.toIntOrNull()

    fun findKnownV7Paths(version: String, advice: String): List<String> {
        if (majorOf(version) != 6) return emptyList()
        return matches(v7OnlyPatterns, advice)
    }

    /** v7 removed the legacy `/tool/user-manager` menu. Blocking it prevents
     *  v6-only commands from being offered on v7 routers. */
    fun findKnownV6Paths(version: String, advice: String): List<String> {
        if (majorOf(version) != 7) return emptyList()
        return matches(v6OnlyPatterns, advice)
    }

    fun findIncompatiblePaths(version: String, advice: String): List<String> =
        (findKnownV7Paths(version, advice) + findKnownV6Paths(version, advice)).distinct()

    private fun matches(patterns: List<Regex>, advice: String): List<String> {
        val found = linkedSetOf<String>()
        patterns.forEach { pattern ->
            pattern.findAll(advice).forEach { found += it.value.trim().trim('`', ' ') }
        }
        return found.toList()
    }
}
