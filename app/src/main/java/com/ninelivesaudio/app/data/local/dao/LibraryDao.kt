package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ninelivesaudio.app.data.local.entity.LibraryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM Libraries ORDER BY DisplayOrder")
    fun observeAll(): Flow<List<LibraryEntity>>

    @Query("SELECT * FROM Libraries ORDER BY DisplayOrder")
    suspend fun getAll(): List<LibraryEntity>

    @Query("SELECT * FROM Libraries WHERE Id = :id")
    suspend fun getById(id: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(library: LibraryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM Libraries WHERE Id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM Libraries")
    suspend fun deleteAll()
}
