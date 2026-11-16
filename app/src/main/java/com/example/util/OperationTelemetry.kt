package com.example.util

import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * Local-only, allowlisted operational events. Never pass user/router supplied text,
 * URLs, command arguments, exception messages, credentials, or response bodies here.
 */
object OperationTelemetry {
    private const val TAG = "NetCraftTelemetry"
    private const val SCHEMA_VERSION = 1
    private val sessionId: String by lazy { UUID.randomUUID().toString() }
    private val lastRateLimitedEventAt = ConcurrentHashMap<String, Long>()

    enum class Event(val wireName: String) {
        APP_STARTED("app_started"),
        APP_CRASH("app_crash"),
        APP_CHECK_RESULT("app_check_result"),
        CONNECTION_RESULT("router_connection_result"),
        AUTH_RESULT("router_auth_result"),
        VERSION_RESULT("router_version_result"),
        COMMAND_RESULT("router_command_result"),
        SYNC_RESULT("router_sync_result"),
        USER_MANAGER_BATCH_RESULT("user_manager_batch_result"),
        BACKUP_DOWNLOAD_RESULT("backup_download_result"),
        AI_REQUEST_RESULT("ai_request_result"),
        TELEGRAM_REQUEST_RESULT("telegram_request_result"),
        CRYPTO_RESULT("crypto_result"),
        EXPORT_RESULT("export_result"),
        STORAGE_MIGRATION("storage_migration")
    }

    enum class Operation(val wireName: String) {
        APP_STARTUP("app_startup"),
        APP_CHECK_INIT("app_check_init"),
        ROUTER_CONNECT("router_connect"),
        ROUTER_AUTH("router_auth"),
        ROUTER_VERSION_DETECT("router_version_detect"),
        ROUTER_COMMAND("router_command"),
        ROUTER_DATA_SYNC("router_data_sync"),
        DASHBOARD_REFRESH("dashboard_refresh"),
        HOTSPOT_USERS_SYNC("hotspot_users_sync"),
        HOTSPOT_ACTIVE_SYNC("hotspot_active_sync"),
        HOTSPOT_PROFILES_SYNC("hotspot_profiles_sync"),
        INTERFACES_SYNC("interfaces_sync"),
        USER_MANAGER_PROFILES_SYNC("user_manager_profiles_sync"),
        DESIGN_LOAD("design_load"),
        DESIGN_SELECT("design_select"),
        DESIGN_SAVE("design_save"),
        DESIGN_DELETE("design_delete"),
        DESIGN_CREATE("design_create"),
        USER_MANAGER_BATCH("user_manager_batch"),
        BACKUP_DOWNLOAD("backup_download"),
        AI_API_PROBE("ai_api_probe"),
        AI_COMPLETION("ai_completion"),
        TELEGRAM_SEND("telegram_send"),
        TELEGRAM_PROBE("telegram_probe"),
        CRYPTO_ENCRYPT("crypto_encrypt"),
        CRYPTO_DECRYPT("crypto_decrypt"),
        EXPORT("export"),
        DATABASE_MIGRATION("database_migration")
    }

    enum class Outcome(val wireName: String) {
        STARTED("started"), SUCCESS("success"), PARTIAL("partial"), FAILURE("failure"),
        TIMEOUT("timeout"), CANCELLED("cancelled")
    }

    enum class ErrorClass(val wireName: String) {
        NETWORK("network"), TIMEOUT("timeout"), AUTHENTICATION("authentication"),
        PERMISSION("permission"), ROUTER_REJECTED("router_rejected"), TLS("tls"),
        VALIDATION("validation"), PARSE("parse"), STORAGE("storage"), CRYPTO("crypto"),
        CANCELLED("cancelled"), UNKNOWN("unknown")
    }

    enum class Transport(val wireName: String) {
        NATIVE_API("native_api"), NATIVE_API_SSL("native_api_ssl"), REST("rest"),
        FTP("ftp"), HTTPS("https"), LOCAL("local")
    }

    enum class CommandFamily(val wireName: String) {
        SYSTEM("system"), USER_MANAGER("user_manager"), HOTSPOT("hotspot"),
        FIREWALL("firewall"), INTERFACE("interface"), ROUTING("routing"),
        QUEUE("queue"), FILES("files"), OTHER("other")
    }

    fun newOperationId(): String = UUID.randomUUID().toString()

    /** Monotonic clock usable both on Android and from plain JVM unit tests. */
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L

    fun classify(error: Throwable): ErrorClass = when (error) {
        is SocketTimeoutException -> ErrorClass.TIMEOUT
        is SSLException -> ErrorClass.TLS
        is SecurityException -> ErrorClass.PERMISSION
        is IllegalArgumentException -> ErrorClass.VALIDATION
        is UnknownHostException, is ConnectException, is IOException -> ErrorClass.NETWORK
        is kotlinx.coroutines.CancellationException -> ErrorClass.CANCELLED
        is org.json.JSONException -> ErrorClass.PARSE
        else -> ErrorClass.UNKNOWN
    }

