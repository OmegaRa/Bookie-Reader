package com.example.bookiereader.data

import com.google.gson.annotations.SerializedName

data class Book(
    @SerializedName("id")
    val id: Int,
    @SerializedName("title")
    val title: String,
    @SerializedName("author")
    val author: String?,
    @SerializedName("file_format")
    val format: String,
    @SerializedName("filename")
    val downloadUrl: String,
    @SerializedName("cover_filename")
    val coverUrl: String? = null,
    @SerializedName("series")
    val series: String? = null,
    @SerializedName("series_order")
    val seriesOrder: Double? = null,
    @SerializedName("tags")
    val tags: List<String>? = null,
    val progress: Float? = null,

    // Audiobook metadata matching Python backend
    @SerializedName("is_audiobook")
    val isAudiobook: Boolean = false,
    @SerializedName("duration")
    val duration: Int? = null,
    @SerializedName("narrator")
    val narrator: String? = null,
    @SerializedName("audio_format")
    val audioFormat: String? = null,
    @SerializedName("chapters")
    val chapters: String? = null,
    @SerializedName("progress_location")
    val progressLocation: String? = null
)

data class BookResponse(
    @SerializedName("books")
    val books: List<Book>,
    @SerializedName("total")
    val total: Int
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class ProgressRequest(
    @SerializedName("progress")
    val progress: Float,
    @SerializedName("progress_location")
    val progressLocation: String? = null,
    @SerializedName("read_status")
    val readStatus: String? = null
)
