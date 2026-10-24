package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.GeneratedBatchEntity
import com.example.data.local.RouterConnection
import com.example.data.model.HotspotProfile
import com.example.data.model.HotspotUser
import com.example.data.model.RouterBackupFile
import com.example.data.model.RouterLogEntry
import com.example.data.model.UserManagerProfile
import com.example.data.model.UserManagerSession
import com.example.data.model.UserManagerUser
import com.example.data.repository.MikroTikRepository
import com.example.util.CryptoManager
import com.example.util.OperationTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MikroTikViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = AppDatabase.getDatabase(application)
    private val routerDao = database.routerDao()
    private val logDao = database.activityLogDao()
    
    val repository = MikroTikRepository(application, logDao, database.cacheDao())

    // Theme Mode (Default is Light mode as requested: "وطبعاً اجعل الوضع الافتراضي اجعله الوضع الفاتح")
    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    // Local saved router list (observed reactively) — passwords are stored encrypted
    // in Room and decrypted transparently for the UI.
    val savedConnections: StateFlow<List<RouterConnection>> = routerDao.getAllConnections()
        .map { list -> list.map { it.copy(password = CryptoManager.decrypt(it.password)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Persisted batch history (real Room table, survives restarts)
    val batchHistory: StateFlow<List<GeneratedBatchEntity>> = database.generatedBatchDao().getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All logs
    val activityLogs = logDao.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI exposed states from repository
    val isConnected = repository.isConnected
    val currentConnection = repository.currentConnection
    val routerStats = repository.routerStats
    val hotspotUsers = repository.hotspotUsers
    val activeHotspot = repository.activeHotspot
    val hotspotProfiles = repository.hotspotProfiles
    val userManagerUsers = repository.userManagerUsers
    val userManagerProfiles = repository.userManagerProfiles
    val interfaces = repository.interfaces
    val operationStatus = repository.operationStatus
    val cloudInfo = repository.cloudInfo
    val cloudBackups = repository.cloudBackups

    // Local ViewModel UI States (Errors, active sub-tabs, filters)
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    fun clearConnectionError() {
        _connectionError.value = null
    }

    // Connect to real saved router
    fun connectRouter(connection: RouterConnection, onSuccess: () -> Unit) {
        connectRouter(connection, connection.host, connection.port, onSuccess)
    }

    // Connect to a temporary target (such as the active VPN endpoint) while
    // preserving the saved router's original host and port in Room.
    fun connectRouter(connection: RouterConnection, targetHost: String, targetPort: Int, onSuccess: () -> Unit) {
        _isConnecting.value = true
        _connectionError.value = null
        repository.connectToRouter(
            connection = connection.copy(host = targetHost.trim(), port = targetPort),
            onSuccess = {
                _isConnecting.value = false
                // Save/update connection timestamp in DB
                persistEncryptedConnection(connection)
                onSuccess()
            },
            onError = { errorMsg ->
                _isConnecting.value = false
                _connectionError.value = errorMsg
            }
        )
    }

    // Connect with manually input Router details
    fun connectManualRouter(name: String, host: String, port: Int, username: String, password: String, useSsl: Boolean, onSuccess: () -> Unit) {
        val conn = RouterConnection(
            name = name.ifBlank { "راوتر جديد" },
            host = host.trim(),
            port = port,
            username = username.trim(),
            password = password,
            useSsl = useSsl
        )
        
        _isConnecting.value = true
        _connectionError.value = null
        
        repository.connectToRouter(
            connection = conn,
            onSuccess = {
                _isConnecting.value = false
                // Persist to local contacts list in SQLite
                persistEncryptedConnection(conn)
                onSuccess()
            },
            onError = { error ->
                _isConnecting.value = false
                _connectionError.value = error
            }
        )
    }
    // Disconnect. repository.disconnect() acquires the native socket's lock to
    // close it, which can briefly block if another coroutine is mid-command on
    // that socket (e.g. a long-running maintenance command awaiting a reply) —
    // run it off the main thread so a logout tap can never freeze the UI.
    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.disconnect()
        }
    }

    // Delete router from database connection history
    fun deleteSavedConnection(connection: RouterConnection) {
        viewModelScope.launch {
            routerDao.deleteConnection(connection)
        }
    }

    // Clear log histories
    fun clearLogs() {
        viewModelScope.launch {
            logDao.clearAllLogs()
        }
    }

    // Write a manual activity log event from the user interface
    fun writeActivityLog(action: String, details: String) {
        viewModelScope.launch {
            logDao.insertLog(
                com.example.data.local.ActivityLog(
                    action = action,
                    details = details,
                    routerHost = currentConnection.value?.host.orEmpty()
                )
            )
        }
    }

    /* Hotspot Business Functions */
    fun addHotspotUser(user: HotspotUser, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.addHotspotUser(user, onSuccess, onError)
    }

    fun deleteHotspotUser(id: String, name: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.deleteHotspotUser(id, name, onSuccess, onError)
    }

    fun kickActiveUser(id: String, username: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.removeActiveHotspotUser(id, username, onSuccess, onError)
    }

    fun addHotspotProfile(profile: HotspotProfile, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.addHotspotProfile(profile, onSuccess, onError)
    }

    /* User Manager Business Functions */
    fun addUserManagerUser(user: UserManagerUser, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.addUserManagerUser(user, onSuccess, onError)
    }

    fun deleteUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.deleteUserManagerUser(username, onSuccess, onError)
    }

    fun renewUserManagerUser(username: String, newProfile: String, resetUptime: Boolean = true, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.renewUserManagerUser(username, newProfile, resetUptime, onSuccess, onError)
    }

    fun bulkDeleteUserManagerUsers(usernames: List<String>, onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repository.bulkDeleteUserManagerUsers(usernames, onSuccess, onError)
    }

    fun bulkToggleUserManagerUsers(usernames: List<String>, active: Boolean, onSuccess: (Int) -> Unit, onError: (String) -> Unit = {}) {
        repository.bulkToggleUserManagerUsers(usernames, active, onSuccess, onError)
    }

    fun addUserManagerProfile(profile: UserManagerProfile, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.addUserManagerProfile(profile, onSuccess, onError)
    }

    fun updateUserManagerProfile(oldName: String, profile: UserManagerProfile, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.updateUserManagerProfile(oldName, profile, onSuccess, onError)
    }

    fun deleteUserManagerProfile(profileName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.deleteUserManagerProfile(profileName, onSuccess, onError)
    }

    fun importUserManagerProfiles(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repository.importUserManagerProfiles(onSuccess, onError)
    }

    fun batchAddUserManagerUsers(
        users: List<UserManagerUser>,
        batchName: String,
        onSuccess: (List<UserManagerUser>) -> Unit,
        onError: (String, List<UserManagerUser>) -> Unit
    ) {
        repository.batchAddUserManagerUsers(users, batchName, onSuccess, onError)
    }

    fun cleanExpiredUserManagerUsers(onSuccess: (Int) -> Unit, onError: (String) -> Unit = {}) {
        repository.cleanExpiredUserManagerUsers(onSuccess, onError)
    }

    fun toggleUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        repository.toggleUserManagerUser(username, onSuccess, onError)
    }

    fun backupUserManagerDatabase(onSuccess: (String) -> Unit, onError: (String) -> Unit = {}) {
        repository.backupUserManagerDatabase(onSuccess, onError)
    }

    /* Router configuration backups (full system config — distinct from the User
     * Manager database backup above and from the MikroTik Cloud backup feature) */
    fun fetchRouterBackups(onSuccess: (List<RouterBackupFile>) -> Unit, onError: (String) -> Unit) {
        repository.fetchRouterBackups(onSuccess, onError)
    }

    fun createRouterBackup(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repository.createRouterBackup(onSuccess, onError)
    }

    fun downloadRouterBackup(
        fileName: String,
        folderUri: Uri,
        expectedSizeBytes: Long,
        onProgress: (Long) -> Unit,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        repository.downloadRouterBackup(fileName, folderUri, expectedSizeBytes, onProgress, onSuccess, onError)
    }

    fun deleteRouterBackup(fileName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.deleteRouterBackup(fileName, onSuccess, onError)
    }

    fun restoreRouterBackup(fileName: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repository.restoreRouterBackup(fileName, onSuccess, onError)
    }

    fun probeAiDoctorRouter(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repository.probeAiDoctorRouter(onSuccess, onError)
    }

    fun collectAiDoctorData(selectedSections: Set<String>, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repository.collectAiDoctorData(selectedSections, onSuccess, onError)
    }

    /* Expired-cards maintenance wizard (9 manual steps) */
    fun archiveExpiredCardsLocally(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repository.archiveExpiredCardsLocally(onSuccess, onError)
    }

    fun disableHotspotForMaintenance(
        onSuccess: (List<String>) -> Unit,
        onError: (String, List<String>) -> Unit
    ) {
        repository.disableHotspotForMaintenance(onSuccess, onError)
    }

    fun cleanStaleUserManagerSessions(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repository.cleanStaleUserManagerSessions(onSuccess, onError)
    }

    fun rebuildUserManagerLocalDatabase(onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.rebuildUserManagerLocalDatabase(onSuccess, onError)
    }

    fun rebuildUserManagerLocalRecords(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        repository.rebuildUserManagerLocalRecords(onSuccess, onError)
    }

    fun restoreHotspotAfterMaintenance(disabledServerIds: List<String>, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.restoreHotspotAfterMaintenance(disabledServerIds, onSuccess, onError)
    }

    fun verifyHotspotAndRebootRouter(
        disabledServerIds: List<String>,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        repository.verifyHotspotAndRebootRouter(disabledServerIds, onSuccess, onError)
    }

    /* User Manager live sessions */
    fun fetchUserManagerSessions(onSuccess: (List<UserManagerSession>) -> Unit, onError: (String) -> Unit) {
        repository.fetchUserManagerSessions(onSuccess, onError)
    }

    /* Cut a user's live session (session/remove) — does NOT delete the card */
    fun disconnectUserManagerUser(username: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.disconnectUserManagerUser(username, onSuccess, onError)
    }

    /* Real router state for the quick-services switches */
    fun fetchQuickServicesState(
        onResult: (freeHotspotEnabled: Boolean, antiShareEnabled: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        repository.fetchQuickServicesState(onResult, onError)
    }

    /* Router's own log buffer (/log/print) */
    fun fetchRouterLogs(onSuccess: (List<RouterLogEntry>) -> Unit, onError: (String) -> Unit) {
        repository.fetchRouterLogs(onSuccess, onError)
    }

    /* Add a real hotspot IP binding (blocked/bypassed/regular) */
    fun addHotspotIpBinding(
        macAddress: String,
        address: String,
        type: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        repository.addHotspotIpBinding(macAddress, address, type, onSuccess, onError)
    }

    /* Router-side user-manager search (?name~ / ?profile~ query words) */
    fun searchUserManagerUsers(
        query: String,
        field: String,
        onSuccess: (List<UserManagerUser>) -> Unit,
        onError: (String) -> Unit
    ) {
        repository.searchUserManagerUsers(query, field, onSuccess, onError)
    }
    /* Test Connection */
    fun testConnection(host: String, port: Int, onResult: (Boolean, String) -> Unit) {
        repository.testConnection(host, port, onResult)
    }

    /* Sync Database */
    fun syncDatabase(
        onProgress: (Int, Int, String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        repository.syncDatabase(onProgress, onComplete, onError)
    }

    /* Save Router Connection without connecting — password stored encrypted */
    fun saveRouterConnection(connection: RouterConnection) {
        persistEncryptedConnection(connection)
    }

    private fun persistEncryptedConnection(connection: RouterConnection) {
        viewModelScope.launch {
            val encryptedPassword = CryptoManager.encrypt(connection.password)
            if (encryptedPassword == null) {
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.CRYPTO_RESULT,
                    operation = OperationTelemetry.Operation.CRYPTO_ENCRYPT,
                    outcome = OperationTelemetry.Outcome.FAILURE,
                    errorClass = OperationTelemetry.ErrorClass.CRYPTO
                )
                _connectionError.value = "تعذر حفظ الراوتر: لم يتوفر التشفير الآمن على هذا الجهاز؛ لم تُحفظ كلمة المرور."
                return@launch
            }
            routerDao.insertConnection(
                connection.copy(
                    password = encryptedPassword,
                    lastConnected = System.currentTimeMillis()
                )
            )
        }
    }

    /* Persist / delete generated batch records in Room */
    fun saveGeneratedBatch(entity: GeneratedBatchEntity) {
        viewModelScope.launch {
            database.generatedBatchDao().insert(entity)
        }
    }

    fun deleteGeneratedBatch(batchId: String) {
        viewModelScope.launch {
            database.generatedBatchDao().deleteById(batchId)
        }
    }

    /* Router System Reboot and Shutdown Control */
    fun rebootSystem(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repository.rebootRouter(onSuccess, onError)
    }

    fun shutdownSystem(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        repository.shutdownRouter(onSuccess, onError)
    }

    fun refreshAllData() {
        repository.refreshData()
    }

    /* Tools & Advanced Router Operations */
    fun setHtmlDirectory(dirName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.setHtmlDirectory(dirName, onSuccess, onError)
    }

    fun toggleFreeHotspot(enableFree: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.toggleFreeHotspot(enableFree, onSuccess, onError)
    }

    fun toggleTtlBypass(blockSharing: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.toggleTtlBypass(blockSharing, onSuccess, onError)
    }

    fun toggleInterface(interfaceName: String, enable: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.toggleInterface(interfaceName, enable, onSuccess, onError)
    }

    fun resetInterfaceCounters(interfaceName: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.resetInterfaceCounters(interfaceName, onSuccess, onError)
    }

    fun fetchPppoeUsers(onSuccess: (List<com.example.data.model.PppoeUser>) -> Unit, onError: (String) -> Unit) {
        repository.fetchPppoeUsers(onSuccess, onError)
    }

    fun addPppoeUser(user: com.example.data.model.PppoeUser, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.addPppoeUser(user, onSuccess, onError)
    }

    fun deletePppoeUser(name: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.deletePppoeUser(name, onSuccess, onError)
    }

    fun installTelegramScript(scriptName: String, scriptSource: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.installTelegramScript(scriptName, scriptSource, onSuccess, onError)
    }

    fun executePing(target: String, count: Int = 4, onResult: (String) -> Unit) {
        repository.executePing(target, count, onResult)
    }

    fun fetchGenericSectionData(categoryKey: String, onSuccess: (List<com.example.data.model.RouterGenericItem>) -> Unit, onError: (String) -> Unit) {
        repository.fetchGenericSectionData(categoryKey, onSuccess, onError)
    }

    // Cloud Operations
    fun fetchCloudData(onSuccess: (com.example.data.model.MikroTikCloudInfo) -> Unit = {}, onError: (String) -> Unit = {}) {
        repository.fetchCloudData(onSuccess, onError)
    }

    fun forceUpdateCloud(onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.forceUpdateCloud(onSuccess, onError)
    }

    fun setCloudDdns(enabled: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.setCloudDdns(enabled, onSuccess, onError)
    }

    fun setCloudUpdateTime(enabled: Boolean, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.setCloudUpdateTime(enabled, onSuccess, onError)
    }

    fun uploadCloudBackup(name: String, password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.uploadCloudBackup(name, password, onSuccess, onError)
    }

    fun removeCloudBackup(backupId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        repository.removeCloudBackup(backupId, onSuccess, onError)
    }

    // Ensures repositoryScope's SupervisorJob (ticker, pending fetches, etc.)
    // is cancelled and the socket closed when this ViewModel is torn down,
    // instead of relying solely on process death.
    override fun onCleared() {
        super.onCleared()
        repository.shutdown()
    }

    // Simple Factory pattern for ViewModel Initialization
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MikroTikViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MikroTikViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
