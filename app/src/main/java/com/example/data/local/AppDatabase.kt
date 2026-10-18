package com.example.data.local

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

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
        private const val DB_NAME = "abo_talal_db"
        private const val LEGACY_DB_NAME = "netcraft_db"

        /**
         * Real schema migration v3 -> v4: adds the generated_batches table.
         * (v1/v2 predate every released build and cannot be reconstructed —
         * fallbackToDestructiveMigrationFrom(1, 2) covers only those ancient
         * installs; every modern install migrates in place without data loss.)
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `generated_batches` (
                        `batchId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `date` TEXT NOT NULL,
                        `profileName` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        `pricePerCard` INTEGER NOT NULL,
                        `prefix` TEXT NOT NULL,
                        `cardsJson` TEXT NOT NULL,
                        PRIMARY KEY(`batchId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** File-level rename of the old database (netcraft_db -> abo_talal_db),
         *  preserving all rows, WAL and SHM files. */
        private fun migrateLegacyDbFile(context: Context) {
            val dbDir = context.getDatabasePath(DB_NAME).parentFile ?: return
            val newMain = File(dbDir, DB_NAME)
            if (newMain.exists()) return
            val oldMain = File(dbDir, LEGACY_DB_NAME)
            if (!oldMain.exists()) return
            try {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    val src = File(dbDir, "$LEGACY_DB_NAME$suffix")
                    if (src.exists()) {
                        src.copyTo(File(dbDir, "$DB_NAME$suffix"), overwrite = false)
                    }
                }
                Log.i("AppDatabase", "Migrated database file $LEGACY_DB_NAME -> $DB_NAME")
            } catch (e: Exception) {
                Log.e("AppDatabase", "Database file rename migration failed", e)
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                migrateLegacyDbFile(context.applicationContext)
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                .addMigrations(MIGRATION_3_4)
                // Only the unreconstructable v1/v2 installs may fall back to a
                // destructive migration — never the current schema.
                .fallbackToDestructiveMigrationFrom(1, 2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
