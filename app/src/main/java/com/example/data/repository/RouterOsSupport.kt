package com.example.data.repository

/** Single source of truth for the RouterOS generation this build supports.
 *
 *  The legacy User Manager and the maintenance wizard were validated against
 *  RouterOS 6.49.21, but all 6.x releases share the v6 API/menu family. Any
 *  non-6.x release is rejected before the connection is published. */
internal object RouterOsSupport {
    const val TARGET_VERSION: String = "6.49.21"

    fun parseVersion(version: String): Pair<Int, Int>? {
        val match = Regex("^\\s*(\\d+)\\.(\\d+)").find(version) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        return major to minor
    }

    fun isSupported(version: String): Boolean {
        val (major, _) = parseVersion(version) ?: return false
        return major == 6
    }

    fun isExactTarget(version: String): Boolean =
        version.trim().startsWith(TARGET_VERSION)

    fun unsupportedMessage(version: String): String {
        val shown = version.trim().ifBlank { "غير معروف" }
        return "إصدار RouterOS المكتشف ($shown) غير مدعوم. هذا الإصدار من التطبيق يعمل فقط مع RouterOS v6 (6.x)؛ تم التحقق على $TARGET_VERSION."
    }
}
