package com.example.data.repository

import org.json.JSONObject

data class AiFix(
    val title: String,
    val risk: String,
    val summary: String,
    val commands: List<String>
)

internal data class SafeAiCommand(val path: String, val params: Map<String, String>)

internal object AiFixGuard {
    const val MAX_FIXES = 5
    const val MAX_COMMANDS_PER_FIX = 3
    const val MAX_TOTAL_COMMANDS = 20

    private const val DEFAULT_PROSE = "تم توليد مقترحات إصلاح؛ راجعها أدناه."

    private val allowedPaths = setOf(
        "/ip/service/set",
        "/ip/firewall/filter/add",
        "/ip/firewall/filter/set",
        "/ip/firewall/filter/enable",
        "/ip/firewall/filter/disable",
        "/ip/firewall/filter/move",
        "/ip/firewall/address-list/add",
        "/ip/dns/set",
        "/ip/dhcp-server/set",
        "/ip/dhcp-server/network/set",
        "/ip/hotspot/set",
        "/ip/hotspot/profile/set",
        "/ip/cloud/set",
        "/interface/set",
        "/ip/neighbor/discovery-settings/set",
        "/snmp/set",
        "/ip/ssh/set",
        "/ip/proxy/set",
        "/tool/mac-server/set",
        "/ip/upnp/set",
        "/tool/bandwidth-server/set",
        "/ip/socks/set",
        "/ppp/profile/set",
        "/queue/simple/add",
        "/queue/simple/set",
        "/queue/type/set",
        "/system/ntp/client/set",
        "/system/clock/set",
        "/ipv6/firewall/filter/add"
    )

    private val verbTokens = setOf("set", "add", "enable", "disable", "move", "make-static", "unset")
    private val commandPathPattern = Regex("^/[a-z0-9][a-z0-9/_-]*$")
    private val paramKeyPattern = Regex("^[a-z][a-z0-9-]*$")
    private val fencePattern = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val forbiddenKeyFragments = listOf("password", "passphrase", "secret", "private", "source", "policy", "script")

    fun parseFixes(raw: String): List<AiFix> {
        val jsonText = extractJson(raw) ?: return emptyList()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("fixes") ?: return emptyList()
        val fixes = mutableListOf<AiFix>()
        var totalCommands = 0
        for (i in 0 until array.length()) {
            if (fixes.size >= MAX_FIXES || totalCommands >= MAX_TOTAL_COMMANDS) break
            val obj = array.optJSONObject(i) ?: continue
            val rawCommands = obj.optJSONArray("commands") ?: continue
            val commands = mutableListOf<String>()
            for (j in 0 until rawCommands.length()) {
                if (commands.size >= MAX_COMMANDS_PER_FIX || totalCommands >= MAX_TOTAL_COMMANDS) break
                val candidate = rawCommands.optString(j, "").trim()
                val safe = runCatching { validateCommand(candidate) }.getOrNull() ?: continue
                commands += render(safe)
                totalCommands++
            }
            if (commands.isEmpty()) continue
            fixes += AiFix(
                title = obj.optString("title", "").trim().take(120).ifBlank { "إصلاح" },
                risk = normalizeRisk(obj.optString("risk", "")),
                summary = obj.optString("summary", "").trim().take(400),
                commands = commands
            )
        }
        return fixes
    }

    fun stripFixBlock(raw: String): String {
        var prose = raw
        fencePattern.findAll(raw)
            .filter { it.groupValues[1].contains("\"fixes\"") }
            .forEach { prose = prose.replace(it.value, "") }
        val trimmed = prose.trim()
        if (trimmed.startsWith("{")) {
            val root = runCatching { JSONObject(trimmed) }.getOrNull()
            if (root != null && root.has("fixes")) {
                return root.optString("summary").trim().ifBlank { DEFAULT_PROSE }
            }
        }
        return trimmed.ifBlank { DEFAULT_PROSE }
    }

    fun buildSafePlan(fixes: List<AiFix>): List<SafeAiCommand> {
        val plan = mutableListOf<SafeAiCommand>()
        for (fix in fixes) {
            for (command in fix.commands) plan += validateCommand(command)
        }
        require(plan.isNotEmpty()) { "لا توجد أوامر صالحة للتطبيق." }
        require(plan.size <= MAX_TOTAL_COMMANDS) { "عدد الأوامر أكبر من الحد المسموح ($MAX_TOTAL_COMMANDS)." }
        return plan
    }

    private fun extractJson(raw: String): String? {
        val fenced = fencePattern.findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter { it.contains("\"fixes\"") }
            .toList()
        if (fenced.isNotEmpty()) return fenced.last()
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.contains("\"fixes\"")) return trimmed
        return null
    }

    private fun validateCommand(raw: String): SafeAiCommand {
        require(raw.isNotBlank()) { "أمر فارغ." }
        require(raw.length <= 300 && !raw.contains('\n') && !raw.contains('\r')) { "الأمر طويل أو متعدد الأسطر." }
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
        require(tokens.size >= 2) { "صيغة الأمر غير صالحة." }

        var path = tokens[0]
        var paramsStart = 1
        if (path !in allowedPaths) {
            val verb = tokens.getOrNull(1).orEmpty()
            val merged = "$path/$verb"
            require(verb in verbTokens && merged in allowedPaths) { "مسار الأمر غير مسموح للتطبيق التلقائي." }
            path = merged
            paramsStart = 2
        }
        require(commandPathPattern.matches(path)) { "مسار الأمر غير صالح." }

        val params = linkedMapOf<String, String>()
        for (index in paramsStart until tokens.size) {
            val token = tokens[index]
            val separator = token.indexOf('=')
            require(separator > 0) { "كل وسيط يجب أن يكون بالشكل key=value." }
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            require(paramKeyPattern.matches(key)) { "اسم الوسيط '$key' غير صالح." }
            require(forbiddenKeyFragments.none { key.contains(it) }) { "الوسيط '$key' ممنوع لأسباب أمنية." }
            require(value.isNotEmpty() && value.length <= 120) { "قيمة الوسيط '$key' غير صالحة." }
            require(value.none { it.isWhitespace() || it == '$' || it == ';' || it == '`' || it == '"' || it == '\'' }) {
                "قيمة الوسيط '$key' تحتوي محارف ممنوعة."
            }
            params[key] = value
        }
        require(params.isNotEmpty()) { "الأمر بلا وسائط." }

        if (path == "/ip/service/set" && params["disabled"].equals("yes", ignoreCase = true)) {
            val numbers = params["numbers"].orEmpty().split(',').map { it.trim().lowercase() }
            require(numbers.none { it == "api" || it == "api-ssl" }) { "لا يمكن تعطيل خدمة API التي يستخدمها التطبيق." }
        }
        if (path.endsWith("/firewall/filter/add") || path.endsWith("/firewall/filter/set")) {
            if (params["action"].equals("drop", ignoreCase = true) &&
                params["chain"].equals("input", ignoreCase = true) &&
                params["src-address"] == null && params["in-interface"] == null
            ) {
                throw IllegalArgumentException("قاعدة drop عامة على input قد تقطع إدارة الراوتر؛ مرفوضة.")
            }
        }
        return SafeAiCommand(path, params)
    }

    private fun render(command: SafeAiCommand): String = buildString {
        append(command.path)
        command.params.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
    }

    private fun normalizeRisk(raw: String): String = when (raw.trim().lowercase()) {
        "low", "منخفض", "منخفضة" -> "low"
        "medium", "متوسط", "متوسطة" -> "medium"
        "high", "مرتفع", "مرتفعة", "عالي", "عالية" -> "high"
        else -> "unknown"
    }
}
