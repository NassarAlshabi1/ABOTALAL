package com.example.data

import androidx.core.graphics.toColorInt

import android.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * نمط عنصر في الكرت (اسم مستخدم، كلمة مرور، عنوان، إلخ)
 */
data class ElementStyle(
    val show: Boolean = false,
    val x: Float = 4f,          // الموضع الأفقي (مم) - من يمين الكرت
    val y: Float = 6f,          // الموضع الرأسي (مم) - من أعلى الكرت
    val fontSize: Float = 10f,  // حجم الخط (نقطة)
    val color: String = "#000000",
    val bold: Boolean = true,
    val italic: Boolean = false,
    val text: String = ""
) {
    fun getColorInt(): Int = try {
        (color).toColorInt()
    } catch (_: Exception) {
        Color.BLACK
    }
    fun getFontWeight(): FontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    fun getFontStyle(): FontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
}

fun elementStyle(overrides: ElementStyle.() -> Unit = {}): ElementStyle {
    return ElementStyle().apply(overrides)
}
