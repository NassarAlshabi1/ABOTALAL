package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CairoFontFamily
import com.example.ui.viewmodel.MikroTikViewModel

private enum class WizardStepStatus { PENDING, RUNNING, DONE, FAILED }

private data class WizardStepUi(
    val title: String,
    val description: String,
    val color: Color
)

// The 9 steps mirror the reference "MkCards" flow exactly (same order, same
// titles/descriptions). See repository functions for what each one actually
// does on the router vs. locally — summarized in the code comments there.
private val WIZARD_STEPS = listOf(
    WizardStepUi(
        "ترحيل محلي للحسابات المالية",
        "يرحّل الكروت المنتهية وجلساتها المحفوظة محلياً إلى الحسابات المالية دون الاتصال بالراوتر، وإعادة التنفيذ تحدّث السجلات نفسها بلا تكرار.",
        Color(0xFFB39DDB)
    ),
    WizardStepUi(
        "تعطيل الهوتسبوت",
        "يعطّل الهوتسبوت مؤقتاً قبل حذف الكروت المنتهية.",
        Color(0xFF80DEEA)
    ),
    WizardStepUi(
        "أخذ نسخة احتياطية",
        "يتم حفظ نسخة احتياطية من قاعدة User Manager قبل حذف الكروت المنتهية.",
        Color(0xFF80CBC4)
    ),
    WizardStepUi(
        "حذف الكروت المنتهية",
        "سيتم حذف الكروت المنتهية.",
        Color(0xFFEF9A9A)
    ),
    WizardStepUi(
        "تنظيف سجلات User Manager",
        "يُغلق ويحذف جميع جلسات User Manager ويحذف سجل الاستخدام على الراوتر (close-session / session remove / log remove).",
        Color(0xFF80CBAE)
    ),
    WizardStepUi(
        "إعادة بناء قاعدة User Manager",
        "ينفّذ أمر إعادة بناء قاعدة البيانات على الراوتر (database/rebuild) ثم يحدّث النسخة المحلية في التطبيق.",
        Color(0xFF90CAF9)
    ),
    WizardStepUi(
        "إعادة بناء سجلات User Manager",
        "ينفّذ أمر إعادة بناء سجل الاستخدام على الراوتر (database/rebuild-log) ثم يحدّث مخزن الجلسات المحلي.",
        Color(0xFFA5D6A7)
    ),
    WizardStepUi(
        "تفعيل الهوتسبوت",
        "يعيد تفعيل الهوتسبوت بعد الحذف وتنظيف السجلات.",
        Color(0xFF90CAF9)
    ),
    WizardStepUi(
        "إعادة تشغيل الراوتر",
        "يتحقق من حالة الهوتسبوت أولاً، ثم يرسل أمر إعادة تشغيل الراوتر النهائي.",
        Color(0xFFEF9A9A)
    )
)

