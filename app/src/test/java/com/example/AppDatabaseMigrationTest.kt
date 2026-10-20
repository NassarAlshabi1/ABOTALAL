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
 * Both migrations are purely additive (CREATE TABLE IF NOT EXISTS, no
 * ALTER/data transforms of existing tables), so testing migrate() directly —
 * instead of going through Room's full open/upgrade path, which would need
 * exported historical schema JSON this project doesn't generate
 * (exportSchema = false) — is a faithful, lower-friction equivalent.
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
}
