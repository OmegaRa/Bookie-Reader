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
    val tags: String? = null, // Stored as comma-separated string
    val lastPageIndex: Int = 0,
    val progress: Float? = null,
    
    // Audiobook fields
    val isAudiobook: Boolean = false,
    val duration: Int? = null,
    val narrator: String? = null,
    val audioFormat: String? = null,
    val chapters: String? = null,
    val progressLocation: String? = null
)
