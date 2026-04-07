package com.example.bookiereader

import android.app.Application
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

class BookieReaderApplication : Application() {

    lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()
        // Initialize with default client, will be updated after login
        imageLoader = buildImageLoader(OkHttpClient())
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun updateAuthenticatedClient(client: OkHttpClient) {
        // Clear caches when client updates (e.g. login/logout or new session)
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        imageLoader = buildImageLoader(client)
    }

    private fun buildImageLoader(client: OkHttpClient): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}