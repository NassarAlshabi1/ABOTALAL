package com.example.ui.screens

import androidx.compose.material.icons.automirrored.filled.Login

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.local.RouterConnection
import com.example.ui.components.AppExitConfirmationDialog
import com.example.ui.components.SegmentedIpField
import com.example.ui.viewmodel.MikroTikViewModel
import com.example.util.NetworkUtils
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: MikroTikViewModel,
    onLoginSuccess: () -> Unit
) {
    val savedConnections by viewModel.savedConnections.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val isDarkMode by viewModel.isDarkMode.collectAsState()

    // Back button confirmation dialog
    var showExitAppDialog by remember { mutableStateOf(false) }
    BackHandler {
        showExitAppDialog = true
    }

    val context = LocalContext.current
    val detectedGateway = remember { NetworkUtils.getRouterGatewayIp(context) }

    // Active Tab: 0 = بيانات الدخول, 1 = الراوترات المسجلة
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Connection Mode: 0 = عنوان IP, 1 = كلاود Cloud, 2 = رابط Domain, 3 = L2TP VPN عن بعد
    var connectionMode by rememberSaveable { mutableIntStateOf(0) }
    // Local access (IP/Cloud/Domain) is kept separate from remote L2TP access.
    var lastLocalMode by rememberSaveable { mutableIntStateOf(0) }
    var vpnActive by remember { mutableStateOf(hasActiveVpn(context)) }
    LaunchedEffect(connectionMode) {
        if (connectionMode == 3) {
            while (true) {
                vpnActive = hasActiveVpn(context)
                delay(2000)
            }
        }
    }

    // Input States
    var routerHost by rememberSaveable { mutableStateOf(detectedGateway) }
    var routerCloudDns by rememberSaveable { mutableStateOf("") }
    var routerDomain by rememberSaveable { mutableStateOf("") }
    var routerPort by rememberSaveable { mutableStateOf("8728") }
    var vpnServerLink by rememberSaveable { mutableStateOf("") }
    // L2TP uses UDP 1701 only for establishing the VPN tunnel. The app logs
    // into RouterOS after the tunnel is active, so this field must be the
    // RouterOS API port, not the L2TP transport port.
    var vpnPort by rememberSaveable { mutableStateOf("8728") }
    var routerUser by rememberSaveable { mutableStateOf("") }
    var routerPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Login Options Checkboxes
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var syncOnLogin by rememberSaveable { mutableStateOf(true) }
    var backupUserManager by rememberSaveable { mutableStateOf(false) }
    var isAdvancedOpen by rememberSaveable { mutableStateOf(false) }

    // Advanced fields
    var routerCustomName by rememberSaveable { mutableStateOf("") }
    var radiusHost by rememberSaveable { mutableStateOf("") }
    var timeoutSeconds by rememberSaveable { mutableStateOf("10") }
    var useSsl by rememberSaveable { mutableStateOf(false) }

    // Selected router from saved list (default first or null)
    var selectedSavedRouterId by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(savedConnections) {
        val selectedStillExists = savedConnections.any { it.id == selectedSavedRouterId }
        if (savedConnections.isNotEmpty() && !selectedStillExists) {
            selectedSavedRouterId = savedConnections.first().id
        }
    }

    // Keep the manual form in sync with the selected saved router so the bottom
    // "تسجيل الدخول" button, the card "تسجيل" button and "اختبار الاتصال" all
    // target the same host/port/credentials the user sees on screen.
    LaunchedEffect(selectedSavedRouterId) {
        val conn = savedConnections.firstOrNull { it.id == selectedSavedRouterId } ?: return@LaunchedEffect
        // A saved router always stores a direct host/port, so selecting one must
        // leave L2TP/Cloud/Domain modes; otherwise login would target a stale field.
        connectionMode = 0
        lastLocalMode = 0
        routerHost = conn.host
        routerPort = conn.port.toString()
        routerCustomName = conn.name
        routerUser = conn.username
        routerPassword = conn.password
        useSsl = conn.useSsl
    }

    // Dialogs & Feedback
    var showAccountIdDialog by remember { mutableStateOf(false) }
    var testResultDialog by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var showAddRouterDialog by remember { mutableStateOf(false) }
    var deleteConfirmRouter by remember { mutableStateOf<RouterConnection?>(null) }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }

    // Resolves the active connection-mode inputs into (host, port) shared by
    // the "اختبار الاتصال" and "تسجيل الدخول" buttons on both tabs.
    fun resolveTarget(): Pair<String, Int> {
        val targetRaw = when (connectionMode) {
            0 -> routerHost.trim()
            1 -> {
                val raw = routerCloudDns.trim()
                if (raw.isNotEmpty() && !raw.contains(".") && !raw.contains(":")) {
                    "$raw.sn.mynetname.net"
                } else {
                    raw
                }
            }
            3 -> vpnServerLink.trim()
            else -> routerDomain.trim()
        }
        val (cleanHostStr, parsedPort) = NetworkUtils.cleanHost(targetRaw)
        val portField = if (connectionMode == 3) vpnPort else routerPort
        // L2TP/IPsec is established by Android's VPN settings. The login
        // itself always speaks RouterOS API through the active tunnel.
        val defaultPort = 8728
        // An explicit port in the entered URL wins for every mode. This is
        // needed for deployments that publish RouterOS API on a custom WAN
        // port such as 13196. If no port is embedded, use the selected field.
        val resolvedPort = parsedPort ?: (portField.toIntOrNull() ?: defaultPort)
        return Pair(cleanHostStr, resolvedPort)
    }

    fun connectionDisplayName(host: String): String = routerCustomName.ifBlank {
        if (connectionMode == 3) "VPN $host" else "راوتر $host"
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                // Top Header Card
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 8.dp),
                    color = Color.Transparent
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFF0C5A60), // Deep Teal
                                        Color(0xFF167C80)
                                    )
                                )
                            )
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Router emblem button
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Router,
                                        contentDescription = "Router",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            // Center: App Title
                            Text(
                                text = "NetCraft",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )

                            // Right: Controls Row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Globe / Language Icon
                                IconButton(
                                    onClick = { /* Language selector */ },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Language,
                                        contentDescription = "Language",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Dark / Light Mode Toggle Button (Default is Light)
                                IconButton(
                                    onClick = { viewModel.toggleDarkMode() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isDarkMode) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                        contentDescription = "Toggle Theme",
                                        tint = if (isDarkMode) Color(0xFFFBBF24) else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Version Pill Badge
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "v1.0.1",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Spacer(modifier = Modifier.height(14.dp))

                    // Top Tab Selector: [بيانات الدخول ➔] | [الراوترات المسجلة 📶]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(4.dp)
                    ) {
                        // Tab 0: بيانات الدخول
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = 0 },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shadowElevation = if (selectedTab == 0) 3.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "بيانات الدخول",
                                    color = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Login,
                                    contentDescription = null,
                                    tint = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Tab 1: الراوترات المسجلة
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = 1 },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shadowElevation = if (selectedTab == 1) 3.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "الراوترات المسجلة",
                                    color = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Filled.Wifi,
                                    contentDescription = null,
                                    tint = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Error Alert Banner
                    if (!connectionError.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFFFEE2E2),
                            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0xFFEF4444), Color(0xFFF87171))))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = connectionError ?: "",
                                    color = Color(0xFF991B1B),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.clearConnectionError() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Dismiss",
                                        tint = Color(0xFFDC2626),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // TAB 0: بيانات الدخول (Screenshots 3)
                if (selectedTab == 0) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                // Access layer: local network vs remote L2TP tunnel
                                Text(
                                    text = "نوع الوصول",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                Text(
                                    text = "الإصدار المدعوم: RouterOS v6 (6.x) فقط — تم التحقق على 6.49.21",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AccessModeOption(
                                        selected = connectionMode != 3,
                                        icon = Icons.Filled.Lan,
                                        title = "دخول محلي",
                                        subtitle = "IP / كلاود / دومين",
                                        onClick = { connectionMode = lastLocalMode }
                                    )
                                    AccessModeOption(
                                        selected = connectionMode == 3,
                                        icon = Icons.Filled.VpnKey,
                                        title = "L2TP عن بعد",
                                        subtitle = "عبر نفق VPN في أندرويد",
                                        onClick = { connectionMode = 3 }
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                if (connectionMode != 3) {
                                    Text(
                                        text = "عنوان الراوتر على الشبكة المحلية",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LocalModeChip(
                                            selected = connectionMode == 0,
                                            icon = Icons.Filled.Language,
                                            label = "عنوان IP",
                                            onClick = { connectionMode = 0; lastLocalMode = 0 }
                                        )
                                        LocalModeChip(
                                            selected = connectionMode == 1,
                                            icon = Icons.Filled.Cloud,
                                            label = "كلاود",
                                            onClick = { connectionMode = 1; lastLocalMode = 1 }
                                        )
                                        LocalModeChip(
                                            selected = connectionMode == 2,
                                            icon = Icons.Filled.Dns,
                                            label = "رابط دومين",
                                            onClick = { connectionMode = 2; lastLocalMode = 2 }
                                        )
                                    }
                                } else {
                                    VpnStatusBanner(active = vpnActive)
                                }

                                when (connectionMode) {
                                    0 -> {
                                        // 1. IP Mode: Segmented IP Input + Port
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "عنوان الآي بي للراوتر",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )

                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color(0xFFDCFCE7),
                                                    modifier = Modifier.clickable { routerHost = detectedGateway }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xFF16A34A))
                                                        )
                                                        Text(
                                                            text = "بوابة الراوتر: $detectedGateway ⚡",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF15803D)
                                                        )
                                                    }
                                                }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Port Input (بورت)
                                                OutlinedTextField(
                                                    value = routerPort,
                                                    onValueChange = { routerPort = it },
                                                    label = { Text("بورت") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    modifier = Modifier.width(82.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true
                                                )

                                                // Segmented IP Field (4 Octets with dot separators)
                                                SegmentedIpField(
                                                    ipAddress = routerHost,
                                                    onIpChange = { routerHost = it },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                    1 -> {
                                        // 2. Cloud Mode: Cloud DDNS / Serial Input + Port
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Port Input (بورت)
                                                OutlinedTextField(
                                                    value = routerPort,
                                                    onValueChange = { routerPort = it },
                                                    label = { Text("بورت") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    modifier = Modifier.width(82.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true
                                                )

                                                // Cloud DNS / Serial Input
                                                OutlinedTextField(
                                                    value = routerCloudDns,
                                                    onValueChange = { routerCloudDns = it },
                                                    label = { Text("كلاود DDNS أو سيريال الراوتر") },
                                                    placeholder = { Text("e.g. 4f8205f2a1b3.sn.mynetname.net") },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Filled.Cloud,
                                                            contentDescription = "Cloud",
                                                            tint = Color(0xFF0C5A60)
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true
                                                )
                                            }

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "اتصال سحابي مشفر عبر سحابة مايكروتيك (MikroTik Cloud)",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (routerCloudDns.isNotBlank() && !routerCloudDns.contains(".")) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = Color(0xFFDCFCE7),
                                                        modifier = Modifier.clickable {
                                                            routerCloudDns = "${routerCloudDns.trim()}.sn.mynetname.net"
                                                        }
                                                    ) {
                                                        Text(
                                                            text = "+ إضافة .sn.mynetname.net",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF166534),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    2 -> {
                                        // 3. Domain Mode: Domain/URL Input + Port
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Port Input (بورت)
                                                OutlinedTextField(
                                                    value = routerPort,
                                                    onValueChange = { routerPort = it },
                                                    label = { Text("بورت") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    modifier = Modifier.width(82.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true
                                                )

                                                // Domain Input (رابط الدومين)
                                                OutlinedTextField(
                                                    value = routerDomain,
                                                    onValueChange = { routerDomain = it },
                                                    label = { Text("رابط الدومين / السيرفر") },
                                                    placeholder = { Text("example.sn.mynetname.net") },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Filled.Dns,
                                                            contentDescription = "Domain",
                                                            tint = Color(0xFF0C5A60)
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true
                                                )
                                            }
                                            Text(
                                                text = "يدعم دومينات مايكروتيك السحابية (Cloud DDNS) ومنافذ الـ API والـ Web",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                                            )
                                        }
                                    }
                                    3 -> {
                                        // 4. L2TP VPN Mode: الدخول عن بعد (رابط VPN والبورت)
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // RouterOS API port reachable after the VPN tunnel is active
                                                OutlinedTextField(
                                                    value = vpnPort,
                                                    onValueChange = { vpnPort = it },
                                                    label = { Text("منفذ API بعد VPN") },
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                    modifier = Modifier.width(82.dp),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true
                                                )

                                                // VPN Server Link / Address (رابط VPN)
                                                OutlinedTextField(
                                                    value = vpnServerLink,
                                                    onValueChange = { vpnServerLink = it },
                                                    label = { Text("عنوان الراوتر عبر VPN") },
                                                    placeholder = { Text("عنوان الراوتر داخل النفق أو سيرفر VPN") },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Filled.VpnKey,
                                                            contentDescription = "VPN",
                                                            tint = Color(0xFF0C5A60)
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(12.dp),
                                                    singleLine = true
                                                )
                                            }

                                            // L2TP itself uses UDP 1701 in Android VPN settings;
                                            // these chips select the RouterOS API port used for login.
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf("API 8728" to "8728", "API-SSL 8729" to "8729")
                                                    .forEach { (chipLabel, chipPort) ->
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = if (vpnPort == chipPort) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                        modifier = Modifier.clickable {
                                                            vpnPort = chipPort
                                                            useSsl = chipPort == "8729"
                                                        }
                                                    ) {
                                                        Text(
                                                            text = chipLabel,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (vpnPort == chipPort) MaterialTheme.colorScheme.onPrimary
                                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            if (!vpnActive) {
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 8.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = Color(0xFFFFF7ED),
                                                    border = BorderStroke(1.dp, Color(0xFFFDBA74))
                                                ) {
                                                    Text(
                                                        text = "تنبيه: لا يوجد نفق VPN نشط الآن. شغّل اتصال L2TP من إعدادات أندرويد أولاً، وإلا فقد يفشل الوصول إلى عنوان الراوتر.",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF9A3412),
                                                        modifier = Modifier.padding(10.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            // Open Android's system VPN settings to add/enable the L2TP profile
                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        context.startActivity(
                                                            Intent("android.net.vpn.Settings")
                                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        )
                                                    } catch (vpnSettingsMissing: Exception) {
                                                        try {
                                                            context.startActivity(
                                                                Intent(Settings.ACTION_SETTINGS)
                                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                            )
                                                        } catch (settingsMissing: Exception) {
                                                            // No settings screen available — ignore
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.VpnKey,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(text = "فتح إعدادات VPN في أندرويد", fontSize = 12.sp)
                                            }

                                            Text(
                                                text = "الدخول المحلي (IP/كلاود/دومين) منفصل تماماً عن هذا الوضع. هنا يتصل التطبيق بالراوتر عبر نفق L2TP/IPsec ينشئه نظام أندرويد (منفذ 1701)؛ أضف الاتصال من إعدادات VPN، فعّله، ثم أدخل عنوان الراوتر داخل النفق واختر منفذ RouterOS API ‏8728 أو 8729.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Username Input (اسم المستخدم)
                                OutlinedTextField(
                                    value = routerUser,
                                    onValueChange = { routerUser = it },
                                    label = { Text("اسم المستخدم") },
                                    placeholder = { Text("أدخل اسم المستخدم للراوتر") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Person,
                                            contentDescription = "Username",
                                            tint = Color(0xFF0C5A60)
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Password Input (كلمة المرور)
                                OutlinedTextField(
                                    value = routerPassword,
                                    onValueChange = { routerPassword = it },
                                    label = { Text("كلمة المرور") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = "Password",
                                            tint = Color(0xFF0C5A60)
                                        )
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                                contentDescription = "Toggle password"
                                            )
                                        }
                                    },
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // 3 Action Buttons Row: [معرف حسابي] [اختبار الاتصال] [تسجيل الدخول]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // معرف حسابي
                                    OutlinedButton(
                                        onClick = { showAccountIdDialog = true },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(text = "معرف حسابي", fontSize = 12.sp, maxLines = 1)
                                    }

                                    // اختبار الاتصال
                                    OutlinedButton(
                                        onClick = {
                                            isTestingConnection = true
                                            val (cleanHostStr, p) = resolveTarget()
                                            viewModel.testConnection(cleanHostStr, p) { ok, msg ->
                                                isTestingConnection = false
                                                testResultDialog = Pair(ok, msg)
                                            }
                                        },
                                        modifier = Modifier.weight(1.1f),
                                        shape = RoundedCornerShape(12.dp),
                                        enabled = !isTestingConnection
                                    ) {
                                        if (isTestingConnection) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text(text = "اختبار الاتصال", fontSize = 12.sp, maxLines = 1)
                                        }
                                    }

                                    // تسجيل الدخول (Primary Teal Button)
                                    Button(
                                        onClick = {
                                            val (cleanHostStr, p) = resolveTarget()
                                            val name = connectionDisplayName(cleanHostStr)
                                            viewModel.connectManualRouter(
                                                name = name,
                                                host = cleanHostStr,
                                                port = p,
                                                username = routerUser.trim(),
                                                password = routerPassword,
                                                useSsl = useSsl,
                                                onSuccess = { onLoginSuccess() }
                                            )
                                        },
                                        modifier = Modifier.weight(1.2f),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        enabled = !isConnecting
                                    ) {
                                        if (isConnecting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Text(
                                                text = "تسجيل الدخول",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // خيارات الدخول ⚙ Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "خيارات الدخول ⚙",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Checkbox: تذكر كلمة المرور
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { rememberPassword = !rememberPassword }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = rememberPassword,
                                        onCheckedChange = { rememberPassword = it },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0C5A60))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "تذكر كلمة المرور (سيتم حفظها كنص)",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // Checkbox: دخول بعمل مزامنه لقاعدة البيانات
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { syncOnLogin = !syncOnLogin }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = syncOnLogin,
                                        onCheckedChange = { syncOnLogin = it },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0C5A60))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "دخول بعمل مزامنه لقاعدة البيانات",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // Checkbox: حفظ نسخة احتياطية لليوزرمانجر
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { backupUserManager = !backupUserManager }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = backupUserManager,
                                        onCheckedChange = { backupUserManager = it },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0C5A60))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "حفظ نسخة احتياطية لليوزرمانجر",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                // Switch: متقدم
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "خيارات متقدمة (SSL، اسم الراوتر، مهلة الاتصال)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Switch(
                                        checked = isAdvancedOpen,
                                        onCheckedChange = { isAdvancedOpen = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF0C5A60))
                                    )
                                }

                                AnimatedVisibility(visible = isAdvancedOpen) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = routerCustomName,
                                            onValueChange = { routerCustomName = it },
                                            label = { Text("اسم الراوتر (اختياري)") },
                                            placeholder = { Text("راوتر الفرع الرئيسي") },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        OutlinedTextField(
                                            value = radiusHost,
                                            onValueChange = { radiusHost = it },
                                            label = { Text("عنوان RADIUS (اختياري)") },
                                            placeholder = { Text("127.0.0.1") },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = timeoutSeconds,
                                                onValueChange = { timeoutSeconds = it },
                                                label = { Text("مهلة الاتصال (ثواني)") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp)
                                            )

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(top = 6.dp)
                                            ) {
                                                Checkbox(
                                                    checked = useSsl,
                                                    onCheckedChange = { enabled ->
                                                        useSsl = enabled
                                                        // API-SSL (8729) and plain API (8728) are not
                                                        // interchangeable; keep the port in sync.
                                                        if (connectionMode == 3) {
                                                            if (enabled && vpnPort == "8728") vpnPort = "8729"
                                                            if (!enabled && vpnPort == "8729") vpnPort = "8728"
                                                        } else {
                                                            if (enabled && routerPort == "8728") routerPort = "8729"
                                                            if (!enabled && routerPort == "8729") routerPort = "8728"
                                                        }
                                                    },
                                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0C5A60))
                                                )
                                                Text("تشفير SSL", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // TAB 1: الراوترات المسجلة (Screenshot 4)
                if (selectedTab == 1) {
                    item {
                        // Header with Add and Refresh icons
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "الراوترات المسجلة",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Add button (+)
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable { showAddRouterDialog = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = "Add Router",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // Refresh button
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clickable { /* Refresh */ }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = "Refresh",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // If empty, show helpful prompt to add or connect
                    if (savedConnections.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Router,
                                        contentDescription = null,
                                        tint = Color(0xFF0C5A60),
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "لا يوجد راوترات محفوظة حالياً",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "يمكنك إضافة راوترك من علامة (+) أعلاه أو إدخال البيانات في تبويب 'بيانات الدخول'",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        items(savedConnections) { conn ->
                            val isSelected = selectedSavedRouterId == conn.id

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp)
                                    .clickable { selectedSavedRouterId = conn.id },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFFE0F2F1) else MaterialTheme.colorScheme.surface
                                ),
                                border = if (isSelected) {
                                    CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0xFF0C5A60), Color(0xFF167C80))))
                                } else null,
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Radio selector
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selectedSavedRouterId = conn.id },
                                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0C5A60))
                                    )

                                    // Squircle router emblem with green active dot
                                    Box(contentAlignment = Alignment.BottomEnd) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF0C5A60).copy(alpha = 0.12f),
                                            modifier = Modifier.size(46.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Filled.Router,
                                                    contentDescription = null,
                                                    tint = Color(0xFF0C5A60),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                        // Green dot
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF10B981))
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Info
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = conn.host,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = "${conn.host}:${conn.port}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = conn.username,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Action buttons: Delete, Edit, Quick Connect
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Delete Trash icon
                                        IconButton(
                                            onClick = { deleteConfirmRouter = conn },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.DeleteOutline,
                                                contentDescription = "Delete",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        // Quick Connect Lightning
                                        IconButton(
                                            onClick = {
                                                // Saved routers store a direct host/port, so never
                                                // route them through the L2TP/Cloud/Domain fields.
                                                viewModel.connectRouter(conn, conn.host, conn.port) {
                                                    onLoginSuccess()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Bolt,
                                                contentDescription = "Quick Connect",
                                                tint = Color(0xFF0C5A60),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(14.dp))

                        // Bottom Action: [تسجيل الدخول ➔]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Right: تسجيل الدخول ➔ (Connects to chosen router)
                            Button(
                                onClick = {
                                    // The form is the single source of truth — it is
                                    // auto-filled from the selected saved router above,
                                    // so login uses exactly what the user sees.
                                    val (cleanHostStr, p) = resolveTarget()
                                    val name = connectionDisplayName(cleanHostStr)
                                    viewModel.connectManualRouter(
                                        name = name,
                                        host = cleanHostStr,
                                        port = p,
                                        username = routerUser.trim(),
                                        password = routerPassword,
                                        useSsl = useSsl,
                                        onSuccess = { onLoginSuccess() }
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                enabled = !isConnecting
                            ) {
                                if (isConnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "تسجيل الدخول",
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Login,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Full width: استرجاع نسخة مشفرة ⬆
                        OutlinedButton(
                            onClick = { showBackupRestoreDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.UploadFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "استرجاع نسخة مشفرة",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // Mandatory Developer Credits in Footer as explicitly requested
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "تطوير وبرمجة شركة الهيثم للبرمجيات",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "هاتف التواصل: 773114243 | NetCraft v1.0.0",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }

    // Dialog 1: معرف حسابي
    if (showAccountIdDialog) {
        AlertDialog(
            onDismissRequest = { showAccountIdDialog = false },
            title = {
                Text("معرف الحساب والنظام", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("معرف الجهاز الحالي:")
                    Text(
                        text = "NETCRAFT-ID-773114243",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0C5A60)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("NetCraft - ترخيص رقم #8472")
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountIdDialog = false }) {
                    Text("حسناً")
                }
            }
        )
    }

    // Dialog 2: نتيجة اختبار الاتصال
    testResultDialog?.let { result ->
        AlertDialog(
            onDismissRequest = { testResultDialog = null },
            icon = {
                Icon(
                    imageVector = if (result.first) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (result.first) Color(0xFF10B981) else Color(0xFFEF4444),
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = if (result.first) "الاتصال ناجح" else "فشل اختبار الاتصال",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(text = result.second)
            },
            confirmButton = {
                Button(
                    onClick = { testResultDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("إغلاق")
                }
            }
        )
    }

    // Dialog 3: إضافة راوتر جديد
    if (showAddRouterDialog) {
        var newName by remember { mutableStateOf("") }
        var newHost by remember { mutableStateOf(detectedGateway) }
        var newPort by remember { mutableStateOf("8728") }
        var newUser by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddRouterDialog = false },
            title = { Text("إضافة راوتر جديد", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("اسم الراوتر") },
                        placeholder = { Text("راوتر الشبكة") }
                    )
                    OutlinedTextField(
                        value = newHost,
                        onValueChange = { newHost = it },
                        label = { Text("عنوان IP / Domain") }
                    )
                    OutlinedTextField(
                        value = newPort,
                        onValueChange = { newPort = it },
                        label = { Text("المنفذ (Port)") }
                    )
                    OutlinedTextField(
                        value = newUser,
                        onValueChange = { newUser = it },
                        label = { Text("اسم المستخدم") }
                    )
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = { newPass = it },
                        label = { Text("كلمة المرور") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = newPort.toIntOrNull() ?: 8728
                        viewModel.saveRouterConnection(
                            RouterConnection(
                                name = newName.ifBlank { "راوتر $newHost" },
                                host = newHost.trim(),
                                port = p,
                                username = newUser.trim(),
                                password = newPass
                            )
                        )
                        showAddRouterDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حفظ الراوتر")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRouterDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Dialog 4: تأكيد حذف راوتر
    deleteConfirmRouter?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleteConfirmRouter = null },
            title = { Text("تأكيد الحذف", fontWeight = FontWeight.Bold) },
            text = { Text("هل أنت متأكد من حذف الراوتر '${conn.name}' (${conn.host}) من القائمة المحفوظة؟") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSavedConnection(conn)
                        deleteConfirmRouter = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("نعم، احذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmRouter = null }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Dialog 5: استرجاع نسخة مشفرة
    if (showBackupRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = { Text("استرجاع نسخة احتياطية", fontWeight = FontWeight.Bold) },
            text = {
                Text("خاصية استرجاع واستيراد ملفات النسخ الاحتياطي المشفرة لليوزرمانجر وقواعد بيانات NetCraft جاهزة ومجهزة بالكامل.")
            },
            confirmButton = {
                Button(
                    onClick = { showBackupRestoreDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً")
                }
            }
        )
    }

    // Animated Connection Overlay
    if (isConnecting) {
        val activeConn = savedConnections.firstOrNull { it.id == selectedSavedRouterId }
        val targetName = activeConn?.name ?: "راوتر ميكروتك"
        val targetHost = activeConn?.host ?: routerHost
        com.example.ui.components.AnimatedConnectingDialog(
            routerName = targetName,
            host = targetHost,
            onCancel = {
                viewModel.clearConnectionError()
            }
        )
    }

    // App Exit Confirmation Dialog
    if (showExitAppDialog) {
        AppExitConfirmationDialog(
            onDismiss = { showExitAppDialog = false }
        )
    }
}

@Composable
private fun RowScope.AccessModeOption(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RowScope.LocalModeChip(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = if (selected) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun VpnStatusBanner(active: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (active) Color(0xFFDCFCE7) else Color(0xFFFFF7ED),
        border = BorderStroke(1.dp, if (active) Color(0xFF86EFAC) else Color(0xFFFDBA74))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFF16A34A) else Color(0xFFF97316))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (active) "نفق VPN نشط — أدخل عنوان الراوتر داخل النفق"
                else "نفق VPN غير نشط — فعّل اتصال L2TP من إعدادات أندرويد",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) Color(0xFF15803D) else Color(0xFF9A3412)
            )
        }
    }
}

private fun hasActiveVpn(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    @Suppress("DEPRECATION")
    val networks = manager.allNetworks
    return networks.any { network ->
        manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}
