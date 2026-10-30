package com.example.crash

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CrashReportManager {

    private const val CRASH_FILE_NAME = "last_crash_report.txt"
    private const val MAX_REPORT_CHARS = 32_000

    private val isInstalled = AtomicBoolean(false)

    fun install(context: Context) {
        if (!isInstalled.compareAndSet(false, true)) return

        val appContext = context.applicationContext ?: context
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReportSynchronously(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never throw from inside the crash reporter
            } finally {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    private fun writeCrashReportSynchronously(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val stackWriter = StringWriter()
        PrintWriter(stackWriter, true).use { pw ->
            throwable.printStackTrace(pw)
        }
        val fullTrace = stackWriter.toString().take(MAX_REPORT_CHARS)

        val report = buildString {
            appendLine("=== RandomXLive Crash Report ===")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                    "(Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"
            )
            appendLine("Exception: ${throwable.javaClass.name}: ${throwable.message.orEmpty()}")
            appendLine("--------------------------------")
            appendLine(fullTrace)
        }

        val crashFile = File(context.filesDir, CRASH_FILE_NAME)
        crashFile.parentFile?.mkdirs()
        crashFile.writeText(report, Charsets.UTF_8)
    }

    suspend fun readPendingCrashReport(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val crashFile = File(context.filesDir, CRASH_FILE_NAME)
            if (crashFile.exists() && crashFile.isFile && crashFile.length() > 0L) {
                crashFile.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun clearCrashReport(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val crashFile = File(context.filesDir, CRASH_FILE_NAME)
            if (crashFile.exists()) {
                crashFile.delete()
            }
        }
    }
}
