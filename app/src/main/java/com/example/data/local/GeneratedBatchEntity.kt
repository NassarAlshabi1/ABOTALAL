package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One generated card batch, persisted locally so batch reports survive navigation
 * and app restarts (previously they lived only in a remember {} block).
 */
@Entity(tableName = "generated_batches")
data class GeneratedBatchEntity(
    @PrimaryKey val batchId: String,
    val createdAt: Long,
    val date: String,
    val profileName: String,
    val count: Int,
    val pricePerCard: Int,
    val prefix: String,
    /** The exact cards created for this batch, serialized as JSON. */
    val cardsJson: String
)

@Dao
interface GeneratedBatchDao {
    @Query("SELECT * FROM generated_batches ORDER BY createdAt DESC")
    fun getAll(): Flow<List<GeneratedBatchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(batches: List<GeneratedBatchEntity>)

    @Query("DELETE FROM generated_batches")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(batches: List<GeneratedBatchEntity>) {
        clearAll()
        insertAll(batches)
    }
}