    fun commandFamily(command: String): CommandFamily = when {
        command.startsWith("/system/") -> CommandFamily.SYSTEM
        command.contains("user-manager") -> CommandFamily.USER_MANAGER
        command.startsWith("/ip/hotspot") -> CommandFamily.HOTSPOT
        command.startsWith("/ip/firewall") -> CommandFamily.FIREWALL
        command.startsWith("/interface") -> CommandFamily.INTERFACE
        command.startsWith("/routing") -> CommandFamily.ROUTING
        command.startsWith("/queue") -> CommandFamily.QUEUE
        command.startsWith("/file") -> CommandFamily.FILES
        else -> CommandFamily.OTHER
    }

    fun isMutationCommand(command: String): Boolean {
        val verb = command.trim().substringAfterLast('/').lowercase()
        // Read-only operations are safe to retry after a timeout. Anything not
        // explicitly known to be read-only is treated as a mutation, so new
        // commands fail closed instead of being replayed accidentally.
        val readOnlyVerbs = setOf(
            "print", "get", "monitor", "listen", "ping", "traceroute", "export",
            "check-for-updates"
        )
        return verb !in readOnlyVerbs
    }

    fun record(
        event: Event,
        operation: Operation,
        operationId: String = newOperationId(),
        outcome: Outcome,
        errorClass: ErrorClass? = null,
        transport: Transport? = null,
        commandFamily: CommandFamily? = null,
        durationMs: Long? = null,
        requestedCount: Int? = null,
        affectedCount: Int? = null,
        bytesTransferred: Long? = null,
        storageVersion: Int? = null,
        routerOsMajor: Int? = null,
        retryCount: Int? = null,
        minIntervalMs: Long = 0
    ) {
        if (minIntervalMs > 0) {
            val key = "${event.wireName}:${operation.wireName}:${outcome.wireName}:${errorClass?.wireName ?: "none"}"
            val now = elapsedRealtime()
            val previous = lastRateLimitedEventAt.put(key, now)
            if (previous != null && now - previous < minIntervalMs) return
        }
        val payload = encode(
            event, operation, operationId, outcome, errorClass, transport, commandFamily,
            durationMs, requestedCount, affectedCount, bytesTransferred, storageVersion, routerOsMajor, retryCount
        )
        val priority = when (outcome) {
            Outcome.FAILURE, Outcome.TIMEOUT -> Log.WARN
            Outcome.PARTIAL -> Log.WARN
            else -> Log.INFO
        }
        runCatching { Log.println(priority, TAG, payload) }
    }

    internal fun encode(
        event: Event,
        operation: Operation,
        operationId: String,
        outcome: Outcome,
        errorClass: ErrorClass? = null,
        transport: Transport? = null,
        commandFamily: CommandFamily? = null,
        durationMs: Long? = null,
        requestedCount: Int? = null,
        affectedCount: Int? = null,
        bytesTransferred: Long? = null,
        storageVersion: Int? = null,
        routerOsMajor: Int? = null,
        retryCount: Int? = null
    ): String {
        val safeDuration = durationMs?.coerceAtLeast(0)
        val safeRequested = requestedCount?.coerceIn(0, MAX_RECORDED_COUNT)
        val safeAffected = affectedCount?.coerceIn(0, MAX_RECORDED_COUNT)
        val fields = mutableListOf<Pair<String, String>>()
        fun text(name: String, value: String) { fields += name to quoteJson(value) }
        fun number(name: String, value: Number?) { value?.let { fields += name to it.toString() } }
        number("schema_version", SCHEMA_VERSION)
        text("event", event.wireName)
        text("session_id", sessionId)
        text("operation_id", operationId)
        number("timestamp_ms", System.currentTimeMillis())
        text("operation", operation.wireName)
        text("outcome", outcome.wireName)
        errorClass?.let { text("error_class", it.wireName) }
        transport?.let { text("transport", it.wireName) }
        commandFamily?.let { text("command_family", it.wireName) }
        number("duration_ms", safeDuration)
        number("requested_count", safeRequested)
        number("affected_count", safeAffected)
        bytesTransferred?.coerceAtLeast(0)?.let { number("bytes_transferred", it) }
        storageVersion?.takeIf { it in 1..100 }?.let { number("storage_version", it) }
        routerOsMajor?.takeIf { it in 6..9 }?.let { number("routeros_major", it) }
        retryCount?.coerceIn(0, 3)?.let { number("retry_count", it) }
        return fields.joinToString(prefix = "{", postfix = "}") { (key, value) -> "${quoteJson(key)}:$value" }
    }

    private fun quoteJson(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private const val MAX_RECORDED_COUNT = 100_000
}
