package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLocationDao {
    @Query("SELECT * FROM saved_locations ORDER BY isPrimary DESC, sortOrder ASC, createdAtEpochMillis ASC")
    fun observeAll(): Flow<List<SavedLocationEntity>>

    @Query("SELECT * FROM saved_locations ORDER BY isPrimary DESC, sortOrder ASC, createdAtEpochMillis ASC")
    suspend fun getAll(): List<SavedLocationEntity>

    @Query("SELECT * FROM saved_locations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SavedLocationEntity?

    @Query("SELECT * FROM saved_locations WHERE isPrimary = 1 LIMIT 1")
    suspend fun getPrimary(): SavedLocationEntity?

    @Query("SELECT * FROM saved_locations WHERE isCurrentLocation = 1 LIMIT 1")
    suspend fun getCurrentLocationEntry(): SavedLocationEntity?

    @Query("SELECT * FROM saved_locations WHERE coordinateKey = :coordinateKey LIMIT 1")
    suspend fun findByCoordinateKey(coordinateKey: String): SavedLocationEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM saved_locations")
    suspend fun getNextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SavedLocationEntity): Long

    @Update
    suspend fun update(entity: SavedLocationEntity)

    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE saved_locations SET isPrimary = 0")
    suspend fun clearPrimaryFlag()

    @Query("UPDATE saved_locations SET isPrimary = 1 WHERE id = :id")
    suspend fun markPrimary(id: Long)

    @Transaction
    suspend fun setPrimaryLocation(id: Long) {
        clearPrimaryFlag()
        markPrimary(id)
    }
}
