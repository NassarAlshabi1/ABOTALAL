package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.ActivityLog
import com.example.data.model.RouterLogEntry
import com.example.ui.theme.CairoFontFamily
import com.example.ui.viewmodel.MikroTikViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    viewModel: MikroTikViewModel,
    onBack: () -> Unit = {}
) {
    val logs by viewModel.activityLogs.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Log source: التطبيق (activity log) or الراوتر (/log/print)
    var showRouterLog by remember { mutableStateOf(false) }
    var routerLogEntries by remember { mutableStateOf<List<RouterLogEntry>>(emptyList()) }
    var routerLogLoading by remember { mutableStateOf(false) }
    var routerLogError by remember { mutableStateOf<String?>(null) }

    // Filters: period (all/today/week) + entry type
    var periodFilter by remember { mutableStateOf("كل الفترات") }
    var typeFilter by remember { mutableStateOf("كل الأنواع") }

    val periodOptions = listOf("كل الفترات", "اليوم", "هذا الأسبوع")
    val typeOptions = listOf("كل الأنواع", "تسجيل الدخول", "اليوزر مانجر", "الهوتسبوت", "الحذف والفصل", "الصيانة والنسخ")

    fun loadRouterLog() {
        routerLogLoading = true
        routerLogError = null
        viewModel.fetchRouterLogs(
            onSuccess = { routerLogLoading = false; routerLogEntries = it },
            onError = { routerLogLoading = false; routerLogError = it }
        )
    }

    LaunchedEffect(showRouterLog) {
        if (showRouterLog && routerLogEntries.isEmpty() && !routerLogLoading) {
            loadRouterLog()
        }
    }

    // ---- App activity log filtering (search + period + type) ----
    val filteredLogs = remember(logs, searchQuery, periodFilter, typeFilter) {
        val periodCutoff = when (periodFilter) {
            "اليوم" -> {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            "هذا الأسبوع" -> System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            else -> 0L
        }
        logs.filter { log ->
            val matchesPeriod = log.timestamp >= periodCutoff
            val matchesType = when (typeFilter) {
                "تسجيل الدخول" -> log.action.contains("تسجيل") || log.action.contains("دخول")
                "اليوزر مانجر" -> log.action.contains("يوزر") || log.action.contains("كروت") || log.action.contains("باقة") || log.action.contains("دفعة") || log.action.contains("جلسة")
                "الهوتسبوت" -> log.action.contains("هوتسبوت") || log.action.contains("هوت") || log.action.contains("بروفايل هوتسبوت")
                "الحذف والفصل" -> log.action.contains("حذف") || log.action.contains("قطع") || log.action.contains("فصل")
                "الصيانة والنسخ" -> log.action.contains("صيانة") || log.action.contains("نسخ") || log.action.contains("مزامنة") || log.action.contains("RADIUS") || log.action.contains("قيود")
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                log.action.contains(searchQuery, ignoreCase = true) ||
                log.details.contains(searchQuery, ignoreCase = true)
            matchesPeriod && matchesType && matchesSearch
        }
    }

    // ---- Router log filtering (search only; the router log carries its own time strings) ----
    val filteredRouterLogs = remember(routerLogEntries, searchQuery) {
        routerLogEntries.filter {
            searchQuery.isBlank() ||
                it.message.contains(searchQuery, ignoreCase = true) ||
                it.topics.contains(searchQuery, ignoreCase = true)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            topBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 4.dp
                ) {
                    Box(
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
                            .statusBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White.copy(alpha = 0.15f),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable { onBack() }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "الرجوع للرئيسية",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Column {
                                    Text(
                                        text = if (showRouterLog) "سجل الراوتر (/log)" else "سجلات النظام والأنشطة",
                                        fontFamily = CairoFontFamily,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = if (showRouterLog) "قراءة مباشرة من ذاكرة الراوتر" else "تتبع السجلات وتفاصيل العمليات",
                                        fontFamily = CairoFontFamily,
                                        fontSize = 11.sp,
                                        color = Color(0xFFBAE6FD)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Source toggle: app <-> router
                                IconButton(
                                    onClick = {
                                        showRouterLog = !showRouterLog
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showRouterLog) Icons.Filled.PhoneAndroid else Icons.Filled.Router,
                                        contentDescription = if (showRouterLog) "التبديل إلى سجل التطبيق" else "التبديل إلى سجل الراوتر",
                                        tint = Color(0xFFBAE6FD)
                                    )
                                }
                                if (!showRouterLog && logs.isNotEmpty()) {
                                    IconButton(
                                        onClick = { showClearConfirm = true },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.DeleteSweep,
                                            contentDescription = "مسح السجلات",
                                            tint = Color(0xFFFCA5A5)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (showRouterLog) "ابحث في رسائل سجل الراوتر..." else "ابحث في سجل العمليات والتحكم..."
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                if (showRouterLog) {
                    // Router log: refresh + honest status line
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when {
                                routerLogLoading -> "جاري القراءة من الراوتر..."
                                routerLogError != null -> "خطأ: $routerLogError"
                                else -> "${filteredRouterLogs.size} رسالة من الراوتر"
                            },
                            fontFamily = CairoFontFamily,
                            fontSize = 11.sp,
                            color = if (routerLogError != null) MaterialTheme.colorScheme.error else Color(0xFF64748B)
                        )
                        TextButton(onClick = { loadRouterLog() }, enabled = !routerLogLoading) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تحديث", fontFamily = CairoFontFamily, fontSize = 12.sp)
                        }
                    }
                } else {
                    // App log: period + type filter chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        periodOptions.forEach { option ->
                            FilterChip(
                                selected = periodFilter == option,
                                onClick = { periodFilter = option },
                                label = { Text(option, fontFamily = CairoFontFamily, fontSize = 11.sp) }
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        typeOptions.forEach { option ->
                            FilterChip(
                                selected = typeFilter == option,
                                onClick = { typeFilter = option },
                                label = { Text(option, fontFamily = CairoFontFamily, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (showRouterLog) {
                    when {
                        routerLogLoading && routerLogEntries.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        filteredRouterLogs.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Router,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                                        modifier = Modifier.size(64.dp)
                                    )
                                    Text(
                                        text = routerLogError ?: "لا توجد رسائل في سجل الراوتر تطابق البحث",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                                    .padding(horizontal = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredRouterLogs, key = { it.id }) { entry ->
                                    RouterLogItemCard(entry)
                                }
                                item { Spacer(modifier = Modifier.height(100.dp)) }
                            }
                        }
                    }
                } else {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = "لا توجد عمليات تطابق البحث أو الفلاتر الخاصة بك",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                                .padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredLogs) { log ->
                                LogItemCard(log)
                            }
                            item { Spacer(modifier = Modifier.height(100.dp)) }
                        }
                    }
                }
            }

            // Clear Log confirmation dialog (app log only)
            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = {
                        Text(
                            text = "حذف وتفريغ السجل",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    text = {
                        Text(
                            text = "هل أنت متأكد من مسح جميع سجلات العمليات والتحكم من ذاكرة الهاتف تماماً؟ لا يمكن التراجع عن هذا الإجراء. (سجل الراوتر نفسه لا يتأثر)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.clearLogs()
                                showClearConfirm = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("نعم، امسح السجل", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirm = false }) {
                            Text("تراجع", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun RouterLogItemCard(entry: RouterLogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = when {
                            entry.topics.contains("error", true) || entry.topics.contains("critical", true) -> MaterialTheme.colorScheme.error
                            entry.topics.contains("warning", true) -> Color(0xFFF59E0B)
                            else -> MaterialTheme.colorScheme.secondary
                        },
                        shape = CircleShape
                    )
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.topics.ifBlank { "سجل الراوتر" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = entry.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LogItemCard(log: ActivityLog) {
    val sdf = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }
    val dateTimeStr = remember(log.timestamp) { sdf.format(Date(log.timestamp)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = when (log.action) {
                            "إضافة مستخدم هوتسبوت", "إضافة بروفايل", "يوزر مانجر" -> MaterialTheme.colorScheme.tertiary
                            "حذف مستخدم هوتسبوت", "قطع اتصال مستخدم هوتسبوت", "حذف جماعي", "قطع جلسة" -> MaterialTheme.colorScheme.error
                            "تسجيل الدخول", "تسجيل الخروج" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        },
                        shape = CircleShape
                    )
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = log.action,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = dateTimeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = log.details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (log.routerHost.isNotBlank()) {
                    Text(
                        text = "عنوان الراوتر: ${log.routerHost}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
