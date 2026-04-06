package com.example.bookiereader.network

import retrofit2.http.GET

data class GithubRelease(
    val tag_name: String,
    val html_url: String
)

interface GithubService {
    @GET("repos/sweatyeggs69/BookieReader/releases/latest")
    suspend fun getLatestRelease(): GithubRelease
}
