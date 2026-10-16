package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CardsCharset {
    NUMBERS_ONLY,
    LETTERS_AND_NUMBERS
}

enum class BackgroundType {
    NONE,
    BUILT_IN,
    CUSTOM
}

@Entity(tableName = "designs")
data class Design(
    @PrimaryKey
    val id: String = "",
    val name: String = "Default_Design",
    
    // إعدادات الصفحة
    val columns: Int = 3,
    val rows: Int = 17,
    val marginH: Float = 0f,
    val marginV: Float = 0f,
    val autoFit: Boolean = true,
    val pageMargin: Float = 5f,
    val cardWidth: Float = 65f,
    val cardHeight: Float = 15f,
    
    // الربط بالباقات
    val userManagerPackageId: String? = null,
    val hotspotPackageId: String? = null,
    
    // عناصر الكرت
    val showUsername: Boolean = true,
    val username: ElementStyle = ElementStyle(
        show = true, x = 4f, y = 4f, fontSize = 11f, bold = true
    ),
    val title: ElementStyle = ElementStyle(
        show = false, y = 2f, fontSize = 8f, text = "MK NETCRAFT"
    ),
    val password: ElementStyle = ElementStyle(
        show = false, x = 34f, y = 4f, fontSize = 10f
    ),
    val payment: ElementStyle = ElementStyle(
        show = false, x = 4f, y = 10f, fontSize = 8f, text = ""
    ),
    val serial: ElementStyle = ElementStyle(
        show = false, x = 34f, y = 10f, fontSize = 8f
    ),
    val price: ElementStyle = ElementStyle(
        show = false, x = 20f, y = 10f, fontSize = 9f
    ),
    val pos: ElementStyle = ElementStyle(
        show = false, x = 4f, y = 12f, fontSize = 8f, text = ""
    ),
    val pageNote: ElementStyle = ElementStyle(
        show = false, x = 40f, y = 6f, fontSize = 10f, text = ""
    ),
    val pageNumber: ElementStyle = ElementStyle(
        show = false, x = 0f, y = 0f, fontSize = 10f, text = "{page}"
    ),
    
    // إطار وخلفية
    val borderOn: Boolean = true,
    val borderSize: Float = 0.35f,
    val borderColor: String = "#000000",
    val bgOn: Boolean = false,
    val bgUrl: String? = null,
    val bgType: BackgroundType = BackgroundType.NONE,
    
    // الباركود
    val barcodeOn: Boolean = false,
    val barcodeWidth: Float = 18f,
    val barcodeHeight: Float = 8f,
    val barcodeX: Float = 44f,
    val barcodeY: Float = 3f,
    
    // الشعار
    val logoOn: Boolean = false,
    val logoUrl: String? = null,
    val logoWidth: Float = 10f,
    val logoHeight: Float = 10f,
    val logoX: Float = 4f,
    val logoY: Float = 8f,
    
    // معاينة حية
    val digits: Int = 10,
    val charset: CardsCharset = CardsCharset.NUMBERS_ONLY,
    val moveStep: Float = 0.5f
)

// خلفيات مدمجة في التطبيق
data class BuiltInBackground(
    val id: String,
    val name: String,
    val resourceId: Int,
    val previewResId: Int
)
