package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One expired/disabled User Manager card archived locally — on the device, never
 * sent to the router — before it is deleted from RouterOS. This is the "محلي"
 * (local-only) financial record of a card that was sold and later expired, used
 * by the "الحسابات المالية" (Financial Accounts) screen and by step 1 of the
 * expired-cards maintenance wizard ("ترحيل محلي للحسابات المالية").
 *
 * Keyed by routerHost+username so re-running the migration step updates the
 * same row instead of duplicating it (matches the wizard's own description:
 * "وإعادة التنفيذ تحدّث السجلات نفسها بلا تكرار").
 */
@Entity(tableName = "expired_card_archive")
data class ExpiredCardArchiveEntry(
    @PrimaryKey val id: String,
    val routerHost: String,
    val username: String,
    val profile: String,
    val price: Double = 0.0,
    val archivedAt: Long
)

@Dao
interface ExpiredCardArchiveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ExpiredCardArchiveEntry>)

    @Query("SELECT * FROM expired_card_archive WHERE routerHost = :host ORDER BY archivedAt DESC")
    suspend fun getForRouter(host: String): List<ExpiredCardArchiveEntry>

    @Query("SELECT COALESCE(SUM(price), 0) FROM expired_card_archive WHERE routerHost = :host")
    suspend fun totalValueForRouter(host: String): Double

    @Query("SELECT COUNT(*) FROM expired_card_archive WHERE routerHost = :host")
    suspend fun countForRouter(host: String): Int
}
