package com.devson.devsonplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache

class DevsonApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // 1. Add the Video Frame Decoder
            .components {
                add(VideoFrameDecoder.Factory())
            }
            // 2. Configure a robust Memory Cache (Fastest, wiped on app close)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // Use 25% of available app memory
                    .build()
            }
            // 3. Configure a persistent Disk Cache (Survives app restarts!)
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("video_thumbnails"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB cache limit
                    .build()
            }
            // Force Coil to ignore headers and aggressively cache everything
            .respectCacheHeaders(false)
            .build()
    }
}