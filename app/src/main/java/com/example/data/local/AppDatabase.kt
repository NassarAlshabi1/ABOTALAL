package com.example.data.local

import android.content.Context
import com.example.util.OperationTelemetry
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
        GeneratedBatchEntity::class,
        ExpiredCardArchiveEntry::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routerDao(): RouterDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun cacheDao(): CacheDao
    abstract fun generatedBatchDao(): GeneratedBatchDao
    abstract fun expiredCardArchiveDao(): ExpiredCardArchiveDao

    companion object {
        private const val DB_NAME = "netcraft_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * REAL migration v3 → v4: adds the persisted generated_batches table.
         * No destructive rebuild — cached routers/users/profiles survive updates.
         * Internal (not private) so AppDatabaseMigrationTest can exercise
         * migrate() directly against a bare SupportSQLiteDatabase.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
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
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.STORAGE_MIGRATION,
                    operation = OperationTelemetry.Operation.DATABASE_MIGRATION,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    storageVersion = 4
                )
            }
        }

        /**
         * REAL migration v4 → v5: adds the expired_card_archive table used by the
         * "صيانة الكروت المنتهية" wizard's local financial-accounts migration step
         * and by the Financial Accounts screen. No destructive rebuild.
         * Internal (not private) so AppDatabaseMigrationTest can exercise
         * migrate() directly against a bare SupportSQLiteDatabase.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expired_card_archive` (
                        `id` TEXT NOT NULL,
                        `routerHost` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `profile` TEXT NOT NULL,
                        `price` REAL NOT NULL DEFAULT 0.0,
                        `archivedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.STORAGE_MIGRATION,
                    operation = OperationTelemetry.Operation.DATABASE_MIGRATION,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    storageVersion = 5
                )
            }
        }

        /**
         * REAL migration v5 → v6: changes `price` from REAL to INTEGER (whole
         * currency units) on cached_usermanager_profiles and
         * expired_card_archive — matches GeneratedBatchEntity.pricePerCard and
         * avoids float rounding drift in financial totals/sums. Confirmed with
         * the app owner that fractional pricing isn't used in real
         * deployments, so CAST(ROUND(price) AS INTEGER) is a no-op for all
         * real existing rows; it's included defensively in case a stray
         * fractional value ever slipped in.
         *
         * SQLite has no ALTER COLUMN TYPE, so each table is rebuilt: create a
         * `_new` table with the correct column type, copy every row across,
         * drop the old table, rename the new one into place. No data is lost
         * — cached_usermanager_profiles is a router-state cache anyway (fully
         * re-synced on the next real fetch), and expired_card_archive rows
         * are copied across with only the price column's representation
         * changing. Internal (not private) so AppDatabaseMigrationTest can
         * exercise migrate() directly against a bare SupportSQLiteDatabase.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_usermanager_profiles_new` (
                        `name` TEXT NOT NULL,
                        `routerHost` TEXT NOT NULL,
                        `validity` TEXT NOT NULL DEFAULT '30d',
                        `price` INTEGER NOT NULL DEFAULT 0,
                        `sharedUsers` INTEGER NOT NULL DEFAULT 1,
                        `downloadLimit` TEXT NOT NULL DEFAULT 'unlimited',
                        `uptimeLimit` TEXT NOT NULL DEFAULT 'unlimited',
                        `templateName` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`name`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `cached_usermanager_profiles_new`
                        (name, routerHost, validity, price, sharedUsers, downloadLimit, uptimeLimit, templateName)
                    SELECT name, routerHost, validity, CAST(ROUND(price) AS INTEGER), sharedUsers, downloadLimit, uptimeLimit, templateName
                    FROM `cached_usermanager_profiles`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `cached_usermanager_profiles`")
                db.execSQL("ALTER TABLE `cached_usermanager_profiles_new` RENAME TO `cached_usermanager_profiles`")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `expired_card_archive_new` (
                        `id` TEXT NOT NULL,
                        `routerHost` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `profile` TEXT NOT NULL,
                        `price` INTEGER NOT NULL DEFAULT 0,
                        `archivedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `expired_card_archive_new`
                        (id, routerHost, username, profile, price, archivedAt)
                    SELECT id, routerHost, username, profile, CAST(ROUND(price) AS INTEGER), archivedAt
                    FROM `expired_card_archive`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `expired_card_archive`")
                db.execSQL("ALTER TABLE `expired_card_archive_new` RENAME TO `expired_card_archive`")

                OperationTelemetry.record(
                    event = OperationTelemetry.Event.STORAGE_MIGRATION,
                    operation = OperationTelemetry.Operation.DATABASE_MIGRATION,
                    outcome = OperationTelemetry.Outcome.SUCCESS,
                    storageVersion = 6
                )
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigrationFrom(false, 1, 2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
