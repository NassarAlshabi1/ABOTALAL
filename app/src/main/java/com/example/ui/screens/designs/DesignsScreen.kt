package com.example.ui.screens.designs

import androidx.core.graphics.toColorInt
import androidx.core.net.toUri

import java.util.Locale
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.BackgroundType
import com.example.data.CardsCharset
import com.example.data.Design
import com.example.data.ElementStyle
import com.example.utils.A4
import com.example.utils.cardSize
import com.example.viewmodel.DesignsViewModel
import com.example.util.CardPrintAndExportHelper
import com.example.util.OperationTelemetry
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignsScreen(
    context: Context = LocalContext.current,
    viewModel: DesignsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = DesignsViewModel.Factory(context)
    ),
    availableUmPackages: List<String> = emptyList(),
    availableHotspotProfiles: List<String> = emptyList(),
    onBack: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val designs by viewModel.designs.collectAsState()
    val selectedDesign by viewModel.selectedDesign.collectAsState()
    val draftDesign by viewModel.draftDesign.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    
    val activeDesign = draftDesign ?: selectedDesign
    
    var showColorPickerFor by remember { mutableStateOf<String?>(null) } // "border", "title", "username", etc.
    var currentColorValue by remember { mutableStateOf("#000000") }
    var onColorSelectedCallback by remember { mutableStateOf<(String) -> Unit>({}) }
    
    var showBuiltInBgDialog by remember { mutableStateOf(false) }
    var showDeleteDesignConfirm by remember { mutableStateOf(false) }

    // Launchers for image picking
    val bgImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            activeDesign?.let { d ->
                try {
                    val storedImage = copyBackgroundToAppStorage(context, it, d.id)
                    if (storedImage != null) {
                        deleteStoredBackgroundIfOwned(context, d.bgUrl)
                        val updatedDesign = d.copy(
                            bgOn = true,
                            bgUrl = storedImage,
                            bgType = BackgroundType.CUSTOM
                        )
                        // الحفظ الفوري يضمن بقاء الصورة مع الباقة المختارة حتى لو أُغلقت الشاشة.
                        viewModel.saveDesign(updatedDesign)
                        CardPrintAndExportHelper.saveDesignAsTemplate(context, updatedDesign)
                        Toast.makeText(context, "تم حفظ الخلفية مع التصميم والباقة المختارة", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "تعذر قراءة صورة الخلفية", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "فشل حفظ الخلفية: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val logoImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            activeDesign?.let { d ->
                viewModel.updateDraft(d.copy(logoOn = true, logoUrl = it.toString()))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تصاميم الكروت", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0C5A60),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = {
                        activeDesign?.let { d ->
                            generateAndPreviewPdf(context, d)
                        }
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "معاينة PDF وطباعة")
                    }
                    IconButton(onClick = { viewModel.createNewDesign() }) {
                        Icon(Icons.Default.Add, contentDescription = "تصميم جديد")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ============================================================
            // ✅ الجزء العلوي: الإعدادات (قابل للتمرير)
            // ============================================================
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // --- 1. اختيار التصميم ---
                item {
                    DesignCard(title = "اختيار التصميم المحفوظ") {
                        if (designs.isEmpty()) {
                            Text(
                                "لا توجد تصاميم محفوظة، اضغط (+) لإضافة تصميم جديد.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                designs.forEach { design ->
                                    val isSel = (selectedDesign?.id == design.id)
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { viewModel.selectDesign(design.id) },
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSel) Color(0xFF0C5A60) else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            text = design.name,
                                            modifier = Modifier.padding(10.dp),
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = if (isSel) Color.White else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                textAlign = TextAlign.Center
                                            ),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // --- 2. اسم القالب ---
                item {
                    DesignCard(title = "اسم القالب") {
                        OutlinedTextField(
                            value = activeDesign?.name ?: "",
                            onValueChange = { newName ->
                                activeDesign?.let {
                                    viewModel.updateDraft(it.copy(name = newName))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("اسم القالب") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
                
                // --- 3. ربط الباقة ---
                item {
                    DesignCard(title = "ربط الباقة مع القالب") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // User Manager Package Dropdown
                            PackageDropdown(
                                label = "باقة User Manager",
                                selectedValue = activeDesign?.userManagerPackageId ?: "",
                                options = availableUmPackages,
                                onSelect = { sel ->
                                    activeDesign?.let {
                                        viewModel.updateDraft(it.copy(userManagerPackageId = sel.ifEmpty { null }))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            
                            // Hotspot Profile Dropdown
                            PackageDropdown(
                                label = "باقة Hotspot",
                                selectedValue = activeDesign?.hotspotPackageId ?: "",
                                options = availableHotspotProfiles,
                                onSelect = { sel ->
                                    activeDesign?.let {
                                        viewModel.updateDraft(it.copy(hotspotPackageId = sel.ifEmpty { null }))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                
                // --- 4. إعدادات الصفحة ---
                item {
                    DesignCard(title = "بيانات الصفحة (A4)") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NumField(
                                label = "الأعمدة",
                                value = (activeDesign?.columns ?: 3).toFloat(),
                                onValueChange = { value ->
                                    activeDesign?.let {
                                        viewModel.updateDraft(it.copy(columns = value.toInt()))
                                    }
                                },
                                step = 1f, min = 1f, max = 12f, decimals = 0,
                                modifier = Modifier.weight(1f)
                            )
                            
                            NumField(
                                label = "الصفوف",
                                value = (activeDesign?.rows ?: 17).toFloat(),
                                onValueChange = { value ->
                                    activeDesign?.let {
                                        viewModel.updateDraft(it.copy(rows = value.toInt()))
                                    }
                                },
                                step = 1f, min = 1f, max = 40f, decimals = 0,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        SwitchRow(
                            checked = activeDesign?.autoFit ?: true,
                            onCheckedChange = { checked ->
                                activeDesign?.let {
                                    viewModel.updateDraft(it.copy(autoFit = checked))
                                }
                            },
                            label = "ملاءمة تلقائية لمقاس A4 (الحساب التلقائي)"
                        )
                        
                        if (activeDesign?.autoFit == false) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NumField(
                                    label = "عرض الكرت (مم)",
                                    value = activeDesign?.cardWidth ?: 65f,
                                    onValueChange = { value ->
                                        activeDesign?.let {
                                            viewModel.updateDraft(it.copy(cardWidth = value))
                                        }
                                    },
                                    step = 0.5f, min = 10f, max = 210f, decimals = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                NumField(
                                    label = "طول الكرت (مم)",
                                    value = activeDesign?.cardHeight ?: 15f,
                                    onValueChange = { value ->
                                        activeDesign?.let {
                                            viewModel.updateDraft(it.copy(cardHeight = value))
                                        }
                                    },
                                    step = 0.5f, min = 6f, max = 297f, decimals = 1,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                
                // --- 5. هامش بين الكروت ---
                item {
                    DesignCard(title = "هوامش الصفحة والتباعد بين الكروت") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NumField(
                                label = "أفقي (مم)",
                                value = activeDesign?.marginH ?: 0f,
                                onValueChange = { value ->
                                    activeDesign?.let {
                                        viewModel.updateDraft(it.copy(marginH = value))
                                    }
                                },
                                step = 0.5f, min = 0f, max = 50f, decimals = 1,
                                modifier = Modifier.weight(1f)
                            )
                            
                            NumField(
                                label = "رأسي (مم)",
                                value = activeDesign?.marginV ?: 0f,
                                onValueChange = { value ->
                                    activeDesign?.let {
                                        viewModel.updateDraft(it.copy(marginV = value))
                                    }
                                },
                                step = 0.5f, min = 0f, max = 50f, decimals = 1,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        NumField(
                            label = "هامش الصفحة الخارجي (مم)",
                            value = activeDesign?.pageMargin ?: 5f,
                            onValueChange = { value ->
                                activeDesign?.let {
                                    viewModel.updateDraft(it.copy(pageMargin = value))
                                }
                            },
                            step = 0.5f, min = 0f, max = 30f, decimals = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                // --- 6. خلفية الكرت ---
                item {
                    DesignCard(title = "خلفية الكرت") {
                        SwitchRow(
                            checked = activeDesign?.bgOn ?: false,
                            onCheckedChange = { checked ->
                                activeDesign?.let {
                                    viewModel.updateDraft(it.copy(bgOn = checked))
                                }
                            },
                            label = "تفعيل خلفية الكرت"
                        )
                        
                        if (activeDesign?.bgOn == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showBuiltInBgDialog = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                                ) {
                                    Text("خلفيات مدمجة")
                                }
                                
                                OutlinedButton(
                                    onClick = {
                                        val design = activeDesign
                                        if (design.userManagerPackageId.isNullOrBlank() && design.hotspotPackageId.isNullOrBlank()) {
                                            Toast.makeText(context, "اختر باقة User Manager أو Hotspot قبل إضافة الخلفية", Toast.LENGTH_LONG).show()
                                        } else {
                                            bgImagePicker.launch("image/*")
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("رفع صورة")
                                }
                            }
                            
                            // عرض الخلفية الحالية
                            if (activeDesign?.bgUrl != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(activeDesign.bgUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "خلفية الكرت",
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(70.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    IconButton(
                                        onClick = {
                                            activeDesign.let {
                                                deleteStoredBackgroundIfOwned(context, it.bgUrl)
                                                val updatedDesign = it.copy(bgUrl = null, bgOn = false)
                                                viewModel.saveDesign(updatedDesign)
                                                CardPrintAndExportHelper.saveDesignAsTemplate(context, updatedDesign)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف الخلفية", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // --- 7. إطار الكرت ---
                item {
                    DesignCard(title = "إطار الكرت (Border)") {
                        SwitchRow(
                            checked = activeDesign?.borderOn ?: false,
                            onCheckedChange = { checked ->
                                activeDesign?.let {
                                    viewModel.updateDraft(it.copy(borderOn = checked))
                                }
                            },
                            label = "تفعيل الإطار الخارجي"
                        )
                        
                        if (activeDesign?.borderOn == true) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NumField(
                                    label = "حجم الإطار (مم)",
                                    value = activeDesign?.borderSize ?: 0.35f,
                                    onValueChange = { value ->
                                        activeDesign?.let {
                                            viewModel.updateDraft(it.copy(borderSize = value))
                                        }
                                    },
                                    step = 0.05f, min = 0.05f, max = 5f, decimals = 2,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                ColorField(
                                    label = "لون الإطار",
                                    value = activeDesign?.borderColor ?: "#000000",
                                    onSelectClick = {
                                        currentColorValue = activeDesign?.borderColor ?: "#000000"
                                        showColorPickerFor = "إطار الكرت"
                                        onColorSelectedCallback = { col ->
                                            activeDesign?.let { viewModel.updateDraft(it.copy(borderColor = col)) }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                
                // --- 8. عناصر الكرت (اسم المستخدم، كلمة المرور، العنوان، السعر، إلخ) ---
                activeDesign?.let { design ->
                    // اسم المستخدم
                    item {
                        ElementEditorCard(
                            title = "اسم المستخدم (Username)",
                            element = design.username,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(username = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            showUsernameToggle = true,
                            isUsernameEnabled = design.showUsername,
                            onUsernameToggle = { enabled ->
                                viewModel.updateDraft(design.copy(showUsername = enabled))
                            },
                            onColorRequest = {
                                currentColorValue = design.username.color
                                showColorPickerFor = "لون اسم المستخدم"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(username = design.username.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // كلمة المرور
                    item {
                        ElementEditorCard(
                            title = "كلمة المرور (Password)",
                            element = design.password,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(password = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            onColorRequest = {
                                currentColorValue = design.password.color
                                showColorPickerFor = "لون كلمة المرور"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(password = design.password.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // العنوان
                    item {
                        ElementEditorCard(
                            title = "نص العنوان (Title / Header)",
                            element = design.title,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(title = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            withText = true,
                            textLabel = "نص العنوان",
                            onColorRequest = {
                                currentColorValue = design.title.color
                                showColorPickerFor = "لون نص العنوان"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(title = design.title.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // السعر
                    item {
                        ElementEditorCard(
                            title = "سعر الباقة (Price)",
                            element = design.price,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(price = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            onColorRequest = {
                                currentColorValue = design.price.color
                                showColorPickerFor = "لون السعر"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(price = design.price.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // الرقم التسلسلي
                    item {
                        ElementEditorCard(
                            title = "الرقم التسلسلي (Serial)",
                            element = design.serial,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(serial = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            onColorRequest = {
                                currentColorValue = design.serial.color
                                showColorPickerFor = "لون الرقم التسلسلي"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(serial = design.serial.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // الدفعة
                    item {
                        ElementEditorCard(
                            title = "رقم الدفعة (Batch)",
                            element = design.payment,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(payment = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            withText = true,
                            textLabel = "نص الدفعة",
                            onColorRequest = {
                                currentColorValue = design.payment.color
                                showColorPickerFor = "لون الدفعة"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(payment = design.payment.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // نقطة البيع
                    item {
                        ElementEditorCard(
                            title = "نقطة البيع (POS)",
                            element = design.pos,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(pos = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            withText = true,
                            textLabel = "نص نقطة البيع",
                            onColorRequest = {
                                currentColorValue = design.pos.color
                                showColorPickerFor = "لون نقطة البيع"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(pos = design.pos.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // ملاحظة الصفحة
                    item {
                        ElementEditorCard(
                            title = "ملاحظة على الصفحة",
                            element = design.pageNote,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(pageNote = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            withText = true,
                            textLabel = "نص الملاحظة",
                            onColorRequest = {
                                currentColorValue = design.pageNote.color
                                showColorPickerFor = "لون ملاحظة الصفحة"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(pageNote = design.pageNote.copy(color = c)))
                                }
                            }
                        )
                    }
                    
                    // ترقيم الصفحة
                    item {
                        ElementEditorCard(
                            title = "ترقيم الصفحة",
                            element = design.pageNumber,
                            onElementChange = { newElement ->
                                viewModel.updateDraft(design.copy(pageNumber = newElement))
                            },
                            step = design.moveStep,
                            onStepChange = { newStep ->
                                viewModel.updateDraft(design.copy(moveStep = newStep))
                            },
                            cardWidth = cardSize(design).first,
                            cardHeight = cardSize(design).second,
                            withText = true,
                            textLabel = "النص — {page}",
                            onColorRequest = {
                                currentColorValue = design.pageNumber.color
                                showColorPickerFor = "لون ترقيم الصفحة"
                                onColorSelectedCallback = { c ->
                                    viewModel.updateDraft(design.copy(pageNumber = design.pageNumber.copy(color = c)))
                                }
                            }
                        )
                    }
                }
                
                // --- 9. الباركود ---
                item {
                    activeDesign?.let { design ->
                        DesignCard(title = "الباركود / رمز الاستجابة السريعة (Barcode)") {
                            SwitchRow(
                                checked = design.barcodeOn,
                                onCheckedChange = { checked ->
                                    viewModel.updateDraft(design.copy(barcodeOn = checked))
                                },
                                label = "تفعيل الباركود على الكرت"
                            )
                            
                            if (design.barcodeOn) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NumField(
                                        label = "العرض (مم)",
                                        value = design.barcodeWidth,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(barcodeWidth = value))
                                        },
                                        step = 0.5f, min = 5f, max = 80f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    NumField(
                                        label = "الارتفاع (مم)",
                                        value = design.barcodeHeight,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(barcodeHeight = value))
                                        },
                                        step = 0.5f, min = 3f, max = 50f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NumField(
                                        label = "الموضع س (أفقي مم)",
                                        value = design.barcodeX,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(barcodeX = value))
                                        },
                                        step = 0.5f, min = 0f, max = 150f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    NumField(
                                        label = "الموضع ص (رأسي مم)",
                                        value = design.barcodeY,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(barcodeY = value))
                                        },
                                        step = 0.5f, min = 0f, max = 150f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // --- 10. الشعار ---
                item {
                    activeDesign?.let { design ->
                        DesignCard(title = "الشعار (Logo)") {
                            SwitchRow(
                                checked = design.logoOn,
                                onCheckedChange = { checked ->
                                    viewModel.updateDraft(design.copy(logoOn = checked))
                                },
                                label = "تفعيل الشعار على الكرت"
                            )
                            
                            if (design.logoOn) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { logoImagePicker.launch("image/*") },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                                    ) {
                                        Text("اختيار صورة الشعار")
                                    }
                                    
                                    if (design.logoUrl != null) {
                                        Button(
                                            onClick = {
                                                viewModel.updateDraft(design.copy(logoUrl = null, logoOn = false))
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("مسح")
                                        }
                                    }
                                }
                                
                                if (design.logoUrl != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(design.logoUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "الشعار",
                                        modifier = Modifier
                                            .size(54.dp)
                                            .align(Alignment.CenterHorizontally)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NumField(
                                        label = "العرض (مم)",
                                        value = design.logoWidth,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(logoWidth = value))
                                        },
                                        step = 0.5f, min = 4f, max = 50f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    NumField(
                                        label = "الارتفاع (مم)",
                                        value = design.logoHeight,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(logoHeight = value))
                                        },
                                        step = 0.5f, min = 4f, max = 50f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NumField(
                                        label = "الموضع س (أفقي مم)",
                                        value = design.logoX,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(logoX = value))
                                        },
                                        step = 0.5f, min = 0f, max = 150f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    
                                    NumField(
                                        label = "الموضع ص (رأسي مم)",
                                        value = design.logoY,
                                        onValueChange = { value ->
                                            viewModel.updateDraft(design.copy(logoY = value))
                                        },
                                        step = 0.5f, min = 0f, max = 150f, decimals = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // --- 11. أزرار التحكم (حفظ، حذف، وتوليد PDF) ---
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    activeDesign?.let {
                                        viewModel.saveDesign(it)
                                        CardPrintAndExportHelper.saveDesignAsTemplate(context, it)
                                        Toast.makeText(context, "تم حفظ التصميم بنجاح ✅", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
                            ) {
                                Icon(Icons.Default.Save, contentDescription = "حفظ")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("حفظ التصميم")
                            }
                            
                            Button(
                                onClick = {
                                    if (selectedDesign != null) showDeleteDesignConfirm = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "حذف")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("حذف")
                            }
                        }

                        Button(
                            onClick = {
                                activeDesign?.let { d ->
                                    generateAndPreviewPdf(context, d)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("معاينة وتصدير PDF (أندرويد)")
                        }
                    }
                }
            }
            
            // ============================================================
            // ✅ الجزء السفلي: معاينة الكرت (ثابتة - غير قابلة للتمرير)
            // ============================================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .height(230.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "معاينة الكرت الفورية",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0C5A60)
                        )
                        activeDesign?.let { d ->
                            val (wMm, hMm) = cardSize(d)
                            Text(
                                text = "${String.format(Locale.US, "%.1f", wMm)} × ${String.format(Locale.US, "%.1f", hMm)} مم",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    activeDesign?.let { design ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CardPreview(
                                design = design,
                                width = 280f
                            )
                        }
                    }
                }
            }
        }
    }

    // Color Picker Dialog
    if (showColorPickerFor != null) {
        ColorPickerDialog(
            title = showColorPickerFor ?: "اختر اللون",
            initialColor = currentColorValue,
            onDismiss = { showColorPickerFor = null },
            onColorSelected = { col ->
                onColorSelectedCallback(col)
                showColorPickerFor = null
            }
        )
    }

    // Built-in Backgrounds Dialog
    if (showBuiltInBgDialog) {
        BuiltInBackgroundsDialog(
            onDismiss = { showBuiltInBgDialog = false },
            onSelect = { bgStyle ->
                val design = activeDesign
                if (design != null) {
                    coroutineScope.launch {
                        try {
                            val storedImage = withContext(Dispatchers.IO) {
                                copyBackgroundToAppStorage(context, (bgStyle).toUri(), design.id)
                            }
                            if (storedImage != null) {
                                deleteStoredBackgroundIfOwned(context, design.bgUrl)
                                val updatedDesign = design.copy(
                                    bgOn = true,
                                    bgType = BackgroundType.BUILT_IN,
                                    bgUrl = storedImage
                                )
                                viewModel.saveDesign(updatedDesign)
                                CardPrintAndExportHelper.saveDesignAsTemplate(context, updatedDesign)
                                Toast.makeText(context, "تم حفظ الخلفية الجاهزة مع التصميم", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "تعذر تنزيل الخلفية الجاهزة", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "فشل حفظ الخلفية: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                showBuiltInBgDialog = false
            }
        )
    }

    if (showDeleteDesignConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteDesignConfirm = false },
            title = {
                Text(
                    text = "حذف التصميم",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "هل تريد حذف التصميم \"${selectedDesign?.name.orEmpty()}\" نهائياً؟ لا يمكن التراجع عن هذه العملية.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDesignConfirm = false
                        selectedDesign?.let { design ->
                            deleteStoredBackgroundIfOwned(context, design.bgUrl)
                            viewModel.deleteDesign(design.id)
                            Toast.makeText(context, "تم حذف التصميم", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("نعم، احذف", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDesignConfirm = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

// ============================================================
// 🧩 المكونات المساعدة (Dropdowns, Cards, Fields, Pad, Preview)
// ============================================================

private fun copyBackgroundToAppStorage(context: Context, source: Uri, designId: String): String? {
    val directory = File(context.filesDir, "card_backgrounds").apply { mkdirs() }
    val target = File(directory, "${designId}_${System.currentTimeMillis()}.img")
    return try {
        val inputStream = when (source.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" -> URL(source.toString()).openStream()
            else -> context.contentResolver.openInputStream(source)
        }
        inputStream?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: return null
        Uri.fromFile(target).toString()
    } catch (e: Exception) {
        target.delete()
        throw e
    }
}

private fun deleteStoredBackgroundIfOwned(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    try {
        val uri = (url).toUri()
        val file = if (uri.scheme == "file") File(uri.path ?: "") else null
        val ownedDirectory = File(context.filesDir, "card_backgrounds").canonicalFile
        if (file != null && file.exists() && file.canonicalFile.parentFile == ownedDirectory) {
            file.delete()
        }
    } catch (_: Exception) {
        // A remote/built-in URL or an already removed file needs no cleanup.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedValue.ifEmpty { "غير مرتبط" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("بدون ربط (افتراضي)") },
                onClick = {
                    onSelect("")
                    expanded = false
                }
            )
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun DesignCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0C5A60),
                modifier = Modifier.padding(bottom = 6.dp)
            )
            content()
        }
    }
}

@Composable
fun SwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun NumField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    step: Float = 1f,
    min: Float = 0f,
    max: Float = 999f,
    decimals: Int = 0,
    modifier: Modifier = Modifier
) {
    val clamp = { v: Float -> min(max, max(min, v)) }
    
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = { onValueChange(clamp(value - step)) },
                modifier = Modifier.size(28.dp)
            ) {
                Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            
            OutlinedTextField(
                value = String.format(Locale.US, "%.${decimals}f", value),
                onValueChange = { newValue ->
                    val parsed = newValue.toFloatOrNull()
                    if (parsed != null) onValueChange(clamp(parsed))
                },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                ),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            
            IconButton(
                onClick = { onValueChange(clamp(value + step)) },
                modifier = Modifier.size(28.dp)
            ) {
                Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ColorField(
    label: String,
    value: String,
    onSelectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = try {
        Color((value).toColorInt())
    } catch (_: Exception) {
        Color.Black
    }
    
    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .clickable { onSelectClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
        )
    }
}

@Composable
fun ElementEditorCard(
    title: String,
    element: ElementStyle,
    onElementChange: (ElementStyle) -> Unit,
    step: Float,
    onStepChange: (Float) -> Unit,
    cardWidth: Float? = null,
    cardHeight: Float? = null,
    withText: Boolean = false,
    textLabel: String = "النص",
    showUsernameToggle: Boolean = false,
    isUsernameEnabled: Boolean = true,
    onUsernameToggle: ((Boolean) -> Unit)? = null,
    onColorRequest: () -> Unit = {}
) {
    DesignCard(title = title) {
        SwitchRow(
            checked = element.show,
            onCheckedChange = { checked ->
                onElementChange(element.copy(show = checked))
            },
            label = "إظهار العنصر على الكرت"
        )
        
        if (element.show) {
            Spacer(modifier = Modifier.height(6.dp))
            // أزرار التموضع السريع (9 أوضاع)
            if (cardWidth != null && cardHeight != null) {
                QuickPositionButtons(
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    onPositionSelected = { x, y ->
                        onElementChange(element.copy(x = x, y = y))
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            // لوحة التحريك
            MovePad(
                step = step,
                onStepChange = onStepChange,
                onMove = { dx, dy ->
                    val newX = maxOf(0f, element.x + dx)
                    val newY = maxOf(0f, element.y + dy)
                    onElementChange(
                        element.copy(
                            x = String.format(Locale.US, "%.1f", newX).toFloat(),
                            y = String.format(Locale.US, "%.1f", newY).toFloat()
                        )
                    )
                }
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            // إعدادات الحجم واللون والموضع
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumField(
                    label = "أفقي س (مم)",
                    value = element.x,
                    onValueChange = { x -> onElementChange(element.copy(x = x)) },
                    step = 0.5f, min = 0f, max = 200f, decimals = 1,
                    modifier = Modifier.weight(1f)
                )
                
                NumField(
                    label = "رأسي ص (مم)",
                    value = element.y,
                    onValueChange = { y -> onElementChange(element.copy(y = y)) },
                    step = 0.5f, min = 0f, max = 200f, decimals = 1,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumField(
                    label = "حجم الخط",
                    value = element.fontSize,
                    onValueChange = { size -> onElementChange(element.copy(fontSize = size)) },
                    step = 0.5f, min = 4f, max = 48f, decimals = 1,
                    modifier = Modifier.weight(1f)
                )
                
                ColorField(
                    label = "لون الخط",
                    value = element.color,
                    onSelectClick = onColorRequest,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SwitchRow(
                    checked = element.bold,
                    onCheckedChange = { bold -> onElementChange(element.copy(bold = bold)) },
                    label = "عريض",
                    modifier = Modifier.weight(1f)
                )
                
                SwitchRow(
                    checked = element.italic,
                    onCheckedChange = { italic -> onElementChange(element.copy(italic = italic)) },
                    label = "مائل",
                    modifier = Modifier.weight(1f)
                )
            }
            
            if (withText) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = element.text,
                    onValueChange = { text -> onElementChange(element.copy(text = text)) },
                    label = { Text(textLabel) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
            
            if (showUsernameToggle && onUsernameToggle != null) {
                Spacer(modifier = Modifier.height(6.dp))
                SwitchRow(
                    checked = isUsernameEnabled,
                    onCheckedChange = onUsernameToggle,
                    label = "إظهار اسم المستخدم على الكرت"
                )
            }
        }
    }
}

@Composable
fun QuickPositionButtons(
    cardWidth: Float,
    cardHeight: Float,
    onPositionSelected: (x: Float, y: Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "أوضاع التموضع الجاهزة (9 اتجاهات)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuickPosButton("↗ أعلى يمين") { onPositionSelected(2f, 2f) }
            QuickPosButton("↑ أعلى وسط") { onPositionSelected(maxOf(0f, (cardWidth - 25) / 2f), 2f) }
            QuickPosButton("↖ أعلى يسار") { onPositionSelected(maxOf(0f, cardWidth - 25f), 2f) }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuickPosButton("→ وسط يمين") { onPositionSelected(2f, maxOf(0f, (cardHeight - 6) / 2f)) }
            QuickPosButton("🎯 مركز الكرت", true) { 
                onPositionSelected(maxOf(0f, (cardWidth - 25) / 2f), maxOf(0f, (cardHeight - 6) / 2f)) 
            }
            QuickPosButton("← وسط يسار") { onPositionSelected(maxOf(0f, cardWidth - 25f), maxOf(0f, (cardHeight - 6) / 2f)) }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuickPosButton("↘ أسفل يمين") { onPositionSelected(2f, maxOf(0f, cardHeight - 6f)) }
            QuickPosButton("↓ أسفل وسط") { onPositionSelected(maxOf(0f, (cardWidth - 25) / 2f), maxOf(0f, cardHeight - 6f)) }
            QuickPosButton("↙ أسفل يسار") { onPositionSelected(maxOf(0f, cardWidth - 25f), maxOf(0f, cardHeight - 6f)) }
        }
    }
}

@Composable
fun QuickPosButton(
    label: String,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        colors = if (isPrimary) {
            ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0C5A60),
                contentColor = Color.White
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun MovePad(
    step: Float,
    onStepChange: (Float) -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = "تحريك الموضع بالأزرار التوجيهية",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NumField(
                label = "خطوة التحريك (مم)",
                value = step,
                onValueChange = onStepChange,
                step = 0.5f, min = 0.1f, max = 10f, decimals = 1,
                modifier = Modifier.weight(1f)
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { onMove(-step, 0f) },
                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Text("→", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = { onMove(step, 0f) },
                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Text("←", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = { onMove(0f, -step) },
                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Text("↑", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(
                    onClick = { onMove(0f, step) },
                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                ) {
                    Text("↓", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================================================
// 🎴 CardPreview - معاينة الكرت الثابتة في الأسفل
// ============================================================

private const val PREVIEW_CARD_NUMBER = "1234567890"

@Composable
fun CardPreview(
    design: Design,
    width: Float = 280f
) {
    val density = LocalDensity.current
    val (cardWidthMm, cardHeightMm) = cardSize(design)
    val scale = with(density) { width.dp.toPx() / maxOf(10f, cardWidthMm) }
    
    Box(
        modifier = Modifier
            .size(
                width = with(density) { width.dp },
                height = with(density) { (cardHeightMm * scale).dp }
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp)
            )
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (design.bgOn && design.bgUrl != null) {
                    Color.Transparent
                } else {
                    Color.White
                }
            )
    ) {
        // الخلفية
        if (design.bgOn && design.bgUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(design.bgUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "خلفية الكرت",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        // Canvas للإطار
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (design.borderOn) {
                val borderColor = try {
                    Color((design.borderColor).toColorInt())
                } catch (_: Exception) {
                    Color.Black
                }
                drawRect(
                    color = borderColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = max(1f, design.borderSize * scale)
                    )
                )
            }
        }
        
        // تحويل المليمترات إلى بكسل
        fun mmToPx(mm: Float): Float = mm * scale
        
        // العنوان
        if (design.title.show && design.title.text.isNotEmpty()) {
            Text(
                text = design.title.text,
                modifier = Modifier.padding(
                    start = with(density) { mmToPx(design.title.x).dp },
                    top = with(density) { mmToPx(design.title.y).dp }
                ),
                fontSize = maxOf(6f, design.title.fontSize * width / (cardWidthMm * 2.83465f)).sp,
                color = try { Color((design.title.color).toColorInt()) } catch (_: Exception) { Color.Black },
                fontWeight = if (design.title.bold) FontWeight.Bold else FontWeight.Normal
            )
        }
        
        // رقم توضيحي للمعاينة فقط؛ لا يُستخدم في توليد أو طباعة بيانات الدفعات.
        // اسم المستخدم
        if (design.showUsername && design.username.show) {
            Text(
                text = PREVIEW_CARD_NUMBER,
                modifier = Modifier.padding(
                    start = with(density) { mmToPx(design.username.x).dp },
                    top = with(density) { mmToPx(design.username.y).dp }
                ),
                fontSize = maxOf(7f, design.username.fontSize * width / (cardWidthMm * 2.83465f)).sp,
                textAlign = TextAlign.Left,
                maxLines = 1,
                softWrap = false,
                color = try { Color((design.username.color).toColorInt()) } catch (_: Exception) { Color.Black },
                fontWeight = if (design.username.bold) FontWeight.Bold else FontWeight.Normal
            )
        }
        
        // كلمة المرور
        if (design.password.show) {
            Text(
                text = "",
                modifier = Modifier.padding(
                    start = with(density) { mmToPx(design.password.x).dp },
                    top = with(density) { mmToPx(design.password.y).dp }
                ),
                fontSize = maxOf(7f, design.password.fontSize * width / (cardWidthMm * 2.83465f)).sp,
                color = try { Color((design.password.color).toColorInt()) } catch (_: Exception) { Color.Black },
                fontWeight = if (design.password.bold) FontWeight.Bold else FontWeight.Normal
            )
        }
        
        // السعر
        if (design.price.show) {
            Text(
                text = design.price.text,
                modifier = Modifier.padding(
                    start = with(density) { mmToPx(design.price.x).dp },
                    top = with(density) { mmToPx(design.price.y).dp }
                ),
                fontSize = maxOf(6f, design.price.fontSize * width / (cardWidthMm * 2.83465f)).sp,
                color = try { Color((design.price.color).toColorInt()) } catch (_: Exception) { Color.Black },
                fontWeight = if (design.price.bold) FontWeight.Bold else FontWeight.Normal
            )
        }
        
        // الرقم التسلسلي
        if (design.serial.show) {
            Text(
                text = design.serial.text,
                modifier = Modifier.padding(
                    start = with(density) { mmToPx(design.serial.x).dp },
                    top = with(density) { mmToPx(design.serial.y).dp }
                ),
                fontSize = maxOf(5f, design.serial.fontSize * width / (cardWidthMm * 2.83465f)).sp,
                color = try { Color((design.serial.color).toColorInt()) } catch (_: Exception) { Color.Black },
                fontWeight = if (design.serial.bold) FontWeight.Bold else FontWeight.Normal
            )
        }

        // الدفعة
        if (design.payment.show && design.payment.text.isNotEmpty()) {
            Text(
                text = design.payment.text,
                modifier = Modifier.padding(
                    start = with(density) { mmToPx(design.payment.x).dp },
                    top = with(density) { mmToPx(design.payment.y).dp }
                ),
                fontSize = maxOf(5f, design.payment.fontSize * width / (cardWidthMm * 2.83465f)).sp,
                color = try { Color((design.payment.color).toColorInt()) } catch (_: Exception) { Color.Black }
            )
        }

        // نقطة البيع
        if (design.pos.show && design.pos.text.isNotEmpty()) {
            Text(
                text = design.pos.text,
                modifier = Modifier.padding(
                    start = with(density) { mmToPx(design.pos.x).dp },
                    top = with(density) { mmToPx(design.pos.y).dp }
                ),
                fontSize = maxOf(5f, design.pos.fontSize * width / (cardWidthMm * 2.83465f)).sp,
                color = try { Color((design.pos.color).toColorInt()) } catch (_: Exception) { Color.Black }
            )
        }
        
        // الباركود / QR
        if (design.barcodeOn) {
            Box(
                modifier = Modifier
                    .padding(
                        start = with(density) { mmToPx(design.barcodeX).dp },
                        top = with(density) { mmToPx(design.barcodeY).dp }
                    )
                    .size(
                        width = with(density) { mmToPx(design.barcodeWidth).dp },
                        height = with(density) { mmToPx(design.barcodeHeight).dp }
                    )
                    .border(0.5.dp, Color.DarkGray)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                // خطوط باركود مدمجة بدقة عالية
                Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                    val barCount = 14
                    val barWidth = size.width / (barCount * 1.6f)
                    for (i in 0 until barCount) {
                        val isThick = (i % 3 == 0)
                        val w = if (isThick) barWidth * 1.5f else barWidth * 0.8f
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(x = i * (size.width / barCount), y = 0f),
                            size = androidx.compose.ui.geometry.Size(w, size.height)
                        )
                    }
                }
            }
        }
        
        // الشعار
        if (design.logoOn && design.logoUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(design.logoUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "الشعار",
                modifier = Modifier
                    .padding(
                        start = with(density) { mmToPx(design.logoX).dp },
                        top = with(density) { mmToPx(design.logoY).dp }
                    )
                    .size(
                        width = with(density) { mmToPx(design.logoWidth).dp },
                        height = with(density) { mmToPx(design.logoHeight).dp }
                    ),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// ============================================================
// 🎨 حوار اختيار الألوان وحوار الخلفيات المدمجة
// ============================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: String,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit
) {
    val presetColors = listOf(
        "#000000", "#FFFFFF", "#0C5A60", "#0284C7", "#16A34A",
        "#DC2626", "#D97706", "#7C3AED", "#475569", "#B45309"
    )
    var customHex by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اختر لوناً من القائمة أو اكتب الرمز:", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetColors.forEach { hex ->
                        val col = try { Color((hex).toColorInt()) } catch (_: Exception) { Color.Black }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    width = if (customHex.equals(hex, ignoreCase = true)) 3.dp else 1.dp,
                                    color = if (customHex.equals(hex, ignoreCase = true)) Color.Blue else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { customHex = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (customHex.equals(hex, ignoreCase = true)) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (hex == "#FFFFFF") Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                
                OutlinedTextField(
                    value = customHex,
                    onValueChange = { customHex = it },
                    label = { Text("رمز اللون Hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onColorSelected(customHex) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C5A60))
            ) {
                Text("تأكيد")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun BuiltInBackgroundsDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val backgrounds = listOf(
        "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?w=600" to "تدرج ناعم أزرق ووردي",
        "https://images.unsplash.com/photo-1557683316-973673baf926?w=600" to "تدرج كحلي راقي",
        "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?w=600" to "شبكة تكنولوجية داكنة",
        "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600" to "فن تجريدي حديث"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختر من الخلفيات الجاهزة", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.height(280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(backgrounds) { (url, name) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(url) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AsyncImage(
                                model = url,
                                contentDescription = name,
                                modifier = Modifier
                                    .size(60.dp, 40.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("إغلاق")
            }
        }
    )
}

// ============================================================
// 📄 دالة توليد وفتح PDF عبر عارض أندرويد الأصلي
// ============================================================

fun generateAndPreviewPdf(context: Context, design: Design) {
    try {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 at 72dpi
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val paintText = Paint().apply {
            isAntiAlias = true
            textSize = 10f
        }
        val paintBorder = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = android.graphics.Color.BLACK
        }

        // حساب الأبعاد
        val (cardW, cardH) = cardSize(design)
        val cols = max(1, design.columns)
        val rows = max(1, design.rows)

        val mmToPt = 72f / 25.4f
        val cardWPt = cardW * mmToPt
        val cardHPt = cardH * mmToPt
        val pageMarginPt = design.pageMargin * mmToPt
        val marginHPt = design.marginH * mmToPt
        val marginVPt = design.marginV * mmToPt
        val backgroundBitmap = loadStoredBackgroundBitmap(context, design)
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        var cardCount = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = pageMarginPt + c * (cardWPt + marginHPt)
                val top = pageMarginPt + r * (cardHPt + marginVPt)
                val right = left + cardWPt
                val bottom = top + cardHPt

                // رسم الخلفية المحفوظة داخل كل كرت في ملف PDF أيضاً.
                if (backgroundBitmap != null) {
                    canvas.drawBitmap(
                        backgroundBitmap,
                        null,
                        RectF(left, top, right, bottom),
                        backgroundPaint
                    )
                }

                // الإطار
                if (design.borderOn) {
                    paintBorder.strokeWidth = max(0.5f, design.borderSize * mmToPt)
                    paintBorder.color = try { (design.borderColor).toColorInt() } catch (_: Exception) { android.graphics.Color.BLACK }
                    canvas.drawRect(left, top, right, bottom, paintBorder)
                }

                cardCount++

                // العنوان
                if (design.title.show && design.title.text.isNotEmpty()) {
                    paintText.textSize = design.title.fontSize * 0.8f
                    paintText.color = try { (design.title.color).toColorInt() } catch (_: Exception) { android.graphics.Color.BLACK }
                    canvas.drawText(design.title.text, left + design.title.x * mmToPt, top + design.title.y * mmToPt + paintText.textSize, paintText)
                }

                // اسم المستخدم
                if (design.showUsername && design.username.show) {
                    paintText.textSize = design.username.fontSize * 0.8f
                    paintText.isFakeBoldText = design.username.bold
                    paintText.color = try { (design.username.color).toColorInt() } catch (_: Exception) { android.graphics.Color.BLACK }
                    canvas.drawText(PREVIEW_CARD_NUMBER, left + design.username.x * mmToPt, top + design.username.y * mmToPt + paintText.textSize, paintText)
                }

                // كلمة المرور
                if (design.password.show) {
                    paintText.textSize = design.password.fontSize * 0.8f
                    paintText.color = try { (design.password.color).toColorInt() } catch (_: Exception) { android.graphics.Color.BLACK }
                    canvas.drawText("", left + design.password.x * mmToPt, top + design.password.y * mmToPt + paintText.textSize, paintText)
                }

                // السعر
                if (design.price.show) {
                    paintText.textSize = design.price.fontSize * 0.8f
                    paintText.color = try { (design.price.color).toColorInt() } catch (_: Exception) { android.graphics.Color.BLACK }
                    canvas.drawText(design.price.text, left + design.price.x * mmToPt, top + design.price.y * mmToPt + paintText.textSize, paintText)
                }

                // الرقم التسلسلي
                if (design.serial.show) {
                    paintText.textSize = design.serial.fontSize * 0.8f
                    paintText.color = try { (design.serial.color).toColorInt() } catch (_: Exception) { android.graphics.Color.BLACK }
                    canvas.drawText(design.serial.text, left + design.serial.x * mmToPt, top + design.serial.y * mmToPt + paintText.textSize, paintText)
                }

                // الباركود المدمج
                if (design.barcodeOn) {
                    val bLeft = left + design.barcodeX * mmToPt
                    val bTop = top + design.barcodeY * mmToPt
                    val bW = design.barcodeWidth * mmToPt
                    val bH = design.barcodeHeight * mmToPt
                    val bPaint = Paint().apply { color = android.graphics.Color.BLACK }
                    val bars = 12
                    val bw = bW / (bars * 1.5f)
                    for (b in 0 until bars) {
                        canvas.drawRect(bLeft + b * (bW / bars), bTop, bLeft + b * (bW / bars) + bw, bTop + bH, bPaint)
                    }
                }
            }
        }

        pdfDocument.finishPage(page)

        // حفظ الملف
        val previewDir = File(context.cacheDir, "designs").apply { mkdirs() }
        val safeDesignName = design.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val pdfFile = File(previewDir, "Card_Design_$safeDesignName.pdf")
        val out = FileOutputStream(pdfFile)
        pdfDocument.writeTo(out)
        out.close()
        pdfDocument.close()

        // فتح الملف بواسطة عارض أندرويد الافتراضي
        val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "معاينة وطباعة ملف PDF"))
    } catch (e: Exception) {
        OperationTelemetry.record(event = OperationTelemetry.Event.EXPORT_RESULT, operation = OperationTelemetry.Operation.EXPORT, outcome = OperationTelemetry.Outcome.FAILURE, errorClass = OperationTelemetry.classify(e))
        Toast.makeText(context, "تعذر فتح ملف PDF.", Toast.LENGTH_LONG).show()
    }
}

private fun loadStoredBackgroundBitmap(context: Context, design: Design): Bitmap? {
    if (!design.bgOn || design.bgUrl.isNullOrBlank()) return null
    return try {
        val uri = (design.bgUrl).toUri()
        when (uri.scheme) {
            "file" -> BitmapFactory.decodeFile(uri.path)
            "content" -> context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
