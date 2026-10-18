package com.example.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RouterConnection::class,
        ActivityLog::class,
        CachedHotspotUser::class,
        CachedUserManagerUser::class,
        CachedUserManagerProfile::class,
        CachedHotspotProfile::class,
        CachedRouterStats::class,
        GeneratedBatchEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routerDao(): RouterDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun cacheDao(): CacheDao
    abstract fun generatedBatchDao(): GeneratedBatchDao

    companion object {
        private const val DB_NAME = "netcraft_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * REAL migration v3 → v4: adds the persisted generated_batches table.
         * No destructive rebuild — cached routers/users/profiles survive updates.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `generated_batches` (
                        `batchId` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `profileName` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        `pricePerCard` INTEGER NOT NULL,
                        `prefix` TEXT NOT NULL,
                        `routerHost` TEXT NOT NULL DEFAULT '',
                        `cardsJson` TEXT NOT NULL DEFAULT '[]',
                        PRIMARY KEY(`batchId`)
                    )
                    """.trimIndent()
                )
                Log.i("AppDatabase", "Migrated database v3 → v4 (generated_batches added)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // Real migrations going forward. v1/v2 databases predate schema
                    // tracking (they were always rebuilt destructively), so only
                    // those two legacy versions keep the destructive fallback.
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
