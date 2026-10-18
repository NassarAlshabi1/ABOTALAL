package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import org.json.JSONObject

/**
 * A persisted record of one generated card batch. Replaces the old session-only
 * in-memory list, so batch history (and the exact cards that were created) now
 * survives app restarts.
 */
@Entity(tableName = "generated_batches")
data class GeneratedBatchEntity(
    @PrimaryKey val batchId: String,
    val date: String,
    val profileName: String,
    val count: Int,
    val pricePerCard: Int,
    val prefix: String,
    val routerHost: String = "",
    /** JSON array of [{username,password,profile,comment}] — the exact cards created. */
    val cardsJson: String = "[]"
) {
    companion object
}

@Dao
interface GeneratedBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: GeneratedBatchEntity)

    @Query("SELECT * FROM generated_batches ORDER BY batchId DESC")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<GeneratedBatchEntity>>

    @Query("DELETE FROM generated_batches WHERE batchId = :batchId")
    suspend fun deleteById(batchId: String)

    @Query("DELETE FROM generated_batches")
    suspend fun deleteAll()
}

/** Serializes the exact cards of a batch into the entity's cardsJson column. */
fun GeneratedBatchEntity.Companion.fromCards(
    batchId: String,
    date: String,
    profileName: String,
    pricePerCard: Int,
    prefix: String,
    routerHost: String,
    cards: List<com.example.data.model.UserManagerUser>
): GeneratedBatchEntity {
    val arr = JSONArray()
    cards.forEach { u ->
        arr.put(JSONObject().apply {
            put("username", u.username)
            put("password", u.password)
            put("profile", u.profile)
            put("comment", u.comment)
            put("active", u.active)
            put("uptimeUsed", u.uptimeUsed)
            put("serialNumber", u.serialNumber)
        })
    }
    return GeneratedBatchEntity(
        batchId = batchId,
        date = date,
        profileName = profileName,
        count = cards.size,
        pricePerCard = pricePerCard,
        prefix = prefix,
        routerHost = routerHost,
        cardsJson = arr.toString()
    )
}

/** Parses cardsJson back into models (empty list on any corruption). */
fun GeneratedBatchEntity.toCards(): List<com.example.data.model.UserManagerUser> {
    return try {
        val arr = JSONArray(cardsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            com.example.data.model.UserManagerUser(
                username = o.optString("username"),
                password = o.optString("password"),
                profile = o.optString("profile", "default"),
                active = o.optBoolean("active", true),
                uptimeUsed = o.optString("uptimeUsed", "0s"),
                serialNumber = o.optString("serialNumber", "-"),
                comment = o.optString("comment", "")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
