package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.example.ui.components.RefreshableListContainer
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

    // Tab: local activity log vs the router's own log buffer (/log/print)
    var selectedTab by remember { mutableStateOf(0) }
    var routerLogs by remember { mutableStateOf<List<RouterLogEntry>?>(null) }
    var routerLogsError by remember { mutableStateOf<String?>(null) }
    var routerLogsLoading by remember { mutableStateOf(false) }
    var routerTopicFilter by remember { mutableStateOf("الكل") }
    val routerTopics = remember(routerLogs) {
        (routerLogs ?: emptyList()).map { it.topics }.filter { it.isNotBlank() }.distinct().take(12)
    }
    val isConnected by viewModel.isConnected.collectAsState()

    // Pull-to-refresh state: refreshes whatever tab is active.
    var isPullRefreshing by remember { mutableStateOf(false) }
    fun pullRefresh() {
        if (isPullRefreshing) return
        if (selectedTab == 1 && isConnected) {
            isPullRefreshing = true
            viewModel.fetchRouterLogs(
                onSuccess = { routerLogs = it; isPullRefreshing = false },
                onError = { routerLogsError = it; routerLogs = null; isPullRefreshing = false }
            )
        } else {
            isPullRefreshing = true
            viewModel.refreshAllData { isPullRefreshing = false }
        }
    }

    fun loadRouterLogs() {
        routerLogsLoading = true
        routerLogsError = null
        viewModel.fetchRouterLogs(
            onSuccess = { routerLogs = it; routerLogsLoading = false },
            onError = { routerLogsError = it; routerLogsLoading = false; routerLogs = null }
        )
    }

    LaunchedEffect(selectedTab, isConnected) {
        if (selectedTab == 1 && routerLogs == null && isConnected && !routerLogsLoading) {
            loadRouterLogs()
        }
    }

    val filteredLogs = remember(logs, searchQuery) {
        logs.filter {
            it.action.contains(searchQuery, ignoreCase = true) ||
                    it.details.contains(searchQuery, ignoreCase = true)
        }
    }
    val filteredRouterLogs = remember(routerLogs, routerTopicFilter, searchQuery) {
        (routerLogs ?: emptyList()).filter { entry ->
            (routerTopicFilter == "الكل" || entry.topics == routerTopicFilter) &&
                (searchQuery.isBlank() || entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.topics.contains(searchQuery, ignoreCase = true))
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
                                        text = "سجلات النظام والأنشطة",
                                        fontFamily = CairoFontFamily,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "تتبع السجلات وتفاصيل العمليات",
                                        fontFamily = CairoFontFamily,
                                        fontSize = 11.sp,
                                        color = Color(0xFFBAE6FD)
                                    )
                                }
                            }

                            if (logs.isNotEmpty()) {
                                TooltipBox(
                                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                    tooltip = {
                                        PlainTooltip {
                                            Text("مسح كل السجلات المحلية", fontFamily = CairoFontFamily, fontSize = 12.sp)
                                        }
                                    },
                                    state = rememberTooltipState()
                                ) {
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
            RefreshableListContainer(
                isRefreshing = isPullRefreshing,
                onRefresh = { pullRefresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Spacer(modifier = Modifier.height(10.dp))

                // Tab selector: local activity log | router log buffer (M3 segmented buttons)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                ) {
                    listOf("سجل العمليات", "سجلات الراوتر").forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            shape = SegmentedButtonDefaults.itemShape(idx, 2),
                            label = { Text(label, fontFamily = CairoFontFamily, fontSize = 12.sp) }
                        )
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (selectedTab == 0) "ابحث في سجل العمليات والتحكم..."
                            else "ابحث في سجلات الراوتر..."
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                if (selectedTab == 1) {
                    // Router log buffer section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (routerLogs != null) "${filteredRouterLogs.size} سجل من الراوتر" else "سجلات الراوتر (/log)",
                            fontFamily = CairoFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        TextButton(
                            onClick = { loadRouterLogs() },
                            enabled = isConnected && !routerLogsLoading
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (routerLogsLoading) Color.Gray else Color(0xFF0C5A60)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (routerLogsLoading) "جاري التحميل..." else "تحديث",
                                fontFamily = CairoFontFamily,
                                fontSize = 12.sp,
                                color = if (routerLogsLoading) Color.Gray else Color(0xFF0C5A60)
                            )
                        }
                    }

                    // Topic filter chips (error / system / info / ... as reported by the router)
                    if (routerTopics.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 4.dp)
                        ) {
                            items(listOf("الكل") + routerTopics) { topic ->
                                FilterChip(
                                    selected = routerTopicFilter == topic,
                                    onClick = { routerTopicFilter = topic },
                                    label = { Text(topic, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    when {
                        routerLogsError != null -> {
                            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.CloudOff,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f),
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Text(
                                        routerLogsError ?: "",
                                        fontFamily = CairoFontFamily,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        routerLogs == null && routerLogsLoading -> {
                            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        filteredRouterLogs.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                                Text(
                                    "لا توجد سجلات من الراوتر تطابق البحث",
                                    fontFamily = CairoFontFamily,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center
                                )
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
                                items(filteredRouterLogs) { entry ->
                                    RouterLogItemCard(entry)
                                }
                                item { Spacer(modifier = Modifier.height(100.dp)) }
                            }
                        }
                    }
                } else if (filteredLogs.isEmpty()) {
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
                                text = "لا توجد عمليات تطابق البحث الخاص بك",
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

            // Clear Log confirmation dialog
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
                            text = "هل أنت متأكد من مسح جميع سجلات العمليات والتحكم من ذاكرة الهاتف تماماً؟ لا يمكن التراجع عن هذا الإجراء.",
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
            } // RefreshableListContainer
        }
    }
}

/** One row of the router's own log buffer — time, topics and the raw message. */
@Composable
fun RouterLogItemCard(entry: RouterLogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = when {
                            entry.topics.contains("error", ignoreCase = true) -> MaterialTheme.colorScheme.error
                            entry.topics.contains("warning", ignoreCase = true) -> Color(0xFFF59E0B)
                            entry.topics.contains("critical", ignoreCase = true) -> Color(0xFFDC2626)
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
                        text = entry.topics.ifBlank { "log" },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
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
                            "حذف مستخدم هوتسبوت", "قطع اتصال مستخدم هوتسبوت" -> MaterialTheme.colorScheme.error
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
