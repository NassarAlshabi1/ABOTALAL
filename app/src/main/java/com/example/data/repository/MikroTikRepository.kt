package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.data.local.ActivityLog
import com.example.data.local.ActivityLogDao
import com.example.data.local.RouterConnection
import com.example.data.local.*
import com.example.data.model.*
import com.example.util.OperationTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MikroTikRepository(
    private val context: Context,
    private val activityLogDao: ActivityLogDao,
    private val cacheDao: com.example.data.local.CacheDao
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Current State Flow
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()


    private val _currentConnection = MutableStateFlow<RouterConnection?>(null)
    val currentConnection: StateFlow<RouterConnection?> = _currentConnection.asStateFlow()

    private val _routerStats = MutableStateFlow<RouterStats?>(null)
    val routerStats: StateFlow<RouterStats?> = _routerStats.asStateFlow()

    private val _hotspotUsers = MutableStateFlow<List<HotspotUser>>(emptyList())
    val hotspotUsers: StateFlow<List<HotspotUser>> = _hotspotUsers.asStateFlow()

    private val _activeHotspot = MutableStateFlow<List<HotspotActive>>(emptyList())
    val activeHotspot: StateFlow<List<HotspotActive>> = _activeHotspot.asStateFlow()

    private val _hotspotProfiles = MutableStateFlow<List<HotspotProfile>>(emptyList())
    val hotspotProfiles: StateFlow<List<HotspotProfile>> = _hotspotProfiles.asStateFlow()

    private val _userManagerUsers = MutableStateFlow<List<UserManagerUser>>(emptyList())
    val userManagerUsers: StateFlow<List<UserManagerUser>> = _userManagerUsers.asStateFlow()

    private val _userManagerProfiles = MutableStateFlow<List<UserManagerProfile>>(emptyList())
    val userManagerProfiles: StateFlow<List<UserManagerProfile>> = _userManagerProfiles.asStateFlow()

    private val _interfaces = MutableStateFlow<List<InterfaceStats>>(emptyList())
    val interfaces: StateFlow<List<InterfaceStats>> = _interfaces.asStateFlow()

    private val _operationStatus = MutableStateFlow<String?>(null)
    val operationStatus: StateFlow<String?> = _operationStatus.asStateFlow()

    private val _cloudInfo = MutableStateFlow(MikroTikCloudInfo())
    val cloudInfo: StateFlow<MikroTikCloudInfo> = _cloudInfo.asStateFlow()

    private val _cloudBackups = MutableStateFlow<List<MikroTikCloudBackup>>(emptyList())
    val cloudBackups: StateFlow<List<MikroTikCloudBackup>> = _cloudBackups.asStateFlow()

    // Native Socket API instance for port 8728. @Volatile because it's written
    // from connect/disconnect coroutines and read from many independent
    // repositoryScope.launch{} coroutines on the IO dispatcher pool — without
    // this, a coroutine could observe a stale/half-published reference after
    // disconnect() runs on a different thread (e.g. mid maintenance-wizard).
    @Volatile
    private var nativeApi: MikroTikNativeApi? = null

    // Real RouterOS version detected from /system/resource/print after login.
    // rosMajor = 6 → user-manager lives under /tool/user-manager
    // rosMajor = 7 → user-manager lives under /user-manager
    // 0 = not detected yet (fall back to probing both paths).
    @Volatile
    private var rosMajor: Int = 0

    // Full version string reported by /system/resource/print. Used by the
    // support gate so an unsupported release cannot reach mutating screens.
    @Volatile
    private var routerVersion: String = ""

    /** RouterOS v6 is the only supported generation, so User Manager always
     *  lives under the legacy `/tool/user-manager` menu. Never probe or send a
     *  v7 `/user-manager` command on a v6 router. */
    private val umBase: String
        get() = "/tool/user-manager"

    private val umBases: List<String>
        get() = listOf(umBase)

    @Volatile
    private var cachedCustomer: Pair<String, String>? = null

    private fun detectRouterOsVersion(api: MikroTikNativeApi, connection: RouterConnection) {
        val operationId = OperationTelemetry.newOperationId()
        val startedAt = OperationTelemetry.elapsedRealtime()
        try {
            val res = api.execute("/system/resource/print").firstOrNull()
            val ver = res?.get("version") ?: ""
            routerVersion = ver
            val major = ver.split('.').firstOrNull()?.trim()?.toIntOrNull() ?: 0
            if (major > 0) {
                rosMajor = major
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.VERSION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_VERSION_DETECT,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    transport = OperationTelemetry.Transport.NATIVE_API,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt,
                    routerOsMajor = major
                )
            } else {
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.VERSION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_VERSION_DETECT,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.FAILURE,
                    errorClass = OperationTelemetry.ErrorClass.PARSE,
                    transport = OperationTelemetry.Transport.NATIVE_API,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
            }
        } catch (e: Exception) {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.VERSION_RESULT,
                operation = OperationTelemetry.Operation.ROUTER_VERSION_DETECT,
                operationId = operationId,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(e),
                transport = OperationTelemetry.Transport.NATIVE_API,
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
        }
    }

    private val lastIfaceCounters = mutableMapOf<String, Triple<Long, Long, Long>>()

    private fun speedFromDelta(name: String, rx: Long, tx: Long): Pair<Double, Double> {
        val now = System.currentTimeMillis()
        val prev = lastIfaceCounters.put(name, Triple(rx, tx, now)) ?: return 0.0 to 0.0
        val dtSec = (now - prev.third) / 1000.0
        if (dtSec <= 0) return 0.0 to 0.0
        val rxKbps = (rx - prev.first).coerceAtLeast(0) * 8.0 / dtSec / 1024.0
        val txKbps = (tx - prev.second).coerceAtLeast(0) * 8.0 / dtSec / 1024.0
        return rxKbps to txKbps
    }

    // Dynamic ticking job for dashboard updates
    private var isTickerRunning = false

    // Plain OkHttp client with normal system trust — no trust-all certs, no
    // hostname-verifier bypass. Router HTTPS endpoints with self-signed certs must
    // be accessed over http (REST on port 80) or with a properly imported cert.
    private var okHttpClient: OkHttpClient? = null

    init {
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // Connect real router (Supports Port 8728 native binary API & HTTP/REST)
    // Transport fallback chain: native API first (non-web ports), then REST on the
    // configured web port, and finally native on the default API port 8728 when the
    // configured port is a web port. Authentication failures are reported directly
    // (the same credentials would fail on every transport).
    fun connectToRouter(connection: RouterConnection, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val operationId = OperationTelemetry.newOperationId()
            _operationStatus.value = "جاري الاتصال بـ ${connection.host}..."
            if (connection.host.isBlank()) {
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.CONNECTION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.FAILURE,
                    errorClass = OperationTelemetry.ErrorClass.VALIDATION
                )
                onError("عنوان الراوتر لا يمكن أن يكون فارغاً")
                return@launch
            }

            val webPorts = listOf(80, 443, 8080, 8081)
            var lastError = ""

            // 1. Native MikroTik API Socket on the configured port (any non-web port,
            //    or the explicit API ports even if they collide with the web list).
            if (connection.port == 8728 || connection.port == 8729 ||
                connection.port !in webPorts) {
                val result = tryNativeConnect(connection, operationId)
                when (result) {
                    is NativeConnectResult.Success -> {
                        launch(Dispatchers.Main) { onSuccess() }
                        return@launch
                    }
                    is NativeConnectResult.AuthError -> {
                        launch(Dispatchers.Main) { onError(result.message) }
                        return@launch
                    }
                    is NativeConnectResult.Unsupported -> {
                        launch(Dispatchers.Main) { onError(result.message) }
                        return@launch
                    }
                    is NativeConnectResult.Failure -> lastError = result.message
                }
            }

            // 2. RouterOS v6 has no REST API. A web port in the saved connection
            //    therefore falls through to the native API on 8728 below instead
            //    of silently using v7-only /rest endpoints.
            if (connection.port in webPorts) {
                lastError = "REST غير مدعوم على RouterOS v6؛ جارٍ محاولة API على المنفذ 8728"
            }

            // 3. Configured port was a web port — try the default native API port 8728.
            if (connection.port in webPorts) {
                val nativeAttempt = tryNativeConnect(connection, operationId, overridePort = 8728)
                when (nativeAttempt) {
                    is NativeConnectResult.Success -> {
                        launch(Dispatchers.Main) { onSuccess() }
                        return@launch
                    }
                    is NativeConnectResult.AuthError -> {
                        launch(Dispatchers.Main) { onError(nativeAttempt.message) }
                        return@launch
                    }
                    is NativeConnectResult.Unsupported -> {
                        launch(Dispatchers.Main) { onError(nativeAttempt.message) }
                        return@launch
                    }
                    is NativeConnectResult.Failure -> lastError = nativeAttempt.message
                }
            }

            launch(Dispatchers.Main) { onError(lastError.ifBlank { "تعذر الاتصال بالراوتر" }) }
        }
    }

    private sealed class NativeConnectResult {
        data class Success(val api: MikroTikNativeApi) : NativeConnectResult()
        data class AuthError(val message: String) : NativeConnectResult()
        data class Failure(val message: String) : NativeConnectResult()
        data class Unsupported(val message: String) : NativeConnectResult()
    }

    private fun tryNativeConnect(
        connection: RouterConnection,
        operationId: String,
        overridePort: Int? = null
    ): NativeConnectResult {
        val startedAt = OperationTelemetry.elapsedRealtime()
        val port = overridePort ?: connection.port
        val sslEnabled = if (overridePort != null) port == 8729 else connection.useSsl
        return try {
            val api = MikroTikNativeApi(
                host = connection.host,
                port = port,
                connectTimeoutMs = 12000,
                commandTimeoutMs = 25000,
                useSsl = if (overridePort != null) port == 8729 else connection.useSsl
            )
            val (loginSuccess, loginMsg) = api.login(connection.username, connection.password)
            if (loginSuccess) {
                // Detect the version BEFORE publishing the connection so an
                // unsupported router never appears as briefly connected.
                rosMajor = 0
                routerVersion = ""
                detectRouterOsVersion(api, connection)

                if (!RouterOsSupport.isSupported(routerVersion)) {
                    val message = RouterOsSupport.unsupportedMessage(routerVersion)
                    insertActivityLog("تسجيل الدخول", "تم رفض الاتصال: $message", connection.host)
                    try { api.close() } catch (_: Exception) {}
                    rosMajor = 0
                    return NativeConnectResult.Unsupported(message)
                }

                nativeApi = api
                _currentConnection.value =
                    if (overridePort != null) connection.copy(port = port) else connection
                _isConnected.value = true

                insertActivityLog(
                    "تسجيل الدخول",
                    "تم الاتصال بنجاح بالراوتر عبر المنفذ $port" +
                        (if (rosMajor > 0) " (RouterOS v$rosMajor)" else ""),
                    connection.host
                )

                // Fetch real initial data via native commands
                fetchRealNativeData(api, _currentConnection.value!!)
                startRealtimeTicker()
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.CONNECTION_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    transport = if (sslEnabled) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt,
                    routerOsMajor = rosMajor
                )
                NativeConnectResult.Success(api)
            } else {
                // The router answered and rejected the credentials — same credentials
                // would fail on any transport, so report directly.
                try { api.close() } catch (_: Exception) {}
                if (loginMsg.contains("كلمة المرور") || loginMsg.contains("المستخدم") ||
                    loginMsg.contains("not allowed", ignoreCase = true) ||
                    loginMsg.contains("invalid", ignoreCase = true)
                ) {
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.CONNECTION_RESULT,
                        operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                        operationId = operationId,
                        outcome = OperationTelemetry.Outcome.FAILURE,
                        errorClass = OperationTelemetry.ErrorClass.AUTHENTICATION,
                        transport = if (sslEnabled) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                        durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                    )
                    NativeConnectResult.AuthError(loginMsg.ifBlank { "خطأ في اسم المستخدم أو كلمة المرور" })
                } else {
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.CONNECTION_RESULT,
                        operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                        operationId = operationId,
                        outcome = OperationTelemetry.Outcome.FAILURE,
                        errorClass = OperationTelemetry.ErrorClass.UNKNOWN,
                        transport = if (sslEnabled) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                        durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                    )
                    val base = loginMsg.ifBlank { "فشل تسجيل الدخول عبر المنفذ $port" }
                    val hint = connectionFailureHint(base, sslEnabled, port)
                    NativeConnectResult.Failure(base + if (hint.isNotBlank()) "\n\n$hint" else "")
                }
            }
        } catch (e: Exception) {
            val errorClass = OperationTelemetry.classify(e)
            OperationTelemetry.record(
                event = OperationTelemetry.Event.CONNECTION_RESULT,
                operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                operationId = operationId,
                outcome = if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                errorClass = errorClass,
                transport = if (sslEnabled) OperationTelemetry.Transport.NATIVE_API_SSL else OperationTelemetry.Transport.NATIVE_API,
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
            val base = "تعذر الاتصال بـ ${connection.host}:$port عبر API. ${e.message ?: ""}".trim()
            val hint = connectionFailureHint(base, sslEnabled, port)
            NativeConnectResult.Failure(base + if (hint.isNotBlank()) "\n\n$hint" else "")
        }
    }

    /** @return (success, errorMessage) — attempts the REST API on the connection port. */
    private fun tryRestConnect(connection: RouterConnection, operationId: String): Pair<Boolean, String> {
        val startedAt = OperationTelemetry.elapsedRealtime()
        return try {
            val protocol = if (connection.useSsl) "https" else "http"
            val url = "$protocol://${connection.host}:${connection.port}/rest/system/resource"

            val credential = Credentials.basic(connection.username, connection.password)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", credential)
                .build()

            okHttpClient?.newCall(request)?.execute().use { response ->
                if (response != null && response.isSuccessful) {
                    val restBody = response.body?.string().orEmpty()
                    val restVersion = runCatching {
                        org.json.JSONObject(restBody).optString("version")
                    }.getOrDefault("")
                    if (!RouterOsSupport.isSupported(restVersion)) {
                        val message = RouterOsSupport.unsupportedMessage(restVersion)
                        insertActivityLog("تسجيل الدخول", "تم رفض الاتصال عبر REST: $message", connection.host)
                        OperationTelemetry.record(
                            event = OperationTelemetry.Event.CONNECTION_RESULT,
                            operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                            operationId = operationId,
                            outcome = OperationTelemetry.Outcome.FAILURE,
                            errorClass = OperationTelemetry.ErrorClass.VALIDATION,
                            transport = OperationTelemetry.Transport.REST,
                            durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                        )
                        return@use Pair(false, message)
                    }
                    _currentConnection.value = connection
                    _isConnected.value = true
                    routerVersion = restVersion
                    rosMajor = 6

                    insertActivityLog("تسجيل الدخول", "تم الاتصال بنجاح بالراوتر ${connection.name} (${connection.host}) عبر REST", connection.host)

                    fetchRealRouterData(connection)
                    startRealtimeTicker()
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.CONNECTION_RESULT,
                        operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                        operationId = operationId,
                        outcome = OperationTelemetry.Outcome.SUCCESS,
                        transport = OperationTelemetry.Transport.REST,
                        durationMs = OperationTelemetry.elapsedRealtime() - startedAt,
                        routerOsMajor = rosMajor
                    )
                    Pair(true, "")
                } else {
                    val code = response?.code ?: 0
                    val msg = when (code) {
                        401 -> "خطأ في اسم المستخدم أو كلمة المرور (غير مصرح)"
                        404 -> "لم يتم العثور على واجهة REST API. يرجى تفعيلها في الراوتر (ROS v7) أو استخدام منفذ API 8728"
                        else -> "فشل الاتصال بالراوتر. رمز الخطأ: $code"
                    }
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.CONNECTION_RESULT,
                        operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                        operationId = operationId,
                        outcome = OperationTelemetry.Outcome.FAILURE,
                        errorClass = if (code == 401) OperationTelemetry.ErrorClass.AUTHENTICATION else OperationTelemetry.ErrorClass.NETWORK,
                        transport = OperationTelemetry.Transport.REST,
                        durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                    )
                    Pair(false, msg)
                }
            }
        } catch (ioe: IOException) {
            val errorClass = OperationTelemetry.classify(ioe)
            OperationTelemetry.record(
                event = OperationTelemetry.Event.CONNECTION_RESULT,
                operation = OperationTelemetry.Operation.ROUTER_CONNECT,
                operationId = operationId,
                outcome = if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                errorClass = errorClass,
                transport = OperationTelemetry.Transport.REST,
                durationMs = OperationTelemetry.elapsedRealtime() - startedAt
            )
            Pair(false, "تعذر الوصول إلى الآيبي ${connection.host} عبر REST. تأكد من اتصال هاتفك بشبكة الراوتر.")
        }
    }

    /**
     * Connection test that matches the real login path.
     *
     * A TCP socket probe alone is not proof that login will work: it ignores
     * credentials, RouterOS version and SSL. When credentials are supplied we
     * therefore perform a real RouterOS API login with the same SSL setting and
     * verify the detected version, so "test succeeded" means "login will work".
     */
    fun testConnection(
        host: String,
        port: Int,
        username: String,
        password: String,
        useSsl: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        repositoryScope.launch {
            val socketResult = MikroTikNativeApi.testSocket(host, port)
            if (!socketResult.first) {
                launch(Dispatchers.Main) { onResult(false, socketResult.second) }
                return@launch
            }
            if (username.isBlank()) {
                launch(Dispatchers.Main) {
                    onResult(
                        true,
                        socketResult.second +
                            "\n\nملاحظة: هذا اختبار وصول للمنفذ فقط. أدخل اسم المستخدم وكلمة المرور ليختبر التطبيق تسجيل الدخول الفعلي وإصدار RouterOS."
                    )
                }
                return@launch
            }
            val api = MikroTikNativeApi(
                host = host,
                port = port,
                connectTimeoutMs = 12000,
                commandTimeoutMs = 25000,
                useSsl = useSsl
            )
            try {
                val (loginOk, loginMsg) = api.login(username, password)
                if (!loginOk) {
                    val hint = connectionFailureHint(loginMsg, useSsl, port)
                    launch(Dispatchers.Main) {
                        onResult(
                            false,
                            "فشل اختبار تسجيل الدخول الفعلي:\n$loginMsg" +
                                if (hint.isNotBlank()) "\n\n$hint" else ""
                        )
                    }
                    return@launch
                }
                val version = try {
                    api.execute("/system/resource/print").firstOrNull()?.get("version").orEmpty()
                } catch (_: Exception) {
                    ""
                }
                if (!RouterOsSupport.isSupported(version)) {
                    launch(Dispatchers.Main) {
                        onResult(false, RouterOsSupport.unsupportedMessage(version))
                    }
                } else {
                    launch(Dispatchers.Main) {
                        onResult(
                            true,
                            "✅ تم اختبار تسجيل الدخول الفعلي بنجاح.\n\n" +
                                "الراوتر: $host:$port\n" +
                                "الإصدار: ${version.trim()}\n" +
                                "التشفير: ${if (useSsl) "SSL" else "بدون SSL"}\n\n" +
                                "يمكنك الآن الضغط على «تسجيل الدخول»."
                        )
                    }
                }
            } catch (e: Exception) {
                val hint = connectionFailureHint(e.localizedMessage.orEmpty(), useSsl, port)
                launch(Dispatchers.Main) {
                    onResult(
                        false,
                        "تعذر اختبار تسجيل الدخول: ${e.localizedMessage ?: "خطأ غير معروف"}" +
                            if (hint.isNotBlank()) "\n\n$hint" else ""
                    )
                }
            } finally {
                try { api.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Actionable hint for the most common transport mistakes on custom API
     * ports: testing plain TCP while login uses SSL (or the opposite), or a
     * TCP port that is not a RouterOS API service at all.
     */
    private fun connectionFailureHint(message: String, useSsl: Boolean, port: Int): String {
        val lower = message.lowercase(Locale.ROOT)
        return when {
            lower.contains("ssl") || lower.contains("handshake") ||
                lower.contains("certificat") || lower.contains("pkix") ||
                lower.contains("trust anchor") -> {
                if (useSsl) {
                    "💡 فشل اتصال SSL. إذا كان المنفذ $port غير مشفر، أطفئ خيار «تشفير SSL» ثم أعد الاختبار."
                } else {
                    "💡 قد يتطلب المنفذ $port اتصال SSL. فعّل خيار «تشفير SSL» ثم أعد الاختبار."
                }
            }
            lower.contains("unexpected") || lower.contains("استجابة غير متوقعة") -> {
                "💡 المنفذ $port يقبل اتصال TCP لكنه لا يستجيب كخدمة RouterOS API. " +
                    "تأكد أنه المنفذ المحوَّل إلى API 8728 (أو 8729 مع SSL) وليس منفذ خدمة أخرى."
            }
            lower.contains("timed out") || lower.contains("timeout") ||
                lower.contains("eof") || lower.contains("reset") -> {
                "💡 قبل المنفذ $port اتصال TCP لكنه لم يرد كخدمة RouterOS API خلال المهلة. " +
                    "تأكد من صحة المنفذ المحوَّل إلى API، وجرّب تبديل خيار «تشفير SSL»."
            }
            else -> ""
        }
    }

    // Native data retrieval via Port 8728
    private fun fetchRealNativeData(api: MikroTikNativeApi, connection: RouterConnection) {
        repositoryScope.launch {
            hydrateCachedUsers(connection.host)
            try {
                // 1. System Resource
                val resList = api.execute("/system/resource/print")
                val res = resList.firstOrNull() ?: emptyMap()
                val totalMem = res["total-memory"]?.toLongOrNull() ?: 0L
                val freeMem = res["free-memory"]?.toLongOrNull() ?: 0L
                val cpu = res["cpu-load"]?.toIntOrNull() ?: 0
                val uptime = res["uptime"] ?: "-"
                val version = res["version"] ?: "-"
                val board = res["board-name"] ?: "-"

                // 2. Hotspot User Profiles
                var hsProfList = api.execute("/ip/hotspot/user/profile/print")
                if (hsProfList.isEmpty()) {
                    hsProfList = api.execute("/ip/hotspot/profile/print")
                }
                val hsProfiles = if (hsProfList.isNotEmpty()) {
                    hsProfList.map {
                        HotspotProfile(
                            name = it["name"] ?: "default",
                            sharedUsers = it["shared-users"] ?: "1",
                            rateLimit = it["rate-limit"] ?: "unlimited",
                            keepaliveTimeout = it["keepalive-timeout"] ?: "2m"
                        )
                    }
                } else emptyList()
                if (hsProfiles.isNotEmpty()) {
                    _hotspotProfiles.value = hsProfiles
                    cacheDao.insertHotspotProfiles(hsProfiles.map {
                        CachedHotspotProfile(
                            name = it.name,
                            routerHost = connection.host,
                            sharedUsers = it.sharedUsers,
                            rateLimit = it.rateLimit,
                            keepaliveTimeout = it.keepaliveTimeout
                        )
                    })
                }

                // 3. Hotspot Users
                val hsUsers = mapHotspotRows(api.execute("/ip/hotspot/user/print"))
                if (hsUsers.isNotEmpty()) {
                    _hotspotUsers.value = hsUsers
                    cacheHotspotSnapshot(connection.host, hsUsers)
                }

                // 4. Active Hotspot
                val actList = api.execute("/ip/hotspot/active/print")
                val activeHotspot = actList.map {
                    HotspotActive(
                        id = it[".id"] ?: UUID.randomUUID().toString(),
                        user = it["user"] ?: "unknown",
                        address = it["address"] ?: "",
                        macAddress = it["mac-address"] ?: "",
                        uptime = it["uptime"] ?: "",
                        bytesIn = it["bytes-in"]?.toLongOrNull() ?: 0L,
                        bytesOut = it["bytes-out"]?.toLongOrNull() ?: 0L
                    )
                }
                _activeHotspot.value = activeHotspot

                // 5. User Manager Profiles — always the v6 legacy menu.
                val umProfList = api.execute("$umBase/profile/print")
                if (umProfList.isEmpty()) {
                    insertActivityLog("باقات اليوزرمانجر", api.lastError
                        ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر", connection.host)
                }
                val limitations = umReadProfileLimitations(api)

                val umProfiles = umProfList.map { p ->
                    val name = p["name"] ?: p["name-for-users"] ?: "default"
                    val lim = limitations[name] ?: emptyMap()
                    val validity = p["validity"] ?: p["starts-at"] ?: "30d"
                    val priceVal = p["price"]?.toDoubleOrNull()?.toInt() ?: 0
                    val dlLimit = lim["download-limit"] ?: lim["transfer-limit"] ?: lim["rate-limit-rx"] ?: p["download-limit"] ?: "unlimited"
                    val upLimit = lim["uptime-limit"] ?: lim["uptime"] ?: p["uptime-limit"] ?: "unlimited"
                    UserManagerProfile(
                        name = name,
                        validity = validity,
                        price = priceVal,
                        downloadLimit = dlLimit,
                        uptimeLimit = upLimit,
                        templateName = name,
                        sharedUsers = p["override-shared-users"]?.toIntOrNull() ?: 1
                    )
                }
                if (umProfiles.isNotEmpty()) {
                    _userManagerProfiles.value = umProfiles
                    cacheDao.insertUserManagerProfiles(umProfiles.map {
                        CachedUserManagerProfile(
                            name = it.name,
                            routerHost = connection.host,
                            validity = it.validity,
                            price = it.price,
                            sharedUsers = it.sharedUsers,
                            downloadLimit = it.downloadLimit,
                            uptimeLimit = it.uptimeLimit,
                            templateName = it.templateName
                        )
                    })
                }

                // 6. User Manager Users — same version-aware base selection
                val umList = api.execute("$umBase/user/print")
                val umUsers = applyRouterExpiry(api, mapUserManagerRows(umList))
                if (umUsers.isNotEmpty()) {
                    _userManagerUsers.value = umUsers
                    cacheUserManagerSnapshot(connection.host, umUsers)
                }

                // 7. Interfaces
                val ifList = api.execute("/interface/print")
                if (ifList.isNotEmpty()) {
                    _interfaces.value = ifList.map {
                        val rx = it["rx-byte"]?.toLongOrNull() ?: 0L
                        val tx = it["tx-byte"]?.toLongOrNull() ?: 0L
                        val name = it["name"] ?: "ether1"
                        val (rs, ts) = speedFromDelta(name, rx, tx)
                        InterfaceStats(
                            name = name,
                            type = it["type"] ?: "ether",
                            rxByte = rx,
                            txByte = tx,
                            rxSpeedKbps = rs,
                            txSpeedKbps = ts,
                            running = it["running"] == "true"
                        )
                    }
                }

                _routerStats.value = RouterStats(
                    cpuUsage = cpu,
                    totalMemoryBytes = totalMem,
                    freeMemoryBytes = freeMem,
                    boardName = board,
                    version = version,
                    uptime = uptime,
                    activeHotspotUsers = activeHotspot.size,
                    activeUserManagerUsers = if (umUsers.isNotEmpty()) umUsers.size else _userManagerUsers.value.size,
                    totalInterfaces = _interfaces.value.size.coerceAtLeast(1)
                )

                // Persist stats and users to local Room database cache
                cacheDao.insertStats(
                    CachedRouterStats(
                        routerHost = connection.host,
                        cpuUsage = cpu,
                        totalMemoryBytes = totalMem,
                        freeMemoryBytes = freeMem,
                        boardName = board,
                        version = version,
                        uptime = uptime,
                        activeHotspotUsers = activeHotspot.size,
                        activeUserManagerUsers = if (umUsers.isNotEmpty()) umUsers.size else _userManagerUsers.value.size,
                        totalInterfaces = _interfaces.value.size.coerceAtLeast(1),
                        lastSyncTime = System.currentTimeMillis()
                    )
                )

            } catch (e: Exception) {
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.SYNC_RESULT,
                    operation = OperationTelemetry.Operation.ROUTER_DATA_SYNC,
                    outcome = OperationTelemetry.Outcome.FAILURE,
                    errorClass = OperationTelemetry.classify(e),
                    transport = OperationTelemetry.Transport.NATIVE_API,
                    minIntervalMs = 60_000
                )
            }
        }
    }

    // ==========================================
    // LOCAL CARD CACHE (delta sync)
    // Synced/generated cards are persisted in Room. A later sync only writes
    // rows that are new or actually changed, and removes rows deleted on the
    // router, instead of rewriting the whole table every time.
    // ==========================================

    private fun mapUserManagerRows(rows: List<Map<String, String>>): List<UserManagerUser> =
        rows.map { mapUserManagerRow(it) }

    private fun mapUserManagerRow(it: Map<String, String>): UserManagerUser {
        val uname = it["name"] ?: it["username"] ?: ""
        val assignedProfile = it["profile"]
        val actualProfile = it["actual-profile"]
        val uptime = it["uptime-used"] ?: it["uptime"]
        return UserManagerUser(
            username = uname,
            password = it["password"] ?: uname,
            profile = actualProfile?.takeIf { p -> p.isNotBlank() } ?: assignedProfile ?: "default",
            active = it["disabled"] != "true",
            uptimeUsed = uptime ?: "0s",
            downloadLimit = it["download-limit"] ?: it["limit-bytes-total"] ?: "unlimited",
            serialNumber = it["comment"]?.takeIf { c -> c.startsWith("SN:") || c.all { ch -> ch.isDigit() } } ?: (it[".id"] ?: "-"),
            comment = it["comment"] ?: "",
            profileExpired = computeProfileExpired(assignedProfile, actualProfile, uptime)
        )
    }

    private fun mapHotspotRows(rows: List<Map<String, String>>): List<HotspotUser> = rows.map {
        HotspotUser(
            id = it[".id"] ?: UUID.randomUUID().toString(),
            name = it["name"] ?: "",
            password = it["password"] ?: "",
            profile = it["profile"] ?: "default",
            limitUptime = it["limit-uptime"] ?: "unlimited",
            comment = it["comment"] ?: "",
            disabled = it["disabled"] == "true",
            bytesOut = it["bytes-out"]?.toLongOrNull() ?: 0L,
            bytesIn = it["bytes-in"]?.toLongOrNull() ?: 0L
        )
    }

    private fun toCachedUserManagerUser(user: UserManagerUser, host: String) =
        CachedUserManagerUser(
            username = user.username,
            routerHost = host,
            password = user.password,
            profile = user.profile,
            active = user.active,
            uptimeUsed = user.uptimeUsed,
            downloadLimit = user.downloadLimit,
            profileExpired = user.profileExpired
        )

    private fun toCachedHotspotUser(user: HotspotUser, host: String) =
        CachedHotspotUser(
            id = user.id,
            routerHost = host,
            name = user.name,
            password = user.password,
            profile = user.profile,
            limitUptime = user.limitUptime,
            comment = user.comment,
            disabled = user.disabled,
            bytesOut = user.bytesOut,
            bytesIn = user.bytesIn
        )

    private suspend fun cacheUserManagerSnapshot(host: String, users: List<UserManagerUser>) {
        if (users.isEmpty()) return
        val cached = cacheDao.getUserManagerUsers(host)
        val existing = cached.associateBy { it.username }
        val rows = users.map { toCachedUserManagerUser(it, host) }
        val changed = rows.filter { existing[it.username] != it }
        if (changed.isNotEmpty()) cacheDao.insertUserManagerUsers(changed)
        val current = rows.map { it.username }.toSet()
        val removed = existing.keys.filter { it !in current }
        // Room/SQLite caps bound variables, so delete in chunks.
        removed.chunked(400).forEach { cacheDao.deleteUserManagerUsers(host, it) }
    }

    private suspend fun cacheHotspotSnapshot(host: String, users: List<HotspotUser>) {
        if (users.isEmpty()) return
        val cached = cacheDao.getHotspotUsers(host)
        val existing = cached.associateBy { it.id }
        val rows = users.map { toCachedHotspotUser(it, host) }
        val changed = rows.filter { existing[it.id] != it }
        if (changed.isNotEmpty()) cacheDao.insertHotspotUsers(changed)
        val current = rows.map { it.id }.toSet()
        val removed = existing.keys.filter { it !in current }
        removed.chunked(400).forEach { cacheDao.deleteHotspotUsers(host, it) }
    }

    private suspend fun hydrateCachedUsers(host: String) {
        if (_userManagerUsers.value.isEmpty()) {
            val cached = cacheDao.getUserManagerUsers(host)
            if (cached.isNotEmpty()) {
                _userManagerUsers.value = cached.map {
                    UserManagerUser(
                        username = it.username,
                        password = it.password,
                        profile = it.profile,
                        active = it.active,
                        uptimeUsed = it.uptimeUsed,
                        downloadLimit = it.downloadLimit,
                        profileExpired = it.profileExpired
                    )
                }
            }
        }
        if (_hotspotUsers.value.isEmpty()) {
            val cached = cacheDao.getHotspotUsers(host)
            if (cached.isNotEmpty()) {
                _hotspotUsers.value = cached.map {
                    HotspotUser(
                        id = it.id,
                        name = it.name,
                        password = it.password,
                        profile = it.profile,
                        limitUptime = it.limitUptime,
                        comment = it.comment,
                        disabled = it.disabled,
                        bytesOut = it.bytesOut,
                        bytesIn = it.bytesIn
                    )
                }
            }
        }
    }

    /** Fast card-only sync used right before bulk voucher generation: one API
     *  call, delta Room write, no full-database delays. */
    fun syncVoucherCards(useUserManager: Boolean, onComplete: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val connection = _currentConnection.value
            if (api == null || connection == null) {
                launch(Dispatchers.Main) { onError("اتصل بالراوتر أولاً") }
                return@launch
            }
            try {
                if (useUserManager) {
                    val rows = api.execute("$umBase/user/print")
                    val users = mapUserManagerRows(rows)
                    if (users.isNotEmpty()) {
                        _userManagerUsers.value = users
                        cacheUserManagerSnapshot(connection.host, users)
                    }
                } else {
                    val users = mapHotspotRows(api.execute("/ip/hotspot/user/print"))
                    if (users.isNotEmpty()) {
                        _hotspotUsers.value = users
                        cacheHotspotSnapshot(connection.host, users)
                    }
                }
                launch(Dispatchers.Main) { onComplete() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "تعذرت مزامنة كروت الراوتر") }
            }
        }
    }

    // Synchronize Database flow matching Screenshot 5
    fun syncDatabase(
        onProgress: (current: Int, total: Int, stepName: String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        repositoryScope.launch {
            val conn = _currentConnection.value
            val host = conn?.host ?: ""
            try {
                val api = nativeApi

                // STEP 1: Sync UserManager Profiles & Users
                launch(Dispatchers.Main) { onProgress(0, 4, "جاري مزامنة باقات وكروت ومستخدمي اليوزرمانجر...") }

                if (api != null && _isConnected.value) {
                    // Fetch Profiles — v6 legacy menu only.
                    val umProfList = api.execute("$umBase/profile/print")
                    if (umProfList.isEmpty()) {
                        insertActivityLog("باقات اليوزرمانجر", api.lastError
                            ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر",
                            _currentConnection.value?.host ?: "")
                    }
                    val limitations = umReadProfileLimitations(api)

                    if (umProfList.isNotEmpty()) {
                        val umProfiles = umProfList.map { p ->
                            val name = p["name"] ?: p["name-for-users"] ?: "default"
                            val lim = limitations[name] ?: emptyMap()
                            UserManagerProfile(
                                name = name,
                                validity = p["validity"] ?: p["starts-at"] ?: "30d",
                                price = p["price"]?.toDoubleOrNull()?.toInt() ?: 0,
                                downloadLimit = lim["download-limit"] ?: lim["transfer-limit"] ?: lim["rate-limit-rx"] ?: p["download-limit"] ?: "unlimited",
                                uptimeLimit = lim["uptime-limit"] ?: lim["uptime"] ?: p["uptime-limit"] ?: "unlimited",
                                templateName = name,
                                sharedUsers = p["override-shared-users"]?.toIntOrNull() ?: 1
                            )
                        }
                        _userManagerProfiles.value = umProfiles
                        cacheDao.insertUserManagerProfiles(umProfiles.map {
                            CachedUserManagerProfile(
                                name = it.name,
                                routerHost = host,
                                validity = it.validity,
                                price = it.price,
                                sharedUsers = it.sharedUsers,
                                downloadLimit = it.downloadLimit,
                                uptimeLimit = it.uptimeLimit,
                                templateName = it.templateName
                            )
                        })
                    }

                    // Fetch Users / Cards — version-aware base
                    val umList = api.execute("$umBase/user/print")
                    if (umList.isNotEmpty()) {
                        val umUsers = applyRouterExpiry(api, mapUserManagerRows(umList))
                        _userManagerUsers.value = umUsers
                        cacheUserManagerSnapshot(host, umUsers)
                    }
                }

                // STEP 2: Sync Hotspot Profiles & Users
                launch(Dispatchers.Main) { onProgress(1, 4, "جاري مزامنة باقات وكروت ومستخدمي الهوتسبوت...") }

                if (api != null && _isConnected.value) {
                    var hsProfList = api.execute("/ip/hotspot/user/profile/print")
                    if (hsProfList.isEmpty()) {
                        hsProfList = api.execute("/ip/hotspot/profile/print")
                    }
                    if (hsProfList.isNotEmpty()) {
                        val hsProfiles = hsProfList.map {
                            HotspotProfile(
                                name = it["name"] ?: "default",
                                sharedUsers = it["shared-users"] ?: "1",
                                rateLimit = it["rate-limit"] ?: "unlimited",
                                keepaliveTimeout = it["keepalive-timeout"] ?: "2m"
                            )
                        }
                        _hotspotProfiles.value = hsProfiles
                        cacheDao.insertHotspotProfiles(hsProfiles.map {
                            CachedHotspotProfile(
                                name = it.name,
                                routerHost = host,
                                sharedUsers = it.sharedUsers,
                                rateLimit = it.rateLimit,
                                keepaliveTimeout = it.keepaliveTimeout
                            )
                        })
                    }

                    val hsUsers = mapHotspotRows(api.execute("/ip/hotspot/user/print"))
                    if (hsUsers.isNotEmpty()) {
                        _hotspotUsers.value = hsUsers
                        cacheHotspotSnapshot(host, hsUsers)
                    }

                    val actList = api.execute("/ip/hotspot/active/print")
                    if (actList.isNotEmpty()) {
                        _activeHotspot.value = actList.map {
                            HotspotActive(
                                id = it[".id"] ?: UUID.randomUUID().toString(),
                                user = it["user"] ?: "unknown",
                                address = it["address"] ?: "",
                                macAddress = it["mac-address"] ?: "",
                                uptime = it["uptime"] ?: "",
                                bytesIn = it["bytes-in"]?.toLongOrNull() ?: 0L,
                                bytesOut = it["bytes-out"]?.toLongOrNull() ?: 0L
                            )
                        }
                    }
                }

                // STEP 3: Sync Router System Resources & Interfaces
                launch(Dispatchers.Main) { onProgress(2, 4, "جاري مزامنة موارد النظام والواجهات...") }

                if (api != null && _isConnected.value) {
                    val ifList = api.execute("/interface/print")
                    if (ifList.isNotEmpty()) {
                        _interfaces.value = ifList.map {
                            val rx = it["rx-byte"]?.toLongOrNull() ?: 0L
                            val tx = it["tx-byte"]?.toLongOrNull() ?: 0L
                            val name = it["name"] ?: "ether1"
                            val (rs, ts) = speedFromDelta(name, rx, tx)
                            InterfaceStats(
                                name = name,
                                type = it["type"] ?: "ether",
                                rxByte = rx,
                                txByte = tx,
                                rxSpeedKbps = rs,
                                txSpeedKbps = ts,
                                running = it["running"] == "true"
                            )
                        }
                    }

                    val res = api.execute("/system/resource/print").firstOrNull()
                    if (res != null) {
                        val freeMem = (res["free-memory"]?.toLongOrNull() ?: 0L)
                        val totalMem = (res["total-memory"]?.toLongOrNull() ?: 0L)
                        val cpu = res["cpu-load"]?.toIntOrNull() ?: 0
                        val board = res["board-name"] ?: "-"
                        val ver = res["version"] ?: "-"
                        val up = res["uptime"] ?: "-"

                        _routerStats.value = RouterStats(
                            cpuUsage = cpu,
                            totalMemoryBytes = totalMem,
                            freeMemoryBytes = freeMem,
                            boardName = board,
                            version = ver,
                            uptime = up,
                            activeHotspotUsers = _activeHotspot.value.size,
                            activeUserManagerUsers = _userManagerUsers.value.count { it.active },
                            totalInterfaces = _interfaces.value.size.coerceAtLeast(1)
                        )

                        cacheDao.insertStats(
                            CachedRouterStats(
                                routerHost = host,
                                cpuUsage = cpu,
                                totalMemoryBytes = totalMem,
                                freeMemoryBytes = freeMem,
                                boardName = board,
                                version = ver,
                                uptime = up,
                                activeHotspotUsers = _activeHotspot.value.size,
                                activeUserManagerUsers = _userManagerUsers.value.count { it.active },
                                totalInterfaces = _interfaces.value.size.coerceAtLeast(1),
                                lastSyncTime = System.currentTimeMillis()
                            )
                        )
                    }
                }

                // STEP 4: Local Cache Finalization
                launch(Dispatchers.Main) { onProgress(3, 4, "جاري حفظ وتثبيت كافة البيانات محلياً...") }

                insertActivityLog("مزامنة قاعدة البيانات", "تمت مزامنة بيانات الراوتر والهوتسبوت واليوزرمانجر محلياً بنجاح", host)
                launch(Dispatchers.Main) {
                    onProgress(4, 4, "اكتملت المزامنة بنجاح تام!")
                    onComplete()
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError("خطأ أثناء المزامنة: ${e.localizedMessage}") }
            }
        }
    }

    // Logoff connection
    fun disconnect() {
        isTickerRunning = false
        cachedCustomer = null
        rosMajor = 0
        routerVersion = ""
        _isConnected.value = false
        try {
            nativeApi?.close()
        } catch (_: Exception) {}
        nativeApi = null
        lastIfaceCounters.clear()
        _currentConnection.value = null
        _routerStats.value = null
        // Drop every router-scoped snapshot so the next login can never render
        // the previous router's users, profiles, interfaces, or cloud data.
        _hotspotUsers.value = emptyList()
        _activeHotspot.value = emptyList()
        _hotspotProfiles.value = emptyList()
        _userManagerUsers.value = emptyList()
        _userManagerProfiles.value = emptyList()
        _interfaces.value = emptyList()
        _cloudInfo.value = MikroTikCloudInfo()
        _cloudBackups.value = emptyList()
        insertActivityLog("تسجيل الخروج", "تم فصل الاتصال بالراوتر", "")
    }

    /** Cancels every in-flight/queued repositoryScope coroutine (ticker, pending
     *  fetches, etc.) and closes the socket. Call from the owning ViewModel's
     *  onCleared() — repositoryScope's SupervisorJob has no other owner, so
     *  without this it would otherwise outlive the ViewModel if one were ever
     *  recreated within the same process (e.g. tests, or a future scoping
     *  change) instead of living exactly as long as the process. */
    fun shutdown() {
        isTickerRunning = false
        try {
            nativeApi?.close()
        } catch (_: Exception) {}
        nativeApi = null
        repositoryScope.cancel()
    }

    // Insert an audit log in local Room database
    private fun insertActivityLog(action: String, details: String, routerHost: String) {
        repositoryScope.launch {
            activityLogDao.insertLog(ActivityLog(action = action, details = details, routerHost = routerHost))
        }
    }

    // Start ticker that keeps the connection alive and updates live interfaces & CPU statistics periodically
    private fun startRealtimeTicker() {
        if (isTickerRunning) return
        isTickerRunning = true
        
        repositoryScope.launch {
            var counter = 0
            while (isTickerRunning) {
                try {
                    
                        val api = nativeApi
                        val conn = _currentConnection.value
                        if (api != null && conn != null) {
                            tickNativeDashboard(api, conn, counter)
                        } else if (conn != null) {
                            fetchRealRouterDashboard(conn)
                        }
                    
                } catch (t: Throwable) {
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.SYNC_RESULT,
                        operation = OperationTelemetry.Operation.DASHBOARD_REFRESH,
                        outcome = OperationTelemetry.Outcome.FAILURE,
                        errorClass = OperationTelemetry.classify(t),
                        transport = if (nativeApi != null) OperationTelemetry.Transport.NATIVE_API else OperationTelemetry.Transport.REST,
                        minIntervalMs = 60_000
                    )
                }
                counter++
                delay(3000) // update stats & heartbeat every 3 seconds
            }
        }
    }

    private fun tickNativeDashboard(api: MikroTikNativeApi, connection: RouterConnection, counter: Int) {
        try {
            // Heartbeat + CPU / Resource update (Every 3 seconds)
            val resList = api.execute("/system/resource/print")
            if (resList.isNotEmpty()) {
                val res = resList.first()
                val totalMem = res["total-memory"]?.toLongOrNull() ?: 0L
                val freeMem = res["free-memory"]?.toLongOrNull() ?: 0L
                val cpu = res["cpu-load"]?.toIntOrNull() ?: 0
                val uptime = res["uptime"] ?: "-"
                val version = res["version"] ?: "-"
                val board = res["board-name"] ?: "-"

                _routerStats.value = RouterStats(
                    cpuUsage = cpu,
                    totalMemoryBytes = totalMem,
                    freeMemoryBytes = freeMem,
                    boardName = board,
                    version = version,
                    uptime = uptime,
                    activeHotspotUsers = _activeHotspot.value.size,
                    activeUserManagerUsers = _userManagerUsers.value.count { it.active },
                    totalInterfaces = _interfaces.value.size.coerceAtLeast(1)
                )
            }

            // Every 9 seconds: Refresh active users & interfaces
            if (counter % 3 == 0) {
                val actList = api.execute("/ip/hotspot/active/print")
                if (actList.isNotEmpty()) {
                    _activeHotspot.value = actList.map {
                        HotspotActive(
                            id = it[".id"] ?: UUID.randomUUID().toString(),
                            user = it["user"] ?: "unknown",
                            address = it["address"] ?: "",
                            macAddress = it["mac-address"] ?: "",
                            uptime = it["uptime"] ?: "",
                            bytesIn = it["bytes-in"]?.toLongOrNull() ?: 0L,
                            bytesOut = it["bytes-out"]?.toLongOrNull() ?: 0L
                        )
                    }
                }

                val ifList = api.execute("/interface/print")
                if (ifList.isNotEmpty()) {
                    _interfaces.value = ifList.map {
                        val ifName = it["name"] ?: "ether1"
                        val rx = it["rx-byte"]?.toLongOrNull() ?: 0L
                        val tx = it["tx-byte"]?.toLongOrNull() ?: 0L
                        val (rxSpd, txSpd) = speedFromDelta(ifName, rx, tx)
                        InterfaceStats(
                            name = ifName,
                            type = it["type"] ?: "ether",
                            rxByte = rx,
                            txByte = tx,
                            rxSpeedKbps = rxSpd,
                            txSpeedKbps = txSpd,
                            running = it["running"] == "true"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.SYNC_RESULT,
                operation = OperationTelemetry.Operation.DASHBOARD_REFRESH,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(e),
                transport = OperationTelemetry.Transport.NATIVE_API,
                minIntervalMs = 60_000
            )
        }
    }

    /* =========================================
       REAL MIKROTIK (ROUTEROS v7 REST API) Implementation
       ========================================= */
    private fun fetchRealRouterData(connection: RouterConnection) {
        val host = connection.host
        // Fetch background details
        repositoryScope.launch {
            hydrateCachedUsers(host)
            fetchRealUsers(connection)
            fetchRealActive(connection)
            fetchRealProfiles(connection)
            fetchRealInterfaces(connection)
            fetchRestUserManagerData(connection)
        }
    }

    /** Reads User Manager profiles & users over the v7 REST API (read-only scope:
     *  UM mutations require the native API port). 404 = router without user-manager
     *  or pre-v7 RouterOS — silently skipped, the honest empty state stays. */
    private suspend fun fetchRestUserManagerData(connection: RouterConnection) {
        val protocol = if (connection.useSsl) "https" else "http"
        val credential = Credentials.basic(connection.username, connection.password)
        val base = "$protocol://${connection.host}:${connection.port}/rest"
        try {
            val profReq = Request.Builder()
                .url("$base/user-manager/profile")
                .header("Authorization", credential)
                .build()
            okHttpClient?.newCall(profReq)?.execute().use { res ->
                if (res != null && res.isSuccessful) {
                    val array = JSONArray(res.body?.string() ?: "[]")
                    if (array.length() > 0) {
                        val profiles = mutableListOf<UserManagerProfile>()
                        for (i in 0 until array.length()) {
                            val obj = array.getJSONObject(i)
                            profiles.add(UserManagerProfile(
                                name = obj.optString("name", obj.optString("name-for-users", "default")),
                                validity = obj.optString("validity", "30d"),
                                price = obj.optString("price", "0").toDoubleOrNull()?.toInt() ?: 0,
                                sharedUsers = obj.optString("override-shared-users", "1").toIntOrNull() ?: 1,
                                templateName = obj.optString("name", "")
                            ))
                        }
                        _userManagerProfiles.value = profiles
                        cacheDao.insertUserManagerProfiles(profiles.map {
                            CachedUserManagerProfile(
                                name = it.name,
                                routerHost = connection.host,
                                validity = it.validity,
                                price = it.price,
                                sharedUsers = it.sharedUsers,
                                downloadLimit = it.downloadLimit,
                                uptimeLimit = it.uptimeLimit,
                                templateName = it.templateName
                            )
                        })
                    }
                }
            }

            val userReq = Request.Builder()
                .url("$base/user-manager/user")
                .header("Authorization", credential)
                .build()
            okHttpClient?.newCall(userReq)?.execute().use { res ->
                if (res != null && res.isSuccessful) {
                    val array = JSONArray(res.body?.string() ?: "[]")
                    val users = mutableListOf<UserManagerUser>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val assigned = obj.optString("profile", "")
                        val actual = obj.optString("actual-profile", "")
                        users.add(UserManagerUser(
                            username = obj.optString("name", ""),
                            password = obj.optString("password", ""),
                            profile = actual.ifBlank { assigned.ifBlank { "default" } },
                            active = !obj.optBoolean("disabled", false),
                            uptimeUsed = obj.optString("uptime-used", "0s"),
                            serialNumber = obj.optString(".id", "-"),
                            comment = obj.optString("comment", ""),
                            profileExpired = assigned.isNotBlank() && actual.isBlank()
                        ))
                    }
                    if (users.isNotEmpty()) {
                        _userManagerUsers.value = users
                        cacheDao.insertUserManagerUsers(
                            users.map { toCachedUserManagerUser(it, connection.host) }
                        )
                    }
                }
            }
        } catch (_: Exception) { /* User Manager may be absent or release-specific; continue sync. */ }
    }

    private fun fetchRealRouterDashboard(connection: RouterConnection) {
        // Fetch resource and status lists
        val protocol = if (connection.useSsl) "https" else "http"
        val credential = Credentials.basic(connection.username, connection.password)
        
        try {
            // Resource endpoint
            val reqResource = Request.Builder()
                .url("$protocol://${connection.host}:${connection.port}/rest/system/resource")
                .header("Authorization", credential)
                .build()

            okHttpClient?.newCall(reqResource)?.execute().use { response ->
                if (response != null && response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    
                    val cpuLoad = json.optInt("cpu-load", 0)
                    val freeMem = json.optLong("free-memory", 0)
                    val totalMem = json.optLong("total-memory", 0)
                    val uptime = json.optString("uptime", "-")
                    val board = json.optString("board-name", "-")
                    val version = json.optString("version", "-")
                    
                    _routerStats.value = RouterStats(
                        cpuUsage = cpuLoad,
                        totalMemoryBytes = totalMem,
                        freeMemoryBytes = freeMem,
                        boardName = board,
                        version = version,
                        uptime = uptime,
                        activeHotspotUsers = _activeHotspot.value.size,
                        activeUserManagerUsers = _userManagerUsers.value.count { it.active },
                        totalInterfaces = _interfaces.value.size
                    )
                }
            }
            
            // Also refresh active users & interfaces during tick
            fetchRealActive(connection)
            fetchRealInterfaces(connection)
            
        } catch (e: Exception) {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.SYNC_RESULT,
                operation = OperationTelemetry.Operation.DASHBOARD_REFRESH,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(e),
                transport = OperationTelemetry.Transport.REST,
                minIntervalMs = 60_000
            )
        }
    }

    private fun fetchRealUsers(connection: RouterConnection) {
        val protocol = if (connection.useSsl) "https" else "http"
        val credential = Credentials.basic(connection.username, connection.password)
        try {
            val req = Request.Builder()
                .url("$protocol://${connection.host}:${connection.port}/rest/ip/hotspot/user")
                .header("Authorization", credential)
                .build()

            okHttpClient?.newCall(req)?.execute().use { res ->
                if (res != null && res.isSuccessful) {
                    val array = JSONArray(res.body?.string() ?: "[]")
                    val list = mutableListOf<HotspotUser>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(HotspotUser(
                            id = obj.optString(".id", i.toString()),
                            name = obj.optString("name", ""),
                            password = obj.optString("password", ""),
                            profile = obj.optString("profile", "default"),
                            limitUptime = obj.optString("limit-uptime", "unlimited"),
                            comment = obj.optString("comment", ""),
                            disabled = obj.optBoolean("disabled", false),
                            bytesOut = obj.optLong("bytes-out", 0),
                            bytesIn = obj.optLong("bytes-in", 0)
                        ))
                    }
                    _hotspotUsers.value = list
                }
            }
        } catch (e: Exception) {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.SYNC_RESULT,
                operation = OperationTelemetry.Operation.HOTSPOT_USERS_SYNC,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(e),
                transport = OperationTelemetry.Transport.REST,
                minIntervalMs = 60_000
            )
        }
    }

    private fun fetchRealActive(connection: RouterConnection) {
        val protocol = if (connection.useSsl) "https" else "http"
        val credential = Credentials.basic(connection.username, connection.password)
        try {
            val req = Request.Builder()
                .url("$protocol://${connection.host}:${connection.port}/rest/ip/hotspot/active")
                .header("Authorization", credential)
                .build()

            okHttpClient?.newCall(req)?.execute().use { res ->
                if (res != null && res.isSuccessful) {
                    val array = JSONArray(res.body?.string() ?: "[]")
                    val list = mutableListOf<HotspotActive>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(HotspotActive(
                            id = obj.optString(".id", i.toString()),
                            user = obj.optString("user", ""),
                            address = obj.optString("address", ""),
                            macAddress = obj.optString("mac-address", ""),
                            uptime = obj.optString("uptime", "00:00:00"),
                            bytesIn = obj.optLong("bytes-in", 0),
                            bytesOut = obj.optLong("bytes-out", 0)
                        ))
                    }
                    _activeHotspot.value = list
                }
            }
        } catch (e: Exception) {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.SYNC_RESULT,
                operation = OperationTelemetry.Operation.HOTSPOT_ACTIVE_SYNC,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(e),
                transport = OperationTelemetry.Transport.REST,
                minIntervalMs = 60_000
            )
        }
    }

    private fun fetchRealProfiles(connection: RouterConnection) {
        val protocol = if (connection.useSsl) "https" else "http"
        val credential = Credentials.basic(connection.username, connection.password)
        try {
            val req = Request.Builder()
                .url("$protocol://${connection.host}:${connection.port}/rest/ip/hotspot/user/profile")
                .header("Authorization", credential)
                .build()

            okHttpClient?.newCall(req)?.execute().use { res ->
                if (res != null && res.isSuccessful) {
                    val array = JSONArray(res.body?.string() ?: "[]")
                    val list = mutableListOf<HotspotProfile>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        list.add(HotspotProfile(
                            name = obj.optString("name", "default"),
                            sharedUsers = obj.optString("shared-users", "1"),
                            rateLimit = obj.optString("rate-limit", "unlimited"),
                            keepaliveTimeout = obj.optString("keepalive-timeout", "2m")
                        ))
                    }
                    _hotspotProfiles.value = list
                }
            }
        } catch (e: Exception) {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.SYNC_RESULT,
                operation = OperationTelemetry.Operation.HOTSPOT_PROFILES_SYNC,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(e),
                transport = OperationTelemetry.Transport.REST,
                minIntervalMs = 60_000
            )
        }
    }

    private fun fetchRealInterfaces(connection: RouterConnection) {
        val protocol = if (connection.useSsl) "https" else "http"
        val credential = Credentials.basic(connection.username, connection.password)
        try {
            val req = Request.Builder()
                .url("$protocol://${connection.host}:${connection.port}/rest/interface")
                .header("Authorization", credential)
                .build()

            okHttpClient?.newCall(req)?.execute().use { res ->
                if (res != null && res.isSuccessful) {
                    val array = JSONArray(res.body?.string() ?: "[]")
                    val list = mutableListOf<InterfaceStats>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val name = obj.optString("name", "")
                        val rxB = obj.optLong("rx-byte", 0)
                        val txB = obj.optLong("tx-byte", 0)
                        val (rs, ts) = speedFromDelta(name, rxB, txB)
                        list.add(InterfaceStats(
                            name = name,
                            type = obj.optString("type", ""),
                            rxByte = rxB,
                            txByte = txB,
                            rxSpeedKbps = rs,
                            txSpeedKbps = ts,
                            running = obj.optBoolean("running", true)
                        ))
                    }
                    _interfaces.value = list
                }
            }
        } catch (e: Exception) {
            OperationTelemetry.record(
                event = OperationTelemetry.Event.SYNC_RESULT,
                operation = OperationTelemetry.Operation.INTERFACES_SYNC,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(e),
                transport = OperationTelemetry.Transport.REST,
                minIntervalMs = 60_000
            )
        }
    }

    /* Hotspot CRUD Real Action Handlers */
    fun addHotspotUser(user: HotspotUser, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val napi = nativeApi
            if (napi != null) {
                try {
                    val params = mutableMapOf("name" to user.name, "password" to user.password, "profile" to user.profile)
                    if (user.limitUptime != "unlimited" && user.limitUptime.isNotBlank()) params["limit-uptime"] = user.limitUptime
                    if (user.comment.isNotBlank()) params["comment"] = user.comment
                    if (user.disabled) params["disabled"] = "yes"
                    napi.executeStrict("/ip/hotspot/user/add", params)
                    insertActivityLog("إضافة مستخدم هوتسبوت", "تم إضافة المستخدم '${user.name}' على الراوتر", _currentConnection.value?.host ?: "")
                    _hotspotUsers.update { it + user }
                    cacheDao.insertHotspotUsers(listOf(toCachedHotspotUser(user, _currentConnection.value?.host ?: "")))
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل الإضافة على الراوتر: ${e.message}") }
                }
                return@launch
            }
                _currentConnection.value?.let { conn ->
                    val protocol = if (conn.useSsl) "https" else "http"
                    val credential = Credentials.basic(conn.username, conn.password)
                    try {
                        val payload = JSONObject().apply {
                            put("name", user.name)
                            put("password", user.password)
                            put("profile", user.profile)
                            if (user.limitUptime != "unlimited") {
                                put("limit-uptime", user.limitUptime)
                            }
                            if (user.comment.isNotBlank()) {
                                put("comment", user.comment)
                            }
                        }

                        val bodyStr = payload.toString()
                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                        val reqBody = bodyStr.toRequestBody(mediaType)

                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/ip/hotspot/user")
                            .post(reqBody)
                            .header("Authorization", credential)
                            .build()

                        okHttpClient?.newCall(req)?.execute().use { res ->
                            if (res != null && res.isSuccessful) {
                                insertActivityLog("إضافة مستخدم هوتسبوت", "تم إضافة المستخدم '${user.name}' على الراوتر", conn.host)
                                fetchRealUsers(conn)
                                launch(Dispatchers.Main) { onSuccess() }
                            } else {
                                val err = res?.body?.string() ?: ""
                                launch(Dispatchers.Main) { onError("فشل الإضافة: $err") }
                            }
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { onError("خطأ في الاتصال: ${e.message}") }
                    }
                }
            
        }
    }

    fun deleteHotspotUser(id: String, name: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val napi = nativeApi
            if (napi != null) {
                try {
                    napi.executeStrict("/ip/hotspot/user/remove", "=numbers=$id")
                    _hotspotUsers.update { it.filter { u -> u.id != id } }
                    insertActivityLog("حذف مستخدم هوتسبوت", "تم حذف المستخدم '$name' من الراوتر", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل الحذف على الراوتر: ${e.message}") }
                }
                return@launch
            }
                _currentConnection.value?.let { conn ->
                    val protocol = if (conn.useSsl) "https" else "http"
                    val credential = Credentials.basic(conn.username, conn.password)
                    try {
                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/ip/hotspot/user/$id")
                            .delete()
                            .header("Authorization", credential)
                            .build()

                        okHttpClient?.newCall(req)?.execute().use { res ->
                            if (res != null && res.isSuccessful) {
                                insertActivityLog("حذف مستخدم هوتسبوت", "تم حذف المستخدم '$name' من الراوتر", conn.host)
                                fetchRealUsers(conn)
                                launch(Dispatchers.Main) { onSuccess() }
                            } else {
                                launch(Dispatchers.Main) { onError("فشل الحذف. رمز الخطأ: ${res?.code}") }
                            }
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { onError("خطأ في الاتصال: ${e.message}") }
                    }
                }
            
        }
    }

    fun removeActiveHotspotUser(id: String, username: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val napi = nativeApi
            if (napi != null) {
                try {
                    napi.executeStrict("/ip/hotspot/active/remove", "=numbers=$id")
                    _activeHotspot.update { it.filter { u -> u.id != id } }
                    insertActivityLog("فصل مستخدم", "تم فصل المستخدم النشط '$username' من الراوتر", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل الفصل على الراوتر: ${e.message}") }
                }
                return@launch
            }
                _currentConnection.value?.let { conn ->
                    val protocol = if (conn.useSsl) "https" else "http"
                    val credential = Credentials.basic(conn.username, conn.password)
                    try {
                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/ip/hotspot/active/$id")
                            .delete()
                            .header("Authorization", credential)
                            .build()

                        okHttpClient?.newCall(req)?.execute().use { res ->
                            if (res != null && res.isSuccessful) {
                                insertActivityLog("فصل مستخدم", "تم فصل المستخدم النشط '$username' من الراوتر", conn.host)
                                fetchRealActive(conn)
                                launch(Dispatchers.Main) { onSuccess() }
                            } else {
                                launch(Dispatchers.Main) { onError("فشل الفصل. رمز الخطأ: ${res?.code}") }
                            }
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { onError("خطأ في الاتصال: ${e.message}") }
                    }
                }
            
        }
    }

    // Add profile
    fun addHotspotProfile(profile: HotspotProfile, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val napi = nativeApi
            if (napi != null) {
                try {
                    val params = mutableMapOf("name" to profile.name)
                    if (profile.rateLimit.isNotBlank() && profile.rateLimit != "unlimited") params["rate-limit"] = profile.rateLimit
                    if (profile.sharedUsers.isNotBlank()) params["shared-users"] = profile.sharedUsers
                    napi.executeStrict("/ip/hotspot/user/profile/add", params)
                    insertActivityLog("إضافة بروفايل هوتسبوت", "تم إضافة البروفايل '${profile.name}' على الراوتر", _currentConnection.value?.host ?: "")
                    _hotspotProfiles.update { it + profile }
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل إضافة البروفايل على الراوتر: ${e.message}") }
                }
                return@launch
            }
                _currentConnection.value?.let { conn ->
                    val protocol = if (conn.useSsl) "https" else "http"
                    val credential = Credentials.basic(conn.username, conn.password)
                    try {
                        val payload = JSONObject().apply {
                            put("name", profile.name)
                            put("shared-users", profile.sharedUsers)
                            if (profile.rateLimit != "unlimited") {
                                put("rate-limit", profile.rateLimit)
                            }
                            put("keepalive-timeout", profile.keepaliveTimeout)
                        }

                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                        val reqBody = payload.toString().toRequestBody(mediaType)

                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/ip/hotspot/user/profile")
                            .post(reqBody)
                            .header("Authorization", credential)
                            .build()

                        okHttpClient?.newCall(req)?.execute().use { res ->
                            if (res != null && res.isSuccessful) {
                                insertActivityLog("إضافة بروفايل", "تم إضافة بروفايل '${profile.name}' على الراوتر", conn.host)
                                fetchRealProfiles(conn)
                                launch(Dispatchers.Main) { onSuccess() }
                            } else {
                                launch(Dispatchers.Main) { onError("فشل إضافة البروفايل: ${res?.body?.string()}") }
                            }
                        }
                    } catch (e: Exception) {
                        launch(Dispatchers.Main) { onError("خطأ: ${e.message}") }
                    }
                }
            
        }
    }

    // User Manager Operations
    // Runs a write on the legacy RouterOS v6 /tool/user-manager menu and
    // throws the router's real error.
    private fun umWrite(api: MikroTikNativeApi, action: String, params: Map<String, String>): List<Map<String, String>> =
        api.executeStrict("$umBase/$action", params)

    /** Reads v6 profile limitations through the profile -> limitation link table. */
    private fun umReadProfileLimitations(api: MikroTikNativeApi): Map<String, Map<String, String>> {
        val limitationRows = api.execute("/tool/user-manager/profile/limitation/print")
            .associateBy { it["name"] ?: "" }
        return api.execute("/tool/user-manager/profile/profile-limitation/print")
            .mapNotNull { link ->
                val profile = link["profile"] ?: return@mapNotNull null
                val limitation = link["limitation"] ?: return@mapNotNull null
                limitationRows[limitation]?.let { profile to it }
            }
            .toMap()
    }

    private fun isRouterOsDuration(value: String): Boolean =
        Regex("^(?:\\d+[smhdw])+$", RegexOption.IGNORE_CASE).matches(value.trim())

    // Resolves the real user-manager customer on the router (v6: /tool/user-manager
    // customer/print). Defaults to "admin" only when the router reports none.
    private fun umResolveCustomer(api: MikroTikNativeApi): String {
        val host = _currentConnection.value?.host ?: ""
        cachedCustomer?.let { (cachedHost, customer) ->
            if (cachedHost == host) return customer
        }
        for (base in umBases) {
            try {
                val list = api.execute("$base/customer/print")
                val login = list.firstOrNull { it["login"] == "admin" }?.get("login")
                    ?: list.firstOrNull()?.get("login") ?: list.firstOrNull()?.get("name")
                if (login != null) {
                    cachedCustomer = host to login
                    return login
                }
            } catch (_: Exception) {}
        }
        cachedCustomer = host to "admin"
        return "admin"
    }

    private enum class UmFlavor { V7, V6 }

    /** This build only admits RouterOS 6.x, so the legacy User Manager menu is
     *  the only valid flavor. No v7 probe is ever sent on a v6 connection. */
    @Suppress("UNUSED_PARAMETER")
    private fun detectUmFlavor(api: MikroTikNativeApi): UmFlavor = UmFlavor.V6


    /** RouterOS v6 may encode an exhausted User Manager profile as an empty
     * actual-profile, "none", "-", or "unknown" depending on the v6 minor
     * release. All of these mean that the assigned profile is no longer active.
     */
    private fun isExpiredActualProfile(assigned: String?, actual: String?): Boolean {
        if (assigned.isNullOrBlank()) return false
        return actual.isNullOrBlank() || actual.trim().lowercase() in setOf("none", "-", "unknown")
    }

    /** Authoritative expiry signal from RouterOS: a user-profile entry in
     *  `used` state with no live entry for the same user. Works on v6 too and
     *  catches validity-expired cards whose `actual-profile` is still present. */
    private fun applyRouterExpiry(
        api: MikroTikNativeApi,
        users: List<UserManagerUser>
    ): List<UserManagerUser> {
        if (users.isEmpty()) return users
        val expired = fetchExpiredUmUsernames(api)
        if (expired.isEmpty()) return users
        return users.map { user ->
            if (user.username in expired) user.copy(profileExpired = true) else user
        }
    }

    private fun fetchExpiredUmUsernames(api: MikroTikNativeApi): Set<String> {
        for (base in umBases) {
            try {
                val rows = api.execute("$base/user-profile/print", ".proplist=user,state")
                if (rows.isEmpty()) continue
                val used = mutableSetOf<String>()
                val live = mutableSetOf<String>()
                rows.forEach { row ->
                    val user = row["user"]?.takeIf { it.isNotBlank() } ?: return@forEach
                    when (row["state"]?.trim()?.lowercase()) {
                        "running", "running-active", "waiting" -> live += user
                        "used" -> used += user
                    }
                }
                return used - live
            } catch (_: Exception) {}
        }
        return emptySet()
    }

    /** RouterOS v6 has no `profile` field on UM users: `actual-profile` is the
     * profile that is currently usable, and the router clears it when the card
     * is exhausted (the documented cleanup is `!actual-profile and
     * uptime-used>0s`). v7 keeps reporting the assigned `profile`, so the old
     * check must stay there to avoid flagging cards that were never activated. */
    private fun computeProfileExpired(assigned: String?, actual: String?, uptime: String?): Boolean {
        val actualMissing = actual.isNullOrBlank() ||
            actual.trim().lowercase() in setOf("none", "-", "unknown")
        if (!actualMissing) return false
        return if (rosMajor == 6 || umBase == "/tool/user-manager") {
            !uptime.isNullOrBlank() && uptime.trim() != "0s"
        } else {
            !assigned.isNullOrBlank()
        }
    }

    // executeStrict() never lets a raw SocketTimeoutException reach the caller —
    // MikroTikNativeApi.executeInternal() catches it, retries once internally, and
    // on failure wraps the result in a plain IOException with an Arabic "انتهت
    // مهلة..." message (or occasionally echoes the retry's own English "timed out"
    // message). Detect "likely still running on the router, not a real failure"
    // by message content instead of exception type.
    private fun isLikelyRouterTimeout(e: Exception?): Boolean {
        val msg = e?.message?.lowercase() ?: return false
        return msg.contains("انتهت مهلة") || msg.contains("timeout") || msg.contains("timed out")
    }

    // Adds one user-manager user through the RouterOS v6 legacy menu:
    // `/tool/user-manager/user/add customer= username= password=`.
    // Returns the flavor that succeeded; throws the router's meaningful error.
    private fun umAddUser(
        api: MikroTikNativeApi,
        username: String,
        password: String,
        customer: () -> String,
        extra: Map<String, String> = emptyMap()
    ): UmFlavor {
        api.executeStrict("/tool/user-manager/user/add", linkedMapOf(
            "customer" to customer(),
            "username" to username,
            "password" to password
        ) + extra)
        return UmFlavor.V6
    }

    // Attaches a profile to an existing user-manager user.
    // v7: /user-manager/user-profile/add user=<name> profile=<profile>
    // v6: /tool/user-manager/user/create-and-activate-profile customer= numbers= profile=
    private fun umAssignProfile(
        api: MikroTikNativeApi,
        flavor: UmFlavor,
        username: String,
        userId: String?,
        profile: String,
        customer: () -> String
    ) {
        when (flavor) {
            UmFlavor.V7 -> api.executeStrict("/user-manager/user-profile/add", mapOf(
                "user" to username,
                "profile" to profile
            ))
            UmFlavor.V6 -> api.executeStrict("/tool/user-manager/user/create-and-activate-profile", mapOf(
                "customer" to customer(),
                "numbers" to (userId ?: username),
                "profile" to profile
            ))
        }
    }

    // Resolves a user-manager user's real .id on the router (numbers= accepts
    // .id reliably on both ROS v6 and v7, usernames not always).
    private fun umResolveUserId(api: MikroTikNativeApi, username: String): String? {
        for (base in umBases) {
            try {
                val list = api.execute("$base/user/print")
                val hit = list.firstOrNull {
                    it["name"] == username || it["username"] == username
                }
                if (hit != null) return hit[".id"]
            } catch (_: Exception) {}
        }
        return null
    }

    // Batched version of umResolveUserId for bulk operations: one $umBase/user/print
    // per candidate menu base for the WHOLE batch, instead of one print per
    // username. The per-username version makes an N-card bulk delete/toggle cost
    // O(N^2) router round-trips (each call re-fetching the entire user table) —
    // this costs the same O(N) round-trips regardless of batch size.
    private fun umResolveUserIds(api: MikroTikNativeApi, usernames: Collection<String>): Map<String, String> {
        val remaining = usernames.toMutableSet()
        val result = mutableMapOf<String, String>()
        for (base in umBases) {
            if (remaining.isEmpty()) break
            try {
                val list = api.execute("$base/user/print")
                list.forEach { row ->
                    val name = row["name"] ?: row["username"]
                    val id = row[".id"]
                    if (name != null && id != null && name in remaining) {
                        result[name] = id
                        remaining.remove(name)
                    }
                }
            } catch (_: Exception) {}
        }
        return result
    }

    fun addUserManagerUser(user: UserManagerUser, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val customer by lazy { umResolveCustomer(api) }
                val extra = mutableMapOf<String, String>()
                if (user.comment.isNotEmpty()) extra["comment"] = user.comment
                if (user.active) extra["disabled"] = "no"
                val flavor = umAddUser(
                    api = api,
                    username = user.username,
                    password = user.password,
                    customer = { customer },
                    extra = extra
                )
                if (user.profile.isNotEmpty()) {
                    if (flavor == UmFlavor.V6) {
                        // v6 accepts the username in numbers=; resolving .id would
                        // cost one extra /user/print per generated card.
                        try {
                            umAssignProfile(api, flavor, user.username, null, user.profile) { customer }
                        } catch (first: Exception) {
                            val uid = umResolveUserId(api, user.username)
                                ?: throw first
                            umAssignProfile(api, flavor, user.username, uid, user.profile) { customer }
                        }
                    } else {
                        umAssignProfile(api, flavor, user.username, null, user.profile) { customer }
                    }
                }
                _userManagerUsers.update { it + user }
                cacheDao.insertUserManagerUsers(listOf(toCachedUserManagerUser(user, _currentConnection.value?.host ?: "")))
                insertActivityLog("يوزر مانجر", "تم إضافة مستخدم يوزر مانجر جديد '${user.username}' بالبروفايل '${user.profile}'", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل إضافة المستخدم على الراوتر") }
            }
        }
    }

    fun renewUserManagerUser(username: String, newProfile: String, resetUptime: Boolean = true, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val uid = umResolveUserId(api, username) ?: username
                // ROS v6 activates/renews a card's profile through
                // create-and-activate-profile; v6 users have no writable `profile`.
                umWrite(api, "user/create-and-activate-profile", mapOf("numbers" to uid, "profile" to newProfile, "customer" to umResolveCustomer(api)))
                if (resetUptime) {
                    runCatching { umWrite(api, "user/reset-counters", mapOf("numbers" to uid)) }
                }
                _userManagerUsers.update { list ->
                    list.map {
                        if (it.username == username) it.copy(profile = newProfile, active = true, profileExpired = false, uptimeUsed = if (resetUptime) "0s" else it.uptimeUsed) else it
                    }
                }
                insertActivityLog("تجديد كرت", "تم تجديد الكرت '$username' بالباقة '$newProfile'", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل تجديد الكرت على الراوتر") }
            }
        }
    }

    fun bulkDeleteUserManagerUsers(usernames: List<String>, onSuccess: (Int) -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            var deleted = 0
            val succeeded = mutableSetOf<String>()
            var firstErr: String? = null
            val idsByUsername = umResolveUserIds(api, usernames)
            usernames.forEach { u ->
                try {
                    umWrite(api, "user/remove", mapOf("numbers" to (idsByUsername[u] ?: u)))
                    deleted++
                    succeeded.add(u)
                } catch (e: Exception) {
                    if (firstErr == null) firstErr = e.message
                }
            }
            if (deleted > 0) {
                _userManagerUsers.update { it.filter { u -> u.username !in succeeded } }
            }
            insertActivityLog("حذف جماعي", "تم حذف $deleted كرت من نظام اليوزر مانجر", _currentConnection.value?.host ?: "")
            launch(Dispatchers.Main) {
                if (deleted == usernames.size) onSuccess(deleted)
                else onError("حُذف $deleted من ${usernames.size}. خطأ: ${firstErr ?: "غير معروف"}")
            }
        }
    }

    fun bulkToggleUserManagerUsers(usernames: List<String>, active: Boolean, onSuccess: (Int) -> Unit, onError: (String) -> Unit = {})  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            val succeeded = mutableSetOf<String>()
            var done = 0
            var firstErr: String? = null
            val idsByUsername = umResolveUserIds(api, usernames)
            usernames.forEach { u ->
                try {
                    umWrite(api, "user/set", mapOf("numbers" to (idsByUsername[u] ?: u), "disabled" to if (active) "no" else "yes"))
                    done++
                    succeeded.add(u)
                } catch (e: Exception) {
                    if (firstErr == null) firstErr = e.message
                }
            }
            if (done > 0) {
                _userManagerUsers.update { list ->
                    list.map { if (it.username in succeeded) it.copy(active = active) else it }
                }
            }
            insertActivityLog("تفعيل/تعطيل جماعي", "تم ${if (active) "تفعيل" else "تعطيل"} $done كرت في اليوزر مانجر", _currentConnection.value?.host ?: "")
            launch(Dispatchers.Main) {
                if (done == usernames.size) onSuccess(done)
                else onError("تم ${if (active) "تفعيل" else "تعطيل"} $done من ${usernames.size}. خطأ: ${firstErr ?: "غير معروف"}")
            }
        }
    }

    fun deleteUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                umWrite(api, "user/remove", mapOf("numbers" to (umResolveUserId(api, username) ?: username)))
                _userManagerUsers.update { it.filter { u -> u.username != username } }
                insertActivityLog("يوزر مانجر", "تم حذف مستخدم '${username}' من نظام اليوزر مانجر", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل حذف المستخدم على الراوتر") }
            }
        }
    }

    fun addUserManagerProfile(profile: UserManagerProfile, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(umRequiresNativeApiMessage()) }
                return@launch
            }
            try {
                if (detectUmFlavor(api) == UmFlavor.V6) {
                    // ROS v6 stores validity/price on the profile, while usage quotas
                    // are separate limitations linked through profile-limitation.
                    if (profile.validity.isNotBlank() && !isRouterOsDuration(profile.validity)) {
                        throw IllegalArgumentException("الصلاحية يجب أن تكون بصيغة RouterOS مثل 10d أو 4w2d")
                    }
                    if (profile.uptimeLimit.isNotBlank() && profile.uptimeLimit != "unlimited" &&
                        !isRouterOsDuration(profile.uptimeLimit)) {
                        throw IllegalArgumentException("حد الوقت يجب أن يكون بصيغة RouterOS مثل 1h أو 4w2d")
                    }
                    val profParams = mutableMapOf(
                        "name" to profile.name,
                        "name-for-users" to profile.name,
                        "override-shared-users" to profile.sharedUsers.toString(),
                        "price" to profile.price.toString()
                    )
                    if (profile.validity.isNotBlank()) profParams["validity"] = profile.validity.trim()
                    umWrite(api, "profile/add", profParams)

                    val limParams = mutableMapOf("name" to "${profile.name}-lim")
                    if (profile.uptimeLimit.isNotBlank() && profile.uptimeLimit != "unlimited") {
                        limParams["uptime-limit"] = profile.uptimeLimit.trim()
                    }
                    if (profile.downloadLimit.isNotBlank() && profile.downloadLimit != "unlimited") {
                        limParams["download-limit"] = profile.downloadLimit.trim()
                    }
                    // Both limitation and profile-limitation are nested under /profile
                    // on ROS v6 (matches the profile/profile-limitation/print path used
                    // for reading above) — NOT top-level /tool/user-manager/limitation.
                    umWrite(api, "profile/limitation/add", limParams)

                    // Link profile <-> limitation using profile=/limitation= fields
                    // (NOT limitations[name] — that form does not exist in RouterOS).
                    umWrite(api, "profile/profile-limitation/add", mapOf(
                        "profile" to profile.name,
                        "limitation" to "${profile.name}-lim"
                    ))
                } else {
                    // ROS v7: validity and price are direct /user-manager/profile attrs.
                    val params = mutableMapOf("name" to profile.name)
                    if (profile.validity.isNotEmpty()) params["validity"] = profile.validity
                    if (profile.price > 0) params["price"] = profile.price.toString()
                    if (profile.sharedUsers > 1) params["override-shared-users"] = profile.sharedUsers.toString()
                    umWrite(api, "profile/add", params)
                }
                val list = _userManagerProfiles.value.toMutableList()
                list.removeAll { it.name == profile.name }
                list.add(profile)
                _userManagerProfiles.value = list
                insertActivityLog("يوزر مانجر بروفايل", "تم إضافة باقة يوزر مانجر جديدة '${profile.name}' على الراوتر", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل إضافة الباقة على الراوتر") }
            }
        }
    }

    fun updateUserManagerProfile(oldName: String, updatedProfile: UserManagerProfile, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                var matchedBase: String? = null
                val pid = run {
                    var id: String? = null
                    try {
                        id = api.execute("/tool/user-manager/profile/print")
                            .firstOrNull { it["name"] == oldName }?.get(".id")
                        if (id != null) matchedBase = "/tool/user-manager"
                    } catch (_: Exception) {}
                    id ?: oldName
                }
                // Fall back to the connection-detected base if the profile lookup above
                // didn't find a match on either menu (e.g. renaming right after creation).
                val isV6 = (matchedBase ?: if (detectUmFlavor(api) == UmFlavor.V6) "/tool/user-manager" else "/user-manager") == "/tool/user-manager"
                if (isV6) {
                    // ROS v6 stores profile validity/price on the profile and usage quotas
                    // on a separate limitation linked through profile-limitation.
                    if (updatedProfile.validity.isNotBlank() && !isRouterOsDuration(updatedProfile.validity)) {
                        throw IllegalArgumentException("الصلاحية يجب أن تكون بصيغة RouterOS مثل 10d أو 4w2d")
                    }
                    if (updatedProfile.uptimeLimit.isNotBlank() && updatedProfile.uptimeLimit != "unlimited" &&
                        !isRouterOsDuration(updatedProfile.uptimeLimit)) {
                        throw IllegalArgumentException("حد الوقت يجب أن يكون بصيغة RouterOS مثل 1h أو 4w2d")
                    }
                    val profileParams = mutableMapOf(
                        "numbers" to pid,
                        "name" to updatedProfile.name,
                        "name-for-users" to updatedProfile.name,
                        "override-shared-users" to updatedProfile.sharedUsers.toString(),
                        "price" to updatedProfile.price.toString()
                    )
                    if (updatedProfile.validity.isNotBlank()) profileParams["validity"] = updatedProfile.validity.trim()
                    umWrite(api, "profile/set", profileParams)
                    val links = api.execute("/tool/user-manager/profile/profile-limitation/print")
                    val link = links.firstOrNull { it["profile"] == oldName || it["profile"] == updatedProfile.name }
                    val limitations = mutableMapOf(
                        "uptime-limit" to if (updatedProfile.uptimeLimit.isBlank() || updatedProfile.uptimeLimit == "unlimited") "0s" else updatedProfile.uptimeLimit.trim(),
                        "download-limit" to if (updatedProfile.downloadLimit.isBlank() || updatedProfile.downloadLimit == "unlimited") "0B" else updatedProfile.downloadLimit.trim()
                    )
                    if (link != null) {
                        val limId = link["limitation"]
                        val limitationRow = limId?.let { lim ->
                            api.execute("/tool/user-manager/profile/limitation/print").firstOrNull { it["name"] == lim }
                        }
                        if (limitationRow != null && limitations.isNotEmpty()) {
                            umWrite(api, "profile/limitation/set", limitations + mapOf("numbers" to (limitationRow[".id"] ?: limId!!)))
                        }
                        if (link["profile"] != updatedProfile.name) {
                            umWrite(api, "profile/profile-limitation/set", mapOf("numbers" to (link[".id"] ?: ""), "profile" to updatedProfile.name))
                        }
                    } else if ((updatedProfile.uptimeLimit.isNotBlank() && updatedProfile.uptimeLimit != "unlimited") ||
                        (updatedProfile.downloadLimit.isNotBlank() && updatedProfile.downloadLimit != "unlimited")) {
                        val limName = "${updatedProfile.name}-lim"
                        umWrite(api, "profile/limitation/add", limitations + mapOf("name" to limName))
                        umWrite(api, "profile/profile-limitation/add", mapOf("profile" to updatedProfile.name, "limitation" to limName))
                    }
                } else {
                    // ROS v7: validity and price are direct /user-manager/profile attributes.
                    val params = mutableMapOf("numbers" to pid, "name" to updatedProfile.name)
                    if (updatedProfile.validity.isNotEmpty()) params["validity"] = updatedProfile.validity
                    if (updatedProfile.price > 0) params["price"] = updatedProfile.price.toString()
                    if (updatedProfile.sharedUsers > 1) params["override-shared-users"] = updatedProfile.sharedUsers.toString()
                    umWrite(api, "profile/set", params)
                }
                _userManagerProfiles.update { list ->
                    list.map { if (it.name == oldName) updatedProfile else it }
                }
                insertActivityLog("يوزر مانجر بروفايل", "تم تحديث باقة يوزر مانجر '${updatedProfile.name}'", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل تحديث الباقة على الراوتر") }
            }
        }
    }

    fun deleteUserManagerProfile(profileName: String, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val pid = run {
                    var id: String? = null
                    try {
                        id = api.execute("/tool/user-manager/profile/print")
                            .firstOrNull { it["name"] == profileName }?.get(".id")
                    } catch (_: Exception) {}
                    id ?: profileName
                }
                // On ROS v6 the profile's limitation + profile-limitation link are separate
                // objects (see addUserManagerProfile) — remove them too so they don't pile
                // up as orphans on the router after their owning profile is gone.
                if (detectUmFlavor(api) == UmFlavor.V6) {
                    runCatching {
                        val link = api.execute("/tool/user-manager/profile/profile-limitation/print")
                            .firstOrNull { it["profile"] == profileName }
                        if (link != null) {
                            umWrite(api, "profile/profile-limitation/remove", mapOf("numbers" to (link[".id"] ?: "")))
                            val limName = link["limitation"]
                            val limId = limName?.let { lim ->
                                api.execute("/tool/user-manager/profile/limitation/print").firstOrNull { it["name"] == lim }?.get(".id")
                            }
                            if (limId != null) umWrite(api, "profile/limitation/remove", mapOf("numbers" to limId))
                        }
                    }
                }
                umWrite(api, "profile/remove", mapOf("numbers" to pid))
                _userManagerProfiles.update { it.filter { p -> p.name != profileName } }
                insertActivityLog("يوزر مانجر بروفايل", "تم حذف باقة يوزر مانجر '${profileName}'", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل حذف الباقة على الراوتر") }
            }
        }
    }

    fun importUserManagerProfiles(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val conn = _currentConnection.value
            val host = conn?.host ?: ""

            if (api != null && _isConnected.value) {
                try {
                    val umProfList = api.execute("$umBase/profile/print")
                    if (umProfList.isEmpty()) {
                        insertActivityLog("باقات اليوزرمانجر", api.lastError
                            ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر",
                            _currentConnection.value?.host ?: "")
                    }
                    val limitations = umReadProfileLimitations(api)

                    if (umProfList.isNotEmpty()) {
                        val fetchedProfiles = umProfList.map { p ->
                            val name = p["name"] ?: p["name-for-users"] ?: "default"
                            val lim = limitations[name] ?: emptyMap()
                            UserManagerProfile(
                                name = name,
                                validity = p["validity"] ?: p["starts-at"] ?: "30d",
                                price = p["price"]?.toDoubleOrNull()?.toInt() ?: 0,
                                downloadLimit = lim["download-limit"] ?: lim["transfer-limit"] ?: lim["rate-limit-rx"] ?: p["download-limit"] ?: "unlimited",
                                uptimeLimit = lim["uptime-limit"] ?: lim["uptime"] ?: p["uptime-limit"] ?: "unlimited",
                                templateName = name,
                                sharedUsers = p["override-shared-users"]?.toIntOrNull() ?: 1
                            )
                        }
                        _userManagerProfiles.value = fetchedProfiles
                        cacheDao.insertUserManagerProfiles(fetchedProfiles.map {
                            CachedUserManagerProfile(
                                name = it.name,
                                routerHost = host,
                                validity = it.validity,
                                price = it.price,
                                sharedUsers = it.sharedUsers,
                                downloadLimit = it.downloadLimit,
                                uptimeLimit = it.uptimeLimit,
                                templateName = it.templateName
                            )
                        })
                        insertActivityLog("استيراد باقات", "تم استيراد ${fetchedProfiles.size} باقة يوزرمانجر من الراوتر مباشرة", host)
                        launch(Dispatchers.Main) { onSuccess(fetchedProfiles.size) }
                        return@launch
                    }
                } catch (e: Exception) {
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.SYNC_RESULT,
                        operation = OperationTelemetry.Operation.USER_MANAGER_PROFILES_SYNC,
                        outcome = OperationTelemetry.Outcome.PARTIAL,
                        errorClass = OperationTelemetry.classify(e),
                        transport = OperationTelemetry.Transport.NATIVE_API,
                        routerOsMajor = rosMajor,
                        minIntervalMs = 60_000
                    )
                }
            }

            // Router returned no profiles — report honestly; never fabricate built-ins
            if (api == null || !_isConnected.value) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            } else {
                insertActivityLog("استيراد باقات", "الراوتر لم يرجع أي باقات يوزرمانجر", host)
                launch(Dispatchers.Main) { onError("الراوتر لم يرجع أي باقات — أنشئ الباقات في User Manager على الراوتر أولاً") }
            }
        }
    }

    /**
     * Adds exactly [users] (1..N) to User Manager and reports back the cards the
     * router actually accepted. [onSuccess] fires only when every requested card
     * was created; otherwise [onError] receives the real router error together
     * with the subset that *was* created so the UI can still print/export them.
     */
    fun batchAddUserManagerUsers(
        users: List<UserManagerUser>,
        batchName: String,
        onSuccess: (List<UserManagerUser>) -> Unit,
        onError: (String, List<UserManagerUser>) -> Unit
    ) {
        repositoryScope.launch {
            val operationId = OperationTelemetry.newOperationId()
            val startedAt = OperationTelemetry.elapsedRealtime()
            val added = mutableListOf<UserManagerUser>()
            try {
                val api = nativeApi
                if (api == null) {
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.USER_MANAGER_BATCH_RESULT,
                        operation = OperationTelemetry.Operation.USER_MANAGER_BATCH,
                        operationId = operationId,
                        outcome = OperationTelemetry.Outcome.FAILURE,
                        errorClass = OperationTelemetry.ErrorClass.NETWORK,
                        transport = OperationTelemetry.Transport.NATIVE_API,
                        requestedCount = users.size,
                        affectedCount = 0,
                        routerOsMajor = rosMajor,
                        durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                    )
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر", emptyList()) }
                    return@launch
                }
                if (users.isEmpty()) {
                    OperationTelemetry.record(
                        event = OperationTelemetry.Event.USER_MANAGER_BATCH_RESULT,
                        operation = OperationTelemetry.Operation.USER_MANAGER_BATCH,
                        operationId = operationId,
                        outcome = OperationTelemetry.Outcome.FAILURE,
                        errorClass = OperationTelemetry.ErrorClass.VALIDATION,
                        transport = OperationTelemetry.Transport.NATIVE_API,
                        requestedCount = 0,
                        affectedCount = 0,
                        routerOsMajor = rosMajor,
                        durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                    )
                    launch(Dispatchers.Main) { onError("لم يتم إدخال عدد صالح للكروت", emptyList()) }
                    return@launch
                }
                var firstErr: String? = null
                val customer by lazy { umResolveCustomer(api) }
                // Detected from the first accepted row so the remaining rows go
                // straight to the right menu (no double round-trip per card).
                var flavor: UmFlavor? = UmFlavor.V6

                users.forEach { user ->
                    try {
                        flavor = umAddUser(api, user.username, user.password, { customer })
                        added.add(user)
                    } catch (e: Exception) {
                        if (firstErr == null) firstErr = e.message
                    }
                }

                // Attach the selected profile. v6 needs .id, so resolve all IDs in one read.
                val detected = flavor
                val withProfile = added.filter { it.profile.isNotEmpty() }
                if (detected != null && withProfile.isNotEmpty()) {
                    val idByUsername: Map<String, String> = if (detected == UmFlavor.V6) {
                        runCatching { api.executeStrict("/tool/user-manager/user/print") }
                            .getOrDefault(emptyList())
                            .mapNotNull { record ->
                                val username = record["name"] ?: record["username"]
                                val id = record[".id"]
                                if (username != null && id != null) username to id else null
                            }.toMap()
                    } else emptyMap()
                    withProfile.forEach { user ->
                        runCatching {
                            umAssignProfile(api, detected, user.username, idByUsername[user.username], user.profile) { customer }
                        }.onFailure { e ->
                            if (firstErr == null) firstErr = e.message
                        }
                    }
                }

                if (added.isNotEmpty()) {
                    // Prepend so new cards appear instantly at top
                    _userManagerUsers.value = added + _userManagerUsers.value
                }
                insertActivityLog("توليد دفعة كروت", "تم توليد دفعة كروت ($batchName): نجح ${added.size} من ${users.size} كرت في اليوزر مانجر", _currentConnection.value?.host ?: "")
                val result = added.toList()
                val complete = result.size == users.size && firstErr == null
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.USER_MANAGER_BATCH_RESULT,
                    operation = OperationTelemetry.Operation.USER_MANAGER_BATCH,
                    operationId = operationId,
                    outcome = when {
                        complete -> OperationTelemetry.Outcome.SUCCESS
                        result.isNotEmpty() -> OperationTelemetry.Outcome.PARTIAL
                        else -> OperationTelemetry.Outcome.FAILURE
                    },
                    errorClass = if (complete) null else OperationTelemetry.ErrorClass.ROUTER_REJECTED,
                    transport = OperationTelemetry.Transport.NATIVE_API,
                    requestedCount = users.size,
                    affectedCount = result.size,
                    routerOsMajor = rosMajor,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
                launch(Dispatchers.Main) {
                    if (complete) onSuccess(result)
                    else onError("نجح ${result.size} من ${users.size}. خطأ: ${firstErr ?: "غير معروف"}", result)
                }
            } catch (e: Exception) {
                val result = added.toList()
                val errorClass = OperationTelemetry.classify(e)
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.USER_MANAGER_BATCH_RESULT,
                    operation = OperationTelemetry.Operation.USER_MANAGER_BATCH,
                    operationId = operationId,
                    outcome = if (result.isNotEmpty()) OperationTelemetry.Outcome.PARTIAL else if (errorClass == OperationTelemetry.ErrorClass.TIMEOUT) OperationTelemetry.Outcome.TIMEOUT else OperationTelemetry.Outcome.FAILURE,
                    errorClass = errorClass,
                    transport = OperationTelemetry.Transport.NATIVE_API,
                    requestedCount = users.size,
                    affectedCount = result.size,
                    routerOsMajor = rosMajor,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
                launch(Dispatchers.Main) { onError(e.message ?: "خطأ في إضافة الكروت", result) }
            }
        }
    }

    fun cleanExpiredUserManagerUsers(onSuccess: (Int) -> Unit, onError: (String) -> Unit = {})  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر، لا يمكن تنظيف الكروت المنتهية") }
                return@launch
            }
            // Do not treat disabled=true as expiry: administrators may disable valid
            // cards intentionally. Delete only cards whose profile expiry is reported
            // by RouterOS and which have been used at least once.
            val toRemove = _userManagerUsers.value.filter { it.isConfirmedExpired }.map { it.username }
            var removed = 0
            val succeeded = mutableSetOf<String>()
            var firstErr: String? = null
            val idsByUsername = umResolveUserIds(api, toRemove)
            toRemove.forEach { u ->
                try {
                    umWrite(api, "user/remove", mapOf("numbers" to (idsByUsername[u] ?: u)))
                    removed++
                    succeeded.add(u)
                } catch (e: Exception) {
                    if (firstErr == null) firstErr = e.message
                }
            }
            if (removed > 0) {
                _userManagerUsers.update { it.filter { u -> u.username !in succeeded } }
            }
            insertActivityLog("صيانة الكروت", "تم فحص وصيانة الكروت المنتهية وتنظيف $removed كرت منتهي", _currentConnection.value?.host ?: "")
            launch(Dispatchers.Main) {
                if (firstErr == null) onSuccess(removed)
                else onError("نُظّف $removed من ${toRemove.size} كرت منتهي. خطأ: $firstErr")
            }
        }
    }

    fun toggleUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit = {})  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            val current = _userManagerUsers.value.find { it.username == username }
            if (current == null) {
                launch(Dispatchers.Main) { onError("المستخدم '$username' غير موجود") }
                return@launch
            }
            val newActive = !current.active
            try {
                umWrite(api, "user/set", mapOf("numbers" to (umResolveUserId(api, username) ?: username), "disabled" to if (newActive) "no" else "yes"))
                _userManagerUsers.update { list ->
                    list.map { if (it.username == username) it.copy(active = newActive) else it }
                }
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل تغيير حالة المستخدم على الراوتر") }
            }
        }
    }

    fun backupUserManagerDatabase(onSuccess: (String) -> Unit, onError: (String) -> Unit = {})  {
        repositoryScope.launch {
            val filename = "userman-backup-${System.currentTimeMillis()}.umb"
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                umWrite(api, "database/save", mapOf("name" to filename.removeSuffix(".umb")))
                insertActivityLog("نسخ احتياطي", "تم إنشاء نسخة احتياطية لقاعدة بيانات اليوزر مانجر باسم $filename", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess(filename) }
            } catch (e: Exception) {
                insertActivityLog("نسخ احتياطي", "فشل النسخ الاحتياطي: ${e.message}", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onError(e.message ?: "فشل النسخ الاحتياطي على الراوتر") }
            }
        }
    }

    // ==========================================
    // USER MANAGER SESSIONS ($umBase/session/*)
    // ==========================================

    /** Live user-manager sessions from `$umBase/session/print` (v6 & v7). */
    fun fetchUserManagerSessions(onSuccess: (List<UserManagerSession>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(umRequiresNativeApiMessage()) }
                return@launch
            }
            try {
                var sessions: List<Map<String, String>> = emptyList()
                var lastErr: Exception? = null
                for (base in umBases) {
                    try {
                        sessions = api.executeStrict("$base/session/print")
                        break
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
                // A successful empty print means "no live sessions right now";
                // a thrown command is a real router/protocol error and must be
                // surfaced instead of showing a fake empty list.
                if (sessions.isEmpty() && lastErr != null) {
                    throw lastErr
                }
                val mapped = sessions.mapIndexed { idx, s ->
                    UserManagerSession(
                        id = s[".id"] ?: idx.toString(),
                        user = s["user"] ?: s["name"] ?: s["username"] ?: "unknown",
                        address = s["address"] ?: s["user-address"] ?: "",
                        uptime = s["uptime"] ?: "0s",
                        bytesIn = s["bytes-in"]?.toLongOrNull() ?: 0L,
                        bytesOut = s["bytes-out"]?.toLongOrNull() ?: 0L,
                        started = s["started"] ?: ""
                    )
                }
                launch(Dispatchers.Main) { onSuccess(mapped) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل جلب جلسات اليوزر مانجر") }
            }
        }
    }

    /**
     * Disconnects a user-manager user's LIVE session via the v6
     * `session/close-session` command (the card itself is not deleted).
     */
    fun disconnectUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(umRequiresNativeApiMessage()) }
                return@launch
            }
            try {
                // Find the user's live session on the router
                var sessionId: String? = null
                for (base in umBases) {
                    try {
                        val list = api.executeStrict("$base/session/print")
                        val hit = list.firstOrNull {
                            it["user"] == username || it["name"] == username || it["username"] == username
                        }
                        if (hit != null) {
                            sessionId = hit[".id"]
                            break
                        }
                    } catch (_: Exception) {}
                }
                if (sessionId == null) {
                    launch(Dispatchers.Main) { onError("لا توجد جلسة نشطة للمستخدم '$username' على الراوتر حالياً") }
                    return@launch
                }
                umWrite(api, "session/close-session", mapOf("numbers" to sessionId))
                insertActivityLog("قطع جلسة", "تم قطع الجلسة النشطة للمستخدم '$username' من الراوتر", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل قطع الجلسة على الراوتر") }
            }
        }
    }

    private fun umRequiresNativeApiMessage(): String =
        "عمليات User Manager تتطلب الاتصال عبر منفذ API الأصلي (8728 أو 8729) — " +
        "غير متاحة في وضع REST الحالي. أعد الاتصال بمنفذ الـ API ثم أعد المحاولة."

    /** Generic variant of the native-API requirement message for tool operations. */
    private fun requiresNativeApiMessage(): String =
        "هذه العملية تتطلب الاتصال عبر منفذ API الأصلي (8728 أو 8729) — " +
        "غير متاحة في وضع REST الحالي. أعد الاتصال بمنفذ الـ API ثم أعد المحاولة."

    // ==========================================
    // QUICK SERVICES STATE (real router reads)
    // ==========================================

    /** Reads the REAL router state for the quick-services switches:
     *  - freeHotspot: every hotspot server row is disabled (open network), and
     *  - antiShare: the ABO_TALAL_ANTI_SHARE mangle rule exists. */
    fun fetchQuickServicesState(
        onResult: (freeHotspotEnabled: Boolean, antiShareEnabled: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val servers = api.execute("/ip/hotspot/print")
                val freeEnabled = servers.isNotEmpty() && servers.all { it["disabled"] == "true" }
                val rules = api.execute("/ip/firewall/mangle/print")
                val antiShare = rules.any { it["comment"]?.contains("ABO_TALAL_ANTI_SHARE") == true }
                launch(Dispatchers.Main) { onResult(freeEnabled, antiShare) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "تعذر قراءة حالة الخدمات من الراوتر") }
            }
        }
    }

    // ==========================================
    // ROUTER LOGS (/log/print)
    // ==========================================

    /** Reads the router's own log buffer via /log/print. */
    fun fetchRouterLogs(onSuccess: (List<RouterLogEntry>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر — سجلات الراوتر تتطلب منفذ API") }
                return@launch
            }
            try {
                val rows = api.execute("/log/print")
                if (rows.isEmpty() && api.lastError != null) {
                    throw java.io.IOException(api.lastError)
                }
                val logs = rows.mapIndexed { idx, r ->
                    RouterLogEntry(
                        id = r[".id"] ?: idx.toString(),
                        time = r["time"] ?: "",
                        topics = r["topics"] ?: "",
                        message = r["message"] ?: ""
                    )
                }
                launch(Dispatchers.Main) { onSuccess(logs) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل جلب سجلات الراوتر") }
            }
        }
    }

    // ==========================================
    // HOTSPOT IP BINDINGS (/ip/hotspot/ip-binding)
    // ==========================================

    /** Adds a REAL hotspot IP binding (blocked / bypassed / regular) on the router. */
    fun addHotspotIpBinding(
        macAddress: String,
        address: String,
        type: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val params = mutableMapOf(
                    "mac-address" to macAddress.trim(),
                    "type" to type
                )
                if (address.isNotBlank()) params["address"] = address.trim()
                api.executeStrict("/ip/hotspot/ip-binding/add", params)
                insertActivityLog(
                    "IP Binding",
                    "تمت إضافة قيد ${if (type == "blocked") "حظر" else if (type == "bypassed") "تجاوز" else "عادي"} للجهاز $macAddress على الراوتر",
                    _currentConnection.value?.host ?: ""
                )
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل إضافة القيد على الراوتر") }
            }
        }
    }

    // ==========================================
    // ROUTER-SIDE SEARCH (query words ?key~value)
    // ==========================================

    /** Searches user-manager users ON THE ROUTER with `?`-query words
     *  (`?name~x`, `?username~x`, `?profile~x`, `?comment~x`) so huge card
     *  databases don't have to be synced to the phone first. */
    fun searchUserManagerUsers(
        query: String,
        field: String,
        onSuccess: (List<UserManagerUser>) -> Unit,
        onError: (String) -> Unit
    ) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            val q = query.trim()
            if (q.isEmpty()) {
                launch(Dispatchers.Main) { onSuccess(emptyList()) }
                return@launch
            }
            try {
                val props: List<String> = when (field) {
                    "الباقة" -> listOf("profile")
                    "الرقم التسلسلي" -> listOf("comment")
                    else -> listOf("name", "username") // v7 uses name, v6 uses username
                }
                var rows: List<Map<String, String>> = emptyList()
                for (base in umBases) {
                    for (prop in props) {
                        rows = api.execute("$base/user/print", "?$prop~$q")
                        if (rows.isNotEmpty()) break
                    }
                    if (rows.isNotEmpty()) break
                }
                val users = rows.map { mapUserManagerRow(it) }
                launch(Dispatchers.Main) { onSuccess(users) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل البحث على الراوتر") }
            }
        }
    }

    // Router Control: Reboot / Shutdown
    fun rebootRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val host = _currentConnection.value?.host ?: ""
            // The native socket may have dropped since the last screen. RouterOS
            // v6 has no REST API at all (it was only added in v7), so falling
            // straight to the REST fallback below would always fail on a real
            // v6 router in this situation — reconnect the native API first. Safe
            // no-op retry for v7/REST-only setups too: if it fails, the existing
            // REST fallback still runs exactly as before.
            if (nativeApi == null) {
                _currentConnection.value?.let { conn -> tryNativeConnect(conn, OperationTelemetry.newOperationId()) }
            }
            if (nativeApi != null) {
                val ok = nativeApi?.reboot() ?: false
                if (ok) {
                    insertActivityLog("إعادة تشغيل", "تم إرسال أمر إعادة التشغيل عبر المنفذ 8728", host)
                    disconnect()
                    launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إعادة تشغيل الراوتر بنجاح!") }
                } else {
                    insertActivityLog("إعادة تشغيل", "فشل إرسال أمر إعادة التشغيل عبر المنفذ 8728", host)
                    launch(Dispatchers.Main) { onError("فشل إرسال أمر إعادة التشغيل إلى الراوتر") }
                }
                return@launch
            }
            
                _currentConnection.value?.let { conn ->
                    val protocol = if (conn.useSsl) "https" else "http"
                    val credential = Credentials.basic(conn.username, conn.password)
                    try {
                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/system/reboot")
                            .post(ByteArray(0).toRequestBody(null))
                            .header("Authorization", credential)
                            .build()

                        okHttpClient?.newCall(req)?.execute().use { res ->
                            if (res != null && res.isSuccessful) {
                                insertActivityLog("إعادة تشغيل", "تم إرسال أمر إعادة التشغيل للراوتر (REST)", conn.host)
                                disconnect()
                                launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إعادة التشغيل للراوتر بنجاح!") }
                            } else {
                                insertActivityLog("إعادة تشغيل", "فشل إرسال أمر إعادة التشغيل — رمز ${res?.code}", conn.host)
                                launch(Dispatchers.Main) { onError("فشل إرسال أمر إعادة التشغيل. رمز الخطأ: ${res?.code}") }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Read timeout AFTER the request was sent: the router usually
                        // reboots without answering — report sent-with-note, not failure.
                        insertActivityLog("إعادة تشغيل", "أُرسل أمر إعادة التشغيل ولم يصل رد (الراوتر يعيد التشغيل عادةً)", conn.host)
                        disconnect()
                        launch(Dispatchers.Main) { onSuccess("تم إرسال الأمر — لم يصل رد من الراوتر (يقوم بإعادة التشغيل عادةً)") }
                    } catch (e: Exception) {
                        val msg = (e.message ?: "").lowercase()
                        if (msg.contains("reset") || msg.contains("econnreset")) {
                            // Connection torn down right after the request — router going down.
                            insertActivityLog("إعادة تشغيل", "أُرسل أمر إعادة التشغيل وانقطع الاتصال (الراوتر يعيد التشغيل)", conn.host)
                            disconnect()
                            launch(Dispatchers.Main) { onSuccess("تم إرسال الأمر — انقطع الاتصال بالراوتر (دليل على بدء إعادة التشغيل)") }
                        } else {
                            insertActivityLog("إعادة تشغيل", "فشل إرسال أمر إعادة التشغيل: ${e.message}", conn.host)
                            launch(Dispatchers.Main) { onError("تعذر إرسال أمر إعادة التشغيل إلى الراوتر: ${e.message}") }
                        }
                    }
                }
            
        }
    }

    fun shutdownRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val host = _currentConnection.value?.host ?: ""
            // See rebootRouter() above: RouterOS v6 has no REST API at all, so
            // reconnect the native API first instead of falling straight to the
            // REST-only fallback below.
            if (nativeApi == null) {
                _currentConnection.value?.let { conn -> tryNativeConnect(conn, OperationTelemetry.newOperationId()) }
            }
            if (nativeApi != null) {
                val ok = nativeApi?.shutdown() ?: false
                if (ok) {
                    insertActivityLog("إيقاف تشغيل", "تم إرسال أمر إيقاف تشغيل الراوتر", host)
                    disconnect()
                    launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إيقاف تشغيل الراوتر بنجاح!") }
                } else {
                    insertActivityLog("إيقاف تشغيل", "فشل إرسال أمر إيقاف التشغيل للراوتر", host)
                    launch(Dispatchers.Main) { onError("فشل إرسال أمر إيقاف التشغيل إلى الراوتر") }
                }
                return@launch
            }
            
                _currentConnection.value?.let { conn ->
                    val protocol = if (conn.useSsl) "https" else "http"
                    val credential = Credentials.basic(conn.username, conn.password)
                    try {
                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/system/shutdown")
                            .post(ByteArray(0).toRequestBody(null))
                            .header("Authorization", credential)
                            .build()

                        okHttpClient?.newCall(req)?.execute().use { res ->
                            if (res != null && res.isSuccessful) {
                                insertActivityLog("إيقاف تشغيل", "تم إرسال أمر إيقاف التشغيل للراوتر (REST)", conn.host)
                                disconnect()
                                launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إيقاف تشغيل الراوتر بنجاح!") }
                            } else {
                                insertActivityLog("إيقاف تشغيل", "فشل إرسال أمر إيقاف التشغيل — رمز ${res?.code}", conn.host)
                                launch(Dispatchers.Main) { onError("فشل إرسال أمر إيقاف التشغيل. رمز الخطأ: ${res?.code}") }
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        insertActivityLog("إيقاف تشغيل", "أُرسل أمر إيقاف التشغيل ولم يصل رد (الراوتر يتوقف عادةً)", conn.host)
                        disconnect()
                        launch(Dispatchers.Main) { onSuccess("تم إرسال الأمر — لم يصل رد من الراوتر (الجهاز يتوقف عن العمل عادةً)") }
                    } catch (e: Exception) {
                        val msg = (e.message ?: "").lowercase()
                        if (msg.contains("reset") || msg.contains("econnreset")) {
                            insertActivityLog("إيقاف تشغيل", "أُرسل أمر إيقاف التشغيل وانقطع الاتصال (الراوتر يتوقف)", conn.host)
                            disconnect()
                            launch(Dispatchers.Main) { onSuccess("تم إرسال الأمر — انقطع الاتصال بالراوتر (دليل على بدء الإيقاف)") }
                        } else {
                            insertActivityLog("إيقاف تشغيل", "فشل إرسال أمر إيقاف التشغيل: ${e.message}", conn.host)
                            launch(Dispatchers.Main) { onError("تعذر إرسال أمر إيقاف التشغيل إلى الراوتر: ${e.message}") }
                        }
                    }
                }
            
        }
    }


    fun refreshData() {
        repositoryScope.launch {
            val conn = _currentConnection.value
            val api = nativeApi
            if (api != null && conn != null) {
                fetchRealNativeData(api, conn)
            }
        }
    }

    // ==========================================
    // ROUTER CONFIGURATION BACKUPS (/system/backup/*, /file/*)
    // Full system config backups, distinct from the User Manager database
    // backup above and from the MikroTik Cloud backup feature (CloudScreen).
    // ==========================================

    fun probeAiDoctorRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val connection = _currentConnection.value
            if (api == null || connection == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "اتصل بالراوتر أولاً من شاشة تسجيل الدخول") }
                return@launch
            }
            try {
                val resource = api.executeStrict("/system/resource/print").firstOrNull()
                    ?: throw IOException("لم يُرجع الراوتر بيانات الموارد")
                launch(Dispatchers.Main) {
                    onSuccess("${connection.host}:${connection.port} — RouterOS ${resource["version"] ?: "غير معروف"}")
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل اختبار الاتصال بالراوتر") }
            }
        }
    }

    /** Applies a user-approved subset of AI-suggested fixes. Always creates a
     *  safety backup first, validates every command against [AiFixGuard]'s
     *  allowlist, and stops at the first failure. */
    fun applyAiFixes(fixes: List<AiFix>, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            val plan = try {
                AiFixGuard.buildSafePlan(fixes)
            } catch (e: IllegalArgumentException) {
                launch(Dispatchers.Main) { onError(e.message ?: "خطة الإصلاح غير صالحة.") }
                return@launch
            }
            val backupName = "netcraft-ai-${System.currentTimeMillis() / 1000}"
            try {
                api.executeStrict("/system/backup/save", mapOf("name" to backupName))
            } catch (e: Exception) {
                insertActivityLog("إصلاح AI", "أُوقف الإصلاح: تعذر إنشاء نسخة احتياطية (${e.message})", host)
                launch(Dispatchers.Main) {
                    onError("تعذر إنشاء نسخة احتياطية قبل الإصلاح؛ لم يُنفذ أي أمر. ${e.message.orEmpty()}".trim())
                }
                return@launch
            }
            var applied = 0
            for (command in plan) {
                try {
                    api.executeStrict(command.path, command.params)
                    applied++
                } catch (e: Exception) {
                    insertActivityLog(
                        "إصلاح AI",
                        "توقف بعد $applied من ${plan.size} أمر عند ${command.path}: ${e.message}",
                        host
                    )
                    launch(Dispatchers.Main) {
                        onError("توقف التنفيذ بعد $applied من ${plan.size} أمر (${command.path}). النسخة الاحتياطية: $backupName.backup. ${e.message.orEmpty()}".trim())
                    }
                    return@launch
                }
            }
            insertActivityLog("إصلاح AI", "تم تطبيق $applied أمرًا بعد إنشاء النسخة الاحتياطية $backupName.backup", host)
            launch(Dispatchers.Main) {
                onSuccess("تم إنشاء نسخة احتياطية ($backupName.backup) وتطبيق $applied أمرًا بنجاح.")
            }
        }
    }

    /** Read-only diagnostics. Script sources, scheduler commands, credentials,
     *  and private keys are never requested from the router. */
    fun collectAiDoctorData(selectedSections: Set<String>, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val connection = _currentConnection.value
            if (api == null || connection == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "اتصل بالراوتر أولاً من شاشة تسجيل الدخول") }
                return@launch
            }
            if (selectedSections.isEmpty()) {
                launch(Dispatchers.Main) { onError("اختر قسمًا واحدًا على الأقل للفحص") }
                return@launch
            }
            try {
                val resource = api.executeStrict("/system/resource/print").firstOrNull()
                    ?: throw IOException("تعذر قراءة بيانات الراوتر؛ لم يتم إرسال أي تحليل")
                val version = resource["version"].orEmpty()
                val selectedGroups = if ("all" in selectedSections) {
                    setOf("network", "queues", "firewall", "hotspot", "performance", "scripts", "security", "wireless", "routing", "ipv6", "usermanager")
                } else selectedSections
                val baseSpecs = listOf(
                    AiDoctorDiagnostics.ReadSpec("network", "interfaces", "/interface/print", ".id,name,type,running,disabled,mtu,actual-mtu"),
                    AiDoctorDiagnostics.ReadSpec("network", "addresses", "/ip/address/print", ".id,address,network,interface,disabled"),
                    AiDoctorDiagnostics.ReadSpec("network", "dns", "/ip/dns/print", "servers,allow-remote-requests,cache-size,cache-used"),
                    AiDoctorDiagnostics.ReadSpec("network", "dhcp_servers", "/ip/dhcp-server/print", ".id,name,interface,address-pool,lease-time,disabled"),
                    AiDoctorDiagnostics.ReadSpec("network", "dhcp_leases", "/ip/dhcp-server/lease/print", ".id,server,address,mac-address,status,expires-after", limit = 60),
                    AiDoctorDiagnostics.ReadSpec("queues", "simple_queues", "/queue/simple/print", ".id,name,target,max-limit,limit-at,queue,disabled", limit = 150),
                    AiDoctorDiagnostics.ReadSpec("queues", "queue_tree", "/queue/tree/print", ".id,name,parent,packet-mark,max-limit,limit-at,disabled", limit = 150),
                    AiDoctorDiagnostics.ReadSpec("queues", "queue_types", "/queue/type/print", ".id,name,kind,pcq-rate,pcq-limit,pcq-total-limit", limit = 80),
                    AiDoctorDiagnostics.ReadSpec("firewall", "filter", "/ip/firewall/filter/print", ".id,chain,action,protocol,src-address,dst-address,in-interface,out-interface,connection-state,disabled,packets,bytes", limit = 200),
                    AiDoctorDiagnostics.ReadSpec("firewall", "nat", "/ip/firewall/nat/print", ".id,chain,action,protocol,src-address,dst-address,in-interface,out-interface,to-addresses,to-ports,disabled,packets,bytes", limit = 160),
                    AiDoctorDiagnostics.ReadSpec("firewall", "mangle", "/ip/firewall/mangle/print", ".id,chain,action,protocol,connection-mark,packet-mark,new-connection-mark,new-packet-mark,src-address,dst-address,disabled,packets,bytes", limit = 200),
                    AiDoctorDiagnostics.ReadSpec("hotspot", "hotspot_servers", "/ip/hotspot/print", ".id,name,interface,address-pool,profile,disabled", limit = 80),
                    AiDoctorDiagnostics.ReadSpec("hotspot", "hotspot_active", "/ip/hotspot/active/print", ".id,user,address,mac-address,uptime,bytes-in,bytes-out"),
                    AiDoctorDiagnostics.ReadSpec("performance", "established_connections", "/ip/firewall/connection/print", ".id,protocol,tcp-state,src-address,dst-address,reply-src-address,reply-dst-address,timeout,orig-packets,orig-bytes,reply-bytes", "?tcp-state=established", 60),
                    AiDoctorDiagnostics.ReadSpec("scripts", "scripts", "/system/script/print", ".id,name,owner,policy,run-count,last-started,disabled"),
                    AiDoctorDiagnostics.ReadSpec("scripts", "scheduler", "/system/scheduler/print", ".id,name,start-date,start-time,interval,disabled,run-count"),
                    AiDoctorDiagnostics.ReadSpec("scripts", "error_logs", "/log/print", ".id,time,topics,message", "?topics~error", 80)
                )
                val specs = baseSpecs + AiDoctorDiagnostics.additionalReadSpecs(version, selectedGroups)
                val output = JSONObject()
                output.put("router", JSONObject()
                    .put("host", connection.host)
                    .put("routeros_version", version.ifBlank { "unknown" })
                    .put("routeros_major", AiDoctorDiagnostics.majorVersion(version) ?: JSONObject.NULL)
                    .put("board", resource["board-name"] ?: "unknown")
                    .put("uptime", resource["uptime"] ?: "unknown")
                    .put("cpu_load_percent", resource["cpu-load"] ?: "unknown")
                    .put("free_memory", resource["free-memory"] ?: "unknown")
                    .put("total_memory", resource["total-memory"] ?: "unknown"))
                val errors = JSONArray()
                val collectedRows = mutableMapOf<String, List<Map<String, String>>>()
                val truncatedKeys = mutableSetOf<String>()
                for (spec in specs.filter { it.group in selectedGroups }) {
                    try {
                        val params = buildList {
                            add(".proplist=${spec.properties}")
                            if (spec.query.isNotBlank()) add(spec.query)
                        }
                        val fetchedRows = api.executeStrict(spec.path, *params.toTypedArray())
                        if (fetchedRows.size > spec.limit) truncatedKeys += spec.key
                        val rows = fetchedRows.take(spec.limit)
                        collectedRows[spec.key] = rows
                        val safeRows = JSONArray()
                        rows.forEach { row ->
                            val safeRow = JSONObject()
                            row.forEach { (key, raw) ->
                                val lower = key.lowercase()
                                val privateField = listOf("password", "passphrase", "secret", "private", "community", "token", "source", "on-event", "certificate", "key")
                                    .any(lower::contains)
                                if (!privateField) {
                                    val safeValue = RouterDiagnosticRedactor.redactAndLimit(raw, 500)
                                    safeRow.put(key, safeValue)
                                }
                            }
                            safeRows.put(safeRow)
                        }
                        output.put(spec.key, safeRows)
                    } catch (e: Exception) {
                        errors.put("${spec.path}: ${e.message?.take(160) ?: e.javaClass.simpleName}")
                    }
                }
                output.put("collection_errors", errors)
                output.put("collection_truncated", JSONArray().apply { truncatedKeys.forEach(::put) })
                output.put("heuristic_findings", JSONArray().apply {
                    AiDoctorDiagnostics.securityFindings(collectedRows, truncatedKeys).forEach { finding ->
                        put(JSONObject()
                            .put("severity", finding.severity)
                            .put("category", finding.category)
                            .put("title", finding.title)
                            .put("evidence", finding.evidence)
                            .put("recommendation", finding.recommendation))
                    }
                })
                if ("wireless" in selectedGroups && AiDoctorDiagnostics.majorVersion(version) !in setOf(6, 7)) {
                    output.put("compatibility_note", "لم يُكتشف إصدار RouterOS 6 أو 7؛ لم تُجرّب مسارات Wi-Fi خاصة بإصدار لتجنب أوامر غير متوافقة.")
                }
                if ("usermanager" in selectedGroups && AiDoctorDiagnostics.majorVersion(version) !in setOf(6, 7)) {
                    output.put("usermanager_compatibility_note", "لم يُكتشف إصدار RouterOS 6 أو 7؛ لم تُقرأ بيانات User Manager لتجنب استخدام مسار غير متوافق.")
                }
                output.put("privacy_note", "Read-only RouterOS data. Wi-Fi passphrases and SNMP community names are not requested; credential fields, script source, scheduler commands, and private keys are excluded or redacted.")
                val snapshot = output.toString()
                if (snapshot.length > 100_000) throw IOException("تقرير الفحص كبير جدًا؛ اختر أقسامًا أقل ثم أعد المحاولة")
                launch(Dispatchers.Main) { onSuccess(snapshot) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "تعذر جمع بيانات فعلية من الراوتر") }
            }
        }
    }

    /** Lists the router's own local system backups: real /file/print rows whose
     *  name ends in ".backup" (what /system/backup/save produces). */
    fun fetchRouterBackups(onSuccess: (List<RouterBackupFile>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val files = api.executeStrict("/file/print")
                    .filter { (it["name"] ?: "").endsWith(".backup") }
                    .map {
                        RouterBackupFile(
                            name = it["name"] ?: "",
                            sizeBytes = it["size"]?.toLongOrNull() ?: 0L,
                            time = it["creation-time"] ?: ""
                        )
                    }
                    .sortedByDescending { it.time }
                launch(Dispatchers.Main) { onSuccess(files) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل جلب قائمة النسخ الاحتياطية من الراوتر") }
            }
        }
    }

    /** Creates a new local system backup on the router via /system/backup/save. */
    fun createRouterBackup(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            val name = "netcraft-${System.currentTimeMillis() / 1000}"
            try {
                api.executeStrict("/system/backup/save", mapOf("name" to name))
                insertActivityLog("نسخ احتياطي", "تم إنشاء نسخة احتياطية جديدة لإعدادات الراوتر: $name.backup", host)
                launch(Dispatchers.Main) { onSuccess("$name.backup") }
            } catch (e: Exception) {
                insertActivityLog("نسخ احتياطي", "فشل إنشاء النسخة الاحتياطية: ${e.message}", host)
                launch(Dispatchers.Main) { onError(e.message ?: "فشل إنشاء النسخة الاحتياطية على الراوتر") }
            }
        }
    }

    /** Downloads one existing binary backup from the router's FTP service into a
     *  user-selected SAF folder (which may be on removable storage). RouterOS FTP
     *  is unencrypted; the UI must warn users to use a trusted LAN or VPN. */
    fun downloadRouterBackup(
        fileName: String,
        folderUri: Uri,
        expectedSizeBytes: Long,
        onProgress: (Long) -> Unit,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        repositoryScope.launch {
            val operationId = OperationTelemetry.newOperationId()
            val startedAt = OperationTelemetry.elapsedRealtime()
            val api = nativeApi
            val connection = _currentConnection.value
            if (api == null || connection == null) {
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.BACKUP_DOWNLOAD_RESULT,
                    operation = OperationTelemetry.Operation.BACKUP_DOWNLOAD,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.FAILURE,
                    errorClass = OperationTelemetry.ErrorClass.NETWORK,
                    transport = OperationTelemetry.Transport.FTP,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt
                )
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            var temporaryDocument: Uri? = null
            var downloadedBytes = 0L
            try {
                if (!fileName.endsWith(".backup", ignoreCase = true) || !fileName.matches(Regex("[A-Za-z0-9._ -]+"))) {
                    throw IOException("اسم ملف النسخة غير صالح")
                }
                val hasPersistedWriteGrant = context.contentResolver.persistedUriPermissions.any {
                    it.uri == folderUri && it.isWritePermission
                }
                if (!hasPersistedWriteGrant) {
                    throw IOException("صلاحية مجلد الحفظ لم تعد متاحة. اختر المجلد على الهاتف أو بطاقة SD مرة أخرى.")
                }
                val remoteFile = api.executeStrict("/file/print")
                    .firstOrNull { it["name"] == fileName }
                    ?: throw IOException("النسخة '$fileName' لم تعد موجودة على الراوتر")
                val size = remoteFile["size"]?.toLongOrNull()?.takeIf { it > 0L }
                    ?: expectedSizeBytes.takeIf { it > 0L }
                    ?: throw IOException("حجم النسخة غير معروف؛ أعد تحميل قائمة النسخ قبل التنزيل")
                val ftpService = api.executeStrict("/ip/service/print", "?name=ftp")
                    .firstOrNull { it["name"] == "ftp" }
                    ?: throw IOException("تعذر قراءة إعداد خدمة FTP على الراوتر")
                if (ftpService["disabled"] == "true") {
                    throw IOException("خدمة FTP معطّلة على الراوتر. فعّلها من IP > Services بعد حصر الوصول على شبكة موثوقة فقط")
                }
                val ftpPort = ftpService["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IOException("منفذ FTP الذي أعلنه الراوتر غير صالح")

                val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    folderUri,
                    DocumentsContract.getTreeDocumentId(folderUri)
                )
                temporaryDocument = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocumentUri,
                    "application/octet-stream",
                    "$fileName.part"
                ) ?: throw IOException("تعذر إنشاء ملف مؤقت في المجلد المختار")
                val output = context.contentResolver.openOutputStream(temporaryDocument, "w")
                    ?: throw IOException("تعذر فتح الملف المؤقت للكتابة في المجلد المختار")
                val transferContext = currentCoroutineContext()
                val downloaded = output.use {
                    RouterFtpClient(
                        host = connection.host,
                        port = ftpPort,
                        username = connection.username,
                        password = connection.password
                    ).download(
                        fileName = fileName,
                        output = it,
                        expectedSize = size,
                        coroutineContext = transferContext,
                        onProgress = { bytes ->
                            downloadedBytes = bytes
                            launch(Dispatchers.Main) { onProgress(bytes) }
                        }
                    )
                }
                if (DocumentsContract.renameDocument(context.contentResolver, temporaryDocument, fileName) == null) {
                    throw IOException("موفر التخزين لا يدعم إكمال التنزيل الآمن بإعادة تسمية الملف المؤقت؛ اختر مجلدًا آخر")
                }
                temporaryDocument = null
                insertActivityLog("تنزيل نسخة احتياطية", "تم تنزيل ملف النسخة '$fileName' إلى موقع الحفظ الذي اختاره المستخدم ($downloaded بايت)", connection.host)
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.BACKUP_DOWNLOAD_RESULT,
                    operation = OperationTelemetry.Operation.BACKUP_DOWNLOAD,
                    operationId = operationId,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    transport = OperationTelemetry.Transport.FTP,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt,
                    bytesTransferred = downloaded
                )
                launch(Dispatchers.Main) { onSuccess(downloaded) }
            } catch (e: Exception) {
                var cleanupWarning = ""
                temporaryDocument?.let { uri ->
                    val deleted = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                    if (!deleted) cleanupWarning = " (تحذير: تعذر حذف الملف المؤقت الجزئي من موقع الحفظ؛ احذفه يدويًا إن ظهر باسم .part)"
                }
                val errorClass = OperationTelemetry.classify(e)
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.BACKUP_DOWNLOAD_RESULT,
                    operation = OperationTelemetry.Operation.BACKUP_DOWNLOAD,
                    operationId = operationId,
                    outcome = when {
                        e is kotlinx.coroutines.CancellationException -> OperationTelemetry.Outcome.CANCELLED
                        errorClass == OperationTelemetry.ErrorClass.TIMEOUT -> OperationTelemetry.Outcome.TIMEOUT
                        downloadedBytes > 0L -> OperationTelemetry.Outcome.PARTIAL
                        else -> OperationTelemetry.Outcome.FAILURE
                    },
                    errorClass = errorClass,
                    transport = OperationTelemetry.Transport.FTP,
                    durationMs = OperationTelemetry.elapsedRealtime() - startedAt,
                    bytesTransferred = downloadedBytes
                )
                val message = (e.message ?: "فشل تنزيل النسخة الاحتياطية") + cleanupWarning
                launch(Dispatchers.Main) { onError(message) }
            }
        }
    }

    /** Permanently deletes one of the router's own local backup files. */
    fun deleteRouterBackup(fileName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                if (!fileName.endsWith(".backup", ignoreCase = true) || !fileName.matches(Regex("[A-Za-z0-9._ -]+"))) {
                    throw IOException("اسم ملف النسخة غير صالح")
                }
                val id = api.executeStrict("/file/print").firstOrNull { it["name"] == fileName }?.get(".id")
                    ?: throw java.io.IOException("الملف '$fileName' غير موجود على الراوتر")
                api.executeStrict("/file/remove", mapOf("numbers" to id))
                insertActivityLog("نسخ احتياطي", "تم حذف النسخة الاحتياطية: $fileName", host)
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل حذف النسخة الاحتياطية") }
            }
        }
    }

    /** Restores the router's configuration from one of its own local backup files
     *  via /system/backup/load. This makes the router reload its config and
     *  reboot — mirrors rebootRouter()'s resilient handling of the connection
     *  dropping before a reply arrives, since the router goes down mid-command. */
    fun restoreRouterBackup(fileName: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                if (!fileName.endsWith(".backup", ignoreCase = true) || !fileName.matches(Regex("[A-Za-z0-9._ -]+"))) {
                    throw IOException("اسم ملف النسخة غير صالح")
                }
                val exists = api.executeStrict("/file/print").any { it["name"] == fileName }
                if (!exists) throw IOException("الملف '$fileName' غير موجود على الراوتر")
                api.executeStrict("/system/backup/load", mapOf("name" to fileName))
                insertActivityLog("استعادة نسخة احتياطية", "تم إرسال أمر استعادة النسخة الاحتياطية '$fileName' — الراوتر سيعيد تشغيل نفسه بالإعدادات المستعادة", host)
                disconnect()
                launch(Dispatchers.Main) { onSuccess("تم إرسال أمر الاستعادة بنجاح — الراوتر سيعيد تشغيل نفسه الآن بالإعدادات المستعادة") }
            } catch (e: Exception) {
                val msg = (e.message ?: "").lowercase()
                if (msg.contains("reset") || msg.contains("econnreset") || msg.contains("closed") || msg.contains("broken pipe")) {
                    insertActivityLog("استعادة نسخة احتياطية", "أُرسل أمر الاستعادة وانقطع الاتصال (الراوتر يعيد التشغيل بالإعدادات المستعادة)", host)
                    disconnect()
                    launch(Dispatchers.Main) { onSuccess("تم إرسال أمر الاستعادة — انقطع الاتصال بالراوتر (دليل على بدء إعادة التشغيل بالإعدادات المستعادة)") }
                } else {
                    insertActivityLog("استعادة نسخة احتياطية", "فشل إرسال أمر الاستعادة: ${e.message}", host)
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل استعادة النسخة الاحتياطية على الراوتر") }
                }
            }
        }
    }

    // ==========================================
    // EXPIRED-CARDS MAINTENANCE WIZARD (9 manual steps, mirrors the reference
    // "MkCards" flow): each step below is called independently by the UI, in
    // order, and only moves the router/local state that step actually promises.
    // ==========================================

    private val expiredCardArchiveDao by lazy { AppDatabase.getDatabase(context).expiredCardArchiveDao() }

    /** Step 1 — local-only: archives the cards currently matching the expired/
     *  disabled criterion into the local "financial accounts" table, keyed by
     *  router+username so re-running updates the same rows instead of
     *  duplicating them. Makes no router call at all. */
    fun archiveExpiredCardsLocally(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val host = _currentConnection.value?.host ?: ""
            try {
                val expired = _userManagerUsers.value.filter { it.isConfirmedExpired }
                if (expired.isNotEmpty()) {
                    val priceByProfile = _userManagerProfiles.value.associate { it.name to it.price }
                    val now = System.currentTimeMillis()
                    val entries = expired.map { u ->
                        ExpiredCardArchiveEntry(
                            id = "$host|${u.username}",
                            routerHost = host,
                            username = u.username,
                            profile = u.profile,
                            price = priceByProfile[u.profile] ?: 0,
                            archivedAt = now
                        )
                    }
                    expiredCardArchiveDao.upsertAll(entries)
                }
                insertActivityLog("صيانة الكروت", "تم ترحيل ${expired.size} كرت منتهي إلى الحسابات المالية محليًا", host)
                launch(Dispatchers.Main) { onSuccess(expired.size) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل الترحيل المحلي للحسابات المالية") }
            }
        }
    }

    /** Step 2 — disables every hotspot server on the router (/ip/hotspot/set
     *  disabled=yes) and returns the .ids that were actually flipped (i.e. were
     *  enabled beforehand), so step 8 can restore exactly those and nothing
     *  else. NOTE: this disables ALL hotspot servers — it cannot reliably tell
     *  which one (if any) the admin's own connection depends on, so there is
     *  no selective "keep my own session's hotspot up" behavior here. */
    fun disableHotspotForMaintenance(
        onSuccess: (List<String>) -> Unit,
        onError: (String, List<String>) -> Unit
    ) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) {
                    onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر", emptyList())
                }
                return@launch
            }
            val attemptedIds = mutableListOf<String>()
            try {
                val servers = api.executeStrict("/ip/hotspot/print")
                val toDisable = servers.filter { it["disabled"] != "true" }
                toDisable.forEach { srv ->
                    val id = srv[".id"] ?: throw java.io.IOException("خادم Hotspot بلا معرّف؛ أُوقف التعطيل لتجنب حالة غير معروفة")
                    // Add before the write: a timeout may occur after RouterOS applied it.
                    attemptedIds.add(id)
                    api.executeStrict("/ip/hotspot/set", mapOf("numbers" to id, "disabled" to "yes"))
                }
                insertActivityLog("صيانة الكروت", "تم تعطيل ${toDisable.size} سيرفر هوتسبوت مؤقتًا قبل حذف الكروت المنتهية", host)
                launch(Dispatchers.Main) { onSuccess(attemptedIds.toList()) }
            } catch (e: Exception) {
                val stillDisabled = mutableListOf<String>()
                attemptedIds.asReversed().forEach { id ->
                    try {
                        api.executeStrict("/ip/hotspot/set", mapOf("numbers" to id, "disabled" to "no"))
                    } catch (_: Exception) {
                        stillDisabled.add(id)
                    }
                }
                val rollbackNote = if (stillDisabled.isEmpty()) {
                    " تم التراجع عن التعطيلات الجزئية."
                } else {
                    " تعذر إعادة تفعيل بعض الخوادم؛ معرّفاتها: ${stillDisabled.joinToString()}"
                }
                insertActivityLog("صيانة الكروت", "فشل تعطيل Hotspot: ${e.message}.$rollbackNote", host)
                launch(Dispatchers.Main) { onError((e.message ?: "فشل تعطيل الهوتسبوت") + rollbackNote, stillDisabled) }
            }
        }
    }

    /** Step 5 — v6 follows the supplied maintenance sequence and purges sessions
     *  and logs only after the wizard's separate destructive-action confirmation.
     *  v7 uses its redesigned User Manager tree and only removes orphan sessions. */
    fun cleanStaleUserManagerSessions(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                var removed = 0
                if (detectUmFlavor(api) == UmFlavor.V6) {
                    // Follow the established RouterOS v6 maintenance sequence, but
                    // the UI requires a separate confirmation because this closes
                    // every User Manager session and deletes its usage log.
                    val sessions = api.executeStrict("/tool/user-manager/session/print")
                    sessions.forEach { session ->
                        val id = session[".id"] ?: throw java.io.IOException("جلسة بلا معرّف؛ أُوقف التنظيف لتجنب حذف غير مكتمل")
                        api.executeStrict("/tool/user-manager/session/close-session", mapOf("numbers" to id))
                    }
                    sessions.forEach { session ->
                        val id = session[".id"] ?: throw java.io.IOException("جلسة بلا معرّف؛ أُوقف التنظيف لتجنب حذف غير مكتمل")
                        api.executeStrict("/tool/user-manager/session/remove", mapOf("numbers" to id))
                        removed++
                    }
                    val logs = api.executeStrict("/tool/user-manager/log/print")
                    logs.forEach { log ->
                        val id = log[".id"] ?: throw java.io.IOException("سجل استخدام بلا معرّف؛ أُوقف حذف السجلات")
                        api.executeStrict("/tool/user-manager/log/remove", mapOf("numbers" to id))
                    }
                    insertActivityLog("صيانة الكروت", "تم إغلاق وحذف $removed جلسة User Manager ومسح سجلات الاستخدام على RouterOS v6", host)
                    launch(Dispatchers.Main) { onSuccess(removed) }
                } else {
                    // v7 does not use the legacy v6 cleanup commands. Restrict it
                    // to sessions for users that no longer exist; never close live
                    // user sessions or purge the complete usage log.
                    var routerUsers: List<Map<String, String>> = emptyList()
                    var userReadSucceeded = false
                    var userReadError: Exception? = null
                    for (base in umBases) {
                        try {
                            routerUsers = api.executeStrict("$base/user/print")
                            userReadSucceeded = true
                            break
                        } catch (e: Exception) {
                            userReadError = e
                        }
                    }
                    if (!userReadSucceeded) throw userReadError ?: java.io.IOException("تعذر قراءة مستخدمي User Manager")
                    val sessions = api.executeStrict("$umBase/session/print")
                    val knownUsers = routerUsers.mapNotNull { it["name"] ?: it["username"] }.toSet()
                    if (sessions.isNotEmpty() && knownUsers.isEmpty()) {
                        throw java.io.IOException("تعذر التحقق من أسماء المستخدمين؛ لم تُحذف أي جلسة احتياطًا")
                    }
                    val orphaned = sessions.filter { session ->
                        val username = session["user"] ?: session["username"] ?: session["name"]
                        username != null && username !in knownUsers
                    }
                    var firstError: String? = null
                    orphaned.forEach { session ->
                        val id = session[".id"] ?: return@forEach
                        try {
                            umWrite(api, "session/remove", mapOf("numbers" to id))
                            removed++
                        } catch (e: Exception) {
                            if (firstError == null) firstError = e.message
                        }
                    }
                    insertActivityLog("صيانة الكروت", "تم حذف $removed جلسة User Manager يتيمة فقط؛ لم تُغلق جلسات المستخدمين ولم تُحذف سجلات الاستخدام", host)
                    launch(Dispatchers.Main) {
                        if (firstError == null) onSuccess(removed)
                        else onError("حُذفت $removed جلسة يتيمة، وتعذر حذف بعض الجلسات: $firstError")
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل تنظيف سجلات User Manager") }
            }
        }
    }

    /** Step 6 — on v6, first runs the real, documented router-side command
     *  `/tool/user-manager/database/rebuild`, which compacts/rebuilds the
     *  User Manager database on the router itself (MikroTik recommends
     *  running it after deleting unused users and clearing logs — exactly
     *  the order steps 4→5→6 already follow). That command can take a while
     *  and the router may not answer while it runs, so a read timeout here
     *  is treated as "sent and still running," not a failure. Either way,
     *  this then re-syncs the app's own local cache of the User Manager
     *  profiles+users database (StateFlow + Room) from the router's current
     *  (post-deletion) state. Runs inline (not via the fire-and-forget
     *  refreshData()/fetchRealNativeData()) so onSuccess only fires once the
     *  resync has actually finished. */
    fun rebuildUserManagerLocalDatabase(onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val conn = _currentConnection.value
            if (api == null || conn == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            var rebuildNote = ""
            var rebuildFailure: String? = null
            val flavor = detectUmFlavor(api)
            if (flavor == UmFlavor.V6) {
                try {
                    api.executeStrict("/tool/user-manager/database/rebuild")
                    rebuildNote = " وتمت إعادة بناء قاعدة البيانات على الراوتر (database/rebuild)"
                } catch (e: Exception) {
                    if (isLikelyRouterTimeout(e)) {
                        rebuildNote = " — أُرسل أمر إعادة البناء database/rebuild للراوتر (قد يستغرق دون رد فوري)"
                    } else {
                        rebuildFailure = "تعذّرت إعادة بناء قاعدة User Manager على الراوتر: ${e.message}"
                    }
                }
                if (rebuildFailure != null) {
                    val message = rebuildFailure!!
                    launch(Dispatchers.Main) { onError(message) }
                    return@launch
                }
                // Match the v6 maintenance script's settling window before the
                // next destructive database operation is enabled in the wizard.
                delay(20_000)
            }
            try {
                val umProfList = api.execute("$umBase/profile/print")
                val limitations = umReadProfileLimitations(api)
                val umProfiles = umProfList.map { p ->
                    val name = p["name"] ?: p["name-for-users"] ?: "default"
                    val lim = limitations[name] ?: emptyMap()
                    UserManagerProfile(
                        name = name,
                        validity = p["validity"] ?: p["starts-at"] ?: "30d",
                        price = p["price"]?.toDoubleOrNull()?.toInt() ?: 0,
                        downloadLimit = lim["download-limit"] ?: lim["transfer-limit"] ?: lim["rate-limit-rx"] ?: p["download-limit"] ?: "unlimited",
                        uptimeLimit = lim["uptime-limit"] ?: lim["uptime"] ?: p["uptime-limit"] ?: "unlimited",
                        templateName = name,
                        sharedUsers = p["override-shared-users"]?.toIntOrNull() ?: 1
                    )
                }
                if (umProfiles.isNotEmpty()) {
                    _userManagerProfiles.value = umProfiles
                    cacheDao.insertUserManagerProfiles(umProfiles.map {
                        CachedUserManagerProfile(
                            name = it.name, routerHost = conn.host, validity = it.validity, price = it.price,
                            sharedUsers = it.sharedUsers, downloadLimit = it.downloadLimit,
                            uptimeLimit = it.uptimeLimit, templateName = it.templateName
                        )
                    })
                }

                val umList = api.execute("$umBase/user/print")
                val umUsers = applyRouterExpiry(api, umList.map { mapUserManagerRow(it) })
                _userManagerUsers.value = umUsers
                if (umUsers.isNotEmpty()) {
                    cacheDao.insertUserManagerUsers(
                        umUsers.map { toCachedUserManagerUser(it, conn.host) }
                    )
                }

                insertActivityLog("صيانة الكروت", "تمت إعادة بناء قاعدة بيانات User Manager المحلية من الراوتر (${umUsers.size} مستخدم، ${umProfiles.size} باقة)$rebuildNote", conn.host)
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل إعادة بناء قاعدة User Manager") }
            }
        }
    }

    /** Step 7 — on v6, first runs the real, documented router-side command
     *  `/tool/user-manager/database/rebuild-log` (confirmed from the same
     *  verified production maintenance script as step 6's database/rebuild;
     *  same tolerant handling since it can run for a while without
     *  responding). Then rebuilds the local user-manager *sessions* store by
     *  re-reading $umBase/session/print (reflects the cleanup step 5 just did
     *  on the router). Distinct from step 6, which rebuilds the profiles/users
     *  cache. */
    fun rebuildUserManagerLocalRecords(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            var rebuildLogNote = ""
            var rebuildLogFailure: String? = null
            if (api != null && detectUmFlavor(api) == UmFlavor.V6) {
                try {
                    api.executeStrict("/tool/user-manager/database/rebuild-log")
                    rebuildLogNote = " وتمت إعادة بناء سجل الاستخدام على الراوتر (database/rebuild-log)"
                } catch (e: Exception) {
                    if (isLikelyRouterTimeout(e)) {
                        rebuildLogNote = " — أُرسل أمر إعادة بناء السجل database/rebuild-log للراوتر (قد يستغرق دون رد فوري)"
                    } else {
                        rebuildLogFailure = "تعذّرت إعادة بناء سجل User Manager على الراوتر: ${e.message}"
                    }
                }
                if (rebuildLogFailure != null) {
                    val message = rebuildLogFailure!!
                    launch(Dispatchers.Main) { onError(message) }
                    return@launch
                }
                delay(20_000)
            }
            fetchUserManagerSessions(
                onSuccess = { sessions ->
                    insertActivityLog("صيانة الكروت", "تمت إعادة بناء مخزن سجلات User Manager المحلي (${sessions.size} جلسة)$rebuildLogNote", _currentConnection.value?.host ?: "")
                    onSuccess(sessions.size)
                },
                onError = onError
            )
        }
    }

    /** Step 8 — re-enables exactly the hotspot servers step 2 disabled. */
    fun restoreHotspotAfterMaintenance(disabledServerIds: List<String>, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                disabledServerIds.forEach { id ->
                    api.executeStrict("/ip/hotspot/set", mapOf("numbers" to id, "disabled" to "no"))
                }
                insertActivityLog("صيانة الكروت", "تمت إعادة تفعيل ${disabledServerIds.size} سيرفر هوتسبوت بعد التنظيف", host)
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل إعادة تفعيل الهوتسبوت") }
            }
        }
    }

    /** Step 9 — re-enables and verifies the Hotspot servers disabled by this
     *  wizard, waits briefly, then sends the final router reboot. */
    fun verifyHotspotAndRebootRouter(
        disabledServerIds: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        repositoryScope.launch {
            val api = nativeApi
            val host = _currentConnection.value?.host ?: ""
            if (api == null) {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val targetIds = disabledServerIds.toSet()
                if (targetIds.isNotEmpty()) {
                    val before = api.executeStrict("/ip/hotspot/print")
                    before.filter { row -> row[".id"]?.let(targetIds::contains) == true && row["disabled"] == "true" }
                        .forEach { server ->
                            val id = server[".id"] ?: return@forEach
                            api.executeStrict("/ip/hotspot/set", mapOf("numbers" to id, "disabled" to "no"))
                        }
                    val after = api.executeStrict("/ip/hotspot/print")
                    val foundIds = after.mapNotNull { it[".id"] }.toSet()
                    val missingIds = targetIds - foundIds
                    if (missingIds.isNotEmpty()) {
                        throw java.io.IOException(
                            "تعذر العثور على خوادم Hotspot التي عطّلها المعالج؛ أُوقف reboot: ${missingIds.joinToString()}"
                        )
                    }
                    val stillDisabled = after.filter { row -> row[".id"]?.let(targetIds::contains) == true && row["disabled"] == "true" }
                    if (stillDisabled.isNotEmpty()) {
                        throw java.io.IOException(
                            "لم تُفعّل خوادم Hotspot التالية؛ أُوقف reboot: ${stillDisabled.mapNotNull { it[".id"] }.joinToString()}"
                        )
                    }
                }
                insertActivityLog("صيانة الكروت", "تم التحقق من إعادة تفعيل خوادم Hotspot التي عطّلها المعالج قبل إعادة التشغيل", host)
                delay(5_000)
                launch(Dispatchers.Main) {
                    rebootRouter(
                        onSuccess = { onSuccess("تمت إعادة تفعيل Hotspot والتحقق منها، ثم أُرسل أمر إعادة التشغيل. $it") },
                        onError = onError
                    )
                }
            } catch (e: Exception) {
                insertActivityLog("صيانة الكروت", "أُوقف reboot لأن إعادة تفعيل Hotspot أو التحقق منها فشل: ${e.message}", host)
                launch(Dispatchers.Main) { onError("لن يُعاد تشغيل الراوتر قبل استعادة Hotspot والتحقق منه: ${e.message}") }
            }
        }
    }

    /* =========================================
       TOOLS & SERVICES REPOSITORY EXTENSIONS
       ========================================= */

    fun setHtmlDirectory(dirName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    val profiles = api.execute("/ip/hotspot/profile/print")
                    val firstId = profiles.firstOrNull()?.get(".id")
                        ?: throw java.io.IOException("الراوتر لم يرجع أي بروفايل هوتسبوت لتطبيق المجلد عليه")
                    api.executeStrict("/ip/hotspot/profile/set", "=numbers=$firstId", "=html-directory=$dirName")
                    insertActivityLog("تغيير مجلد الهوتسبوت", "تم ضبط مجلد صفحة تسجيل الدخول إلى: $dirName", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل تطبيق مجلد الهوتسبوت: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر حالياً") }
            }
        }
    }

    fun toggleFreeHotspot(enableFree: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    val servers = api.execute("/ip/hotspot/print")
                    for (srv in servers) {
                        val srvId = srv[".id"] ?: continue
                        api.executeStrict("/ip/hotspot/set", "=numbers=$srvId", "=disabled=${if (enableFree) "yes" else "no"}")
                    }
                    val statusText = if (enableFree) "تم فتح الشبكة مجاناً بنجاح" else "تم إعادة تشغيل نظام كروت الهوتسبوت"
                    insertActivityLog("حالة الهوتسبوت", statusText, _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل تغيير حالة السيرفر: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun toggleTtlBypass(blockSharing: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    val rules = api.execute("/ip/firewall/mangle/print")
                    val existing = rules.find { it["comment"]?.contains("ABO_TALAL_ANTI_SHARE") == true }
                    if (blockSharing) {
                        if (existing == null) {
                            api.executeStrict(
                                "/ip/firewall/mangle/add",
                                "=chain=postrouting",
                                "=action=change-ttl",
                                "=new-ttl=set:1",
                                "=passthrough=yes",
                                "=comment=ABO_TALAL_ANTI_SHARE"
                            )
                        }
                    } else {
                        if (existing != null) {
                            val ruleId = existing[".id"] ?: ""
                            if (ruleId.isNotBlank()) {
                                api.executeStrict("/ip/firewall/mangle/remove", "=numbers=$ruleId")
                            }
                        }
                    }
                    insertActivityLog("مشاركة الإنترنت", if (blockSharing) "تفعيل TTL=1" else "تعطيل TTL=1", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل تعديل قاعدة المانجل: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun toggleInterface(interfaceName: String, enable: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/interface/set", "=numbers=$interfaceName", "=disabled=${if (enable) "no" else "yes"}")
                    fetchRealInterfaces(_currentConnection.value ?: return@launch)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("تعذر تعديل المنفذ: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun resetInterfaceCounters(interfaceName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/interface/reset-counters", "=numbers=$interfaceName")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل تصفير العدادات: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun fetchPppoeUsers(onSuccess: (List<PppoeUser>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    val secrets = api.execute("/ppp/secret/print")
                    val actives = api.execute("/ppp/active/print")
                    val activeMap = actives.associateBy { it["name"] ?: "" }

                    val list = secrets.map { sec ->
                        val name = sec["name"] ?: ""
                        val act = activeMap[name]
                        PppoeUser(
                            name = name,
                            password = sec["password"] ?: "",
                            service = sec["service"] ?: "pppoe",
                            profile = sec["profile"] ?: "default",
                            localAddress = sec["local-address"] ?: (act?.get("address") ?: ""),
                            remoteAddress = sec["remote-address"] ?: "",
                            comment = sec["comment"] ?: "",
                            disabled = sec["disabled"] == "true",
                            callerId = act?.get("caller-id") ?: "",
                            uptime = act?.get("uptime") ?: "0s",
                            isActive = act != null
                        )
                    }
                    launch(Dispatchers.Main) { onSuccess(list) }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل جلب حسابات البروباند: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun addPppoeUser(user: PppoeUser, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict(
                        "/ppp/secret/add",
                        "=name=${user.name}",
                        "=password=${user.password}",
                        "=service=${user.service}",
                        "=profile=${user.profile}",
                        if (user.comment.isNotBlank()) "=comment=${user.comment}" else "=comment="
                    )
                    insertActivityLog("بروباند", "إضافة مشترك بروباند جديد ${user.name}", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل إضافة المستخدم: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun deletePppoeUser(name: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/ppp/secret/remove", "=numbers=$name")
                    insertActivityLog("بروباند", "حذف مشترك بروباند $name", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل حذف المستخدم: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun installTelegramScript(scriptName: String, scriptSource: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    // Check if exists
                    val existing = api.execute("/system/script/print").find { it["name"] == scriptName }
                    if (existing != null) {
                        val scriptId = existing[".id"] ?: scriptName
                        api.executeStrict("/system/script/set", "=numbers=$scriptId", "=source=$scriptSource")
                    } else {
                        api.executeStrict(
                            "/system/script/add",
                            "=name=$scriptName",
                            // Minimal least-privilege policy: the notification script only
                            // reads state and sends one /tool fetch — no reboot, ftp,
                            // password, sniff or sensitive privileges.
                            "=policy=read,write,policy,test",
                            "=source=$scriptSource"
                        )
                    }
                    insertActivityLog("تيليجرام", "تثبيت سكربت إشعارات تيليجرام على الراوتر: $scriptName", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("تعذر حفظ السكربت على الراوتر: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun executePing(target: String, count: Int = 4, onResult: (String) -> Unit) {
        repositoryScope.launch {
            val cleanTarget = target.trim()
                        val api = nativeApi
            if (api != null) {
                try {
                    val res = api.executeStrict("/ping", "=address=$cleanTarget", "=count=$count")
                    val output = if (res.isNotEmpty()) {
                        res.joinToString("\n") { row ->
                            val host = row["host"] ?: cleanTarget
                            val size = row["size"] ?: "64"
                            val ttl = row["ttl"] ?: "56"
                            val time = row["time"] ?: "15ms"
                            "Reply from $host: bytes=$size time=$time TTL=$ttl"
                        }
                    } else {
                        "لم يتم تلقي أي رد من المضيف: $cleanTarget"
                    }
                    launch(Dispatchers.Main) { onResult(output) }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onResult("خطأ أثناء تنفيذ Ping: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onResult("غير متصل بالراوتر") }
            }
        }
    }

    fun fetchGenericSectionData(categoryKey: String, onSuccess: (List<RouterGenericItem>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    val cmd = when (categoryKey) {
                        "dhcp_server" -> "/ip/dhcp-server/print"
                        "ip_addresses" -> "/ip/address/print"
                        "dhcp_leases" -> "/ip/dhcp-server/lease/print"
                        "dhcp_relay" -> "/ip/dhcp-relay/print"
                        "nat" -> "/ip/firewall/nat/print"
                        "dns" -> "/ip/dns/static/print"
                        "address_lists" -> "/ip/firewall/address-list/print"
                        "firewall_filter" -> "/ip/firewall/filter/print"
                        "arp" -> "/ip/arp/print"
                        "mangle" -> "/ip/firewall/mangle/print"
                        "connections" -> "/ip/firewall/connection/print"
                        "queues" -> "/queue/simple/print"
                        "neighbors" -> "/ip/neighbor/print"
                        "resources" -> "/system/resource/print"
                        "routerboard" -> "/system/routerboard/print"
                        "health" -> "/system/health/print"
                        "logs" -> "/log/print"
                        "users" -> "/user/print"
                        "backup" -> "/file/print"
                        "fetch" -> "/file/print"
                        "blocked_sites" -> "/ip/dns/static/print"
                        "layer7" -> "/ip/firewall/layer7-protocol/print"
                        "bandwidth_test" -> "/tool/bandwidth-server/print"
                        "sniffer" -> "/tool/sniffer/print"
                        "torch" -> "" // interactive tool — see honest error below
                        "netwatch" -> "/tool/netwatch/print"
                        "graphing" -> "/tool/graphing/print"
                        "graphing_interface" -> "/tool/graphing/interface/print"
                        "ppp_active" -> "/ppp/active/print"
                        "email" -> "/tool/e-mail/print"
                        "sms" -> "/tool/sms/print"
                        else -> "/interface/print"
                    }
                    if (cmd.isEmpty()) {
                        launch(Dispatchers.Main) {
                            onError("Torch أداة مراقبة تفاعلية مستمرة لا يمكن عرضها كقائمة — استخدم WinBox لتشغيلها مباشرة على الراوتر")
                        }
                        return@launch
                    }
                    // RouterOS v6 returns !trap for unsupported/unauthorized
                    // menus; strict execution prevents that from looking like
                    // a valid but empty section in the UI.
                    val resList = api.executeStrict(cmd)
                    val spec = sectionFieldSpecs[categoryKey] ?: SectionFieldSpec()
                    val genericList = resList.mapIndexed { idx, map ->
                        val title = spec.titleKeys.firstNotNullOfOrNull { map[it] }
                            ?: "عنصر #${idx + 1}"
                        val sub = spec.subtitleKeys.firstNotNullOfOrNull { map[it] } ?: ""
                        val disabled = map["disabled"]?.trim()?.lowercase() in setOf("true", "yes", "on", "1")
                        val status = map["status"] ?: if (disabled) "معطل" else "نشط"
                        RouterGenericItem(
                            id = map[".id"] ?: idx.toString(),
                            title = title,
                            subtitle = sub,
                            status = status,
                            extra = map["comment"] ?: "",
                            isEnabled = !disabled,
                            rawProperties = map
                        )
                    }
                    launch(Dispatchers.Main) { onSuccess(genericList) }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("خطأ أثناء قراءة البيانات من الراوتر: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    // Precise per-section display mapping: which RouterOS fields label each row.
    private data class SectionFieldSpec(
        val titleKeys: List<String> = listOf("name", "address", "chain", "src-address", "host", "message"),
        val subtitleKeys: List<String> = listOf("interface", "mac-address", "action", "target", "time", "type")
    )

    private val sectionFieldSpecs = mapOf(
        "dhcp_server" to SectionFieldSpec(listOf("name", "interface"), listOf("interface", "lease-time", "address-pool")),
        "ip_addresses" to SectionFieldSpec(listOf("address", "network"), listOf("interface", "network")),
        "dhcp_leases" to SectionFieldSpec(listOf("address", "host-name"), listOf("mac-address", "host-name", "status")),
        "dhcp_relay" to SectionFieldSpec(listOf("name", "interface"), listOf("interface", "dhcp-server", "local-address")),
        "nat" to SectionFieldSpec(listOf("chain", "action"), listOf("action", "src-address", "dst-address", "out-interface")),
        "dns" to SectionFieldSpec(listOf("name", "address"), listOf("address", "type", "ttl")),
        "blocked_sites" to SectionFieldSpec(listOf("name", "address"), listOf("address", "type", "regexp", "comment")),
        "address_lists" to SectionFieldSpec(listOf("list", "address"), listOf("address", "comment")),
        "firewall_filter" to SectionFieldSpec(listOf("chain", "action"), listOf("action", "src-address", "dst-address", "protocol")),
        "arp" to SectionFieldSpec(listOf("address", "mac-address"), listOf("mac-address", "interface", "status")),
        "mangle" to SectionFieldSpec(listOf("chain", "action"), listOf("action", "src-address", "dst-address", "protocol")),
        "connections" to SectionFieldSpec(listOf("src-address", "dst-address"), listOf("dst-address", "protocol", "tcp-state")),
        "queues" to SectionFieldSpec(listOf("name", "target"), listOf("target", "max-limit", "rate")),
        "neighbors" to SectionFieldSpec(listOf("identity", "address"), listOf("interface", "mac-address", "platform", "version")),
        "resources" to SectionFieldSpec(listOf("version", "board-name"), listOf("board-name", "uptime", "cpu-load")),
        "routerboard" to SectionFieldSpec(listOf("model", "serial-number"), listOf("serial-number", "firmware-type", "current-firmware")),
        "health" to SectionFieldSpec(listOf("name", "value"), listOf("value", "type")),
        "logs" to SectionFieldSpec(listOf("message", "topics"), listOf("time", "topics")),
        "users" to SectionFieldSpec(listOf("name", "group"), listOf("group", "last-logged-in")),
        "backup" to SectionFieldSpec(listOf("name", "type"), listOf("type", "size", "creation-time")),
        "fetch" to SectionFieldSpec(listOf("name", "type"), listOf("type", "size", "creation-time")),
        "layer7" to SectionFieldSpec(listOf("name", "regexp"), listOf("regexp", "comment")),
        "bandwidth_test" to SectionFieldSpec(listOf("enabled", "max-session-count"), listOf("max-session-count", "authenticate", "allocate-udp-ports-from")),
        "sniffer" to SectionFieldSpec(listOf("filter-interface", "filter-ip-address"), listOf("filter-interface", "memory-limit", "file-name")),
        "torch" to SectionFieldSpec(listOf("name", "trigger"), listOf("trigger", "threshold", "chain", "timeout")),
        "netwatch" to SectionFieldSpec(listOf("host", "name"), listOf("interval", "timeout", "status", "comment")),
        "graphing" to SectionFieldSpec(listOf("interface", "allow-address"), listOf("allow-address", "store-on-disk", "comment")),
        "graphing_interface" to SectionFieldSpec(listOf("interface", "allow-address"), listOf("allow-address", "store-on-disk", "comment")),
        "ppp_active" to SectionFieldSpec(listOf("name", "service"), listOf("address", "uptime", "caller-id", "encoding")),
        "email" to SectionFieldSpec(listOf("server", "from"), listOf("from", "port", "user")),
        "sms" to SectionFieldSpec(listOf("port", "number"), listOf("channel", "secret", "allowed-number"))
    )

    // ==========================================
    // 🌐 MIKROTIK CLOUD MANAGEMENT (/ip/cloud)
    // ==========================================

    fun fetchCloudData(onSuccess: (MikroTikCloudInfo) -> Unit = {}, onError: (String) -> Unit = {}) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    val cloudResults = api.execute("/ip/cloud/print")
                    val info = cloudResults.firstOrNull() ?: emptyMap()
                    val ddns = info["ddns-enabled"]?.equals("true", ignoreCase = true) ?: (info["ddns-enabled"]?.equals("yes", ignoreCase = true) ?: true)
                    val updateTime = info["update-time"]?.equals("true", ignoreCase = true) ?: (info["update-time"]?.equals("yes", ignoreCase = true) ?: true)
                    val publicIp = info["public-address"] ?: info["public-ip"] ?: ""
                    val publicIp6 = info["public-address-ipv6"] ?: ""
                    val dnsName = info["dns-name"] ?: ""
                    val status = info["status"] ?: "updated"
                    val warning = info["warning"] ?: ""

                    val parsed = MikroTikCloudInfo(
                        ddnsEnabled = ddns,
                        updateTime = updateTime,
                        publicAddress = publicIp,
                        publicAddressIpv6 = publicIp6,
                        dnsName = dnsName,
                        status = status,
                        warning = warning
                    )
                    _cloudInfo.value = parsed

                    try {
                        val backupList = api.execute("/system/backup/cloud/print")
                        _cloudBackups.value = backupList.mapIndexed { idx, b ->
                            MikroTikCloudBackup(
                                // RouterOS v6 addresses cloud slots by their 0-based
                                // number (`remove-file number=0`), not by a v7 .id.
                                id = idx.toString(),
                                name = b["name"] ?: "cloud-backup-$idx",
                                size = b["size"] ?: "",
                                downloadStatus = b["status"] ?: "ok",
                                time = b["date"] ?: "",
                                encrypted = true
                            )
                        }
                    } catch (_: Exception) {}

                    launch(Dispatchers.Main) { onSuccess(parsed) }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل جلب بيانات الكلاود") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun forceUpdateCloud(onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/ip/cloud/force-update")
                    fetchCloudData()
                    insertActivityLog("تحديث الكلاود", "تم إرسال أمر التحديث الفوري لكلاود مايكروتك", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل تحديث الكلاود") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun setCloudDdns(enabled: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/ip/cloud/set", mapOf("ddns-enabled" to if (enabled) "yes" else "no"))
                    _cloudInfo.update { it.copy(ddnsEnabled = enabled) }
                    insertActivityLog("إعدادات الكلاود", "تم ${if (enabled) "تفعيل" else "تعطيل"} الـ DDNS السحابي", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل حفظ إعدادات الكلاود") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun setCloudUpdateTime(enabled: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/ip/cloud/set", mapOf("update-time" to if (enabled) "yes" else "no"))
                    _cloudInfo.update { it.copy(updateTime = enabled) }
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل ضبط مزامنة الوقت") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun uploadCloudBackup(name: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    val params = mutableMapOf("action" to "create-and-upload")
                    if (name.isNotBlank()) params["name"] = name.trim()
                    if (password.isNotBlank()) params["password"] = password.trim()
                    // v6 exposes exactly one cloud slot. Re-uploading without
                    // replace= fails once that slot is occupied, so reuse the
                    // existing slot explicitly instead of surfacing a false error.
                    val existing = runCatching { api.execute("/system/backup/cloud/print") }.getOrNull()
                        ?.firstOrNull()?.get("name")?.takeIf { it.isNotBlank() }
                    if (existing != null) params["replace"] = existing
                    api.executeStrict("/system/backup/cloud/upload-file", params)
                    fetchCloudData()
                    insertActivityLog("نسخ سحابي", "تم رفع نسخة احتياطية جديدة إلى سحابة مايكروتك", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل رفع النسخة السحابية") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }

    fun removeCloudBackup(backupId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/system/backup/cloud/remove-file", mapOf("number" to backupId))
                    fetchCloudData()
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل حذف النسخة السحابية") }
                }
            } else {
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }
}
