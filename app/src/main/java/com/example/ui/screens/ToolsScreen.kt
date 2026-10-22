package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CairoFontFamily
import com.example.ui.viewmodel.MikroTikViewModel

sealed class ToolsActiveView {
    object MainList : ToolsActiveView()
    object PortsBandwidth : ToolsActiveView()
    object TelegramConfig : ToolsActiveView()
    object PppoeManager : ToolsActiveView()
    object CloudManager : ToolsActiveView()
    object AiDoctor : ToolsActiveView()
    data class GenericTool(val key: String, val title: String, val subtitle: String) : ToolsActiveView()
}

data class ToolGridItem(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val iconColor: Color = Color(0xFF0C5A60),
    val badgeCount: Int? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: MikroTikViewModel,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var activeSubView by remember { mutableStateOf<ToolsActiveView>(ToolsActiveView.MainList) }

    var showRebootDialog by remember { mutableStateOf(false) }
    var isOperating by remember { mutableStateOf(false) }

    // Dark-mode aware background for the main tools list
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val toolsScreenBg = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFF8FAFC)

    // Back button handling
    BackHandler(enabled = activeSubView !is ToolsActiveView.MainList) {
        activeSubView = ToolsActiveView.MainList
    }

    when (val view = activeSubView) {
        is ToolsActiveView.PortsBandwidth -> {
            PortsBandwidthScreen(
                viewModel = viewModel,
                onBack = { activeSubView = ToolsActiveView.MainList }
            )
        }

        is ToolsActiveView.TelegramConfig -> {
            TelegramBotConfigScreen(
                viewModel = viewModel,
                onBack = { activeSubView = ToolsActiveView.MainList }
            )
        }

        is ToolsActiveView.PppoeManager -> {
            PppoeManagerScreen(
                viewModel = viewModel,
                onBack = { activeSubView = ToolsActiveView.MainList }
            )
        }

        is ToolsActiveView.CloudManager -> {
            CloudScreen(
                viewModel = viewModel,
                onBack = { activeSubView = ToolsActiveView.MainList }
            )
        }

        is ToolsActiveView.AiDoctor -> {
            AiDoctorScreen(viewModel = viewModel, onBack = { activeSubView = ToolsActiveView.MainList })
        }

        is ToolsActiveView.GenericTool -> {
            SystemToolDetailScreen(
                viewModel = viewModel,
                toolKey = view.key,
                toolTitle = view.title,
                toolSubtitle = view.subtitle,
                onBack = { activeSubView = ToolsActiveView.MainList }
            )
        }

        is ToolsActiveView.MainList -> {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Scaffold(
                    topBar = {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shadowElevation = 6.dp
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF034E54),
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
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
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
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .clickable { onBack() }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = "رجوع",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }

                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "مركز الأدوات والخدمات",
                                                    fontFamily = CairoFontFamily,
                                                    fontSize = 19.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFFF59E0B)
                                                ) {
                                                    Text(
                                                        text = "PRO",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "مجموعة أدوات تحكم وتشخيص شبكات المايكروتك",
                                                fontFamily = CairoFontFamily,
                                                fontSize = 11.sp,
                                                color = Color(0xFFBAE6FD)
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            viewModel.refreshAllData()
                                            Toast.makeText(context, "تم تحديث البيانات", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .background(Color.White.copy(alpha = 0.18f), CircleShape)
                                            .size(38.dp)
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = Color.White)
                                    }
                                }
                            }
                        }
                    }
                ) { padding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .background(toolsScreenBg)
                            .padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item { Spacer(modifier = Modifier.height(6.dp)) }

                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C5A60)),
                                elevation = CardDefaults.cardElevation(4.dp),
                                modifier = Modifier.fillMaxWidth().clickable { activeSubView = ToolsActiveView.AiDoctor }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(
                                        Brush.horizontalGradient(listOf(Color(0xFF063B40), Color(0xFF0C5A60), Color(0xFF0284C7)))
                                    ).padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Filled.HealthAndSafety, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("AI Doctor", fontFamily = CairoFontFamily, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("فحص قراءة فقط وتشخيص توافق RouterOS v6/v7", fontFamily = CairoFontFamily, fontSize = 11.sp, color = Color(0xFFBAE6FD))
                                    }
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "فتح", tint = Color.White)
                                }
                            }
                        }

                        // 5. Ports & Internet Bandwidth (Large Banner Card)
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(3.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { activeSubView = ToolsActiveView.PortsBandwidth }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color(0xFF0C5A60),
                                                    Color(0xFF0284C7)
                                                )
                                            )
                                        )
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White.copy(alpha = 0.22f),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Filled.SettingsEthernet,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }

                                        Column {
                                            Text(
                                                text = "عرض المنافذ واستهلاك الإنترنت",
                                                fontFamily = CairoFontFamily,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = "مراقبة حركة المرور، حجم البيانات، والتحكم الفوري بالواجهات",
                                                fontFamily = CairoFontFamily,
                                                fontSize = 11.sp,
                                                color = Color(0xFFE0F2FE)
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = { activeSubView = ToolsActiveView.PortsBandwidth },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "اضغط للعرض",
                                            fontFamily = CairoFontFamily,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF0C5A60)
                                        )
                                    }
                                }
                            }
                        }

                        // 6. Telegram Bot Banner Card (Complete)
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(3.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(0xFF0088CC).copy(alpha = 0.15f),
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Filled.Send,
                                                        contentDescription = null,
                                                        tint = Color(0xFF0088CC),
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }

                                            Column {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = "ربط وإشعارات التيليجرام",
                                                        fontFamily = CairoFontFamily,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp,
                                                        color = Color(0xFF0088CC)
                                                    )
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = Color(0xFFE0F2FE)
                                                    ) {
                                                        Text(
                                                            text = "VIP Bot",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF0088CC),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = "استقبل تنبيهات المبيعات وسيرفر المايكروتك مباشرة على هاتفك",
                                                    fontFamily = CairoFontFamily,
                                                    fontSize = 11.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { activeSubView = ToolsActiveView.TelegramConfig },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC))
                                        ) {
                                            Icon(Icons.Filled.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("ضبط البوت", fontFamily = CairoFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = { activeSubView = ToolsActiveView.TelegramConfig },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("تخصيص المميزات", fontFamily = CairoFontFamily, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        /* ========================================================
                           SECTION 2: إدارة النظام (SYSTEM) [4] (SCREENSHOT 2)
                           ======================================================== */
                        item {
                            SectionHeader(title = "إدارة النظام (System)", count = 4)
                        }

                        item {
                            ToolsGrid(
                                items = listOf(
                                    ToolGridItem("logs", "سجلات النظام (Log)", Icons.Filled.ListAlt, Color(0xFF0284C7)),
                                    ToolGridItem("users", "المستخدمين والصلاحيات", Icons.Filled.AdminPanelSettings, Color(0xFF10B981)),
                                    ToolGridItem("reboot", "إعادة تشغيل (Reboot)", Icons.Filled.RestartAlt, Color(0xFFEF4444)),
                                    ToolGridItem("backup", "نسخ احتياطي (Backup)", Icons.Filled.CloudUpload, Color(0xFF8B5CF6))
                                ),
                                onItemClick = { item ->
                                    if (item.id == "reboot") {
                                        showRebootDialog = true
                                    } else {
                                        activeSubView = ToolsActiveView.GenericTool(item.id, item.title, "نظام راوتر المايكروتك")
                                    }
                                }
                            )
                        }

                        /* ========================================================
                           SECTION 3: إعدادات الشبكة (IP) [9] (SCREENSHOT 2)
                           ======================================================== */
                        item {
                            SectionHeader(title = "إعدادات الشبكة (IP)", count = 9)
                        }

                        item {
                            ToolsGrid(
                                items = listOf(
                                    ToolGridItem("dhcp_server", "خادم DHCP", Icons.Filled.Router, Color(0xFF0C5A60)),
                                    ToolGridItem("ip_addresses", "IP Addresses", Icons.Filled.PinDrop, Color(0xFF2563EB)),
                                    ToolGridItem("dhcp_leases", "DHCP (Leases)", Icons.Filled.Devices, Color(0xFF10B981)),
                                    ToolGridItem("dhcp_relay", "المزود للخدمه DHCP", Icons.Filled.AltRoute, Color(0xFF6366F1)),
                                    ToolGridItem("nat", "NAT", Icons.Filled.SwapHoriz, Color(0xFFF59E0B)),
                                    ToolGridItem("dns", "خادم DNS", Icons.Filled.Language, Color(0xFF06B6D4)),
                                    ToolGridItem("address_lists", "مجموعات العناوين", Icons.Filled.Ballot, Color(0xFFEC4899)),
                                    ToolGridItem("firewall_filter", "جدار الحماية", Icons.Filled.Security, Color(0xFFDC2626)),
                                    ToolGridItem("arp", "ARP", Icons.Filled.FormatListNumbered, Color(0xFF64748B))
                                ),
                                onItemClick = { item ->
                                    activeSubView = ToolsActiveView.GenericTool(item.id, item.title, "إعدادات الشبكة وبروتوكول IP")
                                }
                            )
                        }

                        /* ========================================================
                           SECTION 4: جدار الحماية (FIREWALL) [6] (SCREENSHOT 3)
                           ======================================================== */
                        item {
                            SectionHeader(title = "جدار الحماية (Firewall)", count = 6)
                        }

                        item {
                            ToolsGrid(
                                items = listOf(
                                    ToolGridItem("nat", "قواعد NAT", Icons.Filled.Rule, Color(0xFFD97706)),
                                    ToolGridItem("firewall_filter", "قواعد التصفية", Icons.Filled.FilterList, Color(0xFFDC2626)),
                                    ToolGridItem("blocked_sites", "المواقع المحجوبة", Icons.Filled.Block, Color(0xFFB91C1C)),
                                    ToolGridItem("mangle", "قواعد Mangle", Icons.Filled.Tune, Color(0xFF7C3AED)),
                                    ToolGridItem("connections", "الاتصالات النشطة", Icons.Filled.Sensors, Color(0xFF059669)),
                                    ToolGridItem("layer7", "بروتوكولات Layer7", Icons.Filled.Layers, Color(0xFF0284C7))
                                ),
                                onItemClick = { item ->
                                    activeSubView = ToolsActiveView.GenericTool(item.id, item.title, "جدار الحماية وقواعد الفلترة")
                                }
                            )
                        }

                        /* ========================================================
                           SECTION 5: قوائم الحظر والعبور [2] (SCREENSHOT 3)
                           ======================================================== */
                        item {
                            SectionHeader(title = "قوائم الحظر والعبور", count = 2)
                        }

                        item {
                            ToolsGrid(
                                items = listOf(
                                    ToolGridItem("address_lists", "القائمة البيضاء", Icons.Filled.CheckCircle, Color(0xFF16A34A)),
                                    ToolGridItem("address_lists", "القائمة السوداء", Icons.Filled.Cancel, Color(0xFFDC2626))
                                ),
                                onItemClick = { item ->
                                    activeSubView = ToolsActiveView.GenericTool(item.id, item.title, "قوائم العناوين والحظر")
                                }
                            )
                        }

                        /* ========================================================
                           SECTION 6: إدارة النطاق الترددي (QUEUE) [1] (SCREENSHOT 3)
                           ======================================================== */
                        item {
                            SectionHeader(title = "إدارة النطاق الترددي (Queue)", count = 1)
                        }

                        item {
                            ToolsGrid(
                                items = listOf(
                                    ToolGridItem("queues", "أسرع في استهلاك البيانات", Icons.Filled.Speed, Color(0xFFEA580C))
                                ),
                                onItemClick = { item ->
                                    activeSubView = ToolsActiveView.GenericTool(item.id, item.title, "تحديد السرعات والتحكم في الباندويث")
                                }
                            )
                        }

                        /* ========================================================
                           SECTION 7: أدوات التشخيص (TOOLS) [10] (SCREENSHOT 4)
                           ======================================================== */
                        item {
                            SectionHeader(title = "أدوات التشخيص (Tools)", count = 10)
                        }

                        item {
                            ToolsGrid(
                                items = listOf(
                                    ToolGridItem("bandwidth_test", "اختبار النطاق", Icons.Filled.NetworkCheck, Color(0xFF2563EB)),
                                    ToolGridItem("ping", "Ping (فحص الاتصال)", Icons.Filled.Radar, Color(0xFF0C5A60)),
                                    ToolGridItem("sniffer", "تنقيط الحزم", Icons.Filled.Troubleshoot, Color(0xFF7C3AED)),
                                    ToolGridItem("torch", "Torch (مراقبة الحزم)", Icons.Filled.FlashlightOn, Color(0xFFF59E0B)),
                                    ToolGridItem("netwatch", "مراقبة الشبكة", Icons.Filled.Visibility, Color(0xFF059669)),
                                    ToolGridItem("graphing", "الرسوم البيانية", Icons.Filled.BarChart, Color(0xFF0284C7)),
                                    ToolGridItem("email", "بريد إلكتروني", Icons.Filled.Email, Color(0xFFDC2626)),
                                    ToolGridItem("neighbors", "أجهزة البث (Neighbor)", Icons.Filled.Hub, Color(0xFF4F46E5)),
                                    ToolGridItem("sms", "SMS (الرسائل)", Icons.Filled.Sms, Color(0xFFD97706)),
                                    ToolGridItem("fetch", "Fetch (تنزيل ملف)", Icons.Filled.CloudDownload, Color(0xFF0D9488))
                                ),
                                onItemClick = { item ->
                                    activeSubView = ToolsActiveView.GenericTool(item.id, item.title, "أدوات فحص وتشخيص النظام")
                                }
                            )
                        }

                        /* ========================================================
                           SECTION 8: مراقبة الأداء [6] (SCREENSHOT 5)
                           ======================================================== */
                        item {
                            SectionHeader(title = "مراقبة الأداء", count = 6)
                        }

                        item {
                            ToolsGrid(
                                items = listOf(
                                    ToolGridItem("cloud", "سحابة مايكروتيك", Icons.Filled.Cloud, Color(0xFF0C5A60)),
                                    ToolGridItem("resources", "ذاكرة / CPU", Icons.Filled.Memory, Color(0xFF0C5A60)),
                                    ToolGridItem("graphing_interface", "مراقبة المرور", Icons.Filled.Insights, Color(0xFF0284C7)),
                                    ToolGridItem("routerboard", "RouterBOARD", Icons.Filled.DeveloperBoard, Color(0xFF7C3AED)),
                                    ToolGridItem("ppp_active", "اتصالات PPP نشطة", Icons.Filled.OnlinePrediction, Color(0xFF10B981)),
                                    ToolGridItem("health", "صحة الجهاز (Health)", Icons.Filled.Favorite, Color(0xFFEF4444))
                                ),
                                onItemClick = { item ->
                                    if (item.id == "cloud") {
                                        activeSubView = ToolsActiveView.CloudManager
                                    } else {
                                        activeSubView = ToolsActiveView.GenericTool(item.id, item.title, "مؤشرات الأداء والمعالجة")
                                    }
                                }
                            )
                        }

                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    // Dialog: Router Reboot Confirmation
    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFEF4444))
                    Text("تأكيد إعادة تشغيل الراوتر", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "تحذير: سيتم إرسال أمر (/system reboot) للراوتر وسينقطع اتصال كافة المستخدمين والشبكة لدقيقتين حتى يكتمل الإقلاع.",
                    fontFamily = CairoFontFamily,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.rebootSystem(
                            onSuccess = { msg ->
                                showRebootDialog = false
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            },
                            onError = { err ->
                                Toast.makeText(context, "فشل: $err", Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("نعم، أعد التشغيل الآن", fontFamily = CairoFontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text("إلغاء", fontFamily = CairoFontFamily)
                }
            }
        )
    }
}

@Composable
private fun HeroServiceCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    actionButtonText: String,
    headerBgColor: Color,
    accentColor: Color,
    icon: ImageVector,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.5.dp),
        modifier = modifier
    ) {
        Column {
            // Header band
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBgColor)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Text(
                            text = title,
                            fontFamily = CairoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }

                    if (badgeText != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Body
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = subtitle,
                    fontFamily = CairoFontFamily,
                    fontSize = 10.sp,
                    color = Color.Gray,
                    minLines = 2,
                    maxLines = 2,
                    lineHeight = 13.sp
                )

                Spacer(modifier = Modifier.height(7.dp))

                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    contentPadding = PaddingValues(vertical = 3.dp)
                ) {
                    Text(
                        text = actionButtonText,
                        fontFamily = CairoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontFamily = CairoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color(0xFF0F172A)
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFFE2E8F0)
        ) {
            Text(
                text = "[$count]",
                fontFamily = CairoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color(0xFF475569),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ToolsGrid(
    items: List<ToolGridItem>,
    onItemClick: (ToolGridItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Chunk into pairs for 2-column layout in LazyColumn
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(1.5.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onItemClick(item) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = item.iconColor.copy(alpha = 0.12f),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = item.iconColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Text(
                                text = item.title,
                                fontFamily = CairoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFF1E293B),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                // If odd number in row, add empty space
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
