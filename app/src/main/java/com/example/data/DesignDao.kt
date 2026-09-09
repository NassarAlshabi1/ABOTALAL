package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DesignDao {
    @Query("SELECT * FROM designs ORDER BY name")
    fun getAllDesigns(): Flow<List<Design>>
    
    @Query("SELECT * FROM designs WHERE id = :id")
    fun getDesignById(id: String): Flow<Design?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDesign(design: Design)
    
    @Update
    suspend fun updateDesign(design: Design)
    
    @Delete
    suspend fun deleteDesign(design: Design)
    
    @Query("DELETE FROM designs WHERE id = :id")
    suspend fun deleteDesignById(id: String)
    
    @Query("SELECT COUNT(*) FROM designs")
    suspend fun getDesignCount(): Int
}
