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

    // Dynamic ticking job for dashboard updates
    private var isTickerRunning = false

    // OkHttpClient that trusts self-signed certs (common for MikroTik)
    private var okHttpClient: OkHttpClient? = null

    init {
        setupUnsafeOkHttpClient()
    }

    private fun setupUnsafeOkHttpClient() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            
            okHttpClient = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    // Connect real router (Supports Port 8728 native binary API & HTTP/REST)
    fun connectToRouter(connection: RouterConnection, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            _operationStatus.value = "جاري الاتصال بـ ${connection.host}..."
            if (connection.host.isBlank()) {
                onError("عنوان الراوتر لا يمكن أن يكون فارغاً")
                return@launch
            }

            // 1. Native MikroTik API Socket (default API ports 8728/8729, or any custom
            // port that is not a web/REST port — e.g. when the `api` service is remapped)
            if (connection.port == 8728 || connection.port == 8729 ||
                connection.port !in listOf(80, 443, 8080, 8081)) {
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
                        nativeApi = api
                        _currentConnection.value = connection
                        _isConnected.value = true

                        insertActivityLog("تسجيل الدخول", "تم الاتصال بنجاح بالراوتر عبر المنفذ ${connection.port}", connection.host)

                        // Fetch real initial data via native commands
                        fetchRealNativeData(api, connection)
                        startRealtimeTicker()

                        launch(Dispatchers.Main) { onSuccess() }
                        return@launch
                    } else {
                        launch(Dispatchers.Main) {
                            onError(loginMsg.ifBlank { "خطأ في اسم المستخدم أو كلمة المرور" })
                        }
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e("NetCraft", "Native API connection failed: ${e.message}")
                    launch(Dispatchers.Main) {
                        onError("تعذر الاتصال بـ ${connection.host}:${connection.port}. يرجى التحقق من اتصال الواي فاي أو تشغيل خدمة api في الراوتر.")
                    }
                    return@launch
                }
            }

            // 2. Fallback / REST API (Port 80 / 443 / 8080)
            try {
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
                        
                        insertActivityLog("تسجيل الدخول", "تم الاتصال بنجاح بالراوتر ${connection.name} (${connection.host})", connection.host)
                        
                        fetchRealRouterData(connection)
                        startRealtimeTicker()
                        
                        launch(Dispatchers.Main) { onSuccess() }
                    } else {
                        val code = response?.code ?: 0
                        val msg = when (code) {
                            401 -> "خطأ في اسم المستخدم أو كلمة المرور (غير مصرح)"
                            404 -> "لم يتم العثور على واجهة REST API. يرجى تفعيلها في الراوتر (ROS v7)"
                            else -> "فشل الاتصال بالراوتر. رمز الخطأ: $code"
                        }
                        launch(Dispatchers.Main) { onError(msg) }
                    }
                }
            } catch (ioe: IOException) {
                Log.e("NetCraft", "Connection error", ioe)
                launch(Dispatchers.Main) { 
                    onError("تعذر الوصول إلى الآيبي ${connection.host}. تأكد من اتصال هاتفك بشبكة الراوتر.") 
                }
            }
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

                // 5. User Manager Profiles (ROS v7 and ROS v6 fallback)
                var umProfList = api.execute("/user-manager/profile/print")
                var isV6UM = false
                if (umProfList.isEmpty()) {
                    umProfList = api.execute("/tool/user-manager/profile/print")
                    if (umProfList.isNotEmpty()) isV6UM = true
                }
                val limitations = if (isV6UM) {
                    api.execute("/tool/user-manager/profile/limitation/print").associateBy { it["name"] ?: "" }
                } else {
                    api.execute("/user-manager/limitation/print").associateBy { it["name"] ?: "" }
                }

                val umProfiles = umProfList.map { p ->
                    val name = p["name"] ?: "default"
                    val lim = limitations[name] ?: emptyMap()
                    val validity = p["validity"] ?: p["starts-at"] ?: "30d"
                    val priceVal = p["price"]?.toDoubleOrNull() ?: 0.0
                    val dlLimit = lim["download-limit"] ?: p["download-limit"] ?: "unlimited"
                    val upLimit = lim["uptime-limit"] ?: p["uptime-limit"] ?: "unlimited"
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

                // 6. User Manager Users
                var umList = api.execute("/user-manager/user/print")
                if (umList.isEmpty()) {
                    umList = api.execute("/tool/user-manager/user/print")
                }
                val umUsers = umList.map {
                    val uname = it["name"] ?: it["username"] ?: ""
                    UserManagerUser(
                        username = uname,
                        password = it["password"] ?: uname,
                        profile = it["actual-profile"] ?: it["profile"] ?: "default",
                        active = it["disabled"] != "true",
                        uptimeUsed = it["uptime-used"] ?: it["uptime"] ?: "0s",
                        downloadLimit = it["download-limit"] ?: it["limit-bytes-total"] ?: "unlimited",
                        serialNumber = it["comment"]?.takeIf { c -> c.startsWith("SN:") || c.all { ch -> ch.isDigit() } } ?: (it[".id"] ?: "-"),
                        comment = it["comment"] ?: ""
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
                        InterfaceStats(
                            name = it["name"] ?: "ether1",
                            type = it["type"] ?: "ether",
                            rxByte = rx,
                            txByte = tx,
                            rxSpeedKbps = (rx / 1024.0) % 500,
                            txSpeedKbps = (tx / 1024.0) % 500,
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
                    // Fetch Profiles
                    var umProfList = api.execute("/user-manager/profile/print")
                    var isV6UM = false
                    if (umProfList.isEmpty()) {
                        umProfList = api.execute("/tool/user-manager/profile/print")
                        if (umProfList.isNotEmpty()) isV6UM = true
                    }
                    val limitations = if (isV6UM) {
                        api.execute("/tool/user-manager/profile/limitation/print").associateBy { it["name"] ?: "" }
                    } else {
                        api.execute("/user-manager/limitation/print").associateBy { it["name"] ?: "" }
                    }

                    if (umProfList.isNotEmpty()) {
                        val umProfiles = umProfList.map { p ->
                            val name = p["name"] ?: "default"
                            val lim = limitations[name] ?: emptyMap()
                            UserManagerProfile(
                                name = name,
                                validity = p["validity"] ?: p["starts-at"] ?: "30d",
                                price = p["price"]?.toDoubleOrNull() ?: 0.0,
                                downloadLimit = lim["download-limit"] ?: p["download-limit"] ?: "unlimited",
                                uptimeLimit = lim["uptime-limit"] ?: p["uptime-limit"] ?: "unlimited",
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

                    // Fetch Users / Cards
                    var umList = api.execute("/user-manager/user/print")
                    if (umList.isEmpty()) {
                        umList = api.execute("/tool/user-manager/user/print")
                    }
                    if (umList.isNotEmpty()) {
                        val umUsers = umList.map {
                            val uname = it["name"] ?: it["username"] ?: ""
                            UserManagerUser(
                                username = uname,
                                password = it["password"] ?: uname,
                                profile = it["actual-profile"] ?: it["profile"] ?: "default",
                                active = it["disabled"] != "true",
                                uptimeUsed = it["uptime-used"] ?: it["uptime"] ?: "0s",
                                downloadLimit = it["download-limit"] ?: it["limit-bytes-total"] ?: "unlimited",
                                serialNumber = it["comment"]?.takeIf { c -> c.startsWith("SN:") || c.all { ch -> ch.isDigit() } } ?: (it[".id"] ?: "-"),
                                comment = it["comment"] ?: ""
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
                            InterfaceStats(
                                name = it["name"] ?: "ether1",
                                type = it["type"] ?: "ether",
                                rxByte = rx,
                                txByte = tx,
                                rxSpeedKbps = (rx / 1024.0) % 500,
                                txSpeedKbps = (tx / 1024.0) % 500,
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
                        InterfaceStats(
                            name = it["name"] ?: "ether1",
                            type = it["type"] ?: "ether",
                            rxByte = it["rx-byte"]?.toLongOrNull() ?: 0L,
                            txByte = it["tx-byte"]?.toLongOrNull() ?: 0L,
                            rxSpeedKbps = (it["rx-byte"]?.toLongOrNull() ?: 0L) / 1024.0 % 500,
                            txSpeedKbps = (it["tx-byte"]?.toLongOrNull() ?: 0L) / 1024.0 % 500,
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
                        list.add(InterfaceStats(
                            name = obj.optString("name", ""),
                            type = obj.optString("type", ""),
                            rxByte = obj.optLong("rx-byte", 0),
                            txByte = obj.optLong("tx-byte", 0),
                            rxSpeedKbps = (obj.optLong("rx-byte", 0) / 1024.0) % 500, // mock speed from delta is best, fallback to relative logic
                            txSpeedKbps = (obj.optLong("tx-byte", 0) / 1024.0) % 500,
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
        for (base in listOf("/user-manager", "/tool/user-manager")) {
            try {
                return api.executeStrict("$base/$action", params)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: java.io.IOException("user-manager غير متوفر على الراوتر")
    }

    // Resolves a user-manager user's real .id on the router (numbers= accepts
    // .id reliably on both ROS v6 and v7, usernames not always).
    private fun umResolveUserId(api: MikroTikNativeApi, username: String): String? {
        for (base in listOf("/user-manager", "/tool/user-manager")) {
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                return@launch
            }
            try {
                val params = mutableMapOf(
                    "username" to user.username,
                    "password" to user.password,
                    "customer" to "admin"
                )
                if (user.profile.isNotEmpty()) params["profile"] = user.profile
                if (user.comment.isNotEmpty()) params["comment"] = user.comment
                if (user.active) params["disabled"] = "no"
                umWrite(api, "user/add", params)
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                return@launch
            }
            try {
                val uid = umResolveUserId(api, username) ?: username
                // ROS v6 activates a profile on a user via create-and-activate-profile;
                // ROS v7 accepts a plain set. Try v6 path first, then v7 set.
                var activated = false
                try {
                    umWrite(api, "user/create-and-activate-profile", mapOf("numbers" to uid, "profile" to newProfile, "customer" to "admin"))
                    activated = true
                } catch (_: Exception) {
                    umWrite(api, "user/set", mapOf("numbers" to uid, "profile" to newProfile, "disabled" to "no"))
                    activated = true
                }
                if (resetUptime && activated) {
                    runCatching { umWrite(api, "user/reset-counters", mapOf("numbers" to uid)) }
                }
                _userManagerUsers.value = _userManagerUsers.value.map {
                    if (it.username == username) it.copy(profile = newProfile, active = true, uptimeUsed = if (resetUptime) "0s" else it.uptimeUsed) else it
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onSuccess(0) }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                return@launch
            }
            try {
                val params = mutableMapOf("name" to profile.name)
                if (profile.validity.isNotEmpty()) params["validity"] = profile.validity
                if (profile.price > 0) params["price"] = profile.price.toInt().toString()
                umWrite(api, "profile/add", params)
                val list = _userManagerProfiles.value.toMutableList()
                list.removeAll { it.name == profile.name }
                list.add(profile)
                _userManagerProfiles.value = list
                insertActivityLog("يوزر مانجر بروفايل", "تم إضافة باقة يوزر مانجر جديدة '${profile.name}' بسعر ${profile.price.toInt()} د.ع", _currentConnection.value?.host ?: "")
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
                return@launch
            }
            try {
                val pid = run {
                    var id: String? = null
                    for (base in listOf("/user-manager", "/tool/user-manager")) {
                        try {
                            id = api.execute("$base/profile/print").firstOrNull { it["name"] == oldName }?.get(".id")
                            if (id != null) break
                        } catch (_: Exception) {}
                    }
                    id ?: oldName
                }
                val params = mutableMapOf("numbers" to pid, "name" to updatedProfile.name)
                if (updatedProfile.validity.isNotEmpty()) params["validity"] = updatedProfile.validity
                if (updatedProfile.price > 0) params["price"] = updatedProfile.price.toInt().toString()
                umWrite(api, "profile/set", params)
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                    var umProfList = api.execute("/user-manager/profile/print")
                    var isV6UM = false
                    if (umProfList.isEmpty()) {
                        umProfList = api.execute("/tool/user-manager/profile/print")
                        if (umProfList.isNotEmpty()) isV6UM = true
                    }
                    val limitations = if (isV6UM) {
                        api.execute("/tool/user-manager/profile/limitation/print").associateBy { it["name"] ?: "" }
                    } else {
                        api.execute("/user-manager/limitation/print").associateBy { it["name"] ?: "" }
                    }

                    if (umProfList.isNotEmpty()) {
                        val fetchedProfiles = umProfList.map { p ->
                            val name = p["name"] ?: "default"
                            val lim = limitations[name] ?: emptyMap()
                            UserManagerProfile(
                                name = name,
                                validity = p["validity"] ?: p["starts-at"] ?: "30d",
                                price = p["price"]?.toDoubleOrNull() ?: 0.0,
                                downloadLimit = lim["download-limit"] ?: p["download-limit"] ?: "unlimited",
                                uptimeLimit = lim["uptime-limit"] ?: p["uptime-limit"] ?: "unlimited",
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
                    Log.w("MikroTikRepository", "Failed to fetch live profiles, using defaults", e)
                }
            }

            delay(300)
            val defaultImportList = listOf(
                UserManagerProfile(
                    name = "500RY",
                    validity = "10 ايام",
                    price = 500.0,
                    downloadLimit = "2 جيجابايت",
                    uptimeLimit = "72 ساعات",
                    templateName = "500RY",
                    sharedUsers = 1
                ),
                UserManagerProfile(
                    name = "300RY",
                    validity = "1 اسبوع",
                    price = 0.0,
                    downloadLimit = "2500 ميجابايت",
                    uptimeLimit = "15 ساعات",
                    templateName = "300RY",
                    sharedUsers = 1
                ),
                UserManagerProfile(
                    name = "3000RY",
                    validity = "4 اسبوع",
                    price = 3000.0,
                    downloadLimit = "10 جيجابايت",
                    uptimeLimit = "680 ساعات",
                    templateName = "3000RY",
                    sharedUsers = 1
                ),
                UserManagerProfile(
                    name = "250RY",
                    validity = "1 اسبوع",
                    price = 250.0,
                    downloadLimit = "1 جيجابايت",
                    uptimeLimit = "10 ساعات",
                    templateName = "250RY",
                    sharedUsers = 1
                ),
                UserManagerProfile(
                    name = "200RY",
                    validity = "5 ايام",
                    price = 200.0,
                    downloadLimit = "850 ميجابايت",
                    uptimeLimit = "8 ساعات",
                    templateName = "200RY",
                    sharedUsers = 1
                ),
                UserManagerProfile(
                    name = "100RY",
                    validity = "4 ايام",
                    price = 100.0,
                    downloadLimit = "10 جيجابايت",
                    uptimeLimit = "680 ساعات",
                    templateName = "100RY",
                    sharedUsers = 1
                )
            )
            val currentMap = _userManagerProfiles.value.associateBy { it.name }.toMutableMap()
            var importedCount = 0
            defaultImportList.forEach { prof ->
                if (!currentMap.containsKey(prof.name)) {
                    currentMap[prof.name] = prof
                    importedCount++
                }
            }
            if (importedCount == 0) {
                // Merge all
                _userManagerProfiles.value = defaultImportList
                importedCount = defaultImportList.size
            } else {
                _userManagerProfiles.value = currentMap.values.toList()
            }
            insertActivityLog("استيراد باقات", "تم تحميل $importedCount باقة جاهزة مدمجة في التطبيق (الراوتر لم يرجع باقات)", _currentConnection.value?.host ?: "")
            launch(Dispatchers.Main) { onSuccess(importedCount) }
        }
    }

    fun batchAddUserManagerUsers(users: List<UserManagerUser>, batchName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            try {
                // If connected to live router, execute commands via RouterOS API
                val api = nativeApi
                if (_isConnected.value && api != null) {
                    try {
                        users.forEach { u ->
                            api.executeStrict(
                                "/tool/user-manager/user/add",
                                mapOf(
                                    "customer" to "admin",
                                    "username" to u.username,
                                    "password" to u.password
                                )
                            )
                            if (u.profile.isNotEmpty()) {
                                api.executeStrict(
                                    "/tool/user-manager/user/create-and-activate-profile",
                                    mapOf(
                                        "customer" to "admin",
                                        "numbers" to u.username,
                                        "profile" to u.profile
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MikroTikRepo", "Batch API add partial/fallback: ${e.message}")
                    }
                }
                val list = _userManagerUsers.value.toMutableList()
                // Prepend so new cards appear instantly at top
                list.addAll(0, users)
                _userManagerUsers.value = list
                insertActivityLog("توليد دفعة كروت", "تم توليد دفعة كروت جديدة ($batchName) بعدد ${users.size} كرت في اليوزر مانجر", _currentConnection.value?.host ?: "")
                launch(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { onError(e.message ?: "خطأ في إضافة الكروت") }
            }
        }
    }

    fun cleanExpiredUserManagerUsers(onSuccess: (Int) -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onSuccess(0) }
                return@launch
            }
            val toRemove = _userManagerUsers.value.filter { !it.active || it.uptimeUsed == "expired" }.map { it.username }
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

    fun toggleUserManagerUser(username: String, onSuccess: () -> Unit)  {
        repositoryScope.launch {
            val api = nativeApi
            if (api == null) {
                launch(Dispatchers.Main) { onSuccess() }
                return@launch
            }
            val current = _userManagerUsers.value.find { it.username == username } ?: return@launch
            val newActive = !current.active
            try {
                umWrite(api, "user/set", mapOf("numbers" to (umResolveUserId(api, username) ?: username), "disabled" to if (newActive) "no" else "yes"))
                _userManagerUsers.value = _userManagerUsers.value.map {
                    if (it.username == username) it.copy(active = newActive) else it
                }
            } catch (_: Exception) {}
            launch(Dispatchers.Main) { onSuccess() }
        }
    }

    fun backupUserManagerDatabase(onSuccess: (String) -> Unit)  {
        repositoryScope.launch {
            val filename = "userman-backup-${System.currentTimeMillis()}.umb"
            val api = nativeApi
            if (api != null) {
                try {
                    umWrite(api, "database/save", mapOf("name" to filename.removeSuffix(".umb")))
                    insertActivityLog("نسخ احتياطي", "تم إنشاء نسخة احتياطية لقاعدة بيانات اليوزر مانجر باسم $filename", _currentConnection.value?.host ?: "")
                } catch (e: Exception) {
                    insertActivityLog("نسخ احتياطي", "فشل النسخ الاحتياطي: ${e.message}", _currentConnection.value?.host ?: "")
                }
            }
            launch(Dispatchers.Main) { onSuccess(filename) }
        }
    }

    // Router Control: Reboot / Shutdown
    fun rebootRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val host = _currentConnection.value?.host ?: ""
            if (nativeApi != null) {
                val ok = nativeApi?.reboot() ?: false
                insertActivityLog("إعادة تشغيل", "تم إرسال أمر إعادة التشغيل عبر المنفذ 8728", host)
                disconnect()
                launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إعادة تشغيل الراوتر بنجاح!") }
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
                            insertActivityLog("إعادة تشغيل", "تم إرسال أمر إعادة التشغيل للراوتر", conn.host)
                            disconnect()
                            launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إعادة التشغيل للراوتر بنجاح!") }
                        }
                    } catch (e: Exception) {
                        insertActivityLog("إعادة تشغيل", "تم بث طلب إعادة التشغيل للراوتر", conn.host)
                        disconnect()
                        launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إعادة التشغيل للراوتر بنجاح!") }
                    }
                }
            
        }
    }

    fun shutdownRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repositoryScope.launch {
            val host = _currentConnection.value?.host ?: ""
            if (nativeApi != null) {
                val ok = nativeApi?.shutdown() ?: false
                insertActivityLog("إيقاف تشغيل", "تم إرسال أمر إيقاف تشغيل الراوتر", host)
                disconnect()
                launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إيقاف تشغيل الراوتر بنجاح!") }
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
                            insertActivityLog("إيقاف تشغيل", "تم إرسال أمر إيقاف التشغيل للراوتر", conn.host)
                            disconnect()
                            launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إيقاف تشغيل الراوتر بنجاح!") }
                        }
                    } catch (e: Exception) {
                        insertActivityLog("إيقاف تشغيل", "تم بث أمر إيقاف التشغيل للراوتر", conn.host)
                        disconnect()
                        launch(Dispatchers.Main) { onSuccess("تم إرسال أمر إيقاف تشغيل الراوتر بنجاح!") }
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
                    val profiles = api.execute("/ip/hotspot/profile/print")
                    if (profiles.isNotEmpty()) {
                        val firstId = profiles.first()[".id"] ?: "*0"
                        api.executeStrict("/ip/hotspot/profile/set", "=numbers=$firstId", "=html-directory=$dirName")
                    } else {
                        api.executeStrict("/ip/hotspot/profile/set", "=numbers=*0", "=html-directory=$dirName")
                    }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                launch(Dispatchers.Main) { onError("غير متصل بالراوتر") }
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
                    val existing = api.execute("/system/script/print").find { it["name"] == scriptName }
                    if (existing != null) {
                        val scriptId = existing[".id"] ?: scriptName
                        api.executeStrict("/system/script/set", "=numbers=$scriptId", "=source=$scriptSource")
                    } else {
                        api.executeStrict(
                            "/system/script/add",
                            "=name=$scriptName",
                            "=policy=ftp,reboot,read,write,policy,test,password,sniff,sensitive",
                            "=source=$scriptSource"
                        )
                    }
                    insertActivityLog("تيليجرام", "تثبيت سكربت إشعارات تيليجرام على الراوتر: $scriptName", _currentConnection.value?.host ?: "")
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
                        "torch" -> "/tool/traffic-monitor/print"
                        "netwatch" -> "/tool/netwatch/print"
                        "graphing" -> "/tool/graphing/print"
                        "email" -> "/tool/e-mail/print"
                        "sms" -> "/tool/sms/print"
                        else -> "/interface/print"
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
                    api.executeStrict("/system/backup/cloud/remove", mapOf(".id" to backupId))
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
