package com.example.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

@Database(
    entities = [Design::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class DesignDatabase : RoomDatabase() {
    abstract fun designDao(): DesignDao
    
    companion object {
        @Volatile
        private var INSTANCE: DesignDatabase? = null
        
        /**
         * v1 → v2: adds the card "profile" (package name) element and the
         * barcode-type selector. Existing designs keep their layout; the new
         * element starts hidden with the same label the editor shows.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Default value is a fully encoded ElementStyle so the new
                // column reads back as the editor's default element.
                db.execSQL(
                    "ALTER TABLE `designs` ADD COLUMN `profile` TEXT NOT NULL DEFAULT " +
                        "'false,4.0,14.0,8.0,#000000,true,false,2KfZhNio2KfZgtip'"
                )
                db.execSQL("ALTER TABLE `designs` ADD COLUMN `barcodeType` TEXT NOT NULL DEFAULT 'barcode'")
            }
        }

        fun getInstance(context: Context): DesignDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DesignDatabase::class.java,
                    "design_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
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
