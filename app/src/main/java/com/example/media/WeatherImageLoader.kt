package com.example.media

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Shared Coil ImageLoader configured to decode VP8X animated WebP drawables natively
 * (with full transparency and infinite frame-by-frame looping on Android 9+/API 28+,
 * and automatic static first-frame decoding on Android 7.0-8.1 / API 24-27), with
 * bounded memory & disk caches to avoid unbounded animation bitmap growth.
 */
object WeatherImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: buildImageLoader(context.applicationContext).also { instance = it }
        }
    }

    private fun buildImageLoader(appContext: Context): ImageLoader {
        return ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("weather_image_cache"))
                    .maxSizeBytes(16L * 1024L * 1024L)
                    .build()
            }
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                }
            }
            .crossfade(false)
            .build()
    }
}
