package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.ActivityLog
import com.example.data.local.ActivityLogDao
import com.example.data.local.RouterConnection
import com.example.data.local.*
import com.example.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

    // Native Socket API instance for port 8728
    private var nativeApi: MikroTikNativeApi? = null

    // ---- Detected RouterOS version & transport state (set after every successful login) ----
    // rosMajor: 6 or 7 parsed once from /system/resource/print (NOT inferred from empty lists).
    // umBase: v7 -> "/user-manager", v6 -> "/tool/user-manager".
    @Volatile private var rosMajor: Int? = null
    @Volatile private var umBasePath: String? = null
    // Non-null ("http(s)://host:port") only while connected through the REST transport.
    @Volatile private var restBase: String? = null

    val umBase: String? get() = umBasePath
    val routerOsMajor: Int? get() = rosMajor
    val isRestTransport: Boolean get() = restBase != null

    private fun clearTransportState() {
        rosMajor = null
        umBasePath = null
        restBase = null
    }

    /** Reads /system/resource/print once and derives [rosMajor]/[umBasePath]. */
    private fun detectRosVersion(api: MikroTikNativeApi) {
        val version = api.execute("/system/resource/print").data.firstOrNull()?.get("version")
        val major = version?.trim()?.substringBefore('.')?.toIntOrNull()
        if (major != null) {
            rosMajor = major
            umBasePath = if (major >= 7) "/user-manager" else "/tool/user-manager"
            Log.i("NetCraft", "Detected RouterOS $version -> major=$major, umBase=$umBasePath")
        } else {
            rosMajor = null
            umBasePath = null
            Log.w("NetCraft", "Could not parse RouterOS version from '$version' — UM paths will be probed")
        }
    }

    /** REST counterpart of [detectRosVersion] (REST only exists on ROS v7). */
    private fun detectRosVersionRest(conn: RouterConnection) {
        try {
            val credential = Credentials.basic(conn.username, conn.password)
            val req = Request.Builder()
                .url("$restBase/rest/system/resource")
                .header("Authorization", credential)
                .build()
            restClientFor(restBase ?: "http")?.newCall(req)?.execute().use { res ->
                if (res != null && res.isSuccessful) {
                    val json = JSONObject(res.body?.string() ?: "{}")
                    val version = json.optString("version", "")
                    val major = version.trim().substringBefore('.').toIntOrNull()
                    if (major != null) {
                        rosMajor = major
                        umBasePath = if (major >= 7) "/user-manager" else "/tool/user-manager"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("NetCraft", "REST version detect failed: ${e.message}")
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

    // OkHttp clients — certificate policy:
    // - [okHttpClient]: default system trust, used for ALL http (cleartext) REST calls.
    // - [okHttpSslOptInClient]: relaxes certificate verification ONLY for connections
    //   where the user explicitly selected SSL — the per-connection opt-in for router
    //   self-signed certificates (mirrors the native API-SSL policy).
    private var okHttpClient: OkHttpClient? = null
    private var okHttpSslOptInClient: OkHttpClient? = null

    init {
        setupOkHttpClients()
    }

    private fun setupOkHttpClients() {
        val base = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        okHttpClient = base
        try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            okHttpSslOptInClient = base.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true } // explicit SSL opt-in connections only
                .build()
        } catch (e: Exception) {
            Log.w("NetCraft", "SSL opt-in client unavailable, falling back to default trust", e)
            okHttpSslOptInClient = base
        }
    }

    /** The OkHttp client to use for a REST call with the given scheme — the
     *  relaxed-trust client is only handed out for explicitly-selected https. */
    private fun restClientFor(protocol: String): OkHttpClient? =
        if (protocol.equals("https", ignoreCase = true)) okHttpSslOptInClient ?: okHttpClient
        else okHttpClient

    // Connect real router (Supports Port 8728 native binary API & HTTP/REST)
    // Automatic transport fallback: native login failure (connection-level) -> try REST;
    // REST connection/404 failure -> try native on 8728/8729. Credential rejections
    // are terminal — the same credentials would fail on the other transport too.
    fun connectToRouter(connection: RouterConnection, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            _operationStatus.value = "جاري الاتصال بـ ${connection.host}..."
            if (connection.host.isBlank()) {
                onError("عنوان الراوتر لا يمكن أن يكون فارغاً")
                return@launch
            }

            val isWebPort = connection.port in listOf(80, 443, 8080, 8081)
            var fallbackError: String? = null

            // 1. Native MikroTik API Socket first for API ports (and custom/unknown ports)
            if (!isWebPort) {
                try {
                    val api = MikroTikNativeApi(
                        host = connection.host,
                        port = connection.port,
                        connectTimeoutMs = 12000,
                        commandTimeoutMs = 25000,
                        useSsl = connection.useSsl
                    )
                    val (loginSuccess, loginMsg) = api.login(connection.username, connection.password)
                    if (loginSuccess) {
                        onNativeConnected(api, connection, onSuccess)
                        return@launch
                    }
                    val msg = loginMsg.ifBlank { "خطأ في اسم المستخدم أو كلمة المرور" }
                    if (isAuthRejection(msg)) {
                        launch(Dispatchers.Main) { onError(msg) }
                        return@launch
                    }
                    // Connection-level failure (closed port / timeout) — REST might still work
                    fallbackError = "اتصال API الأصلي على المنفذ ${connection.port} فشل: $msg"
                    Log.w("NetCraft", "Native login failed, will try REST: $msg")
                } catch (e: Exception) {
                    Log.e("NetCraft", "Native API connection failed: ${e.message}")
                    fallbackError = "تعذر الاتصال بـ ${connection.host}:${connection.port}"
                }
            }

            // 2. REST API (web port, or native connection-level fallback)
            val protocol = if (connection.useSsl) "https" else "http"
            val restBaseCandidate = "$protocol://${connection.host}:${connection.port}"
            try {
                val credential = Credentials.basic(connection.username, connection.password)
                val request = Request.Builder()
                    .url("$restBaseCandidate/rest/system/resource")
                    .header("Authorization", credential)
                    .build()

                Log.d("NetCraft", "Connecting via REST: $restBaseCandidate/rest/system/resource")
                restClientFor(protocol)?.newCall(request)?.execute().use { response ->
                    if (response != null && response.isSuccessful) {
                        restBase = restBaseCandidate
                        _currentConnection.value = connection
                        _isConnected.value = true
                        detectRosVersionRest(connection)

                        insertActivityLog("تسجيل الدخول", "تم الاتصال بنجاح بالراوتر ${connection.name} (${connection.host}) عبر REST", connection.host)

                        fetchRealRouterData(connection)
                        fetchRealUserManagerDataRest(connection)
                        startRealtimeTicker()

                        launch(Dispatchers.Main) { onSuccess() }
                        return@launch
                    }
                    val code = response?.code ?: 0
                    val msg = when (code) {
                        401 -> "خطأ في اسم المستخدم أو كلمة المرور (غير مصرح)"
                        404 -> "لم يتم العثور على واجهة REST API. يرجى تفعيلها في الراوتر (ROS v7)"
                        else -> "فشل الاتصال بالراوتر. رمز الخطأ: $code"
                    }
                    if (code == 401) {
                        launch(Dispatchers.Main) { onError(msg) }
                        return@launch
                    }
                    fallbackError = (fallbackError?.plus("\n") ?: "") + "اتصال REST فشل: $msg"
                }
            } catch (ioe: IOException) {
                Log.e("NetCraft", "REST connection error", ioe)
                fallbackError = (fallbackError?.plus("\n") ?: "") + "تعذر الوصول إلى الآيبي ${connection.host} عبر REST"
            }

            // 3. REST failed on a web port — the router may still expose the native API
            if (isWebPort || fallbackError != null) {
                for (apiPort in intArrayOf(8728, 8729)) {
                    if (connection.port == apiPort) continue
                    try {
                        val api = MikroTikNativeApi(
                            host = connection.host,
                            port = apiPort,
                            connectTimeoutMs = 8000,
                            commandTimeoutMs = 25000,
                            useSsl = apiPort == 8729
                        )
                        val (loginSuccess, loginMsg) = api.login(connection.username, connection.password)
                        if (loginSuccess) {
                            Log.i("NetCraft", "Auto-fallback to native API on port $apiPort succeeded")
                            onNativeConnected(api, connection, onSuccess)
                            return@launch
                        }
                        // Credentials rejected on the API — terminal
                        if (isAuthRejection(loginMsg)) {
                            launch(Dispatchers.Main) { onError(loginMsg.ifBlank { "خطأ في اسم المستخدم أو كلمة المرور" }) }
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.w("NetCraft", "Native fallback on $apiPort failed: ${e.message}")
                    }
                }
            }

            launch(Dispatchers.Main) {
                onError(fallbackError ?: "تعذر الوصول إلى الآيبي ${connection.host}. تأكد من اتصال هاتفك بشبكة الراوتر.")
            }
        }
    }

    /** Credential rejections are identical on every transport — no point falling back. */
    private fun isAuthRejection(msg: String): Boolean {
        val m = msg.trim()
        return m.contains("اسم المستخدم أو كلمة المرور") ||
            m.contains("غير مصرح") ||
            m.contains("invalid user", ignoreCase = true) ||
            m.contains("not allowed", ignoreCase = true)
    }

    private fun onNativeConnected(api: MikroTikNativeApi, connection: RouterConnection, onSuccess: () -> Unit) {
        nativeApi = api
        _currentConnection.value = connection
        _isConnected.value = true
        clearTransportState()
        detectRosVersion(api)

        insertActivityLog("تسجيل الدخول", "تم الاتصال بنجاح بالراوتر عبر المنفذ ${connection.port}", connection.host)

        // Fetch real initial data via native commands
        fetchRealNativeData(api, connection)
        startRealtimeTicker()

        repositoryScope.launch(Dispatchers.Main) { onSuccess() }
    }

    // Fast socket connection test without logging in
    fun testConnection(host: String, port: Int, onResult: (Boolean, String) -> Unit) {
        repositoryScope.launch {
            val res = MikroTikNativeApi.testSocket(host, port)
            launch(Dispatchers.Main) {
                onResult(res.first, res.second)
            }
        }
    }

    // Native data retrieval via Port 8728
    private fun fetchRealNativeData(api: MikroTikNativeApi, connection: RouterConnection) {
        repositoryScope.launch {
            try {
                // 1. System Resource
                val res = api.execute("/system/resource/print").data.firstOrNull() ?: emptyMap()
                val totalMem = res["total-memory"]?.toLongOrNull() ?: 0L
                val freeMem = res["free-memory"]?.toLongOrNull() ?: 0L
                val cpu = res["cpu-load"]?.toIntOrNull() ?: 0
                val uptime = res["uptime"] ?: "-"
                val version = res["version"] ?: "-"
                val board = res["board-name"] ?: "-"

                // 2. Hotspot User Profiles
                var hsProfList = api.execute("/ip/hotspot/user/profile/print").data
                if (hsProfList.isEmpty()) {
                    hsProfList = api.execute("/ip/hotspot/profile/print").data
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
                val hsList = api.execute("/ip/hotspot/user/print").data
                val hsUsers = hsList.map {
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
                if (hsUsers.isNotEmpty()) {
                    _hotspotUsers.value = hsUsers
                }

                // 4. Active Hotspot
                val actList = api.execute("/ip/hotspot/active/print").data
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

                // 5. User Manager Profiles — via the detected $umBase (version-aware,
                // probe fallback only when detection was impossible)
                val umProfList = umRead(api, "profile/print")
                if (umProfList.isEmpty()) {
                    insertActivityLog("باقات اليوزرمانجر", api.lastError
                        ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر", connection.host)
                }
                val limitations = umLimitations(api)

                val umProfiles = umProfList.map { p -> parseUmProfileRow(p, limitations) }
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

                // 6. User Manager Users
                val umList = umRead(api, "user/print")
                val umUsers = umList.map { parseUmUserRow(it) }
                if (umUsers.isNotEmpty()) {
                    _userManagerUsers.value = umUsers
                }

                // 7. Interfaces
                val ifList = api.execute("/interface/print").data
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

                if (hsUsers.isNotEmpty()) {
                    cacheDao.insertHotspotUsers(hsUsers.map {
                        CachedHotspotUser(
                            id = it.id,
                            routerHost = connection.host,
                            name = it.name,
                            password = it.password,
                            profile = it.profile,
                            limitUptime = it.limitUptime,
                            comment = it.comment,
                            disabled = it.disabled,
                            bytesOut = it.bytesOut,
                            bytesIn = it.bytesIn
                        )
                    })
                }

                if (umUsers.isNotEmpty()) {
                    cacheDao.insertUserManagerUsers(umUsers.map {
                        CachedUserManagerUser(
                            username = it.username,
                            routerHost = connection.host,
                            password = it.password,
                            profile = it.profile,
                            active = it.active,
                            uptimeUsed = it.uptimeUsed
                        )
                    })
                }
            } catch (e: Exception) {
                Log.e("MikroTikRepository", "Error fetching native data", e)
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
                delay(600)

                if (api != null && _isConnected.value) {
                    // Fetch Profiles — version-aware via $umBase
                    val umProfList = umRead(api, "profile/print")
                    if (umProfList.isEmpty()) {
                        insertActivityLog("باقات اليوزرمانجر", api.lastError
                            ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر",
                            _currentConnection.value?.host ?: "")
                    }
                    val limitations = umLimitations(api)

                    if (umProfList.isNotEmpty()) {
                        val umProfiles = umProfList.map { p -> parseUmProfileRow(p, limitations) }
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

                    // Fetch Users / Cards
                    val umList = umRead(api, "user/print")
                    if (umList.isNotEmpty()) {
                        val umUsers = umList.map { parseUmUserRow(it) }
                        _userManagerUsers.value = umUsers
                        cacheDao.insertUserManagerUsers(umUsers.map {
                            CachedUserManagerUser(
                                username = it.username,
                                routerHost = host,
                                password = it.password,
                                profile = it.profile,
                                active = it.active,
                                uptimeUsed = it.uptimeUsed
                            )
                        })
                    }
                }

                // STEP 2: Sync Hotspot Profiles & Users
                launch(Dispatchers.Main) { onProgress(1, 4, "جاري مزامنة باقات وكروت ومستخدمي الهوتسبوت...") }
                delay(600)

                if (api != null && _isConnected.value) {
                    var hsProfList = api.execute("/ip/hotspot/user/profile/print").data
                    if (hsProfList.isEmpty()) {
                        hsProfList = api.execute("/ip/hotspot/profile/print").data
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

                    val hsList = api.execute("/ip/hotspot/user/print").data
                    if (hsList.isNotEmpty()) {
                        val hsUsers = hsList.map {
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
                        _hotspotUsers.value = hsUsers
                        cacheDao.insertHotspotUsers(hsUsers.map {
                            CachedHotspotUser(
                                id = it.id,
                                routerHost = host,
                                name = it.name,
                                password = it.password,
                                profile = it.profile,
                                limitUptime = it.limitUptime,
                                comment = it.comment,
                                disabled = it.disabled,
                                bytesOut = it.bytesOut,
                                bytesIn = it.bytesIn
                            )
                        })
                    }

                    val actList = api.execute("/ip/hotspot/active/print").data
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
                delay(600)

                if (api != null && _isConnected.value) {
                    val ifList = api.execute("/interface/print").data
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

                    val res = api.execute("/system/resource/print").data.firstOrNull()
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
                delay(500)

                insertActivityLog("مزامنة قاعدة البيانات", "تمت مزامنة بيانات الراوتر والهوتسبوت واليوزرمانجر محلياً بنجاح", host)
                launch(Dispatchers.Main) {
                    onProgress(4, 4, "اكتملت المزامنة بنجاح تام!")
                    delay(350)
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
        _isConnected.value = false
        try {
            nativeApi?.close()
        } catch (_: Exception) {}
        nativeApi = null
        clearTransportState()
        lastIfaceCounters.clear()
        _currentConnection.value = null
        _routerStats.value = null
        insertActivityLog("تسجيل الخروج", "تم فصل الاتصال بالراوتر", "")
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
                    Log.w("MikroTikRepository", "Protected ticker tick from error: ${t.message}")
                }
                counter++
                delay(3000) // update stats & heartbeat every 3 seconds
            }
        }
    }

    private fun tickNativeDashboard(api: MikroTikNativeApi, connection: RouterConnection, counter: Int) {
        try {
            // Heartbeat + CPU / Resource update (Every 3 seconds)
            val resList = api.execute("/system/resource/print").data
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
                val actList = api.execute("/ip/hotspot/active/print").data
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

                val ifList = api.execute("/interface/print").data
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
            Log.w("MikroTikRepository", "Error in tickNativeDashboard: ${e.message}")
        }
    }

    /* =========================================
       REAL MIKROTIK (ROUTEROS v7 REST API) Implementation
       ========================================= */
    private fun fetchRealRouterData(connection: RouterConnection) {
        val host = connection.host
        // Fetch background details
        repositoryScope.launch {
            fetchRealUsers(connection)
            fetchRealActive(connection)
            fetchRealProfiles(connection)
            fetchRealInterfaces(connection)
        }
    }

    /** Fetches User-Manager users & profiles over REST (v7) — REST connects used to
     *  carry no UM data at all, leaving the UM screens empty for no honest reason. */
    private fun fetchRealUserManagerDataRest(connection: RouterConnection) {
        repositoryScope.launch {
            try {
                val profRows = restUmRead("profile", connection)
                if (profRows.isNotEmpty()) {
                    val umProfiles = profRows.map { p -> parseUmProfileRow(p, emptyMap()) }
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
                val userRows = restUmRead("user", connection)
                if (userRows.isNotEmpty()) {
                    val umUsers = userRows.map { parseUmUserRow(it) }
                    _userManagerUsers.value = umUsers
                    cacheDao.insertUserManagerUsers(umUsers.map {
                        CachedUserManagerUser(
                            username = it.username,
                            routerHost = connection.host,
                            password = it.password,
                            profile = it.profile,
                            active = it.active,
                            uptimeUsed = it.uptimeUsed
                        )
                    })
                }
            } catch (e: Exception) {
                Log.w("NetCraft", "REST UM fetch failed: ${e.message}")
            }
        }
    }

    /** Router-side search of user-manager cards via RouterOS query words
     *  (?name~=...) — far cheaper than printing thousands of rows on big routers. */
    fun fetchUserManagerUsersByQuery(query: String, onSuccess: (List<UserManagerUser>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            val clean = query.trim()
            if (clean.isEmpty()) {
                launch(Dispatchers.Main) { onError("اكتب نص البحث أولاً") }
                return@launch
            }
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val rows = restUmRead("user", conn)
                    val hits = rows.filter {
                        (it["name"] ?: it["username"] ?: "").contains(clean, ignoreCase = true)
                    }.map { parseUmUserRow(it) }
                    launch(Dispatchers.Main) { onSuccess(hits) }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل البحث") }
                }
                return@launch
            }
            try {
                val base = umBasePath ?: "/user-manager"
                val res = api.execute("$base/user/print", "?name~=$clean")
                val rows = when (res) {
                    is ApiResult.Success -> res.data
                    else -> {
                        // fall back to the other menu or plain client-side filter
                        val other = if (base == "/user-manager") "/tool/user-manager" else "/user-manager"
                        val res2 = api.execute("$other/user/print", "?username~=$clean")
                        when (res2) {
                            is ApiResult.Success -> res2.data
                            else -> emptyList()
                        }
                    }
                }
                val hits = if (rows.isNotEmpty()) {
                    rows.map { parseUmUserRow(it) }
                } else {
                    // Query unsupported/empty — filter the already-known list honestly
                    _userManagerUsers.value.filter { it.username.contains(clean, ignoreCase = true) }
                }
                launch(Dispatchers.Main) { onSuccess(hits) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل البحث على الراوتر") }
            }
        }
    }

    /** Reads the router's own /log/print — clearly labelled in the UI as router log. */
    fun fetchRouterLogs(onSuccess: (List<RouterLogEntry>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val req = Request.Builder()
                        .url("$restBase/rest/log")
                        .header("Authorization", restAuth(conn))
                        .build()
                    restClientFor(restBase ?: "http")?.newCall(req)?.execute().use { res ->
                        val body = res?.body?.string() ?: throw IOException("لا يوجد رد")
                        if (!res.isSuccessful) throw IOException("فشل جلب سجل الراوتر (${res.code})")
                        val arr = JSONArray(body)
                        val entries = (0 until arr.length()).map { i ->
                            val obj = arr.getJSONObject(i)
                            RouterLogEntry(
                                id = obj.optString(".id", i.toString()),
                                time = obj.optString("time", ""),
                                topics = obj.optString("topics", ""),
                                message = obj.optString("message", "")
                            )
                        }
                        launch(Dispatchers.Main) { onSuccess(entries) }
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل جلب سجل الراوتر") }
                }
                return@launch
            }
            try {
                val rows = api.execute("/log/print").data
                val entries = rows.mapIndexed { idx, r ->
                    RouterLogEntry(
                        id = r[".id"] ?: idx.toString(),
                        time = r["time"] ?: "",
                        topics = r["topics"] ?: "",
                        message = r["message"] ?: ""
                    )
                }
                launch(Dispatchers.Main) { onSuccess(entries) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل جلب سجل الراوتر") }
            }
        }
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

            restClientFor(if (connection.useSsl) "https" else "http")?.newCall(reqResource)?.execute().use { response ->
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
            Log.e("NetCraft", "Error tick resource", e)
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

            restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
            Log.e("NetCraft", "Error fetch users", e)
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

            restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
            Log.e("NetCraft", "Error fetch active", e)
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

            restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
            Log.e("NetCraft", "Error fetch profiles", e)
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

            restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
            Log.e("NetCraft", "Error fetch interfaces", e)
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
                    _hotspotUsers.value = _hotspotUsers.value + user
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
                        val reqBody = okhttp3.RequestBody.create(mediaType, bodyStr)

                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/ip/hotspot/user")
                            .post(reqBody)
                            .header("Authorization", credential)
                            .build()

                        restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
                    _hotspotUsers.value = _hotspotUsers.value.filter { it.id != id }
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

                        restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
                    _activeHotspot.value = _activeHotspot.value.filter { it.id != id }
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

                        restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
                    _hotspotProfiles.value = _hotspotProfiles.value + profile
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
                        val reqBody = okhttp3.RequestBody.create(mediaType, payload.toString())

                        val req = Request.Builder()
                            .url("$protocol://${conn.host}:${conn.port}/rest/ip/hotspot/user/profile")
                            .post(reqBody)
                            .header("Authorization", credential)
                            .build()

                        restClientFor(protocol)?.newCall(req)?.execute().use { res ->
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
    private val UM_BASES = listOf("/user-manager", "/tool/user-manager")

    /** Base-path order for UM commands: detected $umBase first, the other as safety net. */
    private fun umBaseOrder(): List<String> {
        val base = umBasePath
        return if (base != null) listOf(base) + UM_BASES.filter { it != base } else UM_BASES
    }

    /** Trap messages meaning "this RouterOS has no such menu" — safe to probe the other base. */
    private fun isMenuAbsentTrap(msg: String): Boolean =
        msg.contains("no such command", ignoreCase = true) ||
            msg.contains("bad command", ignoreCase = true) ||
            msg.contains("no such menu", ignoreCase = true)

    /**
     * Version-aware UM read via $umBase. A success (even with zero rows) settles the
     * menu immediately; menu-absence traps ("no such command") fall through to the
     * other base; transport errors and real traps abort with the router's message.
     */
    private fun umRead(api: MikroTikNativeApi, action: String, params: Map<String, String> = emptyMap()): List<Map<String, String>> {
        for (base in umBaseOrder()) {
            when (val res = api.execute("$base/$action", params)) {
                is ApiResult.Success -> {
                    umBasePath = base
                    return res.data
                }
                is ApiResult.Error -> throw java.io.IOException(res.message)
                is ApiResult.Trap -> {
                    if (!isMenuAbsentTrap(res.message)) {
                        // Real refusal (permissions, syntax…) — do not probe further.
                        return emptyList()
                    }
                    // wrong menu for this RouterOS — try the other base
                }
            }
        }
        return emptyList()
    }

    /** Runs a user-manager write on the router via $umBase (other base as fallback).
     *  Throws with the router's real error. */
    private fun umWrite(api: MikroTikNativeApi, action: String, params: Map<String, String>): List<Map<String, String>> {
        var lastErr: Exception? = null
        for (base in umBaseOrder()) {
            try {
                return api.executeStrict("$base/$action", params)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: java.io.IOException("user-manager غير متوفر على الراوتر")
    }

    /**
     * Limitations map keyed by profile name (v6 links profiles to limitations via the
     * profile-limitation table's own profile=/limitation= fields — never by name guess).
     */
    private fun umLimitations(api: MikroTikNativeApi): Map<String, Map<String, String>> {
        return try {
            // v6: rows carry profile=<name> limitation=<lim>; v7: limitation rows keyed by name.
            val isV6 = umBasePath == "/tool/user-manager"
            if (isV6) {
                val links = umRead(api, "profile/profile-limitation/print")
                val lims = umRead(api, "limitation/print").associateBy { it["name"] ?: "" }
                links.mapNotNull { link ->
                    val profile = link["profile"] ?: return@mapNotNull null
                    val limName = link["limitation"] ?: ""
                    val lim = lims[limName]
                    if (lim != null) profile to lim else null
                }.toMap()
            } else {
                val links = umRead(api, "profile-limitation/print")
                val lims = umRead(api, "limitation/print").associateBy { it["name"] ?: "" }
                links.mapNotNull { link ->
                    val profile = link["profile"] ?: return@mapNotNull null
                    val limName = link["limitation"] ?: ""
                    val lim = lims[limName]
                    if (lim != null) profile to lim else null
                }.toMap()
            }
        } catch (e: Exception) {
            Log.w("NetCraft", "umLimitations failed: ${e.message}")
            emptyMap()
        }
    }

    /** One parser for UM profile rows — used by every fetch path in the app. */
    private fun parseUmProfileRow(p: Map<String, String>, limitations: Map<String, Map<String, String>>): UserManagerProfile {
        val name = p["name"] ?: p["name-for-users"] ?: "default"
        val lim = limitations[name] ?: emptyMap()
        return UserManagerProfile(
            name = name,
            validity = p["validity"] ?: p["starts-at"] ?: "30d",
            price = p["price"]?.toDoubleOrNull() ?: 0.0,
            downloadLimit = lim["download-limit"] ?: lim["transfer-limit"] ?: lim["rate-limit-rx"] ?: p["download-limit"] ?: "unlimited",
            uptimeLimit = lim["uptime-limit"] ?: lim["uptime"] ?: p["uptime-limit"] ?: "unlimited",
            templateName = name,
            sharedUsers = p["override-shared-users"]?.toIntOrNull() ?: 1
        )
    }

    /** One parser for UM user rows — used by every fetch path in the app. */
    private fun parseUmUserRow(it: Map<String, String>): UserManagerUser {
        val uname = it["name"] ?: it["username"] ?: ""
        val assignedProfile = it["profile"]
        val actualProfile = it["actual-profile"]
        return UserManagerUser(
            username = uname,
            password = it["password"] ?: uname,
            profile = actualProfile?.takeIf { p -> p.isNotBlank() } ?: assignedProfile ?: "default",
            active = it["disabled"] != "true",
            uptimeUsed = it["uptime-used"] ?: it["uptime"] ?: "0s",
            downloadLimit = it["download-limit"] ?: it["limit-bytes-total"] ?: "unlimited",
            serialNumber = it["comment"]?.takeIf { c -> c.startsWith("SN:") || c.all { ch -> ch.isDigit() } } ?: (it[".id"] ?: "-"),
            comment = it["comment"] ?: "",
            profileExpired = assignedProfile != null && actualProfile.isNullOrBlank()
        )
    }

    // ---- REST (v7) User-Manager layer — used when the app is connected via REST ----

    private fun restAuth(conn: RouterConnection): String = Credentials.basic(conn.username, conn.password)

    private fun restUmUrl(path: String): String = "$restBase/rest/user-manager/$path"

    /** GET /rest/user-manager/<path> -> list of maps. */
    private fun restUmRead(path: String, conn: RouterConnection): List<Map<String, String>> {
        val req = Request.Builder().url(restUmUrl(path)).header("Authorization", restAuth(conn)).build()
        restClientFor(restBase ?: "http")?.newCall(req)?.execute().use { res ->
            if (res == null || !res.isSuccessful) {
                throw IOException("REST $path فشل (${res?.code}): ${res?.body?.string() ?: "لا يوجد رد"}")
            }
            val body = res.body?.string() ?: throw IOException("لا يوجد رد من الراوتر")
            val arr = JSONArray(body)
            val rows = mutableListOf<Map<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                rows.add(obj.keys().asSequence().associateWith { k -> obj.optString(k) })
            }
            return rows
        }
        throw IOException("تعذر تنفيذ طلب REST: لا يوجد عميل")
    }

    /** POST/PATCH/DELETE /rest/user-manager/<path>. Throws with the REST error. */
    private fun restUmWrite(path: String, method: String, body: JSONObject?, conn: RouterConnection): List<Map<String, String>> {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val reqBody = body?.let { okhttp3.RequestBody.create(mediaType, it.toString()) }
        val req = Request.Builder()
            .url(restUmUrl(path))
            .header("Authorization", restAuth(conn))
            .method(method, reqBody)
            .build()
        restClientFor(restBase ?: "http")?.newCall(req)?.execute().use { res ->
            val text = res?.body?.string() ?: ""
            if (res == null || !res.isSuccessful) throw IOException("REST $path فشل (${res?.code}): $text")
            return try {
                val arr = JSONArray(text)
                val rows = mutableListOf<Map<String, String>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    rows.add(obj.keys().asSequence().associateWith { k -> obj.optString(k) })
                }
                rows
            } catch (_: Exception) {
                emptyList()
            }
        }
        throw IOException("تعذر تنفيذ طلب REST: لا يوجد عميل")
    }

    private fun restConnectedOrError(onError: (String) -> Unit): RouterConnection? {
        val conn = _currentConnection.value
        if (conn == null || !_isConnected.value) {
            onError("غير متصل بالراوتر")
            return null
        }
        return conn
    }

    // Resolves the real user-manager customer on the router (v6: /tool/user-manager
    // customer/print). Defaults to "admin" only when the router reports none.
    private fun umResolveCustomer(api: MikroTikNativeApi): String {
        for (base in umBaseOrder()) {
            try {
                val list = api.execute("$base/customer/print").data
                val login = list.firstOrNull { it["login"] == "admin" }?.get("login")
                    ?: list.firstOrNull()?.get("login") ?: list.firstOrNull()?.get("name")
                if (login != null) return login
            } catch (_: Exception) {}
        }
        return "admin"
    }

    private enum class UmFlavor { V7, V6 }

    private fun isNoSuchCommand(e: Exception?): Boolean =
        e?.message?.contains("no such command", ignoreCase = true) == true

    // Adds one user-manager user using the attribute set each RouterOS version
    // actually accepts: v7 `/user-manager/user/add name=` (no customer), v6
    // `/tool/user-manager/user/add customer= username=`. The v7 menu is probed
    // once per batch (test-pinned contract); when [preferred] is known from an
    // earlier row only that path is used. Returns the flavor that succeeded;
    // throws the router's meaningful error.
    private fun umAddUser(
        api: MikroTikNativeApi,
        username: String,
        password: String,
        customer: () -> String,
        extra: Map<String, String> = emptyMap(),
        preferred: UmFlavor? = null
    ): UmFlavor {
        var v7Err: Exception? = null
        if (preferred != UmFlavor.V6) {
            try {
                api.executeStrict("/user-manager/user/add", linkedMapOf(
                    "name" to username,
                    "password" to password
                ) + extra)
                return UmFlavor.V7
            } catch (e: Exception) {
                if (preferred == UmFlavor.V7) throw e
                v7Err = e
            }
        }
        try {
            api.executeStrict("/tool/user-manager/user/add", linkedMapOf(
                "customer" to customer(),
                "username" to username,
                "password" to password
            ) + extra)
            return UmFlavor.V6
        } catch (e: Exception) {
            // On v7 the legacy menu doesn't exist; surface the real v7 error instead.
            throw if (isNoSuchCommand(e) && v7Err != null) v7Err else e
        }
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
        return try {
            umRead(api, "user/print").firstOrNull { row ->
                row["name"] == username || row["username"] == username
            }?.get(".id")
        } catch (_: Exception) {
            null
        }
    }

    /** Current REST connection, or null when not connected via REST. */
    private fun restConnOrNull(): RouterConnection? {
        val conn = _currentConnection.value
        return if (conn != null && restBase != null && _isConnected.value) conn else null
    }

    fun addUserManagerUser(user: UserManagerUser, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                // REST (v7) transport
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val body = JSONObject().apply {
                        put("name", user.username)
                        put("password", user.password)
                        if (user.comment.isNotEmpty()) put("comment", user.comment)
                        if (!user.active) put("disabled", true)
                    }
                    restUmWrite("user", "POST", body, conn)
                    if (user.profile.isNotEmpty()) {
                        restUmWrite("user-profile", "POST", JSONObject().apply {
                            put("user", user.username)
                            put("profile", user.profile)
                        }, conn)
                    }
                    _userManagerUsers.value = _userManagerUsers.value + user
                    insertActivityLog("يوزر مانجر", "تم إضافة مستخدم يوزر مانجر جديد '${user.username}' بالبروفايل '${user.profile}' (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل إضافة المستخدم على الراوتر") }
                }
                return@launch
            }
            try {
                val customer by lazy { umResolveCustomer(api) }
                val extra = mutableMapOf<String, String>()
                if (user.comment.isNotEmpty()) extra["comment"] = user.comment
                if (user.active) extra["disabled"] = "no"
                val flavor = umAddUser(api, user.username, user.password, { customer }, extra)
                if (user.profile.isNotEmpty()) {
                    val uid = if (flavor == UmFlavor.V6) umResolveUserId(api, user.username) else null
                    umAssignProfile(api, flavor, user.username, uid, user.profile) { customer }
                }
                _userManagerUsers.value = _userManagerUsers.value + user
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
                // REST (v7): re-attach the profile via user-profile, re-enable, reset counters
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val uid = restUmRead("user", conn).firstOrNull {
                        it["name"] == username || it["username"] == username
                    }?.get(".id")
                    restUmWrite("user-profile", "POST", JSONObject().apply {
                        put("user", username)
                        put("profile", newProfile)
                    }, conn)
                    if (uid != null) {
                        runCatching {
                            restUmWrite("user/$uid", "PATCH", JSONObject().put("disabled", false), conn)
                            if (resetUptime) {
                                runCatching { restUmWrite("user/reset-counters", "POST", JSONObject().put("numbers", uid), conn) }
                            }
                        }
                    }
                    _userManagerUsers.value = _userManagerUsers.value.map {
                        if (it.username == username) it.copy(profile = newProfile, active = true, profileExpired = false, uptimeUsed = if (resetUptime) "0s" else it.uptimeUsed) else it
                    }
                    insertActivityLog("تجديد كرت", "تم تجديد الكرت '$username' بالباقة '$newProfile' (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل تجديد الكرت على الراوتر") }
                }
                return@launch
            }
            try {
                val uid = umResolveUserId(api, username) ?: username
                // ROS v6 activates a profile on a user via create-and-activate-profile;
                // ROS v7 accepts a plain set. Try v6 path first, then v7 set.
                var activated = false
                try {
                    umWrite(api, "user/create-and-activate-profile", mapOf("numbers" to uid, "profile" to newProfile, "customer" to umResolveCustomer(api)))
                    activated = true
                } catch (_: Exception) {
                    umWrite(api, "user/set", mapOf("numbers" to uid, "profile" to newProfile, "disabled" to "no"))
                    activated = true
                }
                if (resetUptime && activated) {
                    runCatching { umWrite(api, "user/reset-counters", mapOf("numbers" to uid)) }
                }
                _userManagerUsers.value = _userManagerUsers.value.map {
                    if (it.username == username) it.copy(profile = newProfile, active = true, profileExpired = false, uptimeUsed = if (resetUptime) "0s" else it.uptimeUsed) else it
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
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                var deleted = 0
                val succeeded = mutableSetOf<String>()
                var firstErr: String? = null
                try {
                    val idByName = restUmRead("user", conn).mapNotNull { row ->
                        val n = row["name"] ?: row["username"]
                        row[".id"]?.let { n to it }
                    }.toMap()
                    usernames.forEach { u ->
                        try {
                            val id = idByName[u] ?: u
                            restUmWrite("user/$id", "DELETE", null, conn)
                            deleted++; succeeded.add(u)
                        } catch (e: Exception) { if (firstErr == null) firstErr = e.message }
                    }
                } catch (e: Exception) { firstErr = e.message }
                if (deleted > 0) {
                    _userManagerUsers.value = _userManagerUsers.value.filter { it.username !in succeeded }
                }
                insertActivityLog("حذف جماعي", "تم حذف $deleted كرت من نظام اليوزر مانجر (REST)", conn.host)
                launch(Dispatchers.Main) {
                    if (deleted == usernames.size) onSuccess(deleted)
                    else onError("حُذف $deleted من ${usernames.size}. خطأ: ${firstErr ?: "غير معروف"}")
                }
                return@launch
            }
            var deleted = 0
            val succeeded = mutableSetOf<String>()
            var firstErr: String? = null
            usernames.forEach { u ->
                try {
                    umWrite(api, "user/remove", mapOf("numbers" to (umResolveUserId(api, u) ?: u)))
                    deleted++
                    succeeded.add(u)
                } catch (e: Exception) {
                    if (firstErr == null) firstErr = e.message
                }
            }
            if (deleted > 0) {
                _userManagerUsers.value = _userManagerUsers.value.filter { it.username !in succeeded }
            }
            insertActivityLog("حذف جماعي", "تم حذف $deleted كرت من نظام اليوزر مانجر", _currentConnection.value?.host ?: "")
            launch(Dispatchers.Main) {
                if (deleted == usernames.size) onSuccess(deleted)
                else onError("حُذف $deleted من ${usernames.size}. خطأ: ${firstErr ?: "غير معروف"}")
            }
        }
    }

    fun bulkToggleUserManagerUsers(usernames: List<String>, active: Boolean, onSuccess: (Int) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onSuccess(0) }
                    return@launch
                }
                val succeeded = mutableSetOf<String>()
                var done = 0
                try {
                    val idByName = restUmRead("user", conn).mapNotNull { row ->
                        val n = row["name"] ?: row["username"]
                        row[".id"]?.let { n to it }
                    }.toMap()
                    usernames.forEach { u ->
                        try {
                            val id = idByName[u] ?: return@forEach
                            restUmWrite("user/$id", "PATCH", JSONObject().put("disabled", !active), conn)
                            done++; succeeded.add(u)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                _userManagerUsers.value = _userManagerUsers.value.map {
                    if (it.username in succeeded) it.copy(active = active) else it
                }
                insertActivityLog("تفعيل/تعطيل جماعي", "تم ${if (active) "تفعيل" else "تعطيل"} $done كرت في اليوزر مانجر (REST)", conn.host)
                launch(Dispatchers.Main) { onSuccess(done) }
                return@launch
            }
            val toToggle = usernames.toSet()
            val succeeded = mutableSetOf<String>()
            var done = 0
            usernames.forEach { u ->
                try {
                    umWrite(api, "user/set", mapOf("numbers" to (umResolveUserId(api, u) ?: u), "disabled" to if (active) "no" else "yes"))
                    done++
                    succeeded.add(u)
                } catch (_: Exception) {}
            }
            _userManagerUsers.value = _userManagerUsers.value.map {
                if (it.username in succeeded) it.copy(active = active) else it
            }
            insertActivityLog("تفعيل/تعطيل جماعي", "تم ${if (active) "تفعيل" else "تعطيل"} $done كرت في اليوزر مانجر", _currentConnection.value?.host ?: "")
            launch(Dispatchers.Main) { onSuccess(done) }
        }
    }

    fun deleteUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val id = restUmRead("user", conn).firstOrNull {
                        it["name"] == username || it["username"] == username
                    }?.get(".id") ?: username
                    restUmWrite("user/$id", "DELETE", null, conn)
                    _userManagerUsers.value = _userManagerUsers.value.filter { it.username != username }
                    insertActivityLog("يوزر مانجر", "تم حذف مستخدم '${username}' من نظام اليوزر مانجر (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل حذف المستخدم على الراوتر") }
                }
                return@launch
            }
            try {
                umWrite(api, "user/remove", mapOf("numbers" to (umResolveUserId(api, username) ?: username)))
                _userManagerUsers.value = _userManagerUsers.value.filter { it.username != username }
                insertActivityLog("يوزر مانجر", "تم حذف مستخدم '${username}' من نظام اليوزر مانجر", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل حذف المستخدم على الراوتر") }
            }
        }
    }

    /**
     * v6 (and v7) profiles get their download/uptime limits through a limitation row
     * linked via the profile-limitation table (profile=/limitation=), never by name
     * guessing. Creates/updates the limitation and the link when limits are set.
     */
    private fun umEnsureLimitationLink(api: MikroTikNativeApi, profileId: String, profileName: String, profile: UserManagerProfile) {
        val hasDl = profile.downloadLimit.isNotBlank() && profile.downloadLimit != "unlimited"
        val hasUl = profile.uptimeLimit.isNotBlank() && profile.uptimeLimit != "unlimited"
        if (!hasDl && !hasUl) return
        val limName = "lim-${profileName}"
        val limParams = mutableMapOf("name" to limName)
        if (hasDl) limParams["download-limit"] = profile.downloadLimit
        if (hasUl) limParams["uptime-limit"] = profile.uptimeLimit
        // Reuse an existing limitation with the same name if present
        val existing = try {
            umRead(api, "limitation/print").firstOrNull { it["name"] == limName }?.get(".id")
        } catch (_: Exception) { null }
        val limId = if (existing != null) {
            runCatching { umWrite(api, "limitation/set", mapOf("numbers" to existing) + limParams) }
            existing
        } else {
            runCatching { umWrite(api, "limitation/add", limParams) }
            try {
                umRead(api, "limitation/print").firstOrNull { it["name"] == limName }?.get(".id")
            } catch (_: Exception) { null }
        } ?: return
        // Link via the profile-limitation table's own profile=/limitation= fields
        runCatching {
            umWrite(api, "profile-limitation/add", mapOf(
                "profile" to (profileId.takeIf { it.startsWith("*") } ?: profileName),
                "limitation" to (limId.takeIf { it.startsWith("*") } ?: limName)
            ))
        }
    }

    fun addUserManagerProfile(profile: UserManagerProfile, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val body = JSONObject().apply {
                        put("name", profile.name)
                        if (profile.validity.isNotEmpty()) put("validity", profile.validity)
                        if (profile.price > 0) put("price", profile.price.toInt())
                    }
                    val created = restUmWrite("profile", "POST", body, conn)
                    val pid = created.firstOrNull()?.get(".id")
                    if (pid != null) {
                        restEnsureLimitationLink(pid, profile, conn)
                    }
                    val list = _userManagerProfiles.value.toMutableList()
                    list.removeAll { it.name == profile.name }
                    list.add(profile)
                    _userManagerProfiles.value = list
                    insertActivityLog("يوزر مانجر بروفايل", "تم إضافة باقة يوزر مانجر جديدة '${profile.name}' (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل إضافة الباقة على الراوتر") }
                }
                return@launch
            }
            try {
                val params = mutableMapOf("name" to profile.name)
                if (profile.validity.isNotEmpty()) params["validity"] = profile.validity
                if (profile.price > 0) params["price"] = profile.price.toInt().toString()
                umWrite(api, "profile/add", params)
                val pid = try {
                    umRead(api, "profile/print").firstOrNull { it["name"] == profile.name }?.get(".id")
                } catch (_: Exception) { null }
                if (pid != null) umEnsureLimitationLink(api, pid, profile.name, profile)
                val list = _userManagerProfiles.value.toMutableList()
                list.removeAll { it.name == profile.name }
                list.add(profile)
                _userManagerProfiles.value = list
                insertActivityLog("يوزر مانجر بروفايل", "تم إضافة باقة يوزر مانجر جديدة '${profile.name}' بسعر ${profile.price.toInt()}", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل إضافة الباقة على الراوتر") }
            }
        }
    }

    /** REST (v7) counterpart of [umEnsureLimitationLink]. */
    private fun restEnsureLimitationLink(profileId: String, profile: UserManagerProfile, conn: RouterConnection) {
        val hasDl = profile.downloadLimit.isNotBlank() && profile.downloadLimit != "unlimited"
        val hasUl = profile.uptimeLimit.isNotBlank() && profile.uptimeLimit != "unlimited"
        if (!hasDl && !hasUl) return
        try {
            val limName = "lim-${profile.name}"
            val existing = restUmRead("limitation", conn).firstOrNull { it["name"] == limName }
            val limId = if (existing != null) {
                restUmWrite("limitation/${existing[".id"]}", "PATCH", JSONObject().apply {
                    if (hasDl) put("download-limit", profile.downloadLimit)
                    if (hasUl) put("uptime-limit", profile.uptimeLimit)
                }, conn)
                existing[".id"]
            } else {
                val created = restUmWrite("limitation", "POST", JSONObject().apply {
                    put("name", limName)
                    if (hasDl) put("download-limit", profile.downloadLimit)
                    if (hasUl) put("uptime-limit", profile.uptimeLimit)
                }, conn)
                created.firstOrNull()?.get(".id")
            } ?: return
            runCatching {
                restUmWrite("profile-limitation", "POST", JSONObject().apply {
                    put("profile", profileId)
                    put("limitation", limId)
                }, conn)
            }
        } catch (e: Exception) {
            Log.w("NetCraft", "restEnsureLimitationLink failed: ${e.message}")
        }
    }

    fun updateUserManagerProfile(oldName: String, updatedProfile: UserManagerProfile, onSuccess: () -> Unit, onError: (String) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val pid = restUmRead("profile", conn).firstOrNull { it["name"] == oldName }?.get(".id")
                        ?: oldName
                    restUmWrite("profile/$pid", "PATCH", JSONObject().apply {
                        put("name", updatedProfile.name)
                        if (updatedProfile.validity.isNotEmpty()) put("validity", updatedProfile.validity)
                        if (updatedProfile.price > 0) put("price", updatedProfile.price.toInt())
                    }, conn)
                    restEnsureLimitationLink(pid, updatedProfile, conn)
                    _userManagerProfiles.value = _userManagerProfiles.value.map {
                        if (it.name == oldName) updatedProfile else it
                    }
                    insertActivityLog("يوزر مانجر بروفايل", "تم تحديث باقة يوزر مانجر '${updatedProfile.name}' (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل تحديث الباقة على الراوتر") }
                }
                return@launch
            }
            try {
                val pid = run {
                    var id: String? = null
                    for (base in umBaseOrder()) {
                        try {
                            id = api.execute("$base/profile/print").data.firstOrNull { it["name"] == oldName }?.get(".id")
                            if (id != null) break
                        } catch (_: Exception) {}
                    }
                    id ?: oldName
                }
                val params = mutableMapOf("numbers" to pid, "name" to updatedProfile.name)
                if (updatedProfile.validity.isNotEmpty()) params["validity"] = updatedProfile.validity
                if (updatedProfile.price > 0) params["price"] = updatedProfile.price.toInt().toString()
                umWrite(api, "profile/set", params)
                umEnsureLimitationLink(api, pid, updatedProfile.name, updatedProfile)
                _userManagerProfiles.value = _userManagerProfiles.value.map {
                    if (it.name == oldName) updatedProfile else it
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
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val pid = restUmRead("profile", conn).firstOrNull { it["name"] == profileName }?.get(".id")
                        ?: profileName
                    restUmWrite("profile/$pid", "DELETE", null, conn)
                    _userManagerProfiles.value = _userManagerProfiles.value.filter { it.name != profileName }
                    insertActivityLog("يوزر مانجر بروفايل", "تم حذف باقة يوزر مانجر '${profileName}' (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل حذف الباقة على الراوتر") }
                }
                return@launch
            }
            try {
                val pid = run {
                    var id: String? = null
                    for (base in umBaseOrder()) {
                        try {
                            id = api.execute("$base/profile/print").data.firstOrNull { it["name"] == profileName }?.get(".id")
                            if (id != null) break
                        } catch (_: Exception) {}
                    }
                    id ?: profileName
                }
                umWrite(api, "profile/remove", mapOf("numbers" to pid))
                _userManagerProfiles.value = _userManagerProfiles.value.filter { it.name != profileName }
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
                    // Version-aware read via the detected $umBase
                    val umProfList = umRead(api, "profile/print")
                    if (umProfList.isEmpty()) {
                        insertActivityLog("باقات اليوزرمانجر", api.lastError
                            ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر",
                            _currentConnection.value?.host ?: "")
                    }
                    val limitations = umLimitations(api)

                    if (umProfList.isNotEmpty()) {
                        val fetchedProfiles = umProfList.map { p -> parseUmProfileRow(p, limitations) }
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
                    Log.w("MikroTikRepository", "Failed to fetch live profiles", e)
                }
            } else if (conn != null && restBase != null && _isConnected.value) {
                // REST (v7) import
                try {
                    val rows = restUmRead("profile", conn)
                    if (rows.isNotEmpty()) {
                        val fetchedProfiles = rows.map { p ->
                            parseUmProfileRow(p, emptyMap())
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
                        insertActivityLog("استيراد باقات", "تم استيراد ${fetchedProfiles.size} باقة يوزرمانجر من الراوتر عبر REST", host)
                        launch(Dispatchers.Main) { onSuccess(fetchedProfiles.size) }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w("MikroTikRepository", "REST profile import failed", e)
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل استيراد الباقات عبر REST") }
                    return@launch
                }
            }

            // Router returned no profiles — report honestly; never fabricate built-ins
            if ((api == null && restBase == null) || !_isConnected.value) {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
            val added = mutableListOf<UserManagerUser>()
            try {
                val api = nativeApi
                if (api == null) {
                    // REST (v7) transport — same honesty rules as the native path.
                    val conn = restConnOrNull()
                    if (conn == null) {
                        launch(Dispatchers.Main) { onError("غير متصل بالراوتر", emptyList()) }
                        return@launch
                    }
                    if (users.isEmpty()) {
                        launch(Dispatchers.Main) { onError("لم يتم إدخال عدد صالح للكروت", emptyList()) }
                        return@launch
                    }
                    var firstErr: String? = null
                    users.forEach { user ->
                        try {
                            restUmWrite("user", "POST", JSONObject().apply {
                                put("name", user.username)
                                put("password", user.password)
                                if (user.comment.isNotEmpty()) put("comment", user.comment)
                            }, conn)
                            added.add(user)
                        } catch (e: Exception) {
                            if (firstErr == null) firstErr = e.message
                        }
                    }
                    val withProfile = added.filter { it.profile.isNotEmpty() }
                    withProfile.forEach { user ->
                        runCatching {
                            restUmWrite("user-profile", "POST", JSONObject().apply {
                                put("user", user.username)
                                put("profile", user.profile)
                            }, conn)
                        }.onFailure { e -> if (firstErr == null) firstErr = e.message }
                    }
                    if (added.isNotEmpty()) {
                        _userManagerUsers.value = added + _userManagerUsers.value
                    }
                    insertActivityLog("توليد دفعة كروت", "تم توليد دفعة كروت ($batchName): نجح ${added.size} من ${users.size} كرت في اليوزر مانجر (REST)", conn.host)
                    val result = added.toList()
                    launch(Dispatchers.Main) {
                        if (result.size == users.size && firstErr == null) onSuccess(result)
                        else onError("نجح ${result.size} من ${users.size}. خطأ: ${firstErr ?: "غير معروف"}", result)
                    }
                    return@launch
                }
                if (users.isEmpty()) {
                    launch(Dispatchers.Main) { onError("لم يتم إدخال عدد صالح للكروت", emptyList()) }
                    return@launch
                }
                var firstErr: String? = null
                val customer by lazy { umResolveCustomer(api) }
                // Detected from the first accepted row so the remaining rows go
                // straight to the right menu (no double round-trip per card).
                var flavor: UmFlavor? = null

                users.forEach { user ->
                    try {
                        flavor = umAddUser(api, user.username, user.password, { customer }, preferred = flavor)
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
                launch(Dispatchers.Main) {
                    if (result.size == users.size && firstErr == null) onSuccess(result)
                    else onError("نجح ${result.size} من ${users.size}. خطأ: ${firstErr ?: "غير معروف"}", result)
                }
            } catch (e: Exception) {
                val result = added.toList()
                launch(Dispatchers.Main) { onError(e.message ?: "خطأ في إضافة الكروت", result) }
            }
        }
    }

    fun cleanExpiredUserManagerUsers(onSuccess: (Int) -> Unit, onError: (String) -> Unit = {})  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر، لا يمكن تنظيف الكروت المنتهية") }
                    return@launch
                }
                // Same shared predicate as the UI (see UserManagerUser.isExpiredCard)
                val toRemove = _userManagerUsers.value.filter { it.isExpiredCard() }.map { it.username }
                var removed = 0
                val succeeded = mutableSetOf<String>()
                try {
                    val idByName = restUmRead("user", conn).mapNotNull { row ->
                        val n = row["name"] ?: row["username"]
                        row[".id"]?.let { n to it }
                    }.toMap()
                    toRemove.forEach { u ->
                        try {
                            val id = idByName[u] ?: return@forEach
                            restUmWrite("user/$id", "DELETE", null, conn)
                            removed++; succeeded.add(u)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                if (removed > 0) {
                    _userManagerUsers.value = _userManagerUsers.value.filter { it.username !in succeeded }
                }
                insertActivityLog("صيانة الكروت", "تم فحص وصيانة الكروت المنتهية وتنظيف $removed كرت منتهي (REST)", conn.host)
                launch(Dispatchers.Main) { onSuccess(removed) }
                return@launch
            }
            // THE single expiry predicate shared with the UI (Models.kt isExpiredCard):
            // disabled cards, plus cards whose assigned profile ran out and that were
            // actually used at least once (brand-new unused cards are never swept up).
            val toRemove = _userManagerUsers.value.filter { it.isExpiredCard() }.map { it.username }
            var removed = 0
            val succeeded = mutableSetOf<String>()
            toRemove.forEach { u ->
                try {
                    umWrite(api, "user/remove", mapOf("numbers" to (umResolveUserId(api, u) ?: u)))
                    removed++
                    succeeded.add(u)
                } catch (_: Exception) {}
            }
            if (removed > 0) {
                _userManagerUsers.value = _userManagerUsers.value.filter { it.username !in succeeded }
            }
            insertActivityLog("صيانة الكروت", "تم فحص وصيانة الكروت المنتهية وتنظيف $removed كرت منتهي", _currentConnection.value?.host ?: "")
            launch(Dispatchers.Main) { onSuccess(removed) }
        }
    }

    fun toggleUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit = {})  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                val current = _userManagerUsers.value.find { it.username == username }
                if (current == null) {
                    launch(Dispatchers.Main) { onError("المستخدم '$username' غير موجود") }
                    return@launch
                }
                val newActive = !current.active
                try {
                    val id = restUmRead("user", conn).firstOrNull {
                        it["name"] == username || it["username"] == username
                    }?.get(".id") ?: username
                    restUmWrite("user/$id", "PATCH", JSONObject().put("disabled", !newActive), conn)
                    _userManagerUsers.value = _userManagerUsers.value.map {
                        if (it.username == username) it.copy(active = newActive) else it
                    }
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل تغيير حالة المستخدم على الراوتر") }
                }
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
                _userManagerUsers.value = _userManagerUsers.value.map {
                    if (it.username == username) it.copy(active = newActive) else it
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
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    restUmWrite("database/save", "POST", JSONObject().put("name", filename.removeSuffix(".umb")), conn)
                    insertActivityLog("نسخ احتياطي", "تم إنشاء نسخة احتياطية لقاعدة بيانات اليوزر مانجر باسم $filename (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess(filename) }
                } catch (e: Exception) {
                    insertActivityLog("نسخ احتياطي", "فشل النسخ الاحتياطي: ${e.message}", conn.host)
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل النسخ الاحتياطي على الراوتر") }
                }
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
    // User-Manager live sessions ($umBase/session)
    // ==========================================

    /** Live User-Manager sessions straight from the router (native or REST). */
    fun fetchUserManagerSessions(onSuccess: (List<UserManagerSession>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    val rows = restUmRead("session", conn)
                    val sessions = rows.mapIndexed { idx, r ->
                        UserManagerSession(
                            id = r[".id"] ?: idx.toString(),
                            user = r["user"] ?: r["user-name"] ?: "",
                            uptime = r["uptime"] ?: "",
                            address = r["address"] ?: r["nas-ip-address"] ?: "",
                            bytesIn = r["bytes-in"]?.toLongOrNull() ?: 0L,
                            bytesOut = r["bytes-out"]?.toLongOrNull() ?: 0L,
                            nasPortId = r["nas-port-id"] ?: ""
                        )
                    }
                    launch(Dispatchers.Main) { onSuccess(sessions) }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل جلب الجلسات") }
                }
                return@launch
            }
            try {
                val rows = umRead(api, "session/print")
                val sessions = rows.mapIndexed { idx, r ->
                    UserManagerSession(
                        id = r[".id"] ?: idx.toString(),
                        user = r["user"] ?: r["user-name"] ?: "",
                        uptime = r["uptime"] ?: "",
                        address = r["address"] ?: r["nas-ip-address"] ?: "",
                        bytesIn = r["bytes-in"]?.toLongOrNull() ?: 0L,
                        bytesOut = r["bytes-out"]?.toLongOrNull() ?: 0L,
                        nasPortId = r["nas-port-id"] ?: ""
                    )
                }
                launch(Dispatchers.Main) { onSuccess(sessions) }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل جلب الجلسات من الراوتر") }
            }
        }
    }

    /** Disconnects one live session via $umBase/session/remove — the card stays valid. */
    fun disconnectUserManagerSession(sessionId: String, username: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                val conn = restConnOrNull()
                if (conn == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                    return@launch
                }
                try {
                    restUmWrite("session/$sessionId", "DELETE", null, conn)
                    insertActivityLog("قطع جلسة", "تم قطع جلسة المستخدم '$username' (REST)", conn.host)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.message ?: "فشل قطع الجلسة") }
                }
                return@launch
            }
            try {
                umWrite(api, "session/remove", mapOf("numbers" to sessionId))
                insertActivityLog("قطع جلسة", "تم قطع جلسة المستخدم '$username'", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "فشل قطع الجلسة على الراوتر") }
            }
        }
    }

    // Router Control: Reboot / Shutdown
    fun rebootRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val host = _currentConnection.value?.host ?: ""
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
                            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                            .header("Authorization", credential)
                            .build()

                        var sent = false
                        restClientFor(protocol)?.newCall(req)?.execute().use { res ->
                            sent = res != null && res.isSuccessful
                        }
                        if (sent) {
                            insertActivityLog("إعادة تشغيل", "تم إرسال أمر إعادة التشغيل للراوتر عبر REST", conn.host)
                            disconnect()
                            launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إعادة التشغيل للراوتر بنجاح!") }
                        } else {
                            insertActivityLog("إعادة تشغيل", "فشل إرسال أمر إعادة التشغيل عبر REST", conn.host)
                            launch(Dispatchers.Main) { onError("فشل إرسال أمر إعادة التشغيل إلى الراوتر — تحقق من الاتصال والصلاحيات") }
                        }
                    } catch (e: Exception) {
                        // The command may have reached the router before the connection
                        // dropped, but we cannot confirm it — report failure honestly.
                        insertActivityLog("إعادة تشغيل", "انقطع الاتصال أثناء إرسال أمر إعادة التشغيل: ${e.message}", conn.host)
                        disconnect()
                        launch(Dispatchers.Main) { onError("تعذر تأكيد وصول أمر إعادة التشغيل (${e.message}). إذا لم يُعِد الراوتر التشغيل تحقق من الاتصال يدوياً.") }
                    }
                }
            
        }
    }

    fun shutdownRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val host = _currentConnection.value?.host ?: ""
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
                            .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                            .header("Authorization", credential)
                            .build()

                        var sent = false
                        restClientFor(protocol)?.newCall(req)?.execute().use { res ->
                            sent = res != null && res.isSuccessful
                        }
                        if (sent) {
                            insertActivityLog("إيقاف تشغيل", "تم إرسال أمر إيقاف التشغيل للراوتر عبر REST", conn.host)
                            disconnect()
                            launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إيقاف تشغيل الراوتر بنجاح!") }
                        } else {
                            insertActivityLog("إيقاف تشغيل", "فشل إرسال أمر إيقاف التشغيل عبر REST", conn.host)
                            launch(Dispatchers.Main) { onError("فشل إرسال أمر إيقاف التشغيل إلى الراوتر — تحقق من الاتصال والصلاحيات") }
                        }
                    } catch (e: Exception) {
                        insertActivityLog("إيقاف تشغيل", "انقطع الاتصال أثناء إرسال أمر إيقاف التشغيل: ${e.message}", conn.host)
                        disconnect()
                        launch(Dispatchers.Main) { onError("تعذر تأكيد وصول أمر إيقاف التشغيل (${e.message}). إذا لم يتوقف الراوتر تحقق من الاتصال يدوياً.") }
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
            } else if (conn != null) {
                fetchRealRouterData(conn)
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
                    val profiles = api.execute("/ip/hotspot/profile/print").data
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
                    val servers = api.execute("/ip/hotspot/print").data
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
            }
        }
    }

    /**
     * TTL=1 anti-sharing mangle rule, scoped to the hotspot/LAN interfaces so
     * upstream (LAN->WAN) traffic is never touched. An unscoped postrouting rule
     * would also rewrite packets heading to the ISP and black out the whole
     * network, so the rule is created per hotspot interface (chain=forward
     * out-interface=<hotspot-if>) — the classic anti-share recipe.
     */
    fun toggleTtlBypass(blockSharing: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    val rules = api.execute("/ip/firewall/mangle/print").data
                    val existing = rules.filter { it["comment"]?.contains("ABO_TALAL_ANTI_SHARE") == true }
                    if (blockSharing) {
                        // Resolve the hotspot interfaces this router actually serves on.
                        val hotspotIfaces = api.execute("/ip/hotspot/print").data
                            .mapNotNull { it["interface"]?.takeIf { ifs -> ifs.isNotBlank() } }
                            .distinct()
                        if (hotspotIfaces.isEmpty()) {
                            launch(Dispatchers.Main) { onError("تعذر تحديد واجهة الهوتسبوت على الراوتر — أنشئ سيرفر هوتسبوت أولاً أو أضف قاعدة TTL يدوياً من WinBox") }
                            return@launch
                        }
                        // Replace any legacy unscoped rule with the scoped one
                        existing.forEach { old ->
                            old[".id"]?.let { id ->
                                runCatching { api.executeStrict("/ip/firewall/mangle/remove", "=numbers=$id") }
                            }
                        }
                        for (iface in hotspotIfaces) {
                            api.executeStrict(
                                "/ip/firewall/mangle/add",
                                "=chain=forward",
                                "=action=change-ttl",
                                "=new-ttl=set:1",
                                "=passthrough=yes",
                                "=out-interface=$iface",
                                "=comment=ABO_TALAL_ANTI_SHARE"
                            )
                        }
                    } else {
                        existing.forEach { rule ->
                            val ruleId = rule[".id"] ?: ""
                            if (ruleId.isNotBlank()) {
                                api.executeStrict("/ip/firewall/mangle/remove", "=numbers=$ruleId")
                            }
                        }
                    }
                    insertActivityLog("مشاركة الإنترنت", if (blockSharing) "تفعيل TTL=1 على واجهات الهوتسبوت" else "تعطيل TTL=1", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("فشل تعديل قاعدة المانجل: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر — قاعدة TTL تتطلب اتصال API الأصلي") }
            }
        }
    }

    /** True when every hotspot server is disabled = "free network" mode. */
    fun fetchHotspotServerState(onResult: (Boolean?) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) { launch(Dispatchers.Main) { onResult(null) }; return@launch }
            try {
                val servers = api.execute("/ip/hotspot/print").data
                val free = servers.isNotEmpty() && servers.all { it["disabled"] == "true" }
                launch(Dispatchers.Main) { onResult(free) }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    /** True when the TTL=1 anti-share mangle rule exists on the router. */
    fun fetchTtlBypassState(onResult: (Boolean?) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) { launch(Dispatchers.Main) { onResult(null) }; return@launch }
            try {
                val rules = api.execute("/ip/firewall/mangle/print").data
                val enabled = rules.any { it["comment"]?.contains("ABO_TALAL_ANTI_SHARE") == true && it["disabled"] != "true" }
                launch(Dispatchers.Main) { onResult(enabled) }
            } catch (_: Exception) {
                launch(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    /** Adds a real /ip/hotspot/ip-binding entry (blocked / bypassed / regular). */
    fun addHotspotIpBinding(type: String, mac: String, address: String, comment: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر — يتطلب اتصال API") }
                return@launch
            }
            try {
                val params = mutableMapOf("type" to type, "mac-address" to mac)
                if (address.isNotBlank()) params["address"] = address
                if (comment.isNotBlank()) params["comment"] = comment
                api.executeStrict("/ip/hotspot/ip-binding/add", params)
                insertActivityLog("قيود MAC", "تمت إضافة قيد $type للجهاز $mac على الراوتر", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError("فشل إضافة القيد على الراوتر: ${e.message}") }
            }
        }
    }

    /** Reads the current /ip/hotspot/ip-binding table from the router. */
    fun fetchHotspotIpBindings(onSuccess: (List<RouterGenericItem>) -> Unit, onError: (String) -> Unit) {
        fetchGenericSectionData("ip_bindings", onSuccess, onError)
    }

    /** Applies RADIUS auth/acct ports + shared secret + interim-update on the router's
     *  hotspot-serving /radius entry. Errors honestly when no matching entry exists. */
    fun setRadiusSettings(authPort: String, acctPort: String, sharedSecret: String, interimUpdate: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر — يتطلب اتصال API") }
                return@launch
            }
            try {
                val entries = api.execute("/radius/print").data
                val target = entries.firstOrNull { (it["service"] ?: "").contains("hotspot", ignoreCase = true) }
                    ?: entries.firstOrNull { (it["service"] ?: "").contains("user-manager", ignoreCase = true) }
                    ?: entries.firstOrNull()
                if (target == null) {
                    launch(Dispatchers.Main) { onError("لا يوجد إدخال RADIUS على الراوتر — أنشئه أولاً من WinBox (قائمة Radius) ثم عدّل من هنا") }
                    return@launch
                }
                val id = target[".id"] ?: ""
                val params = mutableMapOf("numbers" to id)
                if (authPort.isNotBlank()) params["authentication-port"] = authPort
                if (acctPort.isNotBlank()) params["accounting-port"] = acctPort
                if (sharedSecret.isNotBlank()) params["secret"] = sharedSecret
                if (interimUpdate.isNotBlank()) params["interim-update"] = interimUpdate
                api.executeStrict("/radius/set", params)
                insertActivityLog("إعدادات RADIUS", "تم تحديث إعدادات RADIUS (البورتات/المفتاح/التحديث الدوري) على الراوتر", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError("فشل تحديث إعدادات RADIUS: ${e.message}") }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
            }
        }
    }

    fun fetchPppoeUsers(onSuccess: (List<PppoeUser>) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    val secrets = api.execute("/ppp/secret/print").data
                    val actives = api.execute("/ppp/active/print").data
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
            }
        }
    }

    fun installTelegramScript(scriptName: String, scriptSource: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
                        val api = nativeApi
            if (api != null) {
                try {
                    // Check if exists
                    val existing = api.execute("/system/script/print").data.find { it["name"] == scriptName }
                    if (existing != null) {
                        val scriptId = existing[".id"] ?: scriptName
                        api.executeStrict("/system/script/set", "=numbers=$scriptId", "=source=$scriptSource")
                    } else {
                        // Minimum policy the script actually needs: /tool fetch +
                        // globals (read, write, test). No reboot/policy/password/sniff/sensitive.
                        api.executeStrict(
                            "/system/script/add",
                            "=name=$scriptName",
                            "=policy=read,write,test",
                            "=source=$scriptSource"
                        )
                    }
                    insertActivityLog("تيليجرام", "تثبيت سكربت إشعارات تيليجرام على الراوتر: $scriptName (بصلاحيات دنيا)", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("تعذر حفظ السكربت على الراوتر: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                // Sections that are NOT printable tables — refuse instead of showing
                // unrelated data under their name.
                if (categoryKey == "torch") {
                    launch(Dispatchers.Main) { onError("أداة Torch تحليل حي للtraffic ولا توفر جدولاً للعرض — استخدم WinBox لتشغيلها مباشرة") }
                    return@launch
                }
                val cmd = when (categoryKey) {
                    "dhcp_server" -> "/ip/dhcp-server/print"
                    "ip_addresses" -> "/ip/address/print"
                    "dhcp_leases" -> "/ip/dhcp-server/lease/print"
                    "dhcp_relay" -> "/ip/dhcp-relay/print"
                    "nat" -> "/ip/firewall/nat/print"
                    "dns" -> "/ip/dns/static/print"
                    "address_lists" -> "/ip/firewall/address-list/print"
                    "traffic" -> "/interface/print"
                    "ip_bindings" -> "/ip/hotspot/ip-binding/print"
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
                    "blocked_sites" -> "/ip/firewall/address-list/print"
                    "layer7" -> "/ip/firewall/layer7-protocol/print"
                    "bandwidth_test" -> "/tool/bandwidth-server/print"
                    "sniffer" -> "/tool/sniffer/print"
                    "netwatch" -> "/tool/netwatch/print"
                    "graphing" -> "/tool/graphing/print"
                    "email" -> "/tool/e-mail/print"
                    "sms" -> "/tool/sms/print"
                    else -> null
                }
                if (cmd == null) {
                    launch(Dispatchers.Main) { onError("قسم غير مدعوم: $categoryKey") }
                    return@launch
                }
                try {
                    val result = api.execute(cmd)
                    when (result) {
                        is ApiResult.Error, is ApiResult.Trap -> {
                            launch(Dispatchers.Main) { onError(result.errorMessage ?: "فشل جلب البيانات من الراوتر") }
                        }
                        is ApiResult.Success -> {
                            val resList = result.data
                            val spec = sectionFieldSpecs[categoryKey] ?: SectionFieldSpec()
                            val genericList = resList.mapIndexed { idx, map ->
                                val title = spec.titleKeys.firstNotNullOfOrNull { map[it] }
                                    ?: "عنصر #${idx + 1}"
                                val sub = spec.subtitleKeys.firstNotNullOfOrNull { map[it] } ?: ""
                                val status = map["status"]
                                    ?: (if (map["disabled"] == "true" || map["disabled"] == "yes") "معطل" else "نشط")
                                RouterGenericItem(
                                    id = map[".id"] ?: idx.toString(),
                                    title = title,
                                    subtitle = sub,
                                    status = status,
                                    extra = map["comment"] ?: "",
                                    isEnabled = map["disabled"] != "true",
                                    rawProperties = map
                                )
                            }
                            launch(Dispatchers.Main) { onSuccess(genericList) }
                        }
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError("خطأ أثناء قراءة البيانات من الراوتر: ${e.message}") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
        "traffic" to SectionFieldSpec(listOf("name"), listOf("rx-byte", "tx-byte", "running")),
        "ip_bindings" to SectionFieldSpec(listOf("mac-address", "address"), listOf("address", "type", "comment")),
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
                    val cloudResults = api.execute("/ip/cloud/print").data
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
                        val backupList = api.execute("/system/backup/cloud/print").data
                        _cloudBackups.value = backupList.mapIndexed { idx, b ->
                            MikroTikCloudBackup(
                                id = b[".id"] ?: idx.toString(),
                                name = b["name"] ?: "cloud-backup-$idx",
                                size = b["size"] ?: "",
                                downloadStatus = b["download-status"] ?: "complete",
                                time = b["creation-time"] ?: "",
                                encrypted = true
                            )
                        }
                    } catch (_: Exception) {}

                    launch(Dispatchers.Main) { onSuccess(parsed) }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل جلب بيانات الكلاود") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
            }
        }
    }

    fun setCloudDdns(enabled: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/ip/cloud/set", mapOf("ddns-enabled" to if (enabled) "yes" else "no"))
                    _cloudInfo.value = _cloudInfo.value.copy(ddnsEnabled = enabled)
                    insertActivityLog("إعدادات الكلاود", "تم ${if (enabled) "تفعيل" else "تعطيل"} الـ DDNS السحابي", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل حفظ إعدادات الكلاود") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
            }
        }
    }

    fun setCloudUpdateTime(enabled: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/ip/cloud/set", mapOf("update-time" to if (enabled) "yes" else "no"))
                    _cloudInfo.value = _cloudInfo.value.copy(updateTime = enabled)
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل ضبط مزامنة الوقت") }
                }
            } else {
                _cloudInfo.value = _cloudInfo.value.copy(updateTime = enabled)
                launch(Dispatchers.Main) { onSuccess() }
            }
        }
    }

    fun uploadCloudBackup(name: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    val params = mutableMapOf<String, String>()
                    if (name.isNotBlank()) params["name"] = name.trim()
                    if (password.isNotBlank()) params["password"] = password.trim()
                    api.executeStrict("/system/backup/cloud/upload", params)
                    fetchCloudData()
                    insertActivityLog("نسخ سحابي", "تم رفع نسخة احتياطية جديدة إلى سحابة مايكروتك", _currentConnection.value?.host ?: "")
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل رفع النسخة السحابية") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
            }
        }
    }

    fun removeCloudBackup(backupId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val api = nativeApi
            if (api != null) {
                try {
                    api.executeStrict("/system/backup/cloud/remove", mapOf("numbers" to backupId))
                    fetchCloudData()
                    launch(Dispatchers.Main) { onSuccess() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { onError(e.localizedMessage ?: "فشل حذف النسخة السحابية") }
                }
            } else {
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
            }
        }
    }
}
