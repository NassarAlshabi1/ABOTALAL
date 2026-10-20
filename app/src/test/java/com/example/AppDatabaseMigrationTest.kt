package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises AppDatabase's real (non-destructive) Migration objects directly
 * against a bare SupportSQLiteDatabase, catching regressions like a dropped
 * "IF NOT EXISTS", a typo'd column, or a migration that silently fails to
 * create its table — any of which would otherwise only surface as silent
 * data loss/corruption on a real device upgrade, never caught by the rest of
 * the test suite (which always builds a fresh version-5 database).
 *
 * MIGRATION_3_4/4_5 are purely additive (CREATE TABLE IF NOT EXISTS, no
 * transforms of existing tables); MIGRATION_5_6 rebuilds two existing tables
 * to change a column's type, so its test seeds pre-migration rows itself
 * before calling migrate(). Testing migrate() directly — instead of going
 * through Room's full open/upgrade path, which would need exported
 * historical schema JSON this project doesn't generate (exportSchema =
 * false) — is a faithful, lower-friction equivalent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppDatabaseMigrationTest {

    private fun newInMemoryDb(context: Context): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory — no file on disk, isolated per test
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {}
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
            return c.moveToFirst()
        }
    }

    @Test
    fun `migration 3 to 4 creates generated_batches without touching existing data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        // A pre-existing table that must survive an additive migration untouched.
        db.execSQL("CREATE TABLE IF NOT EXISTS router_connections (host TEXT PRIMARY KEY)")
        db.execSQL("INSERT INTO router_connections (host) VALUES ('192.168.88.1')")

        AppDatabase.MIGRATION_3_4.migrate(db)

        assertTrue("generated_batches table must exist after migration 3->4", tableExists(db, "generated_batches"))
        db.query("SELECT COUNT(*) FROM router_connections").use { c ->
            c.moveToFirst()
            assertEquals("pre-existing cached router data must survive the migration", 1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun `migration 4 to 5 creates expired_card_archive with expected columns`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)

        AppDatabase.MIGRATION_4_5.migrate(db)

        assertTrue("expired_card_archive table must exist after migration 4->5", tableExists(db, "expired_card_archive"))
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(expired_card_archive)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) columns.add(c.getString(nameIdx))
        }
        assertEquals(setOf("id", "routerHost", "username", "profile", "price", "archivedAt"), columns)
        db.close()
    }

    @Test
    fun `migration 4 to 5 is idempotent and safe to run twice`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)

        AppDatabase.MIGRATION_4_5.migrate(db)
        AppDatabase.MIGRATION_4_5.migrate(db) // must not throw — relies on IF NOT EXISTS

        assertTrue(tableExists(db, "expired_card_archive"))
        db.close()
    }

    @Test
    fun `migration 5 to 6 converts price columns from REAL to INTEGER without losing rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        // Recreate the pre-migration (v5) schema for the two affected tables and
        // seed real-looking rows, including one fractional legacy price to prove
        // the defensive ROUND() doesn't drop/crash on it.
        db.execSQL(
            """
            CREATE TABLE `cached_usermanager_profiles` (
                `name` TEXT NOT NULL, `routerHost` TEXT NOT NULL, `validity` TEXT NOT NULL DEFAULT '30d',
                `price` REAL NOT NULL DEFAULT 0.0, `sharedUsers` INTEGER NOT NULL DEFAULT 1,
                `downloadLimit` TEXT NOT NULL DEFAULT 'unlimited', `uptimeLimit` TEXT NOT NULL DEFAULT 'unlimited',
                `templateName` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`name`)
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO cached_usermanager_profiles (name, routerHost, price) VALUES ('باقة 1000', '192.168.88.1', 1000.0)")
        db.execSQL("INSERT INTO cached_usermanager_profiles (name, routerHost, price) VALUES ('legacy-fraction', '192.168.88.1', 10.5)")
        db.execSQL(
            """
            CREATE TABLE `expired_card_archive` (
                `id` TEXT NOT NULL, `routerHost` TEXT NOT NULL, `username` TEXT NOT NULL,
                `profile` TEXT NOT NULL, `price` REAL NOT NULL DEFAULT 0.0, `archivedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO expired_card_archive (id, routerHost, username, profile, price, archivedAt) VALUES ('h|u1', '192.168.88.1', 'u1', 'باقة 1000', 1000.0, 1000)")

        AppDatabase.MIGRATION_5_6.migrate(db)

        db.query("SELECT price FROM cached_usermanager_profiles WHERE name = 'باقة 1000'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1000, c.getInt(0))
        }
        db.query("SELECT price FROM cached_usermanager_profiles WHERE name = 'legacy-fraction'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("fractional legacy price must round, not be dropped", 11, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM expired_card_archive").use { c ->
            c.moveToFirst()
            assertEquals("archived rows must survive the migration", 1, c.getInt(0))
        }
        db.query("SELECT price FROM expired_card_archive WHERE id = 'h|u1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1000, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun `migration 5 to 6 is safe to run on empty tables`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = newInMemoryDb(context)
        db.execSQL(
            """
            CREATE TABLE `cached_usermanager_profiles` (
                `name` TEXT NOT NULL, `routerHost` TEXT NOT NULL, `validity` TEXT NOT NULL DEFAULT '30d',
                `price` REAL NOT NULL DEFAULT 0.0, `sharedUsers` INTEGER NOT NULL DEFAULT 1,
                `downloadLimit` TEXT NOT NULL DEFAULT 'unlimited', `uptimeLimit` TEXT NOT NULL DEFAULT 'unlimited',
                `templateName` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`name`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE `expired_card_archive` (
                `id` TEXT NOT NULL, `routerHost` TEXT NOT NULL, `username` TEXT NOT NULL,
                `profile` TEXT NOT NULL, `price` REAL NOT NULL DEFAULT 0.0, `archivedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        AppDatabase.MIGRATION_5_6.migrate(db) // must not throw on empty source tables

        assertTrue(tableExists(db, "cached_usermanager_profiles"))
        assertTrue(tableExists(db, "expired_card_archive"))
        db.close()
    }
}
