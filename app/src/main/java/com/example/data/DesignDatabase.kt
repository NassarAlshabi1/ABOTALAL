package com.example.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import android.content.Context

@Database(
    entities = [Design::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DesignDatabase : RoomDatabase() {
    abstract fun designDao(): DesignDao
    
    companion object {
        @Volatile
        private var INSTANCE: DesignDatabase? = null
        
        fun getInstance(context: Context): DesignDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DesignDatabase::class.java,
                    "design_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromElementStyle(style: ElementStyle): String {
        val safeText = try {
            android.util.Base64.encodeToString(style.text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            style.text
        }
        return "${style.show},${style.x},${style.y},${style.fontSize},${style.color},${style.bold},${style.italic},$safeText"
    }
    
    @TypeConverter
    fun toElementStyle(data: String): ElementStyle {
        return try {
            val parts = data.split(",")
            val rawText = if (parts.size > 7) {
                try {
                    String(android.util.Base64.decode(parts[7], android.util.Base64.NO_WRAP), Charsets.UTF_8)
                } catch (_: Exception) {
                    parts[7]
                }
            } else ""
            ElementStyle(
                show = parts.getOrNull(0)?.toBoolean() ?: false,
                x = parts.getOrNull(1)?.toFloatOrNull() ?: 4f,
                y = parts.getOrNull(2)?.toFloatOrNull() ?: 6f,
                fontSize = parts.getOrNull(3)?.toFloatOrNull() ?: 10f,
                color = parts.getOrNull(4) ?: "#000000",
                bold = parts.getOrNull(5)?.toBoolean() ?: true,
                italic = parts.getOrNull(6)?.toBoolean() ?: false,
                text = rawText
            )
        } catch (_: Exception) {
            ElementStyle()
        }
    }
    
    @TypeConverter
    fun fromCharset(charset: CardsCharset): String = charset.name
    
    @TypeConverter
    fun toCharset(data: String): CardsCharset = try {
        CardsCharset.valueOf(data)
    } catch (_: Exception) {
        CardsCharset.NUMBERS_ONLY
    }
    
    @TypeConverter
    fun fromBackgroundType(type: BackgroundType): String = type.name
    
    @TypeConverter
    fun toBackgroundType(data: String): BackgroundType = try {
        BackgroundType.valueOf(data)
    } catch (_: Exception) {
        BackgroundType.NONE
    }
}
