package com.example

import android.app.Application
import android.content.Context
import com.example.crash.CrashReportManager

class RandomXLiveApp : Application() {

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
}
