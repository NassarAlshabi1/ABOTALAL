package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.AppExitConfirmationDialog
import com.example.ui.screens.*
import com.example.ui.screens.designs.DesignsScreen
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MikroTikViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val application = context.applicationContext as Application
            val viewModel: MikroTikViewModel = viewModel(
                factory = MikroTikViewModel.Factory(application)
            )
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = isDarkMode) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        (view.context as? Activity)?.window?.let { window ->
                            WindowCompat.getInsetsController(window, view).apply {
                                // The header follows the active light/dark Material You scheme.
                                isAppearanceLightStatusBars = !isDarkMode
                                isAppearanceLightNavigationBars = !isDarkMode
                            }
                        }
                    }
                }
                MainAppNavigation(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppNavigation(viewModel: MikroTikViewModel) {
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val conn by viewModel.currentConnection.collectAsStateWithLifecycle()
    val userManagerProfiles by viewModel.userManagerProfiles.collectAsStateWithLifecycle()
    val hotspotProfiles by viewModel.hotspotProfiles.collectAsStateWithLifecycle()

    var showSplash by rememberSaveable { mutableStateOf(true) }
    // 0 = الرئيسية, 1 = الهوتسبوت, 2 = السجل, 3 = الشبكة, 4 = اليوزرمانجر,
    // 5 = التصاميم, 6 = الأدوات, 7 = الكلاود
    var currentTab by rememberSaveable { mutableIntStateOf(0) }

    // Dialogs for Header Actions
    var showPowerDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showExitAppDialog by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var syncRequest by remember { mutableIntStateOf(0) }
    var isSyncing by remember { mutableStateOf(false) }

    // Keep the sync button enabled even while a previous refresh is running.
    // The repository refresh is asynchronous, so this only acknowledges the
    // request visually and never claims that the operation already finished.
    LaunchedEffect(syncRequest) {
        if (syncRequest == 0) return@LaunchedEffect
        isSyncing = true
        delay(1200)
        isSyncing = false
    }

    // First: Splash Screen
    if (showSplash) {
        SplashScreen(
            onFinished = {
                showSplash = false
            }
        )
        return
    }

    // Enforce Arabic RTL direction by default
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        if (!isConnected) {
            // Login & Router Selection Screen
            LoginScreen(
                viewModel = viewModel,
                onLoginSuccess = {
                    currentTab = 0 // Navigate to Dashboard
                }
            )
        } else {
            // Back handler when logged in
            BackHandler {
                if (currentTab != 0) {
                    currentTab = 0
                } else {
                    showExitAppDialog = true
                }
            }

            // Main Authenticated Scaffold. Tab screens (Hotspot, Tools, Cloud, …)
            // already own a Scaffold/TopAppBar that applies system-bar insets; letting
            // this shell apply them again doubled the status/navigation padding and
            // shrank their content. Keep the shell inset-free and let the active
            // screen handle insets exactly once.
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    if (currentTab == 0) {
                        // Top Header Bar for Home/Dashboard with luxurious teal/blue horizontal gradient
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation = 8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .statusBarsPadding()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Right Side (in RTL): Green "متصل •" Badge + Username & IP
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.onTertiaryContainer)
                                                )
                                                Text(
                                                    text = "متصل",
                                                    fontFamily = CairoFontFamily,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Column {
                                            Text(
                                                text = conn?.username?.takeIf { it.isNotBlank() } ?: "المدير",
                                                fontFamily = CairoFontFamily,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = conn?.host?.takeIf { it.isNotBlank() } ?: "-",
                                                fontFamily = CairoFontFamily,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    // Center: App Brand NetCraft with VIP Star
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = "NetCraft",
                                                fontFamily = CairoFontFamily,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                        Text(
                                            text = "إدارة وطباعة الكروت",
                                            fontFamily = CairoFontFamily,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    // Left Side: 4 Luxury Action Icons
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // 1. Red Power Button (Reboot / Shutdown)
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { showPowerDialog = true }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Filled.PowerSettingsNew,
                                                    contentDescription = "طاقة الراوتر",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // 2. Exit / Disconnect Button
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { showExitDialog = true }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                                    contentDescription = "تسجيل الخروج",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // 3. Dark / Light Mode Toggle
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { viewModel.toggleDarkMode() }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (isDarkMode) Icons.Filled.LightMode else Icons.Filled.NightlightRound,
                                                    contentDescription = "الوضع الليلي",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        // 4. Refresh / Sync Button
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable {
                                                    syncRequest++
                                                    viewModel.refreshAllData()
                                                    actionMessage = "بدأت مزامنة بيانات الشبكة والراوتر. يمكنك متابعة استخدام التطبيق أثناء التحديث."
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                if (isSyncing) {
                                                    CircularProgressIndicator(
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                                        strokeWidth = 2.dp,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Filled.Refresh,
                                                        contentDescription = "مزامنة وتحديث",
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    if (currentTab == 0) {
                        // Custom Bottom Navigation Bar only shown on the Main Screen
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(elevation = 10.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. الرئيسية
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { currentTab = 0 }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Home,
                                                contentDescription = "الرئيسية",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = "الرئيسية",
                                                fontFamily = CairoFontFamily,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }

                                // 2. الهوتسبوت
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { currentTab = 1 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Wifi,
                                        contentDescription = "الهوتسبوت",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = "الهوتسبوت",
                                        fontFamily = CairoFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // 4. الأدوات
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { currentTab = 6 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Build,
                                        contentDescription = "الأدوات",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = "الأدوات",
                                        fontFamily = CairoFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // 5. الشبكة
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { currentTab = 3 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Router,
                                        contentDescription = "الشبكة",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = "الشبكة",
                                        fontFamily = CairoFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentTab) {
                        0 -> DashboardScreen(
                            viewModel = viewModel,
                            navigateToTab = { tabIndex ->
                                currentTab = when (tabIndex) {
                                    1 -> 1 // Services / Hotspot
                                    2 -> 4 // Services / UserManagerScreen
                                    3 -> 2 // Reports / LogScreen
                                    4 -> 3 // Interfaces / Network
                                    5 -> 5 // DesignsScreen
                                    6 -> 6 // ToolsScreen
                                    7 -> 7 // CloudScreen
                                    else -> 0
                                }
                            }
                        )
                        1 -> HotspotScreen(
                            viewModel = viewModel,
                            onBack = { currentTab = 0 }
                        )
                        2 -> LogScreen(
                            viewModel = viewModel,
                            onBack = { currentTab = 0 }
                        )
                        3 -> InterfacesScreen(
                            viewModel = viewModel,
                            onRouterDisconnected = { viewModel.logout() },
                            onBack = { currentTab = 0 }
                        )
                        4 -> UserManagerScreen(
                            viewModel = viewModel,
                            onBack = { currentTab = 0 }
                        )
                        5 -> DesignsScreen(
                            availableUmPackages = userManagerProfiles.map { it.name },
                            availableHotspotProfiles = hotspotProfiles.map { it.name },
                            onBack = { currentTab = 0 }
                        )
                        6 -> ToolsScreen(
                            viewModel = viewModel,
                            onBack = { currentTab = 0 }
                        )
                        7 -> CloudScreen(
                            viewModel = viewModel,
                            onBack = { currentTab = 0 }
                        )
                    }
                }
            }
        }
    }

    // Power Dialog: Reboot or Shutdown Router
    if (showPowerDialog) {
        AlertDialog(
            onDismissRequest = { showPowerDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text("التحكم بطاقة الراوتر", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "اختر الإجراء الذي ترغب بتنفيذه على الراوتر المتصل (${conn?.host ?: "-"}):",
                        fontFamily = CairoFontFamily
                    )

                    // Reboot Button
                    Button(
                        onClick = {
                            showPowerDialog = false
                            viewModel.rebootSystem(
                                onSuccess = { msg -> actionMessage = msg },
                                onError = { err -> actionMessage = err }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Filled.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("إعادة تشغيل الراوتر (Reboot)", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold)
                    }

                    // Shutdown Button
                    Button(
                        onClick = {
                            showPowerDialog = false
                            viewModel.shutdownSystem(
                                onSuccess = { msg -> actionMessage = msg },
                                onError = { err -> actionMessage = err }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Filled.PowerSettingsNew, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("إيقاف تشغيل الراوتر (Shutdown)", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPowerDialog = false }) {
                    Text("إلغاء", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    // Exit Dialog: Disconnect and return to Login
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = Color(0xFF0C5A60),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = { Text("تسجيل الخروج", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text("هل تريد قطع الاتصال بالراوتر والعودة إلى شاشة تسجيل الدخول؟", fontFamily = CairoFontFamily)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitDialog = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("تسجيل الخروج", fontFamily = CairoFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("إلغاء", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    // App Exit Confirmation Dialog
    if (showExitAppDialog) {
        AppExitConfirmationDialog(
            onDismiss = { showExitAppDialog = false }
        )
    }

    // Result Notification Dialog
    actionMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { actionMessage = null },
            title = { Text("تنبيه النظام", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = { Text(msg, fontFamily = CairoFontFamily) },
            confirmButton = {
                Button(
                    onClick = { actionMessage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }
}
