package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MikroTikCloudBackup
import com.example.ui.theme.CairoFontFamily
import com.example.ui.viewmodel.MikroTikViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(
    viewModel: MikroTikViewModel,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val cloudInfo by viewModel.cloudInfo.collectAsStateWithLifecycle()
    val cloudBackups by viewModel.cloudBackups.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

    var isRefreshing by remember { mutableStateOf(false) }
    var isUpdatingDdns by remember { mutableStateOf(false) }
    var showUploadBackupDialog by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) }
    var isPinging by remember { mutableStateOf(false) }
    var backupToDelete by remember { mutableStateOf<MikroTikCloudBackup?>(null) }

    LaunchedEffect(Unit) {
        viewModel.fetchCloudData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "إدارة كلاود مايكروتيك (Cloud DDNS)",
                            fontFamily = CairoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isRefreshing = true
                            viewModel.fetchCloudData(
                                onSuccess = {
                                    isRefreshing = false
                                    Toast.makeText(context, "تم تحديث بيانات الكلاود", Toast.LENGTH_SHORT).show()
                                },
                                onError = {
                                    isRefreshing = false
                                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "تحديث",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0C5A60)
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(6.dp))

                // Hero Banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF063B40), Color(0xFF0C5A60), Color(0xFF0284C7))
                                )
                            )
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (cloudInfo.ddnsEnabled) Color(0xFF22C55E) else Color(0xFFEF4444))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (cloudInfo.ddnsEnabled) "الكلاود نشط ومفعل" else "الكلاود معطل",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = CairoFontFamily
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = cloudInfo.status,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "اسم الدومين السحابي للراوتر (Cloud DNS):",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontFamily = CairoFontFamily
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = cloudInfo.dnsName.ifEmpty { "غير متوفر بعد..." },
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val clip = ClipData.newPlainText("MikroTik Cloud DNS", cloudInfo.dnsName)
                                                clipboard.setPrimaryClip(clip)
                                                Toast.makeText(context, "تم نسخ رابط الكلاود ✅", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ContentCopy,
                                                contentDescription = "نسخ",
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, "رابط الراوتر السحابي: ${cloudInfo.dnsName}")
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "مشاركة رابط الكلاود"))
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Share,
                                                contentDescription = "مشاركة",
                                                tint = Color.White,
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

            // Public IP & Status Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "الآي بي العام (Public IP)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = CairoFontFamily
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = cloudInfo.publicAddress.ifEmpty { "---" },
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0C5A60)
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "مزامنة وقت الراوتر",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = CairoFontFamily
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (cloudInfo.updateTime) "مفعلة تلقائياً ✅" else "معطلة ❌",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (cloudInfo.updateTime) Color(0xFF16A34A) else Color(0xFFDC2626),
                                fontFamily = CairoFontFamily
                            )
                        }
                    }
                }
            }

            // Action: Force Update DDNS Button
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "العمليات السريعة للكلاود",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = CairoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isUpdatingDdns = true
                                    viewModel.forceUpdateCloud(
                                        onSuccess = {
                                            isUpdatingDdns = false
                                            Toast.makeText(context, "تم إرسال أمر التحديث الفوري للكلاود بنجاح ✅", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = {
                                            isUpdatingDdns = false
                                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60)),
                                enabled = !isUpdatingDdns
                            ) {
                                if (isUpdatingDdns) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تحديث فوري DDNS", fontFamily = CairoFontFamily, fontSize = 12.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    if (cloudInfo.dnsName.isNotBlank()) {
                                        val url = "http://${cloudInfo.dnsName}"
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(browserIntent)
                                    } else {
                                        Toast.makeText(context, "اسم الدومين غير متوفر", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("فتح WebFig", fontFamily = CairoFontFamily, fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Toggles
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "تفعيل خدمة الكلاود (DDNS Enabled)",
                                fontSize = 13.sp,
                                fontFamily = CairoFontFamily
                            )
                            Switch(
                                checked = cloudInfo.ddnsEnabled,
                                onCheckedChange = { checked ->
                                    viewModel.setCloudDdns(
                                        checked,
                                        onSuccess = {
                                            Toast.makeText(context, if (checked) "تم تفعيل الكلاود" else "تم تعطيل الكلاود", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "مزامنة وقت الساعة تلقائياً عبر الكلاود",
                                fontSize = 13.sp,
                                fontFamily = CairoFontFamily
                            )
                            Switch(
                                checked = cloudInfo.updateTime,
                                onCheckedChange = { checked ->
                                    viewModel.setCloudUpdateTime(
                                        checked,
                                        onSuccess = { Toast.makeText(context, "تم حفظ ضبط الوقت", Toast.LENGTH_SHORT).show() },
                                        onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Cloud Backups Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "النسخ الاحتياطية السحابية (Cloud Backup)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = CairoFontFamily,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            IconButton(onClick = { showUploadBackupDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.CloudUpload,
                                    contentDescription = "رفع نسخة سحابية",
                                    tint = Color(0xFF0C5A60)
                                )
                            }
                        }

                        Text(
                            text = "حفظ واستعادة إعدادات الراوتر المشفرة مباشرة على سيرفرات مايكروتيك السحابية",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = CairoFontFamily
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (cloudBackups.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = "لا توجد نسخ احتياطية على الكلاود حالياً، اضغط على زر الرفع السحابي لحفظ نسخة الآن.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = CairoFontFamily,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                cloudBackups.forEach { backup ->
                                    CloudBackupItem(
                                        backup = backup,
                                        onDelete = { backupToDelete = backup }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Cloud Ping & Diagnostics
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "فحص استجابة دومين الكلاود (Ping & Diagnostics)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = CairoFontFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val target = cloudInfo.dnsName.ifEmpty { cloudInfo.publicAddress }
                                    if (target.isNotBlank()) {
                                        isPinging = true
                                        viewModel.executePing(target, 4) { result ->
                                            isPinging = false
                                            pingResult = result
                                        }
                                    } else {
                                        Toast.makeText(context, "لا يوجد عنوان لاختباره", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                enabled = !isPinging
                            ) {
                                if (isPinging) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("فحص اتصال الكلاود", fontFamily = CairoFontFamily, fontSize = 12.sp)
                                }
                            }
                        }

                        if (pingResult != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.85f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = pingResult ?: "",
                                    color = Color(0xFF4ADE80),
                                    fontSize = 12.sp,
                                    fontFamily = CairoFontFamily,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Dialog: Upload Cloud Backup
    if (showUploadBackupDialog) {
        var backupName by remember { mutableStateOf("backup-${System.currentTimeMillis() / 1000}") }
        var backupPassword by remember { mutableStateOf("") }
        var isUploading by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showUploadBackupDialog = false },
            title = {
                Text(
                    text = "رفع نسخة احتياطية إلى الكلاود",
                    fontFamily = CairoFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "سيتم رفع ملف النسخة مباشرة إلى حساب الراوتر في سحابة مايكروتيك:",
                        fontFamily = CairoFontFamily,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = backupName,
                        onValueChange = { backupName = it },
                        label = { Text("اسم النسخة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )

                    OutlinedTextField(
                        value = backupPassword,
                        onValueChange = { backupPassword = it },
                        label = { Text("كلمة مرور التشفير (اختياري)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isUploading = true
                        viewModel.uploadCloudBackup(
                            backupName,
                            backupPassword,
                            onSuccess = {
                                isUploading = false
                                showUploadBackupDialog = false
                                Toast.makeText(context, "تم رفع النسخة الاحتياطية بنجاح ✅", Toast.LENGTH_SHORT).show()
                            },
                            onError = {
                                isUploading = false
                                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60)),
                    enabled = !isUploading
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text("رفع النسخة", fontFamily = CairoFontFamily)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUploadBackupDialog = false }) {
                    Text("إلغاء", fontFamily = CairoFontFamily)
                }
            }
        )
    }

    backupToDelete?.let { backup ->
        AlertDialog(
            onDismissRequest = { backupToDelete = null },
            title = { Text("حذف النسخة السحابية", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "هل تريد حذف النسخة \"${backup.name}\" من سيرفر مايكروتيك السحابي؟ لا يمكن التراجع.",
                    fontFamily = CairoFontFamily
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        backupToDelete = null
                        viewModel.removeCloudBackup(
                            backup.id,
                            onSuccess = { Toast.makeText(context, "تم حذف النسخة السحابية", Toast.LENGTH_SHORT).show() },
                            onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("نعم، احذف", fontFamily = CairoFontFamily) }
            },
            dismissButton = {
                TextButton(onClick = { backupToDelete = null }) { Text("إلغاء", fontFamily = CairoFontFamily) }
            }
        )
    }
}

@Composable
fun CloudBackupItem(
    backup: MikroTikCloudBackup,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = backup.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "الحجم: ${backup.size} | الوقت: ${backup.time}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = CairoFontFamily
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "حذف",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
