package com.example.bookiereader.network

import retrofit2.http.GET

data class GithubRelease(
    val tag_name: String,
    val name: String?,
    val html_url: String
)

interface GithubService {
    @GET("repos/OmegaRa/Bookie-Reader/releases/latest")
    suspend fun getLatestRelease(): GithubRelease
}
