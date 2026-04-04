package com.example.bookiereader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_books")
data class LocalBookEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val author: String?,
    val format: String,
    val filePath: String,
    val series: String? = null,
    val seriesOrder: Double? = null,
    val tags: String? = null // Stored as comma-separated string
)
