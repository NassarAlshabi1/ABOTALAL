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

    // Native Socket API instance for port 8728
    private var nativeApi: MikroTikNativeApi? = null

    // Real RouterOS version detected from /system/resource/print after login.
    // rosMajor = 6 → user-manager lives under /tool/user-manager
    // rosMajor = 7 → user-manager lives under /user-manager
    // 0 = not detected yet (fall back to probing both paths).
    @Volatile
    private var rosMajor: Int = 0

    /** User-manager menu base for the DETECTED RouterOS version. */
    private val umBase: String
        get() = if (rosMajor == 6) "/tool/user-manager" else "/user-manager"

    /** Ordered candidate bases for user-manager commands — detected version first. */
    private val umBases: List<String>
        get() = if (rosMajor == 6) listOf("/tool/user-manager", "/user-manager")
                else listOf("/user-manager", "/tool/user-manager")

    private fun detectRouterOsVersion(api: MikroTikNativeApi, connection: RouterConnection) {
        try {
            val res = api.execute("/system/resource/print").firstOrNull()
            val ver = res?.get("version") ?: ""
            val major = ver.split('.').firstOrNull()?.trim()?.toIntOrNull() ?: 0
            if (major > 0) {
                rosMajor = major
                Log.i("NetCraft", "Detected RouterOS $ver on ${connection.host} → major=$major, umBase=$umBase")
            } else {
                Log.w("NetCraft", "Could not parse RouterOS version '$ver' — keeping probe fallback")
            }
        } catch (e: Exception) {
            Log.w("NetCraft", "Version detection failed: ${e.message}")
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
            _operationStatus.value = "جاري الاتصال بـ ${connection.host}..."
            if (connection.host.isBlank()) {
                onError("عنوان الراوتر لا يمكن أن يكون فارغاً")
                return@launch
            }

            val webPorts = listOf(80, 443, 8080, 8081)
            var lastError = ""

            // 1. Native MikroTik API Socket on the configured port (any non-web port,
            //    or the explicit API ports even if they collide with the web list).
            if (connection.port == 8728 || connection.port == 8729 ||
                connection.port !in webPorts) {
                val result = tryNativeConnect(connection)
                when (result) {
                    is NativeConnectResult.Success -> {
                        launch(Dispatchers.Main) { onSuccess() }
                        return@launch
                    }
                    is NativeConnectResult.AuthError -> {
                        launch(Dispatchers.Main) { onError(result.message) }
                        return@launch
                    }
                    is NativeConnectResult.Failure -> lastError = result.message
                }
            }

            // 2. REST API on the configured (web) port
            val restResult = tryRestConnect(connection)
            if (restResult.first) {
                launch(Dispatchers.Main) { onSuccess() }
                return@launch
            }
            lastError = restResult.second

            // 3. Configured port was a web port and REST failed to reach it — try the
            //    default native API port 8728 as a last resort.
            if (connection.port in webPorts) {
                val nativeAttempt = tryNativeConnect(connection, overridePort = 8728)
                when (nativeAttempt) {
                    is NativeConnectResult.Success -> {
                        launch(Dispatchers.Main) { onSuccess() }
                        return@launch
                    }
                    is NativeConnectResult.AuthError -> {
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
    }

    private fun tryNativeConnect(
        connection: RouterConnection,
        overridePort: Int? = null
    ): NativeConnectResult {
        val port = overridePort ?: connection.port
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
                nativeApi = api
                _currentConnection.value =
                    if (overridePort != null) connection.copy(port = port) else connection
                _isConnected.value = true

                // Detect the real RouterOS version right after login so every
                // user-manager command goes to the right menu from the start.
                detectRouterOsVersion(api, _currentConnection.value!!)

                insertActivityLog(
                    "تسجيل الدخول",
                    "تم الاتصال بنجاح بالراوتر عبر المنفذ $port" +
                        (if (rosMajor > 0) " (RouterOS v$rosMajor)" else ""),
                    connection.host
                )

                // Fetch real initial data via native commands
                fetchRealNativeData(api, _currentConnection.value!!)
                startRealtimeTicker()
                NativeConnectResult.Success(api)
            } else {
                // The router answered and rejected the credentials — same credentials
                // would fail on any transport, so report directly.
                try { api.close() } catch (_: Exception) {}
                if (loginMsg.contains("كلمة المرور") || loginMsg.contains("المستخدم") ||
                    loginMsg.contains("not allowed", ignoreCase = true) ||
                    loginMsg.contains("invalid", ignoreCase = true)
                ) {
                    NativeConnectResult.AuthError(loginMsg.ifBlank { "خطأ في اسم المستخدم أو كلمة المرور" })
                } else {
                    NativeConnectResult.Failure(loginMsg.ifBlank { "فشل تسجيل الدخول عبر المنفذ $port" })
                }
            }
        } catch (e: Exception) {
            Log.e("NetCraft", "Native API connection failed on port $port: ${e.message}")
            NativeConnectResult.Failure(
                "تعذر الاتصال بـ ${connection.host}:$port عبر API. ${e.message ?: ""}".trim()
            )
        }
    }

    /** @return (success, errorMessage) — attempts the REST API on the connection port. */
    private fun tryRestConnect(connection: RouterConnection): Pair<Boolean, String> {
        return try {
            val protocol = if (connection.useSsl) "https" else "http"
            val url = "$protocol://${connection.host}:${connection.port}/rest/system/resource"

            val credential = Credentials.basic(connection.username, connection.password)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", credential)
                .build()

            Log.d("NetCraft", "Connecting via REST: $url")
            okHttpClient?.newCall(request)?.execute().use { response ->
                if (response != null && response.isSuccessful) {
                    _currentConnection.value = connection
                    _isConnected.value = true

                    insertActivityLog("تسجيل الدخول", "تم الاتصال بنجاح بالراوتر ${connection.name} (${connection.host}) عبر REST", connection.host)

                    fetchRealRouterData(connection)
                    startRealtimeTicker()
                    Pair(true, "")
                } else {
                    val code = response?.code ?: 0
                    val msg = when (code) {
                        401 -> "خطأ في اسم المستخدم أو كلمة المرور (غير مصرح)"
                        404 -> "لم يتم العثور على واجهة REST API. يرجى تفعيلها في الراوتر (ROS v7) أو استخدام منفذ API 8728"
                        else -> "فشل الاتصال بالراوتر. رمز الخطأ: $code"
                    }
                    Pair(false, msg)
                }
            }
        } catch (ioe: IOException) {
            Log.e("NetCraft", "REST connection error", ioe)
            Pair(false, "تعذر الوصول إلى الآيبي ${connection.host} عبر REST. تأكد من اتصال هاتفك بشبكة الراوتر.")
        }
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
                val hsList = api.execute("/ip/hotspot/user/print")
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

                // 5. User Manager Profiles — use the version-detected menu base; probe the
                // other menu only when the version is still unknown.
                var umProfList = api.execute("$umBase/profile/print")
                var isV6UM = umBase == "/tool/user-manager"
                if (umProfList.isEmpty() && rosMajor == 0) {
                    val alt = if (isV6UM) "/user-manager" else "/tool/user-manager"
                    umProfList = api.execute("$alt/profile/print")
                    if (umProfList.isNotEmpty()) {
                        isV6UM = !isV6UM
                        rosMajor = if (isV6UM) 6 else 7
                    }
                }
                if (umProfList.isEmpty()) {
                    insertActivityLog("باقات اليوزرمانجر", api.lastError
                        ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر", connection.host)
                }
                val limitations = if (isV6UM) {
                    api.execute("/tool/user-manager/profile/profile-limitation/print").associateBy { it["profile"] ?: it["name"] ?: "" }
                } else {
                    api.execute("/user-manager/limitation/print").associateBy { it["name"] ?: "" }
                }

                val umProfiles = umProfList.map { p ->
                    val name = p["name"] ?: p["name-for-users"] ?: "default"
                    val lim = limitations[name] ?: emptyMap()
                    val validity = p["validity"] ?: p["starts-at"] ?: "30d"
                    val priceVal = p["price"]?.toDoubleOrNull() ?: 0.0
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
                var umList = api.execute("$umBase/user/print")
                if (umList.isEmpty() && rosMajor == 0) {
                    umList = api.execute(
                        if (umBase == "/tool/user-manager") "/user-manager/user/print"
                        else "/tool/user-manager/user/print"
                    )
                }
                val umUsers = umList.map {
                    val uname = it["name"] ?: it["username"] ?: ""
                    val assignedProfile = it["profile"]
                    val actualProfile = it["actual-profile"]
                    UserManagerUser(
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
                if (umUsers.isNotEmpty()) {
                    _userManagerUsers.value = umUsers
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
                    // Fetch Profiles — version-aware base, probe only when undetected
                    var umProfList = api.execute("$umBase/profile/print")
                    var isV6UM = umBase == "/tool/user-manager"
                    if (umProfList.isEmpty() && rosMajor == 0) {
                        val alt = if (isV6UM) "/user-manager" else "/tool/user-manager"
                        umProfList = api.execute("$alt/profile/print")
                        if (umProfList.isNotEmpty()) {
                            isV6UM = !isV6UM
                            rosMajor = if (isV6UM) 6 else 7
                        }
                    }
                    if (umProfList.isEmpty()) {
                        insertActivityLog("باقات اليوزرمانجر", api.lastError
                            ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر",
                            _currentConnection.value?.host ?: "")
                    }
                    val limitations = if (isV6UM) {
                        api.execute("/tool/user-manager/profile/profile-limitation/print").associateBy { it["profile"] ?: it["name"] ?: "" }
                    } else {
                        api.execute("/user-manager/limitation/print").associateBy { it["name"] ?: "" }
                    }

                    if (umProfList.isNotEmpty()) {
                        val umProfiles = umProfList.map { p ->
                            val name = p["name"] ?: p["name-for-users"] ?: "default"
                            val lim = limitations[name] ?: emptyMap()
                            UserManagerProfile(
                                name = name,
                                validity = p["validity"] ?: p["starts-at"] ?: "30d",
                                price = p["price"]?.toDoubleOrNull() ?: 0.0,
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
                    var umList = api.execute("$umBase/user/print")
                    if (umList.isEmpty() && rosMajor == 0) {
                        umList = api.execute(
                            if (umBase == "/tool/user-manager") "/user-manager/user/print"
                            else "/tool/user-manager/user/print"
                        )
                    }
                    if (umList.isNotEmpty()) {
                        val umUsers = umList.map {
                            val uname = it["name"] ?: it["username"] ?: ""
                            val assignedProfile = it["profile"]
                            val actualProfile = it["actual-profile"]
                            UserManagerUser(
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

                    val hsList = api.execute("/ip/hotspot/user/print")
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
                delay(600)

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
                                price = obj.optString("price", "0").toDoubleOrNull() ?: 0.0,
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
                        cacheDao.insertUserManagerUsers(users.map {
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
                }
            }
        } catch (e: Exception) {
            Log.w("NetCraft", "REST user-manager read failed (OK on v6/pre-UM routers): ${e.message}")
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
    // Runs a user-manager write on the router: ROS v7 menu first, then the
    // legacy ROS v6 /tool/user-manager path. Throws with the router's real error.
    private fun umWrite(api: MikroTikNativeApi, action: String, params: Map<String, String>): List<Map<String, String>> {
        var lastErr: Exception? = null
        for (base in umBases) {
            try {
                return api.executeStrict("$base/$action", params)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: java.io.IOException("user-manager غير متوفر على الراوتر")
    }

    // Resolves the real user-manager customer on the router (v6: /tool/user-manager
    // customer/print). Defaults to "admin" only when the router reports none.
    private fun umResolveCustomer(api: MikroTikNativeApi): String {
        for (base in umBases) {
            try {
                val list = api.execute("$base/customer/print")
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
    // `/tool/user-manager/user/add customer= username=`. When [preferred] is
    // known (from an earlier row of the same batch) only that path is used.
    // Returns the flavor that succeeded; throws the router's meaningful error.
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
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val uid = umResolveUserId(api, username) ?: username
                // ROS v6 activates a profile on a user via create-and-activate-profile;
                // ROS v7 accepts a plain set. Try v6 path first, then v7 set.
                var activated = false
                var v6Err: Exception? = null
                try {
                    umWrite(api, "user/create-and-activate-profile", mapOf("numbers" to uid, "profile" to newProfile, "customer" to umResolveCustomer(api)))
                    activated = true
                } catch (e: Exception) {
                    v6Err = e
                }
                if (!activated) {
                    try {
                        umWrite(api, "user/set", mapOf("numbers" to uid, "profile" to newProfile, "disabled" to "no"))
                        activated = true
                    } catch (e: Exception) {
                        // Neither shape worked — surface the v6-style error since it's the
                        // first one tried and usually the more specific of the two on a
                        // real v6 router (e.g. "no such item" for a bad profile name).
                        throw if (isNoSuchCommand(v6Err)) e else (v6Err ?: e)
                    }
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
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
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
            usernames.forEach { u ->
                try {
                    umWrite(api, "user/set", mapOf("numbers" to (umResolveUserId(api, u) ?: u), "disabled" to if (active) "no" else "yes"))
                    done++
                    succeeded.add(u)
                } catch (e: Exception) {
                    if (firstErr == null) firstErr = e.message
                }
            }
            if (done > 0) {
                _userManagerUsers.value = _userManagerUsers.value.map {
                    if (it.username in succeeded) it.copy(active = active) else it
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
                _userManagerUsers.value = _userManagerUsers.value.filter { it.username != username }
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
                if (umBase == "/tool/user-manager") {
                    // ROS v6 user-manager: validity/price are NOT profile attributes.
                    // Real structure: profile (name/name-for-users/override-shared-users)
                    // + limitation (uptime/download limits) + profile-limitation link
                    // created with profile= and limitation= fields.
                    val profParams = mutableMapOf(
                        "name" to profile.name,
                        "name-for-users" to profile.name,
                        "override-shared-users" to profile.sharedUsers.toString()
                    )
                    umWrite(api, "profile/add", profParams)

                    val limParams = mutableMapOf("name" to "${profile.name}-lim")
                    // Map human duration strings ("30d", "1w", "12h") to uptime-limit
                    val duration = Regex("^(\\d+)([smhdw])$", RegexOption.IGNORE_CASE)
                        .find(profile.validity.trim())
                    if (duration != null) limParams["uptime-limit"] = profile.validity.trim()
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
                    if (profile.price > 0) params["price"] = profile.price.toInt().toString()
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
                    for (base in listOf("/user-manager", "/tool/user-manager")) {
                        try {
                            id = api.execute("$base/profile/print").firstOrNull { it["name"] == oldName }?.get(".id")
                            if (id != null) { matchedBase = base; break }
                        } catch (_: Exception) {}
                    }
                    id ?: oldName
                }
                // Fall back to the connection-detected base if the profile lookup above
                // didn't find a match on either menu (e.g. renaming right after creation).
                val isV6 = (matchedBase ?: umBase) == "/tool/user-manager"
                if (isV6) {
                    // ROS v6 /tool/user-manager: validity/price are not profile attributes —
                    // they live on a separate limitation object linked via profile-limitation,
                    // exactly as created in addUserManagerProfile(). Update both in place.
                    umWrite(api, "profile/set", mapOf(
                        "numbers" to pid,
                        "name" to updatedProfile.name,
                        "name-for-users" to updatedProfile.name,
                        "override-shared-users" to updatedProfile.sharedUsers.toString()
                    ))
                    val links = api.execute("/tool/user-manager/profile/profile-limitation/print")
                    val link = links.firstOrNull { it["profile"] == oldName || it["profile"] == updatedProfile.name }
                    val limitations = mutableMapOf<String, String>()
                    val duration = Regex("^(\\d+)([smhdw])$", RegexOption.IGNORE_CASE).find(updatedProfile.validity.trim())
                    if (duration != null) limitations["uptime-limit"] = updatedProfile.validity.trim()
                    if (updatedProfile.downloadLimit.isNotBlank() && updatedProfile.downloadLimit != "unlimited") {
                        limitations["download-limit"] = updatedProfile.downloadLimit.trim()
                    }
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
                    } else if (limitations.isNotEmpty()) {
                        val limName = "${updatedProfile.name}-lim"
                        umWrite(api, "profile/limitation/add", limitations + mapOf("name" to limName))
                        umWrite(api, "profile/profile-limitation/add", mapOf("profile" to updatedProfile.name, "limitation" to limName))
                    }
                } else {
                    // ROS v7: validity and price are direct /user-manager/profile attributes.
                    val params = mutableMapOf("numbers" to pid, "name" to updatedProfile.name)
                    if (updatedProfile.validity.isNotEmpty()) params["validity"] = updatedProfile.validity
                    if (updatedProfile.price > 0) params["price"] = updatedProfile.price.toInt().toString()
                    if (updatedProfile.sharedUsers > 1) params["override-shared-users"] = updatedProfile.sharedUsers.toString()
                    umWrite(api, "profile/set", params)
                }
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
                launch(Dispatchers.Main) { onError(if (_isConnected.value) umRequiresNativeApiMessage() else "غير متصل بالراوتر") }
                return@launch
            }
            try {
                val pid = run {
                    var id: String? = null
                    for (base in listOf("/user-manager", "/tool/user-manager")) {
                        try {
                            id = api.execute("$base/profile/print").firstOrNull { it["name"] == profileName }?.get(".id")
                            if (id != null) break
                        } catch (_: Exception) {}
                    }
                    id ?: profileName
                }
                // On ROS v6 the profile's limitation + profile-limitation link are separate
                // objects (see addUserManagerProfile) — remove them too so they don't pile
                // up as orphans on the router after their owning profile is gone.
                if (umBase == "/tool/user-manager") {
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
                    var umProfList = api.execute("$umBase/profile/print")
                    var isV6UM = umBase == "/tool/user-manager"
                    if (umProfList.isEmpty() && rosMajor == 0) {
                        val alt = if (isV6UM) "/user-manager" else "/tool/user-manager"
                        umProfList = api.execute("$alt/profile/print")
                        if (umProfList.isNotEmpty()) {
                            isV6UM = !isV6UM
                            rosMajor = if (isV6UM) 6 else 7
                        }
                    }
                    if (umProfList.isEmpty()) {
                        insertActivityLog("باقات اليوزرمانجر", api.lastError
                            ?: "الراوتر لم يرجع أي باقات — تأكد من وجود باقات في User Manager على الراوتر",
                            _currentConnection.value?.host ?: "")
                    }
                    val limitations = if (isV6UM) {
                        api.execute("/tool/user-manager/profile/profile-limitation/print").associateBy { it["profile"] ?: it["name"] ?: "" }
                    } else {
                        api.execute("/user-manager/limitation/print").associateBy { it["name"] ?: "" }
                    }

                    if (umProfList.isNotEmpty()) {
                        val fetchedProfiles = umProfList.map { p ->
                            val name = p["name"] ?: p["name-for-users"] ?: "default"
                            val lim = limitations[name] ?: emptyMap()
                            UserManagerProfile(
                                name = name,
                                validity = p["validity"] ?: p["starts-at"] ?: "30d",
                                price = p["price"]?.toDoubleOrNull() ?: 0.0,
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
                    Log.w("MikroTikRepository", "Failed to fetch live profiles", e)
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
            val added = mutableListOf<UserManagerUser>()
            try {
                val api = nativeApi
                if (api == null) {
                    launch(Dispatchers.Main) { onError("غير متصل بالراوتر", emptyList()) }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر، لا يمكن تنظيف الكروت المنتهية") }
                return@launch
            }
            // Two independent, router-confirmed expiry signals:
            // 1) disabled == true (admin manually disabled the card), or
            // 2) profileExpired (router cleared "actual-profile" because the assigned
            //    profile's validity/limit ran out — RouterOS v6 user-manager never flips
            //    "disabled" by itself when this happens) combined with having actually
            //    been used at least once, so a brand-new unused card is never swept up
            //    by mistake while its profile activation is still pending.
            val toRemove = _userManagerUsers.value.filter {
                !it.active || (it.profileExpired && it.uptimeUsed.isNotBlank() && it.uptimeUsed != "0s")
            }.map { it.username }
            var removed = 0
            val succeeded = mutableSetOf<String>()
            var firstErr: String? = null
            toRemove.forEach { u ->
                try {
                    umWrite(api, "user/remove", mapOf("numbers" to (umResolveUserId(api, u) ?: u)))
                    removed++
                    succeeded.add(u)
                } catch (e: Exception) {
                    if (firstErr == null) firstErr = e.message
                }
            }
            if (removed > 0) {
                _userManagerUsers.value = _userManagerUsers.value.filter { it.username !in succeeded }
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
                // Both menus refused (or no sessions at all): surface the router's
                // error only when even the first base threw — an empty list from a
                // working menu simply means "no live sessions right now".
                if (sessions.isEmpty() && lastErr != null && rosMajor == 0) {
                    // Version unknown and both probes trapped — report the real error.
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
     * Disconnects a user-manager user's LIVE session via `$umBase/session/remove`
     * (the user/card itself is NOT deleted — only the active connection is cut).
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
                umWrite(api, "session/remove", mapOf("numbers" to sessionId))
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
                val users = rows.map {
                    val uname = it["name"] ?: it["username"] ?: ""
                    val assignedProfile = it["profile"]
                    val actualProfile = it["actual-profile"]
                    UserManagerUser(
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
            } else if (conn != null) {
                fetchRealRouterData(conn)
            }
        }
    }

    // ==========================================
    // ROUTER CONFIGURATION BACKUPS (/system/backup/*, /file/*)
    // Full system config backups, distinct from the User Manager database
    // backup above and from the MikroTik Cloud backup feature (CloudScreen).
    // ==========================================

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
                    val resList = api.execute(cmd)
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
                    _cloudInfo.value = _cloudInfo.value.copy(ddnsEnabled = enabled)
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
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onError(if (_isConnected.value) requiresNativeApiMessage() else "غير متصل بالراوتر") }
            }
        }
    }
}
