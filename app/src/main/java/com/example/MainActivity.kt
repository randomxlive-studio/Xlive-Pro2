package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.crash.CrashReportManager
import com.example.ui.StudioScreen
import com.example.ui.StudioViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { CrashReportManager.install(applicationContext) }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the screen awake automatically for uninterrupted streaming/broadcasting
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        handleIncomingShareIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            LaunchedEffect(state.isBroadcastMode) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (state.isBroadcastMode) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            MyApplicationTheme {
                StudioScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { viewModel.onTrimMemory(level) }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { viewModel.onLowMemory() }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        runCatching {
            if (intent?.action != Intent.ACTION_SEND) return
            val type = intent.type.orEmpty()
            if (type == "text/plain") {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (sharedText.isNotBlank()) {
                    viewModel.handleSharedText(sharedText)
                }
            } else if (type.startsWith("video/")) {
                val videoUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (videoUri != null) {
                    viewModel.handlePickedGameplayVideo(videoUri)
                }
            }
        }
    }
}
