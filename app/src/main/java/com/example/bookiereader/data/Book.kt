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
    val tags: List<String>? = null
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
