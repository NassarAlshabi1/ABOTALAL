package com.example.ui.screens

import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ReceiptLong

import androidx.core.content.edit

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.RouterBackupFile
import com.example.R
import com.example.ui.components.LiveLineChart
import com.example.ui.components.RefreshableListContainer
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.netCraftColors
import com.example.ui.viewmodel.MikroTikViewModel
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: MikroTikViewModel,
    navigateToTab: (Int) -> Unit
) {
    val stats by viewModel.routerStats.collectAsStateWithLifecycle()
    val conn by viewModel.currentConnection.collectAsStateWithLifecycle()
    val logs by viewModel.activityLogs.collectAsStateWithLifecycle()
    val hotspotUsers by viewModel.hotspotUsers.collectAsStateWithLifecycle()
    val userManagerUsers by viewModel.userManagerUsers.collectAsStateWithLifecycle()
    val profiles by viewModel.userManagerProfiles.collectAsStateWithLifecycle()
    val activeHotspot by viewModel.activeHotspot.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val profilePrices = remember(profiles) { profiles.associate { it.name to it.price } }
    val cardsAccountValue = userManagerUsers.sumOf { profilePrices[it.profile] ?: 0 }
    val formattedCardsAccountValue = String.format(Locale.getDefault(), "%,d", cardsAccountValue)

    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshResultMessage by remember { mutableStateOf<String?>(null) }

    // Semantic theme tokens — replaces the old hardcoded isDarkMode colors.
    val appColors = netCraftColors()
    val screenBg = appColors.screenBg
    val cardBg = appColors.cardBg
    val cardBorderColor = appColors.cardBorder
    val textPrimary = appColors.textPrimary
    val textSecondary = appColors.textSecondary

    // Dialog states for services
    var showExpensesDialog by remember { mutableStateOf(false) }
    var showCardsDesignDialog by remember { mutableStateOf(false) }
    var showActiveUsersDialog by remember { mutableStateOf(false) }
    var showFinancialReportDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRecentActivitiesDialog by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showPrintingSystemDialog by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    // Refresh animation rotation
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
    val refreshRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_spin"
    )

    fun doRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        refreshResultMessage = null
        viewModel.refreshAllData { error ->
            isRefreshing = false
            refreshResultMessage = error?.let { "تعذر تحديث البيانات: $it" } ?: "تم تحديث البيانات بنجاح"
        }
    }

    // ── Live metric history (sampled from the 3-second stats ticker) ──
    val cpuHistory = remember { mutableStateListOf<Int>() }
    val memHistory = remember { mutableStateListOf<Int>() }
    LaunchedEffect(Unit) {
        viewModel.routerStats.collect { s ->
            val cpu = s?.cpuUsage ?: return@collect
            val total = s.totalMemoryBytes
            val free = s.freeMemoryBytes
            val memPct = if (total > 0) (((total - free) * 100) / total).toInt() else return@collect
            if (cpuHistory.lastOrNull() != cpu) {
                cpuHistory.add(cpu)
                if (cpuHistory.size > 40) cpuHistory.removeAt(0)
            }
            if (memHistory.lastOrNull() != memPct) {
                memHistory.add(memPct)
                if (memHistory.size > 40) memHistory.removeAt(0)
            }
        }
    }

    RefreshableListContainer(
        isRefreshing = isRefreshing,
        onRefresh = { doRefresh() },
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg),
    ) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. شريط السحب للتحديث
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { doRefresh() },
                shape = RoundedCornerShape(20.dp),
                color = cardBg,
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.horizontalGradient(listOf(cardBorderColor, cardBorderColor))
                ),
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "تحديث",
                        tint = Color(0xFF0284C7),
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (isRefreshing) Modifier.rotate(refreshRotation) else Modifier)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isRefreshing -> "جارٍ تحديث البيانات…"
                            refreshResultMessage != null -> refreshResultMessage!!
                            else -> "اضغط لتحديث البيانات"
                        },
                        fontFamily = CairoFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (refreshResultMessage?.startsWith("تعذر") == true && !isRefreshing) MaterialTheme.colorScheme.error else textPrimary
                    )
                }
            }
        }

        // 2. بطاقة معلومات الراوتر (Dark Teal Router Info Card - مطابقة تماماً للشاشة)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(22.dp)),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A5860))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF0C5A60),
                                    Color(0xFF0A4E53),
                                    Color(0xFF07383C)
                                )
                            )
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // السطر العلوي للبطاقة: عنوان الـ IP وزر التحديث
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // اليمين: أيقونة الراوتر + الـ IP
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Router,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = conn?.host?.ifEmpty { "-" } ?: "-",
                                fontFamily = CairoFontFamily,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // اليسار: زر التحديث الدائري
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier
                                .size(34.dp)
                                .clickable { doRefresh() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "تحديث الراوتر",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .then(if (isRefreshing) Modifier.rotate(refreshRotation) else Modifier)
                                )
                            }
                        }
                    }

                    // شبكة تفاصيل الراوتر (3 صفوف × عمودين كبسولية)
                    // الصف 1: مدة التشغيل | اصدار الراوتر
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RouterDetailPill(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Schedule,
                            label = "مدة التشغيل",
                            value = stats?.uptime?.ifEmpty { "-" } ?: "-"
                        )
                        RouterDetailPill(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Settings,
                            label = "اصدار الراوتر",
                            value = stats?.version?.ifEmpty { "-" } ?: "-"
                        )
                    }

                    // الصف 2: المعالج | الحرارة
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RouterDetailPill(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Memory,
                            label = "المعالج",
                            value = stats?.let { "${it.cpuUsage}%" } ?: "—"
                        )
                        RouterDetailPill(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.DeviceThermostat,
                            label = "الحرارة",
                            value = "-"
                        )
                    }

                    // الصف 3: الذاكرة | المستخدمون النشطون
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RouterDetailPill(
                            modifier = Modifier.weight(1f),
                            icon = Icons.AutoMirrored.Outlined.FormatListBulleted,
                            label = "الذاكرة",
                            value = run {
                                val total = stats?.totalMemoryBytes
                                val free = stats?.freeMemoryBytes
                                if (total != null && free != null && total > 0L) {
                                    val usedMb = ((total - free).coerceAtLeast(0L)) / 1048576f
                                    "%.1f / %.1f MB".format(usedMb, total / 1048576f)
                                } else "—"
                            },
                            hasProgressBar = (stats?.totalMemoryBytes ?: 0L) > 0L,
                            progressFraction = if ((stats?.totalMemoryBytes ?: 0L) > 0L)
                                (((stats!!.totalMemoryBytes - stats!!.freeMemoryBytes).toFloat()) / stats!!.totalMemoryBytes).coerceIn(0f, 1f)
                            else 0f
                        )
                        RouterDetailPill(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Group,
                            label = "المستخدمون النشطون",
                            value = if (stats == null) "—" else "${activeHotspot.size}"
                        )
                    }
                }
            }
        }

        // 2.5 بطاقة المراقبة الحية: مخططات المعالج والذاكرة (تحديث كل 3 ثوانٍ)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveMetricCard(
                    title = stringResource(R.string.cpu_live_chart_title),
                    currentLabel = stringResource(R.string.cpu_current_value, stats?.cpuUsage ?: 0),
                    values = cpuHistory,
                    lineColor = appColors.chartLinePrimary,
                    fillColor = appColors.chartLinePrimary,
                    gridColor = appColors.chartGrid,
                    labelColor = textSecondary,
                )
                LiveMetricCard(
                    title = stringResource(R.string.memory_live_chart_title),
                    currentLabel = stringResource(
                        R.string.memory_current_value,
                        run {
                            val s = stats
                            if (s != null && s.totalMemoryBytes > 0)
                                (((s.totalMemoryBytes - s.freeMemoryBytes) * 100) / s.totalMemoryBytes).toInt()
                            else 0
                        }
                    ),
                    values = memHistory,
                    lineColor = appColors.chartLineSecondary,
                    fillColor = appColors.chartLineSecondary,
                    gridColor = appColors.chartGrid,
                    labelColor = textSecondary,
                )
            }
        }

        // 3. شبكة إحصائيات الكروت (4 بطاقات 2 × 2 Grid - مطابقة تماماً للشاشة)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // الصف العلوي من الكروت: قيمة الحسابات | الكروت المولدة
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // قيمة الحسابات محسوبة من أسعار الباقات المحمّلة من الراوتر.
                    StatCardItem(
                        modifier = Modifier.weight(1f),
                        title = "قيمة الحسابات",
                        icon = Icons.Filled.PointOfSale,
                        iconTint = Color(0xFF0284C7),
                        iconBackground = Color(0xFFE0F2FE),
                        value = "$formattedCardsAccountValue ريال",
                        footer = "حسب أسعار باقات اليوزرمانجر",
                        isDarkMode = isDarkMode,
                        onClick = { showFinancialReportDialog = true }
                    )

                    // كرت الكروت المولدة (يسار)
                    StatCardItem(
                        modifier = Modifier.weight(1f),
                        title = "الكروت المولدة",
                        icon = Icons.Filled.CreditCard,
                        iconTint = Color(0xFFEA580C),
                        iconBackground = Color(0xFFFFEDD5),
                        value = "${userManagerUsers.size}",
                        footer = "الكروت خلال 30 يوماً: ${userManagerUsers.size}",
                        isDarkMode = isDarkMode,
                        onClick = { navigateToTab(2) }
                    )
                }

                // الصف السفلي من الكروت: الكروت النشطة في User Manager | كروت الهوتسبوت
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // الكروت النشطة تُحسب من User Manager فقط، وليس من جلسات الهوتسبوت.
                    StatCardItem(
                        modifier = Modifier.weight(1f),
                        title = "الكروت النشطة",
                        icon = Icons.Filled.CheckCircle,
                        iconTint = Color(0xFF10B981),
                        iconBackground = Color(0xFFD1FAE5),
                        value = "${userManagerUsers.count { it.active }}",
                        footer = "User Manager",
                        isDarkMode = isDarkMode,
                        onClick = { navigateToTab(2) }
                    )

                    // كرت الكروت المتبقية (يسار)
                    StatCardItem(
                        modifier = Modifier.weight(1f),
                        title = "الكروت المتبقية",
                        icon = Icons.AutoMirrored.Filled.Article,
                        iconTint = Color(0xFF0284C7),
                        iconBackground = Color(0xFFE0F2FE),
                        value = "${hotspotUsers.size}",
                        footer = "كروت الهوتسبوت: ${hotspotUsers.size}",
                        isDarkMode = isDarkMode,
                        onClick = { navigateToTab(1) }
                    )
                }
            }
        }

        // 4. شريط نظام الطباعة NetCraft
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 3.dp, shape = RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showPrintingSystemDialog = true },
                shape = RoundedCornerShape(16.dp),
                color = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFFEF3C7),
                border = if (isDarkMode) BorderStroke(1.dp, Color(0xFFD97706).copy(alpha = 0.5f)) else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // اليمين: مكبر الصوت مع النص
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isDarkMode) Color(0xFFD97706).copy(alpha = 0.25f) else Color(0xFFFDE68A),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Campaign,
                                    contentDescription = null,
                                    tint = if (isDarkMode) Color(0xFFFBBF24) else Color(0xFFD97706),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Text(
                            text = "معا نظام الطباعة NetCraft",
                            fontFamily = CairoFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                    }

                    // اليسار: زر عرض بني برتقالي
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFB45309), // Brown/Deep Amber
                        modifier = Modifier.clickable { showPrintingSystemDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "عرض",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "عرض",
                                fontFamily = CairoFontFamily,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 5. عنوان قسم الخدمات
        item {
            Text(
                text = "الخدمات",
                fontFamily = CairoFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = textPrimary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // 6. شبكة أزرار الخدمات
        // الصف الأول من الخدمات: نظام اليوزرمانجر | نظام الهوتسبوت | المصروفات
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // نظام اليوزرمانجر (يمين - أزرق فاتح)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "نظام اليوزرمانجر",
                    icon = Icons.Filled.People,
                    backgroundColor = Color(0xFFBAE6FD),
                    contentColor = Color(0xFF0284C7),
                    isDarkMode = isDarkMode,
                    onClick = { navigateToTab(2) }
                )

                // نظام الهوتسبوت (وسط - تركواز فاتح)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "نظام الهوتسبوت",
                    icon = Icons.Filled.Wifi,
                    backgroundColor = Color(0xFF99F6E4),
                    contentColor = Color(0xFF0D9488),
                    isDarkMode = isDarkMode,
                    onClick = { navigateToTab(1) }
                )

                // المصروفات (يسار - وردي فاتح)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "المصروفات",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    backgroundColor = Color(0xFFFECDD3),
                    contentColor = Color(0xFFE11D48),
                    isDarkMode = isDarkMode,
                    onClick = { showExpensesDialog = true }
                )
            }
        }

        // الصف الثاني من الخدمات: تصاميم الكروت | الاكتف والهوتسبوت | الحسابات المالية
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // تصاميم الكروت (يمين)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "تصاميم الكروت",
                    icon = Icons.Filled.Palette,
                    backgroundColor = Color.White,
                    contentColor = Color(0xFF10B981),
                    hasBorder = true,
                    isDarkMode = isDarkMode,
                    onClick = { navigateToTab(5) }
                )

                // الاكتف والهوتسبوت (وسط)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "الاكتف والهوتسبوت",
                    icon = Icons.Filled.WifiTethering,
                    backgroundColor = Color.White,
                    contentColor = Color(0xFFEA580C),
                    hasBorder = true,
                    isDarkMode = isDarkMode,
                    onClick = { showActiveUsersDialog = true }
                )

                // الحسابات المالية (يسار)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "الحسابات المالية",
                    icon = Icons.Filled.AccountBalanceWallet,
                    backgroundColor = Color.White,
                    contentColor = Color(0xFFD97706),
                    hasBorder = true,
                    isDarkMode = isDarkMode,
                    onClick = { showFinancialReportDialog = true }
                )
            }
        }

        // الصف الثالث من الخدمات: الملف الشخصي | النسخ الاحتياطية | الأنشطة الحديثة
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // الملف الشخصي (يمين)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "الملف الشخصي",
                    icon = Icons.Filled.Person,
                    backgroundColor = Color.White,
                    contentColor = Color(0xFF0EA5E9),
                    hasBorder = true,
                    isDarkMode = isDarkMode,
                    onClick = { showProfileDialog = true }
                )

                // النسخ الاحتياطية (وسط - بيج فاتح)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "النسخ الاحتياطية",
                    icon = Icons.Filled.Save,
                    backgroundColor = Color(0xFFFEF3C7),
                    contentColor = Color(0xFFD97706),
                    isDarkMode = isDarkMode,
                    onClick = { showBackupDialog = true }
                )

                // الأنشطة الحديثة (يسار - تركواز مائي فاتح)
                ServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "الأنشطة الحديثة",
                    icon = Icons.Filled.History,
                    backgroundColor = Color(0xFFCCFBF1),
                    contentColor = Color(0xFF0D9488),
                    isDarkMode = isDarkMode,
                    onClick = { showRecentActivitiesDialog = true }
                )
            }
        }

        // الصف الرابع من الخدمات: الإرشاد والمساعدة | حول التطبيق (عنصران عريضان)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // الإرشاد والمساعدة (يمين - أخضر فاتح ناعم)
                WideServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "الإرشاد والمساعدة",
                    icon = Icons.Filled.HeadsetMic,
                    backgroundColor = Color(0xFFD1FAE5),
                    contentColor = Color(0xFF059669),
                    isDarkMode = isDarkMode,
                    onClick = { showSupportDialog = true }
                )

                // حول التطبيق (يسار - أزرق فاتح ناعم)
                WideServiceCard(
                    modifier = Modifier.weight(1f),
                    title = "حول التطبيق",
                    icon = Icons.Filled.Info,
                    backgroundColor = Color(0xFFE0F2FE),
                    contentColor = Color(0xFF0284C7),
                    isDarkMode = isDarkMode,
                    onClick = { showAboutDialog = true }
                )
            }
        }

        // ==========================================
        // 5. قسم الأدوات (Tools Section)
        // ==========================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "الأدوات",
                        fontFamily = CairoFontFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = textPrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0C5A60)
                    ) {
                        Text(
                            text = "جديد",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                TextButton(onClick = { navigateToTab(6) }) {
                    Text(
                        text = "فتح قسم الأدوات (38+)",
                        fontFamily = CairoFontFamily,
                        color = Color(0xFF0284C7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF0284C7),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Featured Port & Telegram Banners
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = if (isDarkMode) Color(0xFF1E293B) else Color.White),
                elevation = CardDefaults.cardElevation(3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navigateToTab(6) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF063B40),
                                    Color(0xFF0C5A60),
                                    Color(0xFF0284C7)
                                )
                            )
                        )
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.SettingsEthernet,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "عرض المنافذ واستهلاك الإنترنت",
                                fontFamily = CairoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            Text(
                                text = "مراقبة استهلاك وحجم بيانات كل منفذ والتحكم الفوري",
                                fontFamily = CairoFontFamily,
                                fontSize = 11.sp,
                                color = Color(0xFFBAE6FD)
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "دخول",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

    }
    } // RefreshableListContainer

    // --- Dialogs ---
    if (showPrintingSystemDialog) {
        AlertDialog(
            onDismissRequest = { showPrintingSystemDialog = false },
            title = { Text("نظام الطباعة NetCraft", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "يتيح نظام الطباعة NetCraft تصميم وطباعة كروت الهوتسبوت واليوزرمانجر بتنسيقات متعددة وقوالب جاهزة للطباعة الحرارية ومقاس A4.",
                    fontFamily = CairoFontFamily
                )
            },
            confirmButton = {
                Button(
                    onClick = { showPrintingSystemDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showExpensesDialog) {
        AlertDialog(
            onDismissRequest = { showExpensesDialog = false },
            title = { Text("سجل المصروفات", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text("لا توجد حركات مصروفات مسجلة حالياً.", fontFamily = CairoFontFamily)
            },
            confirmButton = {
                Button(
                    onClick = { showExpensesDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("إغلاق", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showCardsDesignDialog) {
        AlertDialog(
            onDismissRequest = { showCardsDesignDialog = false },
            title = { Text("تصاميم الكروت", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text("نماذج كروت الهوتسبوت جاهزة للطباعة والمزامنة مع الراوتر.", fontFamily = CairoFontFamily)
            },
            confirmButton = {
                Button(
                    onClick = { showCardsDesignDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("إغلاق", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showActiveUsersDialog) {
        AlertDialog(
            onDismissRequest = { showActiveUsersDialog = false },
            title = { Text("المستخدمون النشطون (Active)", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text("المتصلون النشطون الآن: ${activeHotspot.size} مستخدم.", fontFamily = CairoFontFamily)
            },
            confirmButton = {
                Button(
                    onClick = { showActiveUsersDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showFinancialReportDialog) {
        AlertDialog(
            onDismissRequest = { showFinancialReportDialog = false },
            title = { Text("الحسابات المالية", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("قيمة الكروت الحالية حسب أسعار الباقات: $formattedCardsAccountValue ريال.", fontFamily = CairoFontFamily)
                    Text("عدد كروت User Manager: ${userManagerUsers.size}", fontFamily = CairoFontFamily)
                    Text("هذه قيمة تقديرية للحسابات وليست سجل مبيعات محصل.", fontFamily = CairoFontFamily, fontSize = 11.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showFinancialReportDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("الملف الشخصي", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("المستخدم: ${conn?.username ?: "-"}", fontFamily = CairoFontFamily)
                    Text("الراوتر: ${conn?.name ?: "-"}", fontFamily = CairoFontFamily)
                    Text("الصلاحية: مدير النظام (Full Admin)", fontFamily = CairoFontFamily)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showProfileDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showBackupDialog) {
        val context = LocalContext.current
        val backupPrefs = remember { context.getSharedPreferences("router_backup_preferences", android.content.Context.MODE_PRIVATE) }
        var backups by remember { mutableStateOf<List<RouterBackupFile>>(emptyList()) }
        var isLoadingList by remember { mutableStateOf(true) }
        var isCreating by remember { mutableStateOf(false) }
        var listError by remember { mutableStateOf<String?>(null) }
        var pendingRestoreFile by remember { mutableStateOf<String?>(null) }
        var pendingDeleteFile by remember { mutableStateOf<String?>(null) }
        var pendingDownloadFile by remember { mutableStateOf<RouterBackupFile?>(null) }
        var backupFolderUriText by remember { mutableStateOf(backupPrefs.getString("folder_uri", null)) }
        var downloadingBackupName by remember { mutableStateOf<String?>(null) }
        var downloadedBackupBytes by remember { mutableLongStateOf(0L) }
        val backupFolderUri = backupFolderUriText?.let(Uri::parse)
        val backupFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                pendingDownloadFile = null
            } else {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    backupPrefs.edit { putString("folder_uri", uri.toString()) }
                    backupFolderUriText = uri.toString()
                } catch (e: Exception) {
                    pendingDownloadFile = null
                    feedbackMessage = "تعذر حفظ صلاحية الوصول إلى المجلد المختار: ${e.localizedMessage ?: "أعد اختيار مجلد آخر"}"
                }
            }
        }

        fun startBackupDownload(file: RouterBackupFile) {
            val folderUri = backupFolderUri
            if (folderUri == null) {
                feedbackMessage = "اختر أولاً مجلد التنزيل على الهاتف أو بطاقة الذاكرة الخارجية."
                return
            }
            pendingDownloadFile = null
            downloadingBackupName = file.name
            downloadedBackupBytes = 0L
            viewModel.downloadRouterBackup(
                fileName = file.name,
                folderUri = folderUri,
                expectedSizeBytes = file.sizeBytes,
                onProgress = { downloadedBackupBytes = it },
                onSuccess = { bytes ->
                    downloadingBackupName = null
                    downloadedBackupBytes = bytes
                    feedbackMessage = "تم تنزيل النسخة '${file.name}' بنجاح (${formatBackupFileSize(bytes)}) إلى المجلد المختار."
                },
                onError = { error ->
                    downloadingBackupName = null
                    feedbackMessage = "فشل تنزيل النسخة '${file.name}': $error"
                }
            )
        }

        fun reloadBackups() {
            isLoadingList = true
            listError = null
            viewModel.fetchRouterBackups(
                onSuccess = { list -> backups = list; isLoadingList = false },
                onError = { err -> listError = err; isLoadingList = false }
            )
        }

        // Fetch the real list from the router the moment the dialog opens, instead
        // of showing a static placeholder — this is the actual fix for "backups" on
        // the dashboard: it used to just be inert text with a Close button.
        LaunchedEffect(showBackupDialog) { reloadBackups() }

        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("النسخ الاحتياطية لإعدادات الراوتر", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "نسخة احتياطية كاملة لإعدادات الراوتر (System Backup) — تُحفظ على الراوتر نفسه ويمكن استعادتها منه في أي وقت.",
                        fontFamily = CairoFontFamily,
                        fontSize = 12.sp,
                        color = textSecondary
                    )

                    OutlinedButton(
                        onClick = { backupFolderPicker.launch(backupFolderUri) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (backupFolderUri == null) "اختيار مجلد على الهاتف أو بطاقة SD"
                            else "تغيير مجلد التنزيل المحفوظ",
                            fontFamily = CairoFontFamily
                        )
                    }
                    Text(
                        if (backupFolderUri == null)
                            "لم يتم اختيار مجلد بعد. سيُحفظ اختيارك للاستخدام القادم. يمكنك اختيار مجلد داخل بطاقة SD."
                        else {
                            val folder = runCatching { DocumentsContract.getTreeDocumentId(backupFolderUri).substringAfter(':') }.getOrNull()
                            "مجلد الحفظ: ${folder?.takeIf { it.isNotBlank() } ?: "المجلد المختار"} — محفوظ على هذا الجهاز."
                        },
                        fontFamily = CairoFontFamily,
                        fontSize = 11.sp,
                        color = textSecondary
                    )
                    Text(
                        "تنبيه أمني: نقل النسخة يتم عبر FTP غير مشفّر. استخدم شبكة محلية موثوقة أو VPN؛ لا تفتح خدمة FTP للإنترنت. النسخة قد تحتوي كلمات مرور ومفاتيح الراوتر.",
                        fontFamily = CairoFontFamily,
                        fontSize = 11.sp,
                        color = Color(0xFFB45309)
                    )
                    Text(
                        "قد يمنع Android اختيار جذر بطاقة SD مباشرة؛ اختر مجلدًا داخل البطاقة.",
                        fontFamily = CairoFontFamily,
                        fontSize = 10.sp,
                        color = textSecondary
                    )

                    Button(
                        onClick = {
                            isCreating = true
                            viewModel.createRouterBackup(
                                onSuccess = { name ->
                                    isCreating = false
                                    feedbackMessage = "تم إنشاء نسخة احتياطية جديدة: $name"
                                    reloadBackups()
                                },
                                onError = { err ->
                                    isCreating = false
                                    feedbackMessage = "فشل إنشاء النسخة الاحتياطية: $err"
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCreating,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("أخذ نسخة احتياطية جديدة الآن", fontFamily = CairoFontFamily)
                        }
                    }

                    HorizontalDivider()

                    Text(
                        "النسخ المحفوظة على الراوتر:",
                        fontFamily = CairoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = textPrimary
                    )

                    when {
                        isLoadingList -> Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        listError != null -> Text(
                            listError ?: "",
                            fontFamily = CairoFontFamily,
                            fontSize = 12.sp,
                            color = Color(0xFFDC2626)
                        )
                        backups.isEmpty() -> Text(
                            "لا توجد نسخ احتياطية محفوظة على الراوتر حالياً.",
                            fontFamily = CairoFontFamily,
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                        else -> LazyColumn(
                            modifier = Modifier.heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(backups) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.name,
                                            fontFamily = CairoFontFamily,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = textPrimary
                                        )
                                        Text(
                                            "${formatBackupFileSize(file.sizeBytes)} • ${file.time}",
                                            fontFamily = CairoFontFamily,
                                            fontSize = 11.sp,
                                            color = textSecondary
                                        )
                                        if (downloadingBackupName == file.name) {
                                            Text(
                                                "جارٍ التنزيل: ${formatBackupFileSize(downloadedBackupBytes)} من ${formatBackupFileSize(file.sizeBytes)}",
                                                fontFamily = CairoFontFamily,
                                                fontSize = 10.sp,
                                                color = Color(0xFF0284C7)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            pendingDownloadFile = file
                                            if (backupFolderUri == null) backupFolderPicker.launch(null)
                                        },
                                        enabled = downloadingBackupName == null
                                    ) {
                                        if (downloadingBackupName == file.name) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Filled.Download, contentDescription = "تنزيل إلى الهاتف", tint = Color(0xFF059669))
                                        }
                                    }
                                    IconButton(onClick = { pendingRestoreFile = file.name }) {
                                        Icon(Icons.Filled.Restore, contentDescription = "استعادة", tint = Color(0xFF0891B2))
                                    }
                                    IconButton(onClick = { pendingDeleteFile = file.name }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = Color(0xFFDC2626))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("إغلاق", fontFamily = CairoFontFamily)
                }
            }
        )

        pendingDownloadFile?.takeIf { backupFolderUri != null }?.let { file ->
            AlertDialog(
                onDismissRequest = { pendingDownloadFile = null },
                title = { Text("تأكيد تنزيل النسخة", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "سيتم حفظ '${file.name}' (${formatBackupFileSize(file.sizeBytes)}) في المجلد الذي اخترته. ملف النسخة يحتوي بيانات حساسة، وFTP يرسل كلمة المرور والملف دون تشفير. تابع فقط عبر LAN موثوقة أو VPN، وتأكد من عدم إتاحة FTP من الإنترنت.",
                        fontFamily = CairoFontFamily,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(onClick = { startBackupDownload(file) }) {
                        Text("متابعة التنزيل", fontFamily = CairoFontFamily)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDownloadFile = null }) {
                        Text("إلغاء", fontFamily = CairoFontFamily)
                    }
                }
            )
        }

        // Restoring overwrites the router's entire current configuration and makes
        // it reboot immediately — a second explicit confirmation before sending it.
        pendingRestoreFile?.let { fileName ->
            AlertDialog(
                onDismissRequest = { pendingRestoreFile = null },
                title = { Text("تأكيد الاستعادة", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)) },
                text = {
                    Text(
                        "استعادة '$fileName' ستستبدل كل إعدادات الراوتر الحالية بالإعدادات المحفوظة في هذه النسخة، وسيُعيد الراوتر تشغيل نفسه فورًا. هل أنت متأكد؟",
                        fontFamily = CairoFontFamily
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val name = fileName
                            pendingRestoreFile = null
                            viewModel.restoreRouterBackup(
                                name,
                                onSuccess = { msg -> feedbackMessage = msg; showBackupDialog = false },
                                onError = { err -> feedbackMessage = err }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("نعم، استعادة الآن", fontFamily = CairoFontFamily)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRestoreFile = null }) {
                        Text("إلغاء", fontFamily = CairoFontFamily)
                    }
                }
            )
        }

        pendingDeleteFile?.let { fileName ->
            AlertDialog(
                onDismissRequest = { pendingDeleteFile = null },
                title = { Text("حذف النسخة الاحتياطية", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
                text = {
                    Text("هل تريد حذف '$fileName' نهائيًا من الراوتر؟ لا يمكن التراجع عن هذا الإجراء.", fontFamily = CairoFontFamily)
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val name = fileName
                            pendingDeleteFile = null
                            viewModel.deleteRouterBackup(
                                name,
                                onSuccess = { feedbackMessage = "تم حذف النسخة الاحتياطية '$name'"; reloadBackups() },
                                onError = { err -> feedbackMessage = err }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("حذف", fontFamily = CairoFontFamily)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteFile = null }) {
                        Text("إلغاء", fontFamily = CairoFontFamily)
                    }
                }
            )
        }
    }

    // Feedback dialog for backup create/restore/delete results (and any future
    // async action on this screen that needs to surface a result to the user).
    feedbackMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { feedbackMessage = null },
            title = { Text("تنبيه النظام", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = { Text(msg, fontFamily = CairoFontFamily) },
            confirmButton = {
                Button(
                    onClick = { feedbackMessage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showRecentActivitiesDialog) {
        AlertDialog(
            onDismissRequest = { showRecentActivitiesDialog = false },
            title = { Text("الأنشطة الحديثة", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text("عدد السجلات المحفوظة: ${logs.size} عملية.", fontFamily = CairoFontFamily)
            },
            confirmButton = {
                Button(
                    onClick = { showRecentActivitiesDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("إغلاق", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("الإرشاد والمساعدة والدعم الفني", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("برمجة وتطوير: شركة الهيثم للبرمجيات", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold)
                    Text("هاتف التواصل: 773114243", fontFamily = CairoFontFamily)
                    Text("فريق الدعم الفني جاهز لمساعدتكم في أي استفسارات أو دعم للشبكة.", fontFamily = CairoFontFamily)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSupportDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("حول التطبيق", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("نظام NetCraft", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold)
                    Text("الإصدار: v1.0.1", fontFamily = CairoFontFamily)
                    Text("تطوير: شركة الهيثم للبرمجيات (773114243)", fontFamily = CairoFontFamily)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAboutDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                ) {
                    Text("حسناً", fontFamily = CairoFontFamily)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// مكونات واجهة المستخدم المخصصة المطابقة لقطة الشاشة
// -------------------------------------------------------------

@Composable
fun RouterDetailPill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    hasProgressBar: Boolean = false,
    progressFraction: Float = 0f
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.22f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color(0xFF99F6E4),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = label,
                        fontFamily = CairoFontFamily,
                        color = Color(0xFFCCFBF1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = value,
                    fontFamily = CairoFontFamily,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hasProgressBar) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color(0xFF38BDF8))
                    )
                }
            }
        }
    }
}

@Composable
fun StatCardItem(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    value: String,
    footer: String,
    isDarkMode: Boolean = false,
    onClick: () -> Unit
) {
    val cardBg = if (isDarkMode) Color(0xFF1E293B) else Color.White
    val titleColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)
    val valueColor = if (isDarkMode) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val footerColor = if (isDarkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    Surface(
        modifier = modifier
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = if (isDarkMode) BorderStroke(1.dp, Color(0xFF334155)) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontFamily = CairoFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isDarkMode) iconTint.copy(alpha = 0.2f) else iconBackground,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Text(
                text = value,
                fontFamily = CairoFontFamily,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                color = valueColor
            )

            Text(
                text = footer,
                fontFamily = CairoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                color = footerColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ServiceCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    backgroundColor: Color,
    contentColor: Color,
    hasBorder: Boolean = false,
    isDarkMode: Boolean = false,
    onClick: () -> Unit
) {
    val actualBg = if (isDarkMode) {
        Color(0xFF1E293B)
    } else {
        backgroundColor
    }
    val titleColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)

    Surface(
        modifier = modifier
            .height(78.dp)
            .shadow(elevation = if (hasBorder) 1.dp else 2.dp, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        color = actualBg,
        border = if (isDarkMode) {
            BorderStroke(1.dp, Color(0xFF334155))
        } else if (hasBorder) {
            CardDefaults.outlinedCardBorder().copy(
                brush = Brush.horizontalGradient(listOf(Color(0xFFE2E8F0), Color(0xFFF1F5F9)))
            )
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = if (isDarkMode) 0.22f else 0.15f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = title,
                fontFamily = CairoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun WideServiceCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    backgroundColor: Color,
    contentColor: Color,
    isDarkMode: Boolean = false,
    onClick: () -> Unit
) {
    val actualBg = if (isDarkMode) Color(0xFF1E293B) else backgroundColor
    val titleColor = if (isDarkMode) Color(0xFFF1F5F9) else Color(0xFF1E293B)

    Surface(
        modifier = modifier
            .height(68.dp)
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = actualBg,
        border = if (isDarkMode) BorderStroke(1.dp, Color(0xFF334155)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = if (isDarkMode) 0.22f else 0.15f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = title,
                fontFamily = CairoFontFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        }
    }
}

private fun formatBackupFileSize(bytes: Long): String {
    val b = bytes.toDouble()
    return when {
        b >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / (1024 * 1024 * 1024))
        b >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024 * 1024))
        b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024)
        else -> "$bytes B"
    }
}

/**
 * Dashboard live-metric card: header row (title + current value) over a
 * [LiveLineChart] rendering the rolling history sampled from routerStats.
 */
@Composable
private fun LiveMetricCard(
    title: String,
    currentLabel: String,
    values: List<Int>,
    lineColor: Color,
    fillColor: Color,
    gridColor: Color,
    labelColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = netCraftColors().cardBg,
        border = BorderStroke(1.dp, netCraftColors().cardBorder),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontFamily = CairoFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor,
                )
                Text(
                    text = if (values.isEmpty()) stringResource(R.string.chart_no_data) else currentLabel,
                    fontFamily = CairoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = lineColor,
                )
            }
            if (values.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chart_no_data),
                        fontFamily = CairoFontFamily,
                        fontSize = 12.sp,
                        color = labelColor,
                    )
                }
            } else {
                LiveLineChart(
                    values = values.map { it.toFloat() },
                    lineColor = lineColor,
                    fillColor = fillColor,
                    gridColor = gridColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                )
            }
        }
    }
}
