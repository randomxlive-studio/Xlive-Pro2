package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.example.crash.CrashReportManager
import com.example.service.LiveCommentBus

class RandomXLiveApp : Application(), ImageLoaderFactory {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base != null) {
            runCatching { CrashReportManager.install(base) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { CrashReportManager.install(this) }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    // Cap image cache tightly (8% of heap) with weak references for 128MB devices
                    .maxSizePercent(0.08)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            clearApplicationCaches()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        clearApplicationCaches()
    }

    private fun clearApplicationCaches() {
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
        runCatching { LiveCommentBus.trimMemory() }
    }
}
