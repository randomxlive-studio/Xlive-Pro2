package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Fallback comment reader using MediaProjection + free offline ML Kit Text Recognition,
 * throttled to 1 capture every 3 seconds, reusing a single capture Bitmap buffer,
 * and recycling cropped Bitmaps immediately after OCR completes.
 */
class TikTokOcrFallbackService : Service() {

    companion object {
        private const val CHANNEL_ID = "rxl_ocr_fallback_channel"
        private const val NOTIFICATION_ID = 4402
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CAPTURE_INTERVAL_MS = 3000L

        fun createStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, TikTokOcrFallbackService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var reusableCaptureBitmap: Bitmap? = null
    private var isCaptureInFlight = false

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (LiveCommentBus.shouldRunOcrCaptureNow() && !isCaptureInFlight) {
                captureAndRecognizeCommentArea()
            }
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsMediaProjectionForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        setupVirtualDisplay(resultCode, resultData)
        LiveCommentBus.setOcrFallbackRunning(true)
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, CAPTURE_INTERVAL_MS)
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay(resultCode: Int, resultData: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val metrics = resources.displayMetrics
        // Modest resolution keeps reusable capture buffer small (~1MB instead of ~8MB)
        val captureW = (metrics.widthPixels / 2).coerceIn(320, 540)
        val captureH = (metrics.heightPixels / 2).coerceIn(560, 1100)
        val density = metrics.densityDpi

        tearDownProjection()

        val projection = mgr.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            },
            handler
        )

        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        val vDisplay = projection?.createVirtualDisplay(
            "RxlCommentOcr",
            captureW,
            captureH,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = vDisplay
    }

    private fun obtainReusableCaptureBitmap(targetW: Int, targetH: Int): Bitmap {
        val existing = reusableCaptureBitmap
        if (existing != null &&
            !existing.isRecycled &&
            existing.width == targetW &&
            existing.height == targetH
        ) {
            return existing
        }
        existing?.let { bmp ->
            if (!bmp.isRecycled) runCatching { bmp.recycle() }
        }
        val created = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        reusableCaptureBitmap = created
        return created
    }

    private fun recycleReusableBuffer() {
        reusableCaptureBitmap?.let { bmp ->
            if (!bmp.isRecycled) runCatching { bmp.recycle() }
        }
        reusableCaptureBitmap = null
    }

    private fun captureAndRecognizeCommentArea() {
        val reader = imageReader ?: return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        isCaptureInFlight = true

        var croppedBitmap: Bitmap? = null
        try {
            val plane = image.planes.firstOrNull()
            if (plane == null) {
                image.close()
                isCaptureInFlight = false
                return
            }
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val bufferWidth = width + (rowPadding / pixelStride).coerceAtLeast(0)

            val fullBitmap = obtainReusableCaptureBitmap(bufferWidth, height)
            val byteBuffer = plane.buffer
            byteBuffer.rewind()
            fullBitmap.copyPixelsFromBuffer(byteBuffer)
            image.close()

            // Crop strictly to TikTok LIVE bottom-left comment stream area (50%..88% height, 0..78% width)
            val cropX = 0
            val cropY = (height * 0.50f).toInt().coerceIn(0, height - 10)
            val cropW = (width * 0.78f).toInt().coerceIn(10, width)
            val cropH = (height * 0.38f).toInt().coerceIn(10, height - cropY)

            val crop = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
            croppedBitmap = crop

            val inputImage = InputImage.fromBitmap(crop, 0)
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val lineStr = line.text.trim()
                            val colonIdx = lineStr.indexOf(':')
                            if (colonIdx in 1..28 && colonIdx < lineStr.length - 2) {
                                LiveCommentBus.submitCandidate(
                                    rawUsername = lineStr.substring(0, colonIdx),
                                    rawText = lineStr.substring(colonIdx + 1),
                                    fromAccessibility = false
                                )
                            } else {
                                LiveCommentBus.submitCandidate(
                                    rawUsername = "",
                                    rawText = lineStr,
                                    fromAccessibility = false
                                )
                            }
                        }
                    }
                }
                .addOnCompleteListener {
                    if (!crop.isRecycled) {
                        runCatching { crop.recycle() }
                    }
                    isCaptureInFlight = false
                }
        } catch (_: Throwable) {
            runCatching { image.close() }
            croppedBitmap?.let { bmp ->
                if (!bmp.isRecycled) runCatching { bmp.recycle() }
            }
            isCaptureInFlight = false
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !isCaptureInFlight) {
            recycleReusableBuffer()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!isCaptureInFlight) {
            recycleReusableBuffer()
        }
    }

    private fun startAsMediaProjectionForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.accessibility_service_label),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("TikTok comment OCR fallback active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun tearDownProjection() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        recycleReusableBuffer()
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        LiveCommentBus.setOcrFallbackRunning(false)
        tearDownProjection()
        runCatching { recognizer.close() }
        super.onDestroy()
    }
}