/**
 * Full "صيانة الكروت المنتهية" maintenance wizard — 9 manually-triggered steps
 * executed strictly in order, mirroring the reference MkCards app's flow.
 * Each step is independent: the admin presses "تنفيذ الخطوة" on one at a time,
 * all other buttons are disabled while a step is running, and each step's real
 * backend action is documented on its repository function.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagerMaintenanceWizardScreen(
    viewModel: MikroTikViewModel,
    onBack: () -> Unit = {}
) {
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val userManagerUsers by viewModel.userManagerUsers.collectAsState()
    val expiredCount = remember(userManagerUsers) {
        userManagerUsers.count {
            !it.active || (it.profileExpired && it.uptimeUsed.isNotBlank() && it.uptimeUsed != "0s")
        }
    }

    val screenBg = if (isDarkMode) Color(0xFF0F172A) else Color(0xFF0B1220)
    val cardBg = if (isDarkMode) Color(0xFF1E293B) else Color(0xFF162032)

    val statuses = remember { mutableStateListOf(*Array(9) { WizardStepStatus.PENDING }) }
    val messages = remember { mutableStateListOf(*Array<String?>(9) { null }) }
    var runningIndex by remember { mutableStateOf(-1) }
    var disabledHotspotIds by remember { mutableStateOf<List<String>>(emptyList()) }

    fun setStatus(index: Int, status: WizardStepStatus, message: String? = null) {
        statuses[index] = status
        messages[index] = message
    }

    fun run(index: Int, action: (onDone: (Boolean, String?) -> Unit) -> Unit) {
        if (runningIndex != -1) return
        runningIndex = index
        setStatus(index, WizardStepStatus.RUNNING)
        action { success, message ->
            runningIndex = -1
            setStatus(index, if (success) WizardStepStatus.DONE else WizardStepStatus.FAILED, message)
        }
    }

    fun executeStep(index: Int) {
        when (index) {
            0 -> run(index) { done ->
                viewModel.archiveExpiredCardsLocally(
                    onSuccess = { n -> done(true, "تم ترحيل $n كرت") },
                    onError = { err -> done(false, err) }
                )
            }
            1 -> run(index) { done ->
                viewModel.disableHotspotForMaintenance(
                    onSuccess = { ids -> disabledHotspotIds = ids; done(true, "تم تعطيل ${ids.size} سيرفر") },
                    onError = { err -> done(false, err) }
                )
            }
            2 -> run(index) { done ->
                viewModel.backupUserManagerDatabase(
                    onSuccess = { name -> done(true, "تم حفظ: $name") },
                    onError = { err -> done(false, err) }
                )
            }
            3 -> run(index) { done ->
                viewModel.cleanExpiredUserManagerUsers(
                    onSuccess = { n -> done(true, "تم حذف $n كرت") },
                    onError = { err -> done(false, err) }
                )
            }
            4 -> run(index) { done ->
                viewModel.cleanStaleUserManagerSessions(
                    onSuccess = { n -> done(true, "تم تنظيف $n سجل يتيم") },
                    onError = { err -> done(false, err) }
                )
            }
            5 -> run(index) { done ->
                viewModel.rebuildUserManagerLocalDatabase(
                    onSuccess = { done(true, "تم تحديث القاعدة المحلية") },
                    onError = { err -> done(false, err) }
                )
            }
            6 -> run(index) { done ->
                viewModel.rebuildUserManagerLocalRecords(
                    onSuccess = { n -> done(true, "تم تحديث $n جلسة") },
                    onError = { err -> done(false, err) }
                )
            }
            7 -> run(index) { done ->
                viewModel.restoreHotspotAfterMaintenance(
                    disabledHotspotIds,
                    onSuccess = { done(true, "تم تفعيل ${disabledHotspotIds.size} سيرفر") },
                    onError = { err -> done(false, err) }
                )
            }
            8 -> run(index) { done ->
                viewModel.verifyHotspotAndRebootRouter(
                    onSuccess = { msg -> done(true, msg) },
                    onError = { err -> done(false, err) }
                )
            }
        }
    }

    Scaffold(
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = { Text("صيانة الكروت المنتهية", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = screenBg,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(screenBg)
                .padding(padding)
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0C5A60), modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Build, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("حذف الكروت المنتهية", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "نفّذ كل خطوة يدوياً بالترتيب. أثناء تنفيذ أي خطوة سيتم تعطيل بقية الأزرار.",
                            fontFamily = CairoFontFamily, fontSize = 12.sp, color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF3A2E12), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "يُنصح بعدم حذف الكروت المنتهية في أوقات الذروة، لأن الهوتسبوت قد يتوقف مؤقتاً وقد يتم إعادة تشغيل الراوتر.",
                                    fontFamily = CairoFontFamily, fontSize = 11.sp, color = Color(0xFFFBBF24)
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(WIZARD_STEPS) { index, step ->
                val status = statuses[index]
                val message = messages[index]
                val description = if (index == 3) "سيتم حذف الكروت المنتهية وعددها $expiredCount." else step.description

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Surface(shape = RoundedCornerShape(10.dp), color = step.color.copy(alpha = 0.25f), modifier = Modifier.size(32.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (status == WizardStepStatus.DONE) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = step.color, modifier = Modifier.size(16.dp))
                                    } else {
                                        Text("${index + 1}", fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold, color = step.color, fontSize = 13.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(step.title, fontFamily = CairoFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(description, fontFamily = CairoFontFamily, fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { executeStep(index) },
                                enabled = runningIndex == -1 && status != WizardStepStatus.RUNNING,
                                colors = ButtonDefaults.buttonColors(containerColor = step.color, contentColor = Color(0xFF0B1220)),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                if (status == WizardStepStatus.RUNNING) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF0B1220))
                                } else {
                                    Text("تنفيذ الخطوة", fontFamily = CairoFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = when (status) {
                                    WizardStepStatus.PENDING -> "بانتظار التنفيذ"
                                    WizardStepStatus.RUNNING -> "جارٍ التنفيذ..."
                                    WizardStepStatus.DONE -> message ?: "تم بنجاح"
                                    WizardStepStatus.FAILED -> message ?: "فشلت الخطوة"
                                },
                                fontFamily = CairoFontFamily,
                                fontSize = 11.sp,
                                color = when (status) {
                                    WizardStepStatus.DONE -> Color(0xFF34D399)
                                    WizardStepStatus.FAILED -> Color(0xFFF87171)
                                    else -> Color(0xFF94A3B8)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
