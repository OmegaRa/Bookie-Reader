package com.example.bookiereader.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalBookDao {
    @Query("SELECT * FROM local_books")
    suspend fun getAllBooks(): List<LocalBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: LocalBookEntity)

    @Delete
    suspend fun deleteBook(book: LocalBookEntity)
}
