package com.example.bookiereader.network

import com.example.bookiereader.data.BookResponse
import com.example.bookiereader.data.LoginRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @POST("auth/login") // Relative to /api, this becomes /api/auth/login
    suspend fun login(@Body request: LoginRequest): Response<Unit>

    @GET("books") // Relative to /api, this becomes /api/books
    suspend fun getBooks(
        @retrofit2.http.Query("page") page: Int = 1,
        @retrofit2.http.Query("per_page") perPage: Int = 100
    ): BookResponse
}
