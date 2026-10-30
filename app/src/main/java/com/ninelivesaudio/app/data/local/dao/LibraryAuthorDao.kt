package com.ninelivesaudio.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ninelivesaudio.app.data.local.entity.LibraryAuthorEntity

@Dao
interface LibraryAuthorDao {

    @Query("SELECT * FROM LibraryAuthors WHERE LibraryId = :libraryId ORDER BY AuthorId")
    suspend fun getForLibrary(libraryId: String): List<LibraryAuthorEntity>

    @Query("DELETE FROM LibraryAuthors WHERE LibraryId = :libraryId")
    suspend fun deleteForLibrary(libraryId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<LibraryAuthorEntity>)

    /** The library's whole author list, swapped in one transaction so a reader never sees half of it. */
    @Transaction
    suspend fun replaceForLibrary(libraryId: String, rows: List<LibraryAuthorEntity>) {
        deleteForLibrary(libraryId)
        insertAll(rows)
    }
}
